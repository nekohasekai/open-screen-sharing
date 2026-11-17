package media

import (
	"bytes"
	"encoding/binary"
)

const (
	maxAccessUnitSize  = 32 << 20
	maxNALUnitsPerUnit = 4096
	maxHeldPackets     = 16384
	hevcNALAggregation = 48
	hevcNALFragment    = 49
	hevcNALPACI        = 50
	h264NALSTAPA       = 24
	h264NALSTAPB       = 25
	h264NALFUA         = 28
	h264NALFUB         = 29
	hevcNALVPS         = 32
	hevcNALSPS         = 33
	hevcNALPPS         = 34
	h264NALSPS         = 7
	h264NALPPS         = 8
	h264NALIDR         = 5
	decodeOrderAbsent  = -1
	maxSparePackets    = 1024
)

type accessUnit struct {
	ssrc          uint32
	timestamp     uint32
	decodeOrder   int
	data          []byte
	parameterSets [][]byte
	receivedAt    int64
	completedAt   int64
}

func hevcNALType(nal []byte) int {
	return int(nal[0]>>1) & 0x3f
}

func nextNAL(data []byte) (nal []byte, rest []byte) {
	end := 4 + int(binary.BigEndian.Uint32(data))
	return data[4:end], data[end:]
}

func (u *accessUnit) isSync(codec Codec) bool {
	for rest := u.data; len(rest) > 0; {
		var nal []byte
		nal, rest = nextNAL(rest)
		switch codec {
		case CodecHEVC:
			nalType := hevcNALType(nal)
			if nalType >= 16 && nalType <= 23 {
				return true
			}
		case CodecH264:
			if nal[0]&0x1f == h264NALIDR {
				return true
			}
		}
	}
	return false
}

// With FLS EOD:1, VideoProcessing reads bit 1 of the last VCL NAL's
// control byte, ignoring non-VCL NALs such as suffix SEI (VideoProcessing
// 1395.65.1, macOS 27.0.1 26A434, UUID D87079FD-5315-32B1-8022-E6144A69A8EE,
// sub_1b50c8bc0 0x1b50c8bc0..0x1b50c8dc4, sub_1b50c95c4 0x1b50c987c..0x1b50c9894;
// VideoProcessing 1415.6.1, macOS 27.2 26B5101f, UUID
// A6FC3A01-8834-335F-A877-FDACE2E1AECC, sub_1cb6f1744 0x1cb6f1744..0x1cb6f1948,
// sub_1cb6f2148 0x1cb6f2400..0x1cb6f2418).
func (u *accessUnit) frameEnd(codec Codec) (end, hasPicture bool) {
	for rest := u.data; len(rest) > 0; {
		var nal []byte
		nal, rest = nextNAL(rest)
		switch codec {
		case CodecHEVC:
			if len(nal) < 3 || hevcNALType(nal) >= 32 {
				continue
			}
		default:
			if len(nal) < 2 || nal[0]&0x1f < 1 || nal[0]&0x1f > 5 {
				continue
			}
		}
		end, hasPicture = nal[len(nal)-1]&0x02 != 0, true
	}
	return end, hasPicture
}

func isParameterSet(codec Codec, nal []byte) bool {
	switch codec {
	case CodecHEVC:
		nalType := hevcNALType(nal)
		return nalType == hevcNALVPS || nalType == hevcNALSPS || nalType == hevcNALPPS
	default:
		nalType := nal[0] & 0x1f
		return nalType == h264NALSPS || nalType == h264NALPPS
	}
}

type ssrcDepacketizer struct {
	codec        Codec
	decodeOrders bool
	ssrc         uint32

	started      bool
	nextSequence uint16
	held         map[uint16]heldPacket
	gapSince     int64
	newestHeld   uint32
	lastPacketAt int64
	late         uint64
	spare        [][]byte

	unit          *accessUnit
	damaged       bool
	nalUnits      []byte
	count         int
	fragmentStart int
	inFragment    bool
	avcC          [][]byte

	emit func(unit *accessUnit, damaged bool)
}

type heldPacket struct {
	raw       []byte
	timestamp uint32
	arrival   int64
}

func newSSRCDepacketizer(codec Codec, decodeOrders bool, ssrc uint32, emit func(*accessUnit, bool)) *ssrcDepacketizer {
	return &ssrcDepacketizer{
		codec:        codec,
		decodeOrders: decodeOrders,
		ssrc:         ssrc,
		held:         make(map[uint16]heldPacket),
		emit:         emit,
	}
}

