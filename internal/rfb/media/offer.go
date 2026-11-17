package media

import (
	"bytes"
	"compress/zlib"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"slices"
	"strings"
	"sync/atomic"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	ClientMessageMediaStream = 0x1c

	// ScreenSharing copies 46 bytes for each negotiated SRTP key
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID
	// EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBMediaStreamServerConfiguration 0x1cc14a08c..0x1cc14a100;
	// ScreenSharing 786.3, macOS 27.2 26B5101f, UUID
	// 21DF7A41-0D38-3DF3-B87A-8A6249686735,
	// _RFBMediaStreamServerConfiguration 0x22e58d688..0x22e58d6fc).
	masterKeyLength = 46

	negotiatorModeRemoteMic                  = 4
	negotiatorModeCoreDeviceScreenSharing    = 5
	negotiatorModeRemoteDesktopScreenSharing = 7
	negotiatorModeRemoteDesktopSystemAudio   = 8
	negotiatorModeNearbyScreenSharing        = 9

	hdrModeStandard  = 0
	hdrModeReference = 3

	mediaStreamMessageVersion = 3
	messageFlagFrameRate60    = 1 << 0
	messageFlagNoCursor       = 1 << 2

	accessNetworkTypeViewer = 1

	// AVConference writes blob version 2 and user agent "Viceroy 1.7.0" into
	// both offer and answer blobs (AVConference 2235.63.5.2, macOS 27.0.1
	// 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// -[VCMediaNegotiator negotiationData] 0x1c122bdd4..0x1c122bde8,
	// -[VCMediaNegotiator newResponseBlob] 0x1c122d41c..0x1c122d430;
	// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
	// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// -[VCMediaNegotiator negotiationData] 0x1c089eb0c..0x1c089eb20,
	// -[VCMediaNegotiator newResponseBlob] 0x1c08a0154..0x1c08a0168).
	negotiatorUserAgent   = "Viceroy 1.7.0"
	negotiatorBlobVersion = 2

	// Native AVConference mode-7 offers use transport 1, operations 1/2 and
	// format bitmap 0xc3c3, and add the display-dependent AR/XR after these
	// codec FLS prefixes. VideoProcessing builds the two prefixes
	// (VideoProcessing 1395.65.1, macOS 27.0.1 26A434, UUID
	// D87079FD-5315-32B1-8022-E6144A69A8EE,
	// _VCPCodecCopyProperties 0x1b4fdf4c4..0x1b4fdf4d8;
	// VideoProcessing 1415.6.1, macOS 27.2 26B5101f, UUID
	// A6FC3A01-8834-335F-A877-FDACE2E1AECC,
	// _VCPCodecCopyProperties 0x1cb6084f0..0x1cb608504).
	videoRuleTransport       = 1
	videoRuleOperationEncode = 1
	videoRuleOperationDecode = 2
	screenFormats            = 0xc3c3

	featureListAspectRatios = "AR:16/9,5/8;XR:16/9,5/8;"
	featureListH264         = "FLS;MS:-1;LF:-1;LTR;CABAC;POS:0;EOD:1;HTS:2;RR:3;SW:4;" + featureListAspectRatios
	featureListHEVC         = "FLS;LF:-1;POS:5;EOD:1;HTS:2;RR:3;POSE:4;SW:0;" + featureListAspectRatios
	// AVConference _VCVideoParameterSets_DefaultSupportedSets returns 1 for PT 123
	// and 14 for PT 100 (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID
	// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// _VCVideoParameterSets_DefaultSupportedSets 0x1c1195f18..0x1c1195f3c;
	// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
	// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// _VCVideoParameterSets_DefaultSupportedSets 0x1c0812664..0x1c0812688).
	parameterSetsH264 = 1
	parameterSetsHEVC = 14

	endpointDeviceModel         = "Mac14,3"
	endpointOSBuild             = "25G72"
	endpointAVConferenceVersion = "2215.5.1"
	endpointClientVersion       = 1
	minimumVideoBitrate         = 5_000_000
	maximumVideoBitrate         = 60_000_000

	// AVConference caps negotiator mode 9 by the smaller of 40 Mbit/s and the
	// field-9 entry with bit 4096: Nearby settings use operatingMode 8
	// (arbiter mode 4) on connection type 4, and the blob decoder reads bit 12
	// as arbiter 4 on connection 4 (AVConference 2235.63.5.2, macOS 27.0.1
	// 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// -[AVCMediaStreamNegotiatorSettingsNearbyScreenSharing operatingMode] 0x1c0fb73b0,
	// -[AVCMediaStreamNegotiatorSettingsNearbyScreenSharing maxBandwidth] 0x1c0fb73b8,
	// -[AVCMediaStreamNegotiatorSettings connectionType] 0x1c11af800,
	// _VCBitrateArbiter_ModeFromOperatingMode 0x1c13b4ed0..0x1c13b4ee8, table 0x1c15ea260,
	// -[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) newBandwidthConfigurations]
	// 0x1c1315f10..0x1c1315f2c, 0x1c1316110..0x1c131612c,
	// -[AVCMediaStreamNegotiator setupVideoStreamConfiguration:errorReason:]
	// 0x1c12e0f00..0x1c12e0f70, 0x1c12e12e0..0x1c12e1310; AVConference 2260.14.1,
	// macOS 27.2 26B5101f, UUID EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// -[AVCMediaStreamNegotiatorSettingsNearbyScreenSharing operatingMode] 0x1c0632cd8,
	// -[AVCMediaStreamNegotiatorSettingsNearbyScreenSharing maxBandwidth] 0x1c0632ce0,
	// -[AVCMediaStreamNegotiatorSettings connectionType] 0x1c082bf4c,
	// _VCBitrateArbiter_ModeFromOperatingMode 0x1c0a262cc..0x1c0a262e4, table 0x1c0c675c0,
	// -[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) newBandwidthConfigurations]
	// 0x1c0986788..0x1c09867a4, 0x1c0986988..0x1c09869a4,
	// -[AVCMediaStreamNegotiator setupVideoStreamConfiguration:errorReason:]
	// 0x1c0954cf0..0x1c0954d60, 0x1c09550d0..0x1c0955100).
	nearbyBandwidthBit   = 4096
	nearbyMaximumBitrate = 40_000_000
)

