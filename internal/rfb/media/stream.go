package media

import (
	"errors"
	"net"
	"net/netip"
	"os"
	"sync"
	"syscall"
	"time"

	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	N "github.com/sagernet/sing/common/network"
)

const (
	videoReceiveBuffer = 4 << 20
	audioReceiveBuffer = 256 << 10
	// AVConference startFeedbackSource sets a 50,000,000 ns timer interval
	// (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID
	// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// -[VCVideoStreamRateAdaptationFeedbackOnly startFeedbackSource] 0x1c1189bf0..0x1c1189c00;
	// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
	// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// -[VCVideoStreamRateAdaptationFeedbackOnly startFeedbackSource] 0x1c0806298..0x1c08062a8).
	rateFeedbackInterval = 50 * time.Millisecond
	reportInterval       = time.Second
	// AVConference GetNextFrame repeats packet-loss refresh requests after
	// 0.25 s; DecoderRequestKeyFrame independently handles decoder failures
	// (26A434 AVConference, _VideoPacketBuffer_GetNextFrame
	// 0x1c0f30b0c..0x1c0f30b70, __VideoReceiver_DecoderRequestKeyFrame
	// 0x1c0f51200).
	keyframeInterval = 250 * time.Millisecond
	receiveTick      = 10 * time.Millisecond
	maxDatagram      = 65536
	readBatchSize    = 16

	drainQuiet   = 150 * time.Millisecond
	drainMinimum = 250 * time.Millisecond
	drainLimit   = time.Second

	// On kCGSDisplayWillReconfigure the Agent releases its media sender, whose sockets only
	// avconferenced holds, before the daemon announces the ports again in the first
	// SendFrameBuffer after DisplayChangeFinished. The Agent waits at most 90 ticks for the
	// reconfiguration and the daemon holds framebuffer updates for at most 90 ticks, 1440 ms
	// (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
	// DisplayReconfigureNotificationHandler 0x100024208..0x100024290,
	// createAVCVideoStreamWithRemoteAddress: 0x100009db0..0x100009db8,
	// WaitForDisplayReconfigureToFinish 0x10003e2b4..0x10003e354; screensharingd 756.36.5.2,
	// macOS 27.0.1 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D,
	// SendFrameBuffer 0x100021f1c..0x100021f40, 0x100023a14..0x100023a94).
	restartLimit = 3 * time.Second
)

var ErrStreamSilent = E.New("media: the server closed its media ports and did not announce them again")

type StreamConfig struct {
	Offer  *Offer
	Remote netip.Addr
	Local  netip.Addr
	Sink   Sink
}

type StreamStats struct {
	VideoPackets             uint64
	VideoPacketsExpected     uint64
	VideoBytes               uint64
	AudioBytes               uint64
	VideoAccessUnitsReceived uint64
	VideoAccessUnits         uint64
	VideoFrames              uint64
	VideoLosses              uint64
	VideoLatePackets         uint64
	VideoPlayoutDelay        time.Duration
	AuthFailures             uint64
	KeyframeRequests         uint64
	SenderReports            uint64
	OneWayDelay              time.Duration
	Ports                    Ports
	Answer                   Answer
}

type Stream struct {
	config StreamConfig

	access   sync.Mutex
	ports    *Ports
	answer   *Answer
	video    *leg
	audio    *leg
	stopOnce sync.Once
	done     chan struct{}
	wg       sync.WaitGroup
	keyframe chan struct{}

	rate                rateFeedback
	lastKeyframeRequest time.Time
	refusedAt           time.Time
	restartExpected     bool
	displaysAsleep      bool
	stats               StreamStats
	receiveRTCP         []byte
	feedbackRTCP        []byte
}

type leg struct {
	conn        *net.UDPConn
	reader      N.ConnectedPacketBatchReadWaiter
	localSSRC   uint32
	srtp        *srtpReceiver
	reports     *srtcpReceiver
	sendAccess  sync.Mutex
	sender      *srtcpSender
	sendBuffer  []byte
	sources     map[uint32]*receptionStats
	clock       senderClock
	lastHeardAt time.Time
	heard       bool
}

func NewStream(config StreamConfig) *Stream {
	config.Remote = config.Remote.Unmap()
	config.Local = config.Local.Unmap()
	return &Stream{
		config:   config,
		done:     make(chan struct{}),
		keyframe: make(chan struct{}, 1),
		rate:     newRateFeedback(time.Now()),
	}
}