func (d *ssrcDepacketizer) push(packet rtpPacket, raw []byte, now int64) {
	if !d.started {
		d.started = true
		d.nextSequence = packet.sequence
	}
	if packet.sequence != d.nextSequence {
		if !sequenceNewer(packet.sequence, d.nextSequence) {
			d.late++
			return
		}
		if _, exists := d.held[packet.sequence]; exists {
			return
		}
		if len(d.held) == 0 {
			d.gapSince = now
			d.newestHeld = packet.timestamp
		} else if timestampNewer(packet.timestamp, d.newestHeld) {
			d.newestHeld = packet.timestamp
		}
		d.held[packet.sequence] = heldPacket{raw: d.keep(raw), timestamp: packet.timestamp, arrival: now}
		if len(d.held) > maxHeldPackets {
			d.skipGap(now)
		}
		return
	}
	d.process(packet, now)
	d.nextSequence++
	d.drainHeld(now)
}

func (d *ssrcDepacketizer) keep(raw []byte) []byte {
	var buffer []byte
	if last := len(d.spare) - 1; last >= 0 && cap(d.spare[last]) >= len(raw) {
		buffer = d.spare[last]
		d.spare[last] = nil
		d.spare = d.spare[:last]
	}
	return append(buffer[:0], raw...)
}

func (d *ssrcDepacketizer) recycle(raw []byte) {
	if len(d.spare) < maxSparePackets {
		d.spare = append(d.spare, raw)
	}
}

func (d *ssrcDepacketizer) stalled() (timestamp uint32, since int64, newer bool, ok bool) {
	switch {
	case len(d.held) > 0:
		if d.unit != nil {
			timestamp = d.unit.timestamp
		} else {
			first := true
			var lowest uint16
			for sequence := range d.held {
				if first || sequenceNewer(lowest, sequence) {
					lowest = sequence
					first = false
				}
			}
			timestamp = d.held[lowest].timestamp
		}
		return timestamp, d.gapSince, timestampNewer(d.newestHeld, timestamp), true
	case d.unit != nil:
		return d.unit.timestamp, d.lastPacketAt, false, true
	}
	return 0, 0, false, false
}

func (d *ssrcDepacketizer) giveUp(now int64) {
	if len(d.held) > 0 {
		d.skipGap(now)
		return
	}
	if d.unit != nil {
		d.damaged = true
		d.finish()
	}
}

func (d *ssrcDepacketizer) skipGap(now int64) {
	var lowest uint16
	first := true
	for sequence := range d.held {
		if first || sequenceNewer(lowest, sequence) {
			lowest = sequence
			first = false
		}
	}
	d.damaged = true
	d.dropFragment()
	d.nextSequence = lowest
	d.drainHeld(now)
}

func (d *ssrcDepacketizer) drainHeld(now int64) {
	for len(d.held) > 0 {
		held, found := d.held[d.nextSequence]
		if !found {
			d.gapSince = now
			return
		}
		delete(d.held, d.nextSequence)
		packet, err := parseRTPHeader(held.raw)
		if err == nil {
			packet, err = parseRTPPayload(held.raw, packet)
		}
		if err == nil {
			d.process(packet, held.arrival)
		}
		d.recycle(held.raw)
		d.nextSequence++
	}
}

func (d *ssrcDepacketizer) reset() {
	d.started = false
	d.nextSequence = 0
	for _, held := range d.held {
		d.recycle(held.raw)
	}
	clear(d.held)
	d.gapSince = 0
	d.unit = nil
	d.damaged = false
	d.inFragment = false
	d.nalUnits = d.nalUnits[:0]
	d.count = 0
}

func (d *ssrcDepacketizer) process(packet rtpPacket, arrival int64) {
	d.lastPacketAt = max(d.lastPacketAt, arrival)
	if d.unit != nil && d.unit.timestamp != packet.timestamp {
		d.finish()
	}
	if d.unit == nil {
		d.unit = &accessUnit{
			ssrc:        d.ssrc,
			timestamp:   packet.timestamp,
			decodeOrder: decodeOrderAbsent,
			receivedAt:  arrival,
		}
	}
	d.unit.completedAt = max(d.unit.completedAt, arrival)
	var ok bool
	switch d.codec {
	case CodecHEVC:
		ok = d.processHEVC(packet.payload)
	default:
		ok = d.processH264(packet.payload)
	}
	if !ok {
		d.damaged = true
	}
	if packet.marker {
		d.finish()
	}
}

