package libsharing

import (
	"errors"
	"math/rand/v2"
	"slices"
	"strconv"
	"time"

	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
	"github.com/nekohasekai/open-screen-sharing/internal/rfb/media"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	CodecHEVC int32 = 1
	CodecH264 int32 = 2
)

const (
	DecoderCodecHEVC int32 = 1 << 0
	DecoderCodecH264 int32 = 1 << 1
)

const (
	MediaErrorNone int32 = 0

	MediaErrorBusy int32 = 1

	MediaErrorRefused int32 = 2

	MediaErrorSilent int32 = 3

	MediaErrorNetwork int32 = 4
)

func classifyMediaError(err error) (int32, string) {
	var serverError *media.ServerError
	switch {
	case err == nil:
		return MediaErrorNone, ""
	case errors.As(err, &serverError):
		if serverError.Type == media.ServerErrorBusy && serverError.SubCode == media.ServerErrorBusySubCode {
			return MediaErrorBusy, ""
		}
		return MediaErrorRefused, err.Error()
	case errors.Is(err, media.ErrStreamSilent):
		return MediaErrorSilent, err.Error()
	}
	return MediaErrorNetwork, err.Error()
}

const (
	// AVConference mode 5 uses CoreDeviceScreenSharing settings
	// (AVConference 2235.63.5.2, macOS 27.0.1 26A434,
	// UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B,
	// +[AVCMediaStreamNegotiatorSettings negotiatorSettingsForMode:deviceRole:options:errorString:]
	// 0x1c11af840..0x1c11af874, mode table entry 0x1e1c9ba10;
	// AVConference 2260.14.1, macOS 27.2 26B5101f,
	// UUID EDFB99EF-2F5E-35DD-8E63-089CBD102EFE,
	// +[AVCMediaStreamNegotiatorSettings negotiatorSettingsForMode:deviceRole:options:errorString:]
	// 0x1c082bf8c..0x1c082bfc0, mode table entry 0x1e18e2fa0).
	VideoModeCoreDevice int32 = 5
	// AVConference mode 7 uses RemoteDesktopScreenSharing settings
	// (AVConference 2235.63.5.2, macOS 27.0.1 26A434,
	// UUID 93D8C8C0-8B9C-3A5E-A47D-01B5FAF73E1B, mode table entry 0x1e1c9ba20;
	// AVConference 2260.14.1, macOS 27.2 26B5101f,
	// UUID EDFB99EF-2F5E-35DD-8E63-089CBD102EFE, mode table entry 0x1e18e2fb0).
	VideoModeRemoteDesktop int32 = 7
)

type HighPerformanceOptions struct {
	SupportedCodecs int32

	PixelFormats int32

	Audio bool

	Bands int32

	VideoMode int32

	ImmediatePlayout bool
}

func NewHighPerformanceOptions() *HighPerformanceOptions {
	return &HighPerformanceOptions{SupportedCodecs: DecoderCodecHEVC, Audio: true, Bands: 1}
}

type VideoFormat struct {
	Codec int32

	Bands int32

	Height int32

	ChromaFormat int32

	HDR           bool
	parameterSets [][]byte
}

func (f *VideoFormat) Len() int32 {
	return int32(len(f.parameterSets))
}

func (f *VideoFormat) Get(index int32) []byte {
	return f.parameterSets[index]
}

type VideoAccessUnit struct {
	Data []byte

	Timestamp int64
	ClockRate int32

	Sync  bool
	Band  int32
	Bands int32

	FrameID int64

	FrameEnd      bool
	ChangedBands  int32
	Discontinuity bool

	HoldNanoseconds    int64
	Composed           bool
	ComposeNanoseconds int64
}

type AudioFormat struct {
	FormatID        int64
	SampleRate      int32
	Channels        int32
	FramesPerPacket int32
	MagicCookie     []byte
}

type AudioPacket struct {
	Data []byte

	Timestamp int64

	Lost int32
}