func (s *Stream) HandleRectangle(payload []byte) error {
	reply, err := parseReply(payload)
	if err == nil {
		switch message := reply.(type) {
		case *Ports:
			err = s.handlePorts(message)
		case *Answer:
			s.access.Lock()
			s.answer = message
			s.stats.Answer = *message
			s.rate.restart()
			s.stats.OneWayDelay = 0
			s.awaitPacketsLocked()
			s.access.Unlock()
		case *ServerError:
			err = message
		}
	}
	if err != nil {
		s.stop(err)
	}
	return err
}

func (s *Stream) handlePorts(ports *Ports) error {
	s.config.Offer.hdr.Store(ports.Video.HDR)
	s.access.Lock()
	defer s.access.Unlock()
	s.stats.Ports = *ports
	if s.isDone() {
		return os.ErrClosed
	}
	if !ports.Video.Enabled || ports.Video.Port == 0 {
		return E.New("media: server did not enable the video leg (enabled ", ports.Video.Enabled, ", port ", ports.Video.Port, ")")
	}
	if s.ports != nil && s.ports.Video.Port == ports.Video.Port && s.ports.Audio.Port == ports.Audio.Port {
		s.ports = ports
		s.restartExpected = false
		s.awaitPacketsLocked()
		return nil
	}
	if s.ports != nil {
		return E.New("media: server moved the media ports from video ", s.ports.Video.Port, " and audio ", s.ports.Audio.Port,
			" to video ", ports.Video.Port, " and audio ", ports.Audio.Port)
	}
	offer := s.config.Offer
	video, err := s.openLeg(ports.Video.Port, offer.videoSend[:], offer.videoReceive[:], offer.videoSSRC, videoReceiveBuffer, readBatchSize)
	if err != nil {
		return err
	}
	var audio *leg
	if ports.Audio.Enabled && ports.Audio.Port != 0 {
		audio, err = s.openLeg(ports.Audio.Port, offer.audioSend[:], offer.audioReceive[:], offer.audioSSRC, audioReceiveBuffer, 1)
		if err != nil {
			_ = video.conn.Close()
			return err
		}
	}
	s.ports = ports
	s.video = video
	s.audio = audio
	s.wg.Add(2)
	go s.receiveVideo(video)
	go s.feedback()
	if audio != nil {
		s.wg.Add(1)
		go s.receiveAudio(audio)
	}
	return nil
}

func (s *Stream) openLeg(port uint16, sendKey []byte, receiveKey []byte, localSSRC uint32, buffer int, batch int) (*leg, error) {
	conn, err := dialLeg(s.config.Local, netip.AddrPortFrom(s.config.Remote, port), buffer)
	if err != nil {
		return nil, E.Cause(err, "media: open UDP port ", port)
	}
	reader, created := bufio.CreateConnectedPacketBatchReadWaiter(bufio.NewUnbindPacketConn(conn))
	if !created {
		_ = conn.Close()
		return nil, E.New("media: batched UDP reads are unsupported on this platform")
	}
	reader.InitializeReadWaiter(N.ReadWaitOptions{MTU: maxDatagram, BatchSize: batch})
	return &leg{
		conn:        conn,
		reader:      reader,
		localSSRC:   localSSRC,
		srtp:        newSRTPReceiver(receiveKey),
		reports:     newSRTCPReceiver(receiveKey),
		sender:      newSRTCPSender(sendKey),
		sources:     make(map[uint32]*receptionStats),
		lastHeardAt: time.Now(),
	}, nil
}

func (l *leg) source(ssrc uint32) *receptionStats {
	source := l.sources[ssrc]
	if source == nil {
		if len(l.sources) >= maxTrackedVideoSSRC {
			clear(l.sources)
		}
		source = &receptionStats{}
		l.sources[ssrc] = source
	}
	return source
}

func (s *Stream) send(target *leg, packet []byte) {
	target.sendAccess.Lock()
	target.sendBuffer = target.sender.appendProtected(target.sendBuffer[:0], packet)
	_, err := target.conn.Write(target.sendBuffer)
	target.sendAccess.Unlock()
	s.refused(target, err)
}