// AVConference maps modes 5/7/9 to CoreDeviceScreenSharing, RemoteDesktopScreenSharing,
// and NearbyScreenSharing settings (AVConference 2235.63.5.2, macOS 27.0.1 26A434,
// UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
// +[AVCMediaStreamNegotiatorSettings negotiatorSettingsForMode:deviceRole:options:errorString:] 0x1c11af820..0x1c11af8e0,
// class table 0x1e1c9b9f0; AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
// +[AVCMediaStreamNegotiatorSettings negotiatorSettingsForMode:deviceRole:options:errorString:] 0x1c082bf6c..0x1c082c02c,
// class table 0x1e18e2f80).
type VideoMode int

const (
	VideoModeAutomatic     VideoMode = 0
	VideoModeCoreDevice    VideoMode = negotiatorModeCoreDeviceScreenSharing
	VideoModeRemoteDesktop VideoMode = negotiatorModeRemoteDesktopScreenSharing
	VideoModeNearby        VideoMode = negotiatorModeNearbyScreenSharing
)

func (m VideoMode) String() string {
	switch m {
	case VideoModeAutomatic:
		return "automatic"
	case VideoModeCoreDevice:
		return "CoreDevice"
	case VideoModeRemoteDesktop:
		return "RemoteDesktop"
	case VideoModeNearby:
		return "Nearby"
	default:
		return fmt.Sprint("mode(", int(m), ")")
	}
}

type Codec int

const (
	CodecHEVC Codec = iota + 1
	CodecH264
)

func (c Codec) String() string {
	switch c {
	case CodecHEVC:
		return "HEVC"
	case CodecH264:
		return "H.264"
	default:
		return fmt.Sprint("codec(", int(c), ")")
	}
}

type PixelFormats uint32