func (s *Session) StartHighPerformance(options *HighPerformanceOptions) error {
	if s.options.Protocol != ProtocolRFB {
		return E.New("High Performance is not supported over RDP")
	}
	offer, err := offerOptions(options)
	if err != nil {
		return err
	}
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.highPerformance = &offer
	})
	if kept {
		return nil
	}
	client, err := rfbClient(connected)
	if err != nil {
		return err
	}
	return s.startMediaStream(client, offer)
}

const (
	peerNamespace              = "io.nekohasekai.screensharing"
	peerMessage         uint32 = 0x4f53534d
	peerQuery                  = "query"
	peerOccupied               = "occupied"
	peerTakeOver               = "takeOver"
	peerReleased               = "released"
	peerQueryTimeout           = time.Second
	peerTakeOverTimeout        = 3 * time.Second
)

func (s *Session) startMediaStream(client *rfb.Client, offer media.OfferOptions) error {
	events := &mediaEvents{session: s, client: client, offer: offer, occupied: make(chan struct{}), released: make(chan struct{})}
	s.access.Lock()
	s.highPerformance = events
	events.query = s.newPeerMessageLocked()
	s.access.Unlock()
	err := sendPeerMessage(client, events.query, map[string]string{"action": peerQuery})
	if err != nil {
		client.Logger().Debug("ask other clients about High Performance", "error", err)
	} else {
		events.queryDeadline = time.Now().Add(peerQueryTimeout)
	}
	err = client.StartMediaStream(offer, events)
	if err != nil {
		s.access.Lock()
		if s.highPerformance == events {
			s.highPerformance = nil
		}
		s.access.Unlock()
	}
	return err
}

func (s *Session) newPeerMessageLocked() uint32 {
	for {
		id := rand.Uint32()
		if id != 0 && !slices.Contains(s.peerMessages, id) {
			s.peerMessages = append(s.peerMessages, id)
			return id
		}
	}
}

func peerMessageData(values map[string]string) ([]byte, error) {
	values["namespace"] = peerNamespace
	return archiveDictionary(values)
}

func sendPeerMessage(client *rfb.Client, id uint32, values map[string]string) error {
	data, err := peerMessageData(values)
	if err != nil {
		return err
	}
	// ScreensharingAgent skips the accessibility owner check for feature 0, and
	// AXSSRemoteDesktopServerMac echoes any message other than 1 back with reply=true,
	// which screensharingd broadcasts as message 0x15 with message 0 and replyTo = messageID
	// to every viewer of the same desktop (ScreensharingAgent 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
	// 0x10001da5c; AccessibilitySharedSupport 26A434, UUID A7AF2B80-315A-3ACC-A77A-10BEC3E70F9D,
	// 0x1b0854260; screensharingd 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D, 0x100008f10..0x100008f5c).
	return client.SendAccessibilityViewerMessage(0, peerMessage, id, data)
}

func (e *sessionEvents) Accessibility(feature, message, replyTo uint32, data []byte) {
	if feature != 0 || message != 0 || replyTo == 0 {
		return
	}
	values, err := unarchiveDictionary(data)
	if err != nil || values["namespace"] != peerNamespace {
		return
	}
	s := e.session
	client, err := s.connected()
	if err != nil {
		return
	}
	s.access.Lock()
	own := slices.Contains(s.peerMessages, replyTo)
	s.access.Unlock()
	if own {
		return
	}
	to, _ := strconv.ParseUint(values["to"], 10, 32)
	switch values["action"] {
	case peerQuery:
		s.answerPeerQuery(client, replyTo)
	case peerOccupied:
		s.takeOverOccupant(client, uint32(to))
	case peerReleased:
		s.access.Lock()
		current := s.highPerformance
		if current != nil && current.takeOver != 0 && current.takeOver == uint32(to) {
			select {
			case <-current.released:
			default:
				close(current.released)
			}
		}
		s.access.Unlock()
	case peerTakeOver:
		s.releaseHighPerformance(client, replyTo)
	}
}

func (s *Session) answerPeerQuery(client *rfb.Client, query uint32) {
	s.access.Lock()
	current := s.highPerformance
	if current == nil || !current.running {
		s.access.Unlock()
		return
	}
	id := s.newPeerMessageLocked()
	s.access.Unlock()
	err := sendPeerMessage(client, id, map[string]string{"action": peerOccupied, "to": strconv.FormatUint(uint64(query), 10)})
	if err != nil {
		client.Logger().Debug("answer a High Performance query", "error", err)
	}
}

