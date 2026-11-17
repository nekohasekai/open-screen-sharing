package media

import (
	"bytes"
	"math"
	"slices"
	"time"
)

const (
	videoClockRate      = 24000
	maxHeldUnits        = 32
	decodeOrderWait     = 150 * time.Millisecond
	maxTrackedVideoSSRC = 16
	frameStepWindow     = 120
	// In AVConference video jitter-buffer mode 2, the minimum and maximum
	// queue sizes default to 0.1 s and 0.5 s, and the video player's average
	// decode time defaults to 1/60 s; VCDefaults can override each default
	// (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID
	// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// _VCVideoJitterBuffer_UpdateMode 0x1c0e43644..0x1c0e43764,
	// _VCVideoPlayer_UpdateJitterBufferMode 0x1c0f08f84..0x1c0f08fa0;
	// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
	// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// _VCVideoJitterBuffer_UpdateMode 0x1c04bd9a4..0x1c04bdac4,
	// _VCVideoPlayer_UpdateJitterBufferMode 0x1c0583a14..0x1c0583a30).
	minReorderWait     = 100 * time.Millisecond
	maxReorderWait     = 500 * time.Millisecond
	displayAllowance   = time.Second / 60
	playoutClockWindow = 2 * time.Second
	playoutSlewDivisor = 100
	playoutQuantile    = 0.95
	playoutAllowance   = 4 * time.Millisecond
)

type videoAssembler struct {
	codec        Codec
	bands        int
	decodeOrders bool
	sink         Sink
	clock        *senderClock
	onLoss       func()
	immediate    bool

	depacketizers map[uint32]*ssrcDepacketizer
	lastSeen      map[uint32]int64

	expectedBase  uint32
	haveExpected  bool
	width         int
	height        int
	base          uint32
	awaitingSync  bool
	recovering    bool
	nextOrder     uint16
	held          []*accessUnit
	stalledSince  int64
	parameterSets [][]byte
	timestamps    timestampExtender
	formatPending bool

	frameID        int64
	frameActive    bool
	frameTimestamp uint32
	frameBands     uint32
	frameStartedAt int64
	frameSteps     []int64
	framePeriod    int64
	discontinuity  bool
	err            error

	acknowledgesReferences bool
	acknowledgment         uint16
	acknowledgmentPending  bool

	anchored        bool
	anchorAt        int64
	anchorTimestamp int64
	targetAnchorAt  int64
	clockTimestamp  int64
	outbox          []videoOutput
	playoutDelay    int64
	offsets         []int64
	windowSince     int64

	now      int64
	received uint64
	units    uint64
	frames   uint64
	losses   uint64
}

type videoOutput struct {
	format *VideoFormat
	unit   VideoAccessUnit
	due    int64
}

func newVideoAssembler(codec Codec, bands int, immediate bool, sink Sink, clock *senderClock, onLoss func()) *videoAssembler {
	return &videoAssembler{
		codec:         codec,
		bands:         bands,
		decodeOrders:  bands > 1,
		sink:          sink,
		clock:         clock,
		onLoss:        onLoss,
		immediate:     immediate,
		depacketizers: make(map[uint32]*ssrcDepacketizer),
		lastSeen:      make(map[uint32]int64),
		awaitingSync:  true,
	}
}

func (a *videoAssembler) expectAnswer(answer *Answer) {
	if a.haveExpected && (answer.SSRC != a.expectedBase || answer.Width != a.width || answer.Height != a.height) {
		a.requestSync()
		a.recovering = false
		clear(a.depacketizers)
		clear(a.lastSeen)
		a.timestamps = timestampExtender{}
		a.formatPending = true
		a.flush(math.MaxInt64)
		a.anchored = false
	}
	a.expectedBase = answer.SSRC
	a.haveExpected = true
	a.acknowledgesReferences = answer.LTRPEnabled && a.codec == CodecHEVC
	a.width = answer.Width
	a.height = answer.Height
}

