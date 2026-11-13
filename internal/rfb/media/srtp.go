package media

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/binary"
	"hash"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	authTagLength     = 10
	sessionSaltLength = 14
	replayWindowWords = 64
	replayWindowSize  = replayWindowWords * 64
)

var errAuthentication = E.New("media: SRTP authentication failed")

type sessionKeys struct {
	block  cipher.Block
	stream *keyStream
	mac    hash.Hash
	salt   [sessionSaltLength]byte
	iv     [aes.BlockSize]byte
	sum    [sha1.Size]byte
}

func deriveSessionKeys(master []byte, labelBase byte) *sessionKeys {
	masterBlock, _ := aes.NewCipher(master[:32])
	derive := func(label byte, length int) []byte {
		var iv [16]byte
		copy(iv[:], master[32:32+sessionSaltLength])
		iv[7] ^= label
		output := make([]byte, length)
		cipher.NewCTR(masterBlock, iv[:]).XORKeyStream(output, output)
		return output
	}
	keys := &sessionKeys{}
	keys.block, _ = aes.NewCipher(derive(labelBase, 32))
	keys.stream = newKeyStream(keys.block)
	keys.mac = hmac.New(sha1.New, derive(labelBase+1, 20))
	copy(keys.salt[:], derive(labelBase+2, sessionSaltLength))
	return keys
}

func (k *sessionKeys) xorKeyStream(ssrc uint32, index uint64, data []byte) {
	k.iv = [aes.BlockSize]byte{}
	copy(k.iv[:], k.salt[:])
	var ssrcBytes [4]byte
	binary.BigEndian.PutUint32(ssrcBytes[:], ssrc)
	for i := range 4 {
		k.iv[4+i] ^= ssrcBytes[i]
	}
	var indexBytes [8]byte
	binary.BigEndian.PutUint64(indexBytes[:], index<<16)
	for i := range 8 {
		k.iv[8+i] ^= indexBytes[i]
	}
	if k.stream != nil {
		k.stream.xorKeyStream(&k.iv, data)
		return
	}
	cipher.NewCTR(k.block, k.iv[:]).XORKeyStream(data, data)
}

func (k *sessionKeys) tag(data []byte) []byte {
	k.mac.Reset()
	k.mac.Write(data)
	return k.mac.Sum(k.sum[:0])[:authTagLength]
}

func (k *sessionKeys) rtpTag(packet []byte, rollover uint32) []byte {
	k.mac.Reset()
	k.mac.Write(packet)
	binary.BigEndian.PutUint32(k.sum[:], rollover)
	k.mac.Write(k.sum[:4])
	return k.mac.Sum(k.sum[:0])[:authTagLength]
}

type replayState struct {
	started bool
	highest uint64
	window  [replayWindowWords]uint64
}

func (r *replayState) check(index uint64) bool {
	if !r.started || index > r.highest {
		return true
	}
	distance := r.highest - index
	if distance >= replayWindowSize {
		return false
	}
	return r.window[distance/64]&(1<<(distance%64)) == 0
}

func (r *replayState) accept(index uint64) {
	if !r.started {
		r.started = true
		r.highest = index
		r.window = [replayWindowWords]uint64{1}
		return
	}
	if index > r.highest {
		shift := index - r.highest
		if shift >= replayWindowSize {
			r.window = [replayWindowWords]uint64{}
		} else {
			words := int(shift / 64)
			bits := shift % 64
			for i := replayWindowWords - 1; i >= 0; i-- {
				var value uint64
				if i-words >= 0 {
					value = r.window[i-words] << bits
					if bits != 0 && i-words-1 >= 0 {
						value |= r.window[i-words-1] >> (64 - bits)
					}
				}
				r.window[i] = value
			}
		}
		r.window[0] |= 1
		r.highest = index
		return
	}
	distance := r.highest - index
	r.window[distance/64] |= 1 << (distance % 64)
}