const (
	PixelFormat420FullRange       PixelFormats = 1 << 0
	PixelFormat420VideoRange      PixelFormats = 1 << 1
	PixelFormat420TenBit          PixelFormats = 1 << 2
	PixelFormat444FullRange       PixelFormats = 1 << 3
	PixelFormat444VideoRange      PixelFormats = 1 << 4
	PixelFormat444TenBitFullRange PixelFormats = 1 << 5
	PixelFormat420Alpha           PixelFormats = 1 << 6
	PixelFormat444Alpha16         PixelFormats = 1 << 7

	PixelFormatsSDR420 PixelFormats = PixelFormat420FullRange | PixelFormat420VideoRange
)

type OfferOptions struct {
	Codecs              []Codec
	DecoderMaxPixelRate int64
	MaximumFrameRate    int
	MaximumBitrate      int64
	CursorInVideo       bool
	PixelFormats        PixelFormats
	TilesPerFrame       int
	VideoMode           VideoMode
	Audio               bool
	ImmediatePlayout    bool
}

type Offer struct {
	options           OfferOptions
	videoMode         int64
	sessionID         [16]byte
	audioCallID       string
	videoCallID       string
	audioSend         [masterKeyLength]byte
	audioReceive      [masterKeyLength]byte
	videoSend         [masterKeyLength]byte
	videoReceive      [masterKeyLength]byte
	audioSSRC         uint32
	videoSSRC         uint32
	hdr               atomic.Bool
	decoderDimensions atomic.Uint64
}

func NewOffer(options OfferOptions) (*Offer, error) {
	if options.PixelFormats == 0 {
		options.PixelFormats = PixelFormatsSDR420
	}
	switch options.TilesPerFrame {
	case 0:
		options.TilesPerFrame = 1
	case 1, 4:
	default:
		return nil, E.New("media: unsupported tiles per frame ", options.TilesPerFrame)
	}
	if options.DecoderMaxPixelRate < 0 {
		return nil, E.New("media: decoder maximum pixel rate ", options.DecoderMaxPixelRate, " must not be negative")
	}
	switch options.MaximumFrameRate {
	case 0, 30, 60:
	default:
		return nil, E.New("media: unsupported maximum frame rate ", options.MaximumFrameRate)
	}
	if options.MaximumBitrate == 0 {
		options.MaximumBitrate = maximumVideoBitrate
	}
	if options.MaximumBitrate < minimumVideoBitrate || options.MaximumBitrate > maximumVideoBitrate {
		return nil, E.New("media: maximum bitrate ", options.MaximumBitrate, " bit/s is outside ", minimumVideoBitrate, " to ", maximumVideoBitrate, " bit/s")
	}
	offer := &Offer{options: options, videoMode: negotiatorModeRemoteDesktopScreenSharing}
	switch options.VideoMode {
	case VideoModeAutomatic:
		if options.TilesPerFrame == 1 {
			offer.videoMode = negotiatorModeNearbyScreenSharing
		}
	case VideoModeNearby:
		if options.TilesPerFrame != 1 {
			return nil, E.New("media: the Nearby profile sends one band, not ", options.TilesPerFrame)
		}
		offer.videoMode = negotiatorModeNearbyScreenSharing
	case VideoModeRemoteDesktop, VideoModeCoreDevice:
		offer.videoMode = int64(options.VideoMode)
	default:
		return nil, E.New("media: unsupported video mode ", int(options.VideoMode))
	}
	for _, buffer := range [][]byte{
		offer.sessionID[:], offer.audioSend[:], offer.audioReceive[:],
		offer.videoSend[:], offer.videoReceive[:],
	} {
		_, err := rand.Read(buffer)
		if err != nil {
			return nil, err
		}
	}
	setUUIDVersion4(&offer.sessionID)
	for _, callID := range []*string{&offer.audioCallID, &offer.videoCallID} {
		var id [16]byte
		_, err := rand.Read(id[:])
		if err != nil {
			return nil, err
		}
		setUUIDVersion4(&id)
		*callID = uuidString(id)
	}
	offer.audioSSRC = randomSSRC()
	offer.videoSSRC = randomSSRC()
	return offer, nil
}

