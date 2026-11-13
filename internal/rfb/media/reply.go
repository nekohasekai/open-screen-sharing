package media

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"fmt"
	"io"
	"reflect"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	payloadTypeHEVC    = 100
	payloadTypeAudio   = 101
	payloadTypeH264    = 123
	payloadTypeH264Alt = 126
	maxNegotiatorBlob  = 1 << 20
)

type Leg struct {
	Port    uint16
	Enabled bool
	HDR     bool
}

type Ports struct {
	Audio  Leg
	Video  Leg
	Video2 Leg
}

type Answer struct {
	SSRC          uint32
	Width         int
	Height        int
	TilesPerFrame int
	LTRPEnabled   bool
}

const (
	ServerErrorBusy        uint32 = 1
	ServerErrorBusySubCode uint32 = 1
)

type ServerError struct {
	Type    uint32
	SubCode uint32
}

func (e *ServerError) Error() string {
	return fmt.Sprint("media: server refused the media stream (type ", e.Type, ", sub-code ", e.SubCode, ")")
}

func ParsePorts(payload []byte) (*Ports, error) {
	reply, err := parseReply(payload)
	if err != nil {
		return nil, err
	}
	ports, isPorts := reply.(*Ports)
	if !isPorts {
		return nil, E.New("media: expected a ports message, got ", reflect.TypeOf(reply))
	}
	return ports, nil
}

func parseReply(payload []byte) (any, error) {
	if len(payload) < 2 {
		return nil, E.New("media: truncated media stream reply: ", len(payload), " of 2 length bytes")
	}
	size := int(binary.BigEndian.Uint16(payload))
	if size < 8 {
		return nil, E.New("media: media stream reply declares ", size, " bytes, less than its 8-byte header")
	}
	if len(payload) < 2+size {
		return nil, E.New("media: truncated media stream reply: ", len(payload)-2, " of ", size, " bytes")
	}
	body := payload[2 : 2+size]
	kind := binary.BigEndian.Uint16(body)
	switch kind {
	case 1:
		if len(body) < 26 {
			return nil, E.New("media: truncated ports reply: ", len(body), " of 26 bytes")
		}
		leg := func(offset int) Leg {
			flags := binary.BigEndian.Uint32(body[offset+2:])
			return Leg{
				Port:    binary.BigEndian.Uint16(body[offset:]),
				Enabled: flags&1 != 0,
				HDR:     flags&2 != 0,
			}
		}
		return &Ports{Audio: leg(8), Video: leg(14), Video2: leg(20)}, nil
	case 2:
		if len(body) < 18 {
			return nil, E.New("media: truncated answer reply: ", len(body), " of 18 header bytes")
		}
		audioLength := int(binary.BigEndian.Uint16(body[8:]))
		videoLength := int(binary.BigEndian.Uint16(body[10:]))
		video2Length := int(binary.BigEndian.Uint16(body[12:]))
		if len(body) < 18+audioLength+videoLength+video2Length {
			return nil, E.New("media: truncated answer reply: ", len(body), " of ", 18+audioLength+videoLength+video2Length, " bytes")
		}
		answer, err := parseVideoAnswer(body[18+audioLength : 18+audioLength+videoLength])
		if err != nil {
			return nil, E.Cause(err, "parse video answer")
		}
		return answer, nil
	case 3:
		if len(body) < 16 {
			return nil, E.New("media: truncated error reply: ", len(body), " of 16 bytes")
		}
		return &ServerError{
			Type:    binary.BigEndian.Uint32(body[8:]),
			SubCode: binary.BigEndian.Uint32(body[12:]),
		}, nil
	default:
		return nil, E.New("media: unknown media stream reply type ", kind)
	}
}

// AVConference createAnswer puts the compressed response blob under
// avcMediaStreamNegotiatorMediaBlob in a binary plist when the offer carries
// avcMediaStreamOptionRemoteEndpointInfo, and returns the bare compressed blob
// otherwise (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID
// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
// -[AVCMediaStreamNegotiator processOfferWithError:errorReason:] 0x1c12dfa14..0x1c12dfa20,
// -[AVCMediaStreamNegotiator createAnswer] 0x1c12e0294..0x1c12e03a0,
// -[VCMediaNegotiator newResponseBlob] 0x1c122d9d0; AVConference 2260.14.1,
// macOS 27.2 26B5101f, UUID EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
// -[AVCMediaStreamNegotiator processOfferWithError:errorReason:] 0x1c0953804..0x1c0953810,
// -[AVCMediaStreamNegotiator createAnswer] 0x1c0954084..0x1c0954190,
// -[VCMediaNegotiator newResponseBlob] 0x1c08a0708).
func parseVideoAnswer(blob []byte) (*Answer, error) {
	answer := &Answer{}
	if len(blob) == 0 {
		return answer, nil
	}
	compressed := blob
	value, err := decodeBinaryPlist(blob)
	if err == nil {
		dictionary, isDictionary := value.(map[string]any)
		if !isDictionary {
			return nil, E.New("media: property list is ", reflect.TypeOf(value), ", not a dictionary")
		}
		var isData bool
		compressed, isData = dictionary["avcMediaStreamNegotiatorMediaBlob"].([]byte)
		if !isData {
			return nil, E.New("media: no avcMediaStreamNegotiatorMediaBlob")
		}
	}
	message, err := parseNegotiatorBlob(compressed)
	if err != nil {
		return nil, err
	}
	streams := message.messages(5)
	if len(streams) == 0 {
		return answer, nil
	}
	stream := streams[0]
	ssrc, _ := stream.uint(1)
	width, _ := stream.uint(4)
	height, _ := stream.uint(5)
	tiles, _ := stream.uint(6)
	ltrp, _ := stream.uint(7)
	answer.SSRC = uint32(ssrc)
	answer.Width = int(width)
	answer.Height = int(height)
	answer.TilesPerFrame = int(tiles)
	answer.LTRPEnabled = ltrp != 0
	return answer, nil
}

func parseNegotiatorBlob(compressed []byte) (protoMessage, error) {
	reader, err := zlib.NewReader(bytes.NewReader(compressed))
	if err != nil {
		return nil, E.Cause(err, "media: decompress negotiator blob")
	}
	inflated, err := io.ReadAll(io.LimitReader(reader, maxNegotiatorBlob))
	if err != nil {
		return nil, E.Cause(err, "media: decompress negotiator blob")
	}
	return parseProto(inflated)
}

func codecForPayloadType(payloadType int) Codec {
	switch payloadType {
	case payloadTypeHEVC:
		return CodecHEVC
	case payloadTypeH264, payloadTypeH264Alt:
		return CodecH264
	default:
		return 0
	}
}