func (a *videoAssembler) push(packet rtpPacket, raw []byte, now int64) {
	a.now = now
	if a.haveExpected && packet.ssrc-a.expectedBase >= uint32(a.bands) {
		return
	}
	depacketizer := a.depacketizers[packet.ssrc]
	if depacketizer == nil {
		if len(a.depacketizers) >= maxTrackedVideoSSRC {
			a.forgetOldestSSRC()
		}
		depacketizer = newSSRCDepacketizer(a.codec, a.decodeOrders, packet.ssrc, a.accept)
		a.depacketizers[packet.ssrc] = depacketizer
	}
	a.lastSeen[packet.ssrc] = now
	depacketizer.push(packet, raw, now)
	a.flush(now)
}

func (a *videoAssembler) forgetOldestSSRC() {
	var oldest uint32
	var oldestTime int64
	first := true
	for ssrc, seen := range a.lastSeen {
		if first || seen < oldestTime {
			oldest = ssrc
			oldestTime = seen
			first = false
		}
	}
	delete(a.depacketizers, oldest)
	delete(a.lastSeen, oldest)
}

func (a *videoAssembler) tick(now int64) {
	a.now = now
	for _, depacketizer := range a.depacketizers {
		timestamp, since, newer, stalled := depacketizer.stalled()
		if stalled && now >= a.giveUpAt(timestamp, since, newer) {
			depacketizer.giveUp(now)
		}
	}
	defer a.flush(now)
	if !a.awaitingSync && a.decodeOrders && a.stalledSince != 0 && now-a.stalledSince >= int64(decodeOrderWait) {
		a.lose()
	}
	if !a.awaitingSync && a.frameActive && now-a.frameStartedAt >= int64(decodeOrderWait) {
		a.lose()
	}
}

func (a *videoAssembler) giveUpAt(timestamp uint32, since int64, newer bool) int64 {
	floor := since + int64(minReorderWait)
	limit := since + int64(maxReorderWait)
	switch {
	case a.anchored:
		return min(max(a.due(timestamp)+int64(displayAllowance), floor), limit)
	case newer:
		return floor
	default:
		return limit
	}
}

func (a *videoAssembler) due(timestamp uint32) int64 {
	extended := int64(timestamp)
	if a.timestamps.started {
		extended = a.timestamps.value + int64(int32(timestamp-a.timestamps.last))
	}
	return a.anchorAt + rtpDuration(extended-a.anchorTimestamp)
}

func rtpDuration(ticks int64) int64 {
	return ticks * int64(time.Millisecond) / (videoClockRate / 1000)
}

func (a *videoAssembler) flush(now int64) {
	sent := 0
	for _, output := range a.outbox {
		if output.format == nil && output.due > now {
			break
		}
		if output.format != nil {
			a.sink.VideoFormat(*output.format)
		} else {
			a.sink.VideoAccessUnit(output.unit)
		}
		sent++
	}
	if sent > 0 {
		a.outbox = append(a.outbox[:0], a.outbox[sent:]...)
	}
}

func (a *videoAssembler) nextDue() int64 {
	if len(a.outbox) == 0 {
		return 0
	}
	return a.outbox[0].due
}

func (a *videoAssembler) latePackets() uint64 {
	var late uint64
	for _, depacketizer := range a.depacketizers {
		late += depacketizer.late
	}
	return late
}

func (a *videoAssembler) wantsKeyframe() bool {
	return a.awaitingSync && a.recovering
}

func (a *videoAssembler) requestSync() {
	a.awaitingSync = true
	a.recovering = true
	a.stalledSince = 0
	a.held = a.held[:0]
	a.frameActive = false
	a.frameBands = 0
	a.discontinuity = true
	a.acknowledgmentPending = false
	for _, depacketizer := range a.depacketizers {
		depacketizer.reset()
	}
}

func (a *videoAssembler) takeAcknowledgment() (uint16, bool) {
	pending := a.acknowledgmentPending
	a.acknowledgmentPending = false
	return a.acknowledgment, pending
}

func (a *videoAssembler) lose() {
	a.losses++
	a.requestSync()
	a.onLoss()
}

func (a *videoAssembler) inGroup(ssrc uint32) bool {
	return ssrc-a.base < uint32(a.bands)
}