func (o *Offer) LegacyFallback() (*Offer, error) {
	if o.videoMode != negotiatorModeNearbyScreenSharing {
		return nil, nil
	}
	fallback, err := NewOffer(o.options)
	if err != nil {
		return nil, err
	}
	fallback.videoMode = negotiatorModeRemoteDesktopScreenSharing
	fallback.hdr.Store(o.hdr.Load())
	fallback.decoderDimensions.Store(o.decoderDimensions.Load())
	return fallback, nil
}

func setUUIDVersion4(id *[16]byte) {
	id[6] = id[6]&0x0f | 0x40
	id[8] = id[8]&0x3f | 0x80
}

func uuidString(id [16]byte) string {
	return strings.ToUpper(fmt.Sprintf("%x-%x-%x-%x-%x", id[0:4], id[4:6], id[6:8], id[8:10], id[10:16]))
}

func (o *Offer) UpdateVideoDimensions(width, height int) {
	if width <= 0 || height <= 0 || uint64(width) > 0xffffffff || uint64(height) > 0xffffffff {
		o.decoderDimensions.Store(0)
		return
	}
	o.decoderDimensions.Store(uint64(width)<<32 | uint64(height))
}

func Supports60FPS(decoderMaxPixelRate int64, width, height int) bool {
	if decoderMaxPixelRate <= 0 || width <= 0 || height <= 0 {
		return false
	}
	// ScreenSharing _GetHEVCDecoderMaxSupportedFrameRate: the single-display path
	// divides MaxDecoderPixelRate by width * height * 3 and selects 60 FPS at a
	// result of at least 60 (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID
	// EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _GetHEVCDecoderMaxSupportedFrameRate 0x1cc0fbf9c..0x1cc0fc068;
	// ScreenSharing 786.3, macOS 27.2 26B5101f, UUID
	// 21DF7A41-0D38-3DF3-B87A-8A6249686735,
	// _GetHEVCDecoderMaxSupportedFrameRate 0x22e53f01c..0x22e53f0e8).
	return uint64(decoderMaxPixelRate)/uint64(width)/uint64(height)/3 >= 60
}

func (o *Offer) requests60FPS() bool {
	if o.options.MaximumFrameRate == 30 {
		return false
	}
	dimensions := o.decoderDimensions.Load()
	return Supports60FPS(o.options.DecoderMaxPixelRate, int(dimensions>>32), int(dimensions&0xffffffff))
}

func randomSSRC() uint32 {
	var buffer [4]byte
	for {
		_, _ = rand.Read(buffer[:])
		value := binary.BigEndian.Uint32(buffer[:])
		if value != 0 {
			return value
		}
	}
}

func ntpTime(t time.Time) uint64 {
	const secondsFrom1900To1970 = 2208988800
	seconds := uint64(t.Unix() + secondsFrom1900To1970)
	fraction := uint64(t.Nanosecond()) << 32 / 1_000_000_000
	return seconds<<32 | fraction
}

func (o *Offer) Message() []byte {
	created := ntpTime(time.Now())
	audioMode := int64(negotiatorModeRemoteDesktopSystemAudio)
	if !o.options.Audio {
		// RemoteMic settings make the answerer receive-only, so the server sends no audio
		// (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
		// -[AVCMediaStreamNegotiator initWithOffer:options:error:] 0x1c12dffb4,
		// -[AVCMediaStreamNegotiatorSettingsRemoteMic initWithOptions:deviceRole:error:] 0x1c1021f6c..0x1c1021f74).
		audioMode = negotiatorModeRemoteMic
	}
	audioOffer := o.streamOffer(audioMode, o.audioCallID, o.audioBlob(created))
	videoOffer := o.streamOffer(o.videoMode, o.videoCallID, o.videoBlob(created))
	// ScreenSharing _RFBMediaStreamServerConfiguration writes version 3, lengths
	// at 0x0a/0x0c/0x0e, UUID at 0x14, 46-byte audio keys at 0x24/0x52, audio blob
	// at 0x80, then two 46-byte video keys and the video blob; flag bits 0/2
	// select 60 FPS/no cursor (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID
	// EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBMediaStreamServerConfiguration 0x1cc14a050..0x1cc14a120, 0x1cc14a458,
	// 0x1cc14a478; ScreenSharing 786.3, macOS 27.2 26B5101f, UUID
	// 21DF7A41-0D38-3DF3-B87A-8A6249686735,
	// _RFBMediaStreamServerConfiguration 0x22e58d64c..0x22e58d71c, 0x22e58da54,
	// 0x22e58da74).
	message := make([]byte, 0x80, 0x80+len(audioOffer)+2*masterKeyLength+len(videoOffer))
	message[0] = ClientMessageMediaStream
	binary.BigEndian.PutUint16(message[4:], mediaStreamMessageVersion)
	var flags uint32
	if o.requests60FPS() {
		flags |= messageFlagFrameRate60
	}
	if !o.options.CursorInVideo {
		flags |= messageFlagNoCursor
	}
	binary.BigEndian.PutUint32(message[6:], flags)
	binary.BigEndian.PutUint16(message[0x0a:], uint16(len(audioOffer)))
	binary.BigEndian.PutUint16(message[0x0c:], uint16(len(videoOffer)))
	copy(message[0x14:], o.sessionID[:])
	copy(message[0x24:], o.audioSend[:])
	copy(message[0x52:], o.audioReceive[:])
	message = append(message, audioOffer...)
	message = append(message, o.videoSend[:]...)
	message = append(message, o.videoReceive[:]...)
	message = append(message, videoOffer...)
	binary.BigEndian.PutUint16(message[2:], uint16(len(message)-4))
	return message
}