func (r *replayState) estimateIndex(sequence uint16) uint64 {
	if !r.started {
		return uint64(sequence)
	}
	rollover := r.highest >> 16
	highestSequence := uint16(r.highest)
	if highestSequence < 0x8000 {
		if int(sequence)-int(highestSequence) > 0x8000 && rollover > 0 {
			rollover--
		}
	} else if int(highestSequence)-0x8000 > int(sequence) {
		rollover++
	}
	return rollover<<16 | uint64(sequence)
}

type srtpReceiver struct {
	keys    *sessionKeys
	replays map[uint32]*replayState
}

func newSRTPReceiver(master []byte) *srtpReceiver {
	return &srtpReceiver{keys: deriveSessionKeys(master, 0), replays: make(map[uint32]*replayState)}
}

func (r *srtpReceiver) unprotect(datagram []byte) (rtpPacket, error) {
	if len(datagram) < 12+authTagLength {
		return rtpPacket{}, E.New("media: SRTP packet of ", len(datagram), " bytes is shorter than ", 12+authTagLength)
	}
	body := datagram[:len(datagram)-authTagLength]
	packet, err := parseRTPHeader(body)
	if err != nil {
		return rtpPacket{}, err
	}
	replay := r.replays[packet.ssrc]
	if replay == nil {
		replay = &replayState{}
	}
	index := replay.estimateIndex(packet.sequence)
	if !replay.check(index) {
		return rtpPacket{}, E.New("media: SRTP packet ", index, " of SSRC ", packet.ssrc, " is replayed or too old")
	}
	if subtle.ConstantTimeCompare(r.keys.rtpTag(body, uint32(index>>16)), datagram[len(body):]) != 1 {
		return rtpPacket{}, errAuthentication
	}
	if r.replays[packet.ssrc] == nil {
		r.replays[packet.ssrc] = replay
	}
	replay.accept(index)
	r.keys.xorKeyStream(packet.ssrc, index, body[packet.payloadOffset:])
	return parseRTPPayload(body, packet)
}

type srtcpReceiver struct {
	keys    *sessionKeys
	replays map[uint32]*replayState
}

func newSRTCPReceiver(master []byte) *srtcpReceiver {
	return &srtcpReceiver{keys: deriveSessionKeys(master, 3), replays: make(map[uint32]*replayState)}
}

func (r *srtcpReceiver) unprotect(datagram []byte) ([]byte, error) {
	if len(datagram) < 8+4+authTagLength {
		return nil, E.New("media: SRTCP packet of ", len(datagram), " bytes is shorter than ", 8+4+authTagLength)
	}
	end := len(datagram) - authTagLength
	if subtle.ConstantTimeCompare(r.keys.tag(datagram[:end]), datagram[end:]) != 1 {
		return nil, errAuthentication
	}
	ssrc := binary.BigEndian.Uint32(datagram[4:])
	word := binary.BigEndian.Uint32(datagram[end-4:])
	index := word & 0x7fffffff
	replay := r.replays[ssrc]
	if replay == nil {
		if len(r.replays) >= maxTrackedVideoSSRC {
			clear(r.replays)
		}
		replay = &replayState{}
		r.replays[ssrc] = replay
	}
	if !replay.check(uint64(index)) {
		return nil, E.New("media: SRTCP packet ", index, " of SSRC ", ssrc, " is replayed or too old")
	}
	replay.accept(uint64(index))
	packet := datagram[:end-4]
	if word&0x80000000 != 0 {
		r.keys.xorKeyStream(ssrc, uint64(index), packet[8:])
	}
	return packet, nil
}

type srtcpSender struct {
	keys  *sessionKeys
	index uint32
}

func newSRTCPSender(master []byte) *srtcpSender {
	return &srtcpSender{keys: deriveSessionKeys(master, 3)}
}

func (s *srtcpSender) appendProtected(out []byte, packet []byte) []byte {
	ssrc := binary.BigEndian.Uint32(packet[4:])
	start := len(out)
	out = append(out, packet...)
	s.keys.xorKeyStream(ssrc, uint64(s.index), out[start+8:])
	out = binary.BigEndian.AppendUint32(out, 0x80000000|s.index)
	out = append(out, s.keys.tag(out[start:])...)
	s.index = (s.index + 1) & 0x7fffffff
	return out
}