func (a *videoAssembler) accept(unit *accessUnit, damaged bool) {
	a.received++
	if a.haveExpected && unit.ssrc-a.expectedBase >= uint32(a.bands) {
		return
	}
	if a.decodeOrders && unit.decodeOrder == decodeOrderAbsent {
		damaged = true
	}
	if damaged {
		if a.awaitingSync {
			a.recovering = true
		} else if a.inGroup(unit.ssrc) {
			a.lose()
		}
		return
	}
	sync := unit.isSync(a.codec)
	if sync && (a.awaitingSync || !a.inGroup(unit.ssrc) || a.decodeOrders && orderNewer(uint16(unit.decodeOrder), a.nextOrder)) {
		a.startChain(unit)
		return
	}
	if _, picture := unit.frameEnd(a.codec); picture && a.awaitingSync {
		a.recovering = true
	}
	if a.awaitingSync || !a.inGroup(unit.ssrc) {
		a.hold(unit)
		return
	}
	if !a.decodeOrders {
		a.deliver(unit)
		return
	}
	order := uint16(unit.decodeOrder)
	if order != a.nextOrder && !orderNewer(order, a.nextOrder) {
		return
	}
	a.hold(unit)
	a.release()
}

func orderNewer(a, b uint16) bool {
	return a != b && a-b < 0x8000
}

func (a *videoAssembler) startChain(unit *accessUnit) {
	a.frameActive = false
	a.frameBands = 0
	a.discontinuity = true
	if a.haveExpected && unit.ssrc-a.expectedBase < uint32(a.bands) {
		a.base = a.expectedBase
	} else {
		a.base = unit.ssrc
	}
	a.awaitingSync = false
	a.recovering = false
	a.stalledSince = 0
	if !a.decodeOrders {
		a.held = a.held[:0]
		a.deliver(unit)
		return
	}
	origin := uint16(unit.decodeOrder)
	kept := a.held[:0]
	for _, held := range a.held {
		if a.inGroup(held.ssrc) && orderNewer(uint16(held.decodeOrder), origin) {
			kept = append(kept, held)
		}
	}
	a.held = kept
	a.nextOrder = origin
	a.hold(unit)
	a.release()
}

func (a *videoAssembler) hold(unit *accessUnit) {
	if slices.ContainsFunc(a.held, func(held *accessUnit) bool {
		return held.ssrc == unit.ssrc && held.decodeOrder == unit.decodeOrder && held.timestamp == unit.timestamp
	}) {
		return
	}
	if len(a.held) >= maxHeldUnits {
		if !a.awaitingSync && a.inGroup(unit.ssrc) {
			a.lose()
			return
		}
		copy(a.held, a.held[1:])
		a.held = a.held[:len(a.held)-1]
	}
	a.held = append(a.held, unit)
}

func (a *videoAssembler) release() {
	progressed := false
	for {
		index := slices.IndexFunc(a.held, func(held *accessUnit) bool {
			return a.inGroup(held.ssrc) && uint16(held.decodeOrder) == a.nextOrder
		})
		if index < 0 {
			break
		}
		unit := a.held[index]
		a.held = append(a.held[:index], a.held[index+1:]...)
		a.nextOrder++
		progressed = true
		a.deliver(unit)
		if a.awaitingSync || a.err != nil {
			return
		}
	}
	waiting := slices.ContainsFunc(a.held, func(held *accessUnit) bool {
		return a.inGroup(held.ssrc)
	})
	if !waiting {
		a.stalledSince = 0
	} else if progressed || a.stalledSince == 0 {
		a.stalledSince = a.now
	}
}