func (o *Offer) streamOffer(mode int64, callID string, blob []byte) []byte {
	var compressed bytes.Buffer
	writer := zlib.NewWriter(&compressed)
	_, _ = writer.Write(blob)
	_ = writer.Close()
	var endpoint protoMessage
	endpoint.addUint(1, 0)
	endpoint.addUint(2, endpointClientVersion)
	endpoint.addBytes(3, []byte(endpointDeviceModel))
	endpoint.addBytes(4, []byte(endpointAVConferenceVersion))
	endpoint.addBytes(5, []byte(endpointOSBuild))
	entries := []plistEntry{
		{"avcMediaStreamOptionRemoteEndpointInfo", endpoint.encode()},
		{"avcMediaStreamNegotiatorMode", mode},
		{"avcMediaStreamNegotiatorMediaBlob", compressed.Bytes()},
		{"avcMediaStreamOptionCallID", callID},
	}
	if mode == negotiatorModeNearbyScreenSharing {
		entries = append(entries, plistEntry{"avcMediaStreamNegotiatorDirection", int64(2)})
	}
	return encodeBinaryPlist(entries)
}

func (o *Offer) appendBlobTail(blob *protoMessage, created uint64, video bool) {
	blob.addBytes(6, []byte(negotiatorUserAgent))
	blob.addUint(8, 0)
	// The fixed field-9 entries below occur in native AVConference mode-7 offers.
	for _, entry := range [][3]uint64{
		{4074, 0, 16384},
		{0, 20000000, 98304},
		{0, 40000000, 12288},
		{0, 100000000, 1048576},
		{0, 6000000, 131072},
		{1, 299, 0},
		{16, 4100, 0},
		{0, uint64(o.options.MaximumBitrate), 262144},
		{4, 6500, 0},
		{0, 75000000, 524288},
	} {
		if video && o.videoMode == negotiatorModeNearbyScreenSharing && entry[2] == 12288 {
			// AVConference bandwidthConfigurationWithArbiterMode:connectionType: returns
			// 4096 for arbiter 4 / connection 4 (AVConference 2235.63.5.2, macOS 27.0.1
			// 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
			// +[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) bandwidthConfigurationWithArbiterMode:connectionType:] 0x1c1315910..0x1c1315a04,
			// +[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) bandwidthConfigurationForAWDLWithArbiterMode:] 0x1c13156d0..0x1c13156ec,
			// table 0x1c15e9708; AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
			// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
			// +[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) bandwidthConfigurationWithArbiterMode:connectionType:] 0x1c0986188..0x1c098627c,
			// +[VCMediaNegotiationBlobBandwidthSettings(BandwidthSettings) bandwidthConfigurationForAWDLWithArbiterMode:] 0x1c0985f48..0x1c0985f64,
			// table 0x1c0c66a78).
			entry[1], entry[2] = uint64(min(o.options.MaximumBitrate, nearbyMaximumBitrate)), nearbyBandwidthBit
		}
		var item protoMessage
		item.addUint(1, entry[0])
		item.addUint(2, entry[1])
		if entry[2] != 0 {
			item.addUint(3, entry[2])
		}
		blob.addMessage(9, item)
	}
	blob.addUint(13, created)
	blob.addUint(14, negotiatorBlobVersion)
	blob.addUint(16, 0)
	blob.addUint(18, accessNetworkTypeViewer)
}