func (s *Stream) refused(target *leg, err error) bool {
	if !errors.Is(err, syscall.ECONNREFUSED) {
		return false
	}
	s.access.Lock()
	if target.heard && !s.restartExpected && !s.displaysAsleep && s.refusedAt.IsZero() {
		s.refusedAt = time.Now()
	}
	s.access.Unlock()
	return true
}

func (s *Stream) awaitPacketsLocked() {
	s.refusedAt = time.Time{}
	for _, target := range []*leg{s.video, s.audio} {
		if target != nil {
			target.heard = false
		}
	}
}

func (s *Stream) ExpectRestart() {
	s.access.Lock()
	defer s.access.Unlock()
	s.restartExpected = true
	s.refusedAt = time.Time{}
}

// screensharingd sends the DisplaysSleep and DisplaysWake statuses from its display power
// handler, while its SendFrameBuffer thread, which announces the media ports again after a
// display reconfiguration, blocks until the displays wake (screensharingd 756.36.5.2,
// macOS 27.0.1 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D,
// DisplayNotification_HandlePowerChange 0x1000483d4..0x1000485b8,
// SendFrameBuffer 0x100021720..0x1000217e0, 0x100021aa4..0x100021ad4).
func (s *Stream) SetDisplaysAsleep(asleep bool) {
	s.access.Lock()
	defer s.access.Unlock()
	s.displaysAsleep = asleep
	s.awaitPacketsLocked()
}

func (s *Stream) RequestKeyframe() {
	select {
	case s.keyframe <- struct{}{}:
	default:
	}
}

func (s *Stream) Stats() StreamStats {
	s.access.Lock()
	defer s.access.Unlock()
	return s.stats
}

func (s *Stream) Close() error {
	s.stop(nil)
	s.wg.Wait()
	return nil
}

// An avconferenced crash on macOS 27.0.1 (26A434), 2026-10-05 11:12,
// shows stop waiting for a write lock in VTP_Close while VTPRecvProc is
// blocked in __recvmsg (AVConference 2235.63.5.2, macOS 27.0.1 26A434, UUID
// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B, _VTP_Close 0x1c10daf24,
// _VTPRecvProc 0x1c10d5bc8).
func (s *Stream) Drain(closed time.Time) {
	for {
		s.access.Lock()
		video := s.video
		var heard time.Time
		if video != nil {
			heard = video.lastHeardAt
		}
		s.access.Unlock()
		if video == nil {
			return
		}
		now := time.Now()
		wait := max(drainMinimum-now.Sub(closed), drainQuiet-now.Sub(heard))
		wait = min(wait, drainLimit-now.Sub(closed))
		if wait <= 0 {
			return
		}
		select {
		case <-s.done:
			return
		case <-time.After(wait):
		}
	}
}

func (s *Stream) stop(cause error) {
	s.stopOnce.Do(func() {
		s.access.Lock()
		close(s.done)
		video, audio := s.video, s.audio
		s.access.Unlock()
		if video != nil {
			_ = video.conn.Close()
		}
		if audio != nil {
			_ = audio.conn.Close()
		}
		go func() {
			s.wg.Wait()
			s.config.Sink.StreamStopped(cause)
		}()
	})
}