func (d *ssrcDepacketizer) finish() {
	unit := d.unit
	damaged := d.damaged || d.inFragment
	empty := d.count == 0
	if unit != nil && !damaged && !empty {
		d.copyNALUnits(unit)
	}
	d.unit = nil
	d.damaged = false
	d.inFragment = false
	d.nalUnits = d.nalUnits[:0]
	d.count = 0
	if unit == nil || (empty && !damaged) {
		return
	}
	d.emit(unit, damaged)
}

func (d *ssrcDepacketizer) copyNALUnits(unit *accessUnit) {
	size := 0
	for rest := d.nalUnits; len(rest) > 0; {
		var nal []byte
		nal, rest = nextNAL(rest)
		if isParameterSet(d.codec, nal) {
			unit.parameterSets = append(unit.parameterSets, bytes.Clone(nal))
		} else {
			size += 4 + len(nal)
		}
	}
	if len(unit.parameterSets) == 0 {
		unit.data = bytes.Clone(d.nalUnits)
		return
	}
	if size == 0 {
		return
	}
	unit.data = make([]byte, 0, size)
	for rest := d.nalUnits; len(rest) > 0; {
		var nal []byte
		nal, rest = nextNAL(rest)
		if !isParameterSet(d.codec, nal) {
			unit.data = binary.BigEndian.AppendUint32(unit.data, uint32(len(nal)))
			unit.data = append(unit.data, nal...)
		}
	}
}

func (d *ssrcDepacketizer) setDecodeOrder(value uint16) {
	if d.unit.decodeOrder == decodeOrderAbsent {
		d.unit.decodeOrder = int(value)
	}
}

func (d *ssrcDepacketizer) appendNAL(header []byte, body []byte) bool {
	length := len(header) + len(body)
	if length == 0 || d.count >= maxNALUnitsPerUnit {
		return false
	}
	d.nalUnits = binary.BigEndian.AppendUint32(d.nalUnits, uint32(length))
	d.nalUnits = append(d.nalUnits, header...)
	d.nalUnits = append(d.nalUnits, body...)
	d.count++
	return true
}

func (d *ssrcDepacketizer) startFragment(header ...byte) {
	d.dropFragment()
	d.fragmentStart = len(d.nalUnits)
	d.nalUnits = append(d.nalUnits, 0, 0, 0, 0)
	d.nalUnits = append(d.nalUnits, header...)
	d.inFragment = true
}

func (d *ssrcDepacketizer) continueFragment(body []byte, end bool) bool {
	if len(d.nalUnits)-d.fragmentStart-4+len(body) > maxAccessUnitSize {
		d.dropFragment()
		return false
	}
	d.nalUnits = append(d.nalUnits, body...)
	if !end {
		return true
	}
	d.inFragment = false
	length := len(d.nalUnits) - d.fragmentStart - 4
	if d.count >= maxNALUnitsPerUnit {
		d.nalUnits = d.nalUnits[:d.fragmentStart]
		return false
	}
	binary.BigEndian.PutUint32(d.nalUnits[d.fragmentStart:], uint32(length))
	d.count++
	return true
}

func (d *ssrcDepacketizer) dropFragment() {
	if d.inFragment {
		d.nalUnits = d.nalUnits[:d.fragmentStart]
		d.inFragment = false
	}
}

func (d *ssrcDepacketizer) processHEVC(payload []byte) bool {
	if len(payload) < 3 {
		return false
	}
	header := payload[:2]
	nalType := int(payload[0]>>1) & 0x3f
	switch {
	case nalType < hevcNALAggregation:
		d.dropFragment()
		body := payload[2:]
		if d.decodeOrders {
			if len(body) < 2 {
				return false
			}
			d.setDecodeOrder(binary.BigEndian.Uint16(body))
			body = body[2:]
		}
		return d.appendNAL(header, body)
	case nalType == hevcNALAggregation:
		d.dropFragment()
		body := payload[2:]
		if d.decodeOrders {
			if len(body) < 2 {
				return false
			}
			d.setDecodeOrder(binary.BigEndian.Uint16(body))
			body = body[2:]
		}
		for len(body) > 0 {
			if len(body) < 2 {
				return false
			}
			size := int(binary.BigEndian.Uint16(body))
			if size == 0 || len(body) < 2+size {
				return false
			}
			if !d.appendNAL(nil, body[2:2+size]) {
				return false
			}
			body = body[2+size:]
		}
		return true
	case nalType == hevcNALFragment:
		fragmentHeader := payload[2]
		body := payload[3:]
		start := fragmentHeader&0x80 != 0
		end := fragmentHeader&0x40 != 0
		if d.decodeOrders {
			if len(body) < 2 {
				return false
			}
			if start {
				d.setDecodeOrder(binary.BigEndian.Uint16(body))
			}
			body = body[2:]
		}
		if start {
			d.startFragment(payload[0]&0x81|(fragmentHeader&0x3f)<<1, payload[1])
		} else if !d.inFragment {
			return false
		}
		return d.continueFragment(body, end)
	default:
		return nalType == hevcNALPACI
	}
}