func (o *Offer) audioBlob(created uint64) []byte {
	var stream protoMessage
	stream.addUint(1, uint64(o.audioSSRC))
	stream.addUint(2, 0)
	stream.addUint(3, 0)
	stream.addUint(4, 0x5e7f)
	stream.addUint(5, 0)
	stream.addUint(6, 0)
	var blob protoMessage
	blob.addUint(1, 1)
	blob.addUint(2, 1)
	blob.addMessage(3, stream)
	o.appendBlobTail(&blob, created, false)
	return blob.encode()
}

func (o *Offer) videoBlob(created uint64) []byte {
	var stream protoMessage
	stream.addUint(1, uint64(o.videoSSRC))
	stream.addUint(2, 0)
	for _, codec := range []Codec{CodecH264, CodecHEVC} {
		if !slices.Contains(o.options.Codecs, codec) {
			continue
		}
		var payload protoMessage
		var features string
		var parameterSets uint64
		switch codec {
		case CodecH264:
			payload.addUint(1, payloadTypeH264)
			features, parameterSets = featureListH264, parameterSetsH264
		case CodecHEVC:
			payload.addUint(1, payloadTypeHEVC)
			features, parameterSets = featureListHEVC, parameterSetsHEVC
		}
		for _, operation := range []uint64{videoRuleOperationEncode, videoRuleOperationDecode} {
			var rule protoMessage
			rule.addUint(1, videoRuleTransport)
			rule.addUint(2, operation)
			rule.addUint(3, screenFormats)
			rule.addUint(4, 0)
			payload.addMessage(2, rule)
		}
		payload.addBytes(3, []byte(features))
		payload.addUint(4, parameterSets)
		stream.addMessage(3, payload)
	}
	stream.addUint(6, uint64(o.options.TilesPerFrame))
	stream.addUint(7, 1)
	// AVConference storePixelFormatsInBitMap: maps bits 0–7 to 420f, 420v, x420,
	// 444f, 444v, xf44, v0a8, s4as (AVConference 2235.63.5.2, macOS 27.0.1
	// 26A434, UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// +[VCMediaNegotiationBlobVideoSettings(VideoRules) storePixelFormatsInBitMap:] 0x1c130f5e0..0x1c130f7cc;
	// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
	// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// +[VCMediaNegotiationBlobVideoSettings(VideoRules) storePixelFormatsInBitMap:] 0x1c097fe58..0x1c0980044).
	pixelFormats := o.options.PixelFormats
	hdrModes := uint64(1 << hdrModeStandard)
	if o.hdr.Load() && pixelFormats&PixelFormat444TenBitFullRange != 0 {
		// AVConference isPixelFormatValid:hdrMode: accepts only x444 and xf44 for
		// HDR mode 3, and 444f and 420f only for HDR mode 0 (AVConference
		// 2235.63.5.2, macOS 27.0.1 26A434, UUID
		// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
		// +[AVCVideoStreamConfig isPixelFormatValid:hdrMode:] 0x1c143b3fc..0x1c143b56c;
		// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
		// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
		// +[AVCVideoStreamConfig isPixelFormatValid:hdrMode:] 0x1c0ab88a4..0x1c0ab8a14).
		hdrModes |= 1 << hdrModeReference
	}
	stream.addUint(8, uint64(pixelFormats))
	stream.addUint(9, hdrModes)
	stream.addUint(12, 1)
	var blob protoMessage
	blob.addUint(1, 1)
	blob.addUint(2, 1)
	blob.addMessage(5, stream)
	o.appendBlobTail(&blob, created, true)
	return blob.encode()
}