func (s *Stream) receiveVideo(video *leg) {
	defer s.wg.Done()
	var assembler *videoAssembler
	var appliedAnswer *Answer
	var unitsAtAnswer uint64
	for {
		deadline := time.Now().Add(receiveTick)
		if assembler != nil {
			if due := assembler.nextDue(); due != 0 && due < deadline.UnixNano() {
				deadline = time.Unix(0, due)
			}
		}
		_ = video.conn.SetReadDeadline(deadline)
		datagrams, _, err := video.reader.WaitReadConnectedPackets()
		now := time.Now()
		if err != nil {
			if s.isDone() {
				return
			}
			if !errors.Is(err, os.ErrDeadlineExceeded) && !s.refused(video, err) {
				s.stop(E.Cause(err, "media: read video leg"))
				return
			}
		}
		for index := range max(len(datagrams), 1) {
			select {
			case <-s.keyframe:
				if assembler != nil {
					assembler.requestSync()
				}
				s.requestKeyframe(video, true)
			default:
			}
			if s.applyVideoAnswer(assembler, &appliedAnswer, &unitsAtAnswer) {
				s.requestKeyframe(video, false)
			}
			if index < len(datagrams) && !datagrams[index].IsEmpty() {
				s.handleVideoDatagram(video, &assembler, datagrams[index].Bytes(), now)
			}
			if assembler != nil {
				assembler.tick(now.UnixNano())
				if assembler.err != nil {
					s.stop(assembler.err)
					return
				}
				if order, pending := assembler.takeAcknowledgment(); pending {
					s.receiveRTCP = appendReferenceAcknowledgment(s.receiveRTCP[:0], video.localSSRC, order)
					s.send(video, s.receiveRTCP)
				}
				if assembler.wantsKeyframe() {
					s.requestKeyframe(video, false)
				}
				s.access.Lock()
				if s.answer == appliedAnswer && assembler.units > unitsAtAnswer {
					s.rate.videoArrived = true
				}
				if !assembler.awaitingSync {
					s.lastKeyframeRequest = time.Time{}
				}
				s.stats.VideoAccessUnitsReceived = assembler.received
				s.stats.VideoAccessUnits = assembler.units
				s.stats.VideoFrames = assembler.frames
				s.stats.VideoLosses = assembler.losses
				s.stats.VideoLatePackets = assembler.latePackets()
				s.stats.VideoPlayoutDelay = time.Duration(assembler.playoutDelay)
				s.access.Unlock()
			}
		}
		buf.ReleaseMulti(datagrams)
		s.access.Lock()
		silent := !s.refusedAt.IsZero() && now.Sub(s.refusedAt) > restartLimit
		s.access.Unlock()
		if silent {
			s.stop(ErrStreamSilent)
			return
		}
	}
}

func (s *Stream) applyVideoAnswer(assembler *videoAssembler, applied **Answer, unitsAtAnswer *uint64) (missedVideo bool) {
	s.access.Lock()
	defer s.access.Unlock()
	if s.answer == nil || s.answer == *applied {
		return false
	}
	*applied = s.answer
	*unitsAtAnswer = 0
	if assembler == nil {
		return s.stats.VideoPackets > 0
	}
	if s.answer.SSRC != 0 {
		assembler.expectAnswer(s.answer)
	}
	*unitsAtAnswer = assembler.units
	return false
}

func (s *Stream) handleVideoDatagram(video *leg, assembler **videoAssembler, datagram []byte, now time.Time) {
	if isRTCP(datagram) {
		if s.handleReports(video, datagram, now) && *assembler != nil && (*assembler).awaitingSync {
			s.requestKeyframe(video, false)
		}
		return
	}
	packet, err := video.srtp.unprotect(datagram)
	if err != nil {
		if errors.Is(err, errAuthentication) {
			s.access.Lock()
			s.stats.AuthFailures++
			s.access.Unlock()
		}
		return
	}
	s.access.Lock()
	video.lastHeardAt = now
	video.heard = true
	s.refusedAt = time.Time{}
	s.stats.VideoPackets++
	s.stats.VideoBytes += uint64(len(datagram))
	s.stats.VideoPacketsExpected += uint64(video.source(packet.ssrc).record(packet.sequence, packet.timestamp, videoClockRate, now))
	s.rate.record(packet.ssrc, packet.sequence, packet.timestamp, now)
	s.stats.OneWayDelay = time.Duration(s.rate.delay.value * float64(time.Second))
	s.access.Unlock()
	if *assembler == nil {
		codec := codecForPayloadType(int(packet.payloadType))
		if codec == 0 {
			return
		}
		// AVConference negotiateTilesPerFrame:negotiatedSettings: selects the smaller
		// of local tilesPerVideoFrame and remote tilesPerFrame (AVConference
		// 2235.63.5.2, macOS 27.0.1 26A434, UUID
		// 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
		// -[VCMediaNegotiator negotiateTilesPerFrame:negotiatedSettings:] 0x1c12305b4..0x1c123061c;
		// AVConference 2260.14.1, macOS 27.2 26B5101f, UUID
		// EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
		// -[VCMediaNegotiator negotiateTilesPerFrame:negotiatedSettings:] 0x1c08a32ec..0x1c08a3354).
		bands := s.config.Offer.options.TilesPerFrame
		s.access.Lock()
		answer := s.answer
		s.access.Unlock()
		if answer != nil && answer.TilesPerFrame >= 1 && answer.TilesPerFrame <= bands {
			bands = answer.TilesPerFrame
		}
		if bands > 1 {
			if answer == nil {
				return
			}
			if answer.SSRC == 0 || answer.Height <= 0 {
				s.stop(E.New("media: the answer gives no layout for ", bands, " bands (SSRC ", answer.SSRC, ", height ", answer.Height, ")"))
				return
			}
		}
		*assembler = newVideoAssembler(codec, bands, s.config.Offer.options.ImmediatePlayout, s.config.Sink, &video.clock, func() {
			s.requestKeyframe(video, false)
		})
		if answer != nil && answer.SSRC != 0 {
			(*assembler).expectAnswer(answer)
		}
	}
	(*assembler).push(packet, datagram[:len(datagram)-authTagLength], now.UnixNano())
}