func (d *ssrcDepacketizer) processH264(payload []byte) bool {
	if len(payload) == 0 {
		return false
	}
	nalType := payload[0] & 0x1f
	switch {
	case nalType >= 1 && nalType <= 23:
		d.dropFragment()
		var found bool
		d.avcC, found = avcCParameterSets(payload, d.avcC[:0])
		if found {
			for _, set := range d.avcC {
				if !d.appendNAL(nil, set) {
					return false
				}
			}
			return true
		}
		return d.appendNAL(nil, payload)
	case nalType == h264NALSTAPA || nalType == h264NALSTAPB:
		d.dropFragment()
		body := payload[1:]
		if nalType == h264NALSTAPB {
			if len(body) < 2 {
				return false
			}
			d.setDecodeOrder(binary.BigEndian.Uint16(body))
			body = body[2:]
		}
		for len(body) > 0 {
			if len(body) < 2 {
				return false
			}
			size := int(binary.BigEndian.Uint16(body))
			if size == 0 || len(body) < 2+size {
				return false
			}
			item := body[2 : 2+size]
			body = body[2+size:]
			if item[0]&0x80 == 0 && item[0]&0x1f >= 1 && item[0]&0x1f <= 23 {
				if !d.appendNAL(nil, item) {
					return false
				}
				continue
			}
			d.avcC, _ = avcCParameterSets(item, d.avcC[:0])
			for _, set := range d.avcC {
				if !d.appendNAL(nil, set) {
					return false
				}
			}
		}
		return true
	case nalType == h264NALFUA || nalType == h264NALFUB:
		if len(payload) < 2 {
			return false
		}
		fragmentHeader := payload[1]
		body := payload[2:]
		start := fragmentHeader&0x80 != 0
		end := fragmentHeader&0x40 != 0
		if nalType == h264NALFUB {
			if len(body) < 2 {
				return false
			}
			d.setDecodeOrder(binary.BigEndian.Uint16(body))
			body = body[2:]
		}
		if start {
			d.startFragment(payload[0]&0xe0 | fragmentHeader&0x1f)
		} else if !d.inFragment {
			return false
		}
		return d.continueFragment(body, end)
	default:
		return false
	}
}

func avcCParameterSets(data []byte, sets [][]byte) ([][]byte, bool) {
	for {
		position := bytes.Index(data, []byte("avcC"))
		if position < 4 {
			if position < 0 {
				return sets, false
			}
			data = data[position+4:]
			continue
		}
		size := int(binary.BigEndian.Uint32(data[position-4:]))
		end := position - 4 + size
		if size >= 8 && end <= len(data) {
			var found bool
			sets, found = parseAVCDecoderConfiguration(data[position+4:end], sets)
			if found {
				return sets, true
			}
		}
		data = data[position+4:]
	}
}

func parseAVCDecoderConfiguration(record []byte, sets [][]byte) ([][]byte, bool) {
	if len(record) < 7 || record[0] != 1 {
		return sets, false
	}
	start := len(sets)
	position := 5
	readSets := func(count int, expectedType byte) bool {
		for range count {
			if position+2 > len(record) {
				return false
			}
			length := int(binary.BigEndian.Uint16(record[position:]))
			position += 2
			if length == 0 || position+length > len(record) || record[position]&0x1f != expectedType {
				return false
			}
			sets = append(sets, record[position:position+length])
			position += length
		}
		return true
	}
	spsCount := int(record[position] & 0x1f)
	position++
	if spsCount == 0 || !readSets(spsCount, h264NALSPS) || position >= len(record) {
		return sets[:start], false
	}
	ppsCount := int(record[position])
	position++
	if ppsCount == 0 || !readSets(ppsCount, h264NALPPS) {
		return sets[:start], false
	}
	return sets, true
}