func (a *videoAssembler) deliver(unit *accessUnit) {
	if a.err != nil {
		return
	}
	if len(unit.parameterSets) > 0 && !slices.EqualFunc(unit.parameterSets, a.parameterSets, bytes.Equal) {
		a.parameterSets = unit.parameterSets
		a.formatPending = true
	}
	if a.formatPending && len(a.parameterSets) > 0 {
		color, err := parseColor(a.codec, a.parameterSets)
		if err != nil {
			a.err = err
			return
		}
		a.outbox = append(a.outbox, videoOutput{format: &VideoFormat{
			Codec:         a.codec,
			ParameterSets: a.parameterSets,
			Bands:         a.bands,
			Width:         a.width,
			Height:        a.height,
			Color:         color,
		}})
		a.formatPending = false
		a.frameActive = false
		a.frameBands = 0
		a.discontinuity = true
	}
	frameEnd, hasPicture := unit.frameEnd(a.codec)
	if len(unit.data) == 0 || !hasPicture {
		return
	}
	if a.frameActive && unit.timestamp != a.frameTimestamp {
		a.lose()
		return
	}
	if !a.frameActive {
		if step := int64(int32(unit.timestamp - a.frameTimestamp)); a.frameID > 0 && step > 0 {
			if len(a.frameSteps) == frameStepWindow {
				a.frameSteps = a.frameSteps[1:]
			}
			a.frameSteps = append(a.frameSteps, step)
			a.framePeriod = slices.Min(a.frameSteps)
		}
		a.frameID++
		a.frameActive = true
		a.frameTimestamp = unit.timestamp
		a.frameBands = 0
		a.frameStartedAt = a.now
	}
	band := int(unit.ssrc - a.base)
	a.frameBands |= 1 << band
	if band == 0 && a.decodeOrders && a.acknowledgesReferences {
		a.acknowledgment = uint16(unit.decodeOrder)
		a.acknowledgmentPending = true
	}
	frameEnd = frameEnd || a.bands == 1
	a.units++
	timestamp := a.timestamps.extend(unit.timestamp)
	restarted := !a.anchored || timestamp < a.clockTimestamp && rtpDuration(a.clockTimestamp-timestamp) > int64(maxReorderWait)
	if restarted {
		a.anchored = true
		a.anchorAt = a.now
		a.anchorTimestamp = timestamp
		a.targetAnchorAt = a.now
		a.clockTimestamp = timestamp
	} else if timestamp > a.clockTimestamp {
		limit := rtpDuration(timestamp-a.clockTimestamp) / playoutSlewDivisor
		a.anchorAt += max(-limit, min(a.targetAnchorAt-a.anchorAt, limit))
		a.clockTimestamp = timestamp
	}
	due := a.anchorAt + rtpDuration(timestamp-a.anchorTimestamp)
	if due-a.now > int64(maxReorderWait) {
		a.anchorAt = a.now
		a.anchorTimestamp = timestamp
		a.targetAnchorAt = a.now
		a.clockTimestamp = timestamp
		due = a.now
		restarted = true
	}
	offset := a.now - rtpDuration(timestamp-a.anchorTimestamp)
	switch {
	case restarted:
		a.windowSince, a.offsets = 0, a.offsets[:0]
	case a.windowSince == 0:
		a.windowSince, a.offsets = a.now, append(a.offsets[:0], offset)
	default:
		a.offsets = append(a.offsets, offset)
		if a.now-a.windowSince >= int64(playoutClockWindow) {
			slices.Sort(a.offsets)
			a.targetAnchorAt = a.offsets[int(float64(len(a.offsets)-1)*playoutQuantile)] + int64(playoutAllowance)
			a.windowSince, a.offsets = a.now, append(a.offsets[:0], offset)
		}
	}
	if a.immediate {
		due = a.now
	}
	a.playoutDelay = due - a.now
	var composedAt time.Time
	if displayed := a.clock.localTime(a.base, a.bands, unit.timestamp); displayed != 0 && a.framePeriod > 0 {
		// Apple stamps a frame with the time ScreenCaptureKit gives for displaying it, one refresh
		// period after the vsync that composed it; the refresh period is the smallest timestamp
		// step between frames (observed on Mac14,3, macOS 27.0.1 26A434, at 30 and 60 Hz).
		composedAt = time.Unix(0, displayed-rtpDuration(a.framePeriod))
	}
	a.outbox = append(a.outbox, videoOutput{due: due, unit: VideoAccessUnit{
		Data:          unit.data,
		Timestamp:     timestamp,
		ClockRate:     videoClockRate,
		FrameID:       a.frameID,
		DecodeOrder:   int64(unit.decodeOrder),
		FrameEnd:      frameEnd,
		ChangedBands:  a.frameBands,
		Discontinuity: a.discontinuity,
		Sync:          unit.isSync(a.codec),
		Band:          band,
		Bands:         a.bands,
		ReceivedAt:    time.Unix(0, unit.receivedAt),
		CompletedAt:   time.Unix(0, unit.completedAt),
		ComposedAt:    composedAt,
	}})
	a.discontinuity = false
	if frameEnd {
		a.frameActive = false
		a.frames++
	}
}