func (s *Stream) requestKeyframe(video *leg, immediate bool) {
	s.access.Lock()
	if !immediate && !s.lastKeyframeRequest.IsZero() && time.Since(s.lastKeyframeRequest) < keyframeInterval {
		s.access.Unlock()
		return
	}
	s.lastKeyframeRequest = time.Now()
	s.stats.KeyframeRequests++
	s.receiveRTCP = appendKeyframeRequest(s.receiveRTCP[:0], video.localSSRC, video.sources, s.lastKeyframeRequest)
	s.access.Unlock()
	s.send(video, s.receiveRTCP)
}

func (s *Stream) handleReports(target *leg, datagram []byte, now time.Time) bool {
	compound, err := target.reports.unprotect(datagram)
	if err != nil {
		return false
	}
	s.access.Lock()
	defer s.access.Unlock()
	target.lastHeardAt = now
	target.heard = true
	s.refusedAt = time.Time{}
	s.stats.SenderReports++
	senderReports(compound, func(ssrc uint32, ntpMiddle uint32, timestamp uint32) {
		source := target.sources[ssrc]
		if source == nil {
			return
		}
		source.lastSenderReport = ntpMiddle
		source.senderReportAt = now
		target.clock.observe(ssrc, timestamp, now.UnixNano())
	})
	return true
}

func (s *Stream) receiveAudio(audio *leg) {
	defer s.wg.Done()
	receiver := &audioReceiver{sink: s.config.Sink}
	for {
		_ = audio.conn.SetReadDeadline(time.Now().Add(receiveTick))
		datagrams, _, err := audio.reader.WaitReadConnectedPackets()
		now := time.Now()
		if err != nil {
			if s.isDone() {
				return
			}
			if !errors.Is(err, os.ErrDeadlineExceeded) && !s.refused(audio, err) {
				s.stop(E.Cause(err, "media: read audio leg"))
				return
			}
		}
		for _, buffer := range datagrams {
			datagram := buffer.Bytes()
			if isRTCP(datagram) {
				s.handleReports(audio, datagram, now)
			} else {
				packet, unprotectErr := audio.srtp.unprotect(datagram)
				if unprotectErr == nil {
					s.access.Lock()
					audio.heard = true
					s.refusedAt = time.Time{}
					s.stats.AudioBytes += uint64(len(datagram))
					audio.source(packet.ssrc).record(packet.sequence, packet.timestamp, audioSampleRate, now)
					s.access.Unlock()
					receiver.push(packet, now)
				}
			}
		}
		buf.ReleaseMulti(datagrams)
		receiver.tick(now)
	}
}

func (s *Stream) isDone() bool {
	select {
	case <-s.done:
		return true
	default:
		return false
	}
}

func (s *Stream) feedback() {
	defer s.wg.Done()
	rateTicker := time.NewTicker(rateFeedbackInterval)
	defer rateTicker.Stop()
	s.sendReports()
	lastReport := time.Now()
	for {
		select {
		case <-s.done:
			return
		case now := <-rateTicker.C:
			var report bool
			s.access.Lock()
			s.feedbackRTCP, report = s.rate.appendReport(s.feedbackRTCP[:0], s.video.localSSRC, now)
			s.access.Unlock()
			if report {
				s.send(s.video, s.feedbackRTCP)
			}
			if now.Sub(lastReport) >= reportInterval {
				lastReport = now
				s.sendReports()
			}
		}
	}
}

func (s *Stream) sendReports() {
	now := time.Now()
	for _, target := range []*leg{s.video, s.audio} {
		if target == nil {
			continue
		}
		s.access.Lock()
		s.feedbackRTCP = appendReceiverReport(s.feedbackRTCP[:0], target.localSSRC, target.sources, now)
		s.access.Unlock()
		s.send(target, s.feedbackRTCP)
	}
}