func (s *Session) takeOverOccupant(client *rfb.Client, query uint32) {
	s.access.Lock()
	current := s.highPerformance
	if current == nil || current.query == 0 || current.query != query || current.takeOver != 0 {
		s.access.Unlock()
		return
	}
	id := s.newPeerMessageLocked()
	current.takeOver, current.takeOverDeadline = id, time.Now().Add(peerTakeOverTimeout)
	close(current.occupied)
	s.access.Unlock()
	client.Logger().Info("take over High Performance from another client")
	err := sendPeerMessage(client, id, map[string]string{"action": peerTakeOver})
	if err != nil {
		client.Logger().Debug("take over High Performance", "error", err)
	}
}

func (s *Session) releaseHighPerformance(client *rfb.Client, takeOver uint32) {
	s.access.Lock()
	current := s.highPerformance
	if current == nil || !current.running || s.finished || s.disconnecting {
		s.access.Unlock()
		return
	}
	s.disconnecting, s.takenOver = true, true
	id := s.newPeerMessageLocked()
	s.cancelPromptsLocked()
	s.access.Unlock()
	s.closeDone()
	client.Logger().Info("another client took over High Performance")
	data, err := peerMessageData(map[string]string{"action": peerReleased, "to": strconv.FormatUint(uint64(takeOver), 10)})
	var released []byte
	if err == nil {
		released, err = client.AccessibilityViewerMessage(0, peerMessage, id, data)
	}
	if err != nil {
		client.Logger().Debug("announce the High Performance release", "error", err)
	}
	go client.CloseWith(released)
}

func offerOptions(options *HighPerformanceOptions) (media.OfferOptions, error) {
	offer := media.OfferOptions{
		Audio:            options.Audio,
		TilesPerFrame:    int(options.Bands),
		VideoMode:        media.VideoMode(options.VideoMode),
		PixelFormats:     media.PixelFormats(options.PixelFormats),
		ImmediatePlayout: options.ImmediatePlayout,
	}
	if options.PixelFormats < 0 || options.PixelFormats&^0xff != 0 {
		return offer, E.New("invalid decoder pixel formats 0x", strconv.FormatUint(uint64(uint32(options.PixelFormats)), 16), "; only the low 8 bits may be set")
	}
	if options.SupportedCodecs == 0 || options.SupportedCodecs&^(DecoderCodecHEVC|DecoderCodecH264) != 0 {
		return offer, E.New("High Performance needs a supported hardware decoder")
	}
	if options.SupportedCodecs&DecoderCodecHEVC != 0 {
		offer.Codecs = append(offer.Codecs, media.CodecHEVC)
	}
	if options.SupportedCodecs&DecoderCodecH264 != 0 {
		offer.Codecs = append(offer.Codecs, media.CodecH264)
	}
	if len(offer.Codecs) == 1 && offer.Codecs[0] == media.CodecH264 {
		offer.PixelFormats &= media.PixelFormatsSDR420
	}
	return offer, nil
}

func (s *Session) RestartHighPerformance(options *HighPerformanceOptions) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	go func() {
		_ = client.StopMediaStream()
		err := s.StartHighPerformance(options)
		if err != nil {
			s.handler.OnMediaStopped(classifyMediaError(err))
		}
	}()
	return nil
}

func (s *Session) SetHDR(enabled bool) error {
	if s.options.Protocol != ProtocolRFB {
		return E.New("HDR is not supported over RDP")
	}
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.hdr = &enabled
	})
	if kept {
		return nil
	}
	client, err := rfbClient(connected)
	if err != nil {
		return err
	}
	return client.SetHDR(enabled)
}

func newAudioFormat(format media.AudioFormat) *AudioFormat {
	return &AudioFormat{
		FormatID:        int64(format.FormatID),
		SampleRate:      int32(format.SampleRate),
		Channels:        int32(format.Channels),
		FramesPerPacket: int32(format.FramesPerPacket),
		MagicCookie:     format.MagicCookie,
	}
}

func (s *Session) mediaStream() (*media.Stream, error) {
	client, err := s.connected()
	if err != nil {
		return nil, err
	}
	stream := client.MediaStream()
	if stream == nil {
		return nil, E.New("the High Performance stream is not running")
	}
	return stream, nil
}

func (s *Session) RequestKeyframe() error {
	stream, err := s.mediaStream()
	if err != nil {
		return err
	}
	stream.RequestKeyframe()
	return nil
}

type mediaEvents struct {
	session       *Session
	client        *rfb.Client
	offer         media.OfferOptions
	occupied      chan struct{}
	released      chan struct{}
	queryDeadline time.Time

	running          bool
	query            uint32
	takeOver         uint32
	takeOverDeadline time.Time
}

func (e *mediaEvents) VideoFormat(format media.VideoFormat) {
	e.session.access.Lock()
	e.running, e.query = true, 0
	e.session.access.Unlock()
	codec := CodecHEVC
	if format.Codec == media.CodecH264 {
		codec = CodecH264
	}
	e.session.handler.OnVideoFormat(&VideoFormat{
		Codec:         codec,
		Bands:         int32(format.Bands),
		Height:        int32(format.Height),
		ChromaFormat:  int32(format.Color.ChromaFormat),
		HDR:           format.Color.HDR(),
		parameterSets: format.ParameterSets,
	})
}

func (e *mediaEvents) VideoAccessUnit(unit media.VideoAccessUnit) {
	var hold time.Duration
	if !unit.CompletedAt.IsZero() {
		hold = max(time.Since(unit.CompletedAt), 0)
	}
	var composed time.Duration
	if !unit.ComposedAt.IsZero() {
		composed = time.Since(unit.ComposedAt)
	}
	e.session.handler.OnVideoAccessUnit(&VideoAccessUnit{
		Data:               unit.Data,
		Timestamp:          unit.Timestamp,
		ClockRate:          int32(unit.ClockRate),
		Sync:               unit.Sync,
		Band:               int32(unit.Band),
		Bands:              int32(unit.Bands),
		FrameID:            unit.FrameID,
		FrameEnd:           unit.FrameEnd,
		ChangedBands:       int32(unit.ChangedBands),
		Discontinuity:      unit.Discontinuity,
		HoldNanoseconds:    int64(hold),
		Composed:           !unit.ComposedAt.IsZero(),
		ComposeNanoseconds: int64(composed),
	})
}

func (e *mediaEvents) AudioFormat(format media.AudioFormat) {
	e.session.handler.OnAudioFormat(newAudioFormat(format))
}

func (e *mediaEvents) AudioPacket(packet media.AudioPacket) {
	e.session.handler.OnAudioPacket(&AudioPacket{Data: packet.Data, Timestamp: packet.Timestamp, Lost: int32(packet.Lost)})
}

func (e *mediaEvents) StreamStopped(err error) {
	s := e.session
	code, message := classifyMediaError(err)
	if code == MediaErrorBusy {
		retry, ended := e.awaitRelease()
		if ended {
			return
		}
		if retry {
			err = e.client.StartMediaStream(e.offer, e)
			if err == nil {
				return
			}
			code, message = classifyMediaError(err)
		}
	}
	s.access.Lock()
	e.running, e.query = false, 0
	if s.highPerformance == e {
		s.highPerformance = nil
	}
	s.access.Unlock()
	s.handler.OnMediaStopped(code, message)
}

func (e *mediaEvents) awaitRelease() (retry bool, ended bool) {
	s := e.session
	timer := time.NewTimer(time.Until(e.queryDeadline))
	defer timer.Stop()
	select {
	case <-e.occupied:
	case <-timer.C:
	case <-s.done:
		return false, true
	}
	s.access.Lock()
	takeOver, deadline := e.takeOver, e.takeOverDeadline
	e.query, e.takeOver = 0, 0
	s.access.Unlock()
	if takeOver == 0 {
		return false, false
	}
	timer.Reset(time.Until(deadline))
	select {
	case <-e.released:
	case <-timer.C:
	case <-s.done:
		return false, true
	}
	return true, false
}
