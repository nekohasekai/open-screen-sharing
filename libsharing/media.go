package libsharing

import (
	"errors"
	"strconv"
	"time"

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

func classifyMediaError(err error) int32 {
	var serverError *media.ServerError
	switch {
	case err == nil:
		return MediaErrorNone
	case errors.As(err, &serverError):
		if serverError.Type == media.ServerErrorBusy && serverError.SubCode == media.ServerErrorBusySubCode {
			return MediaErrorBusy
		}
		return MediaErrorRefused
	case errors.Is(err, media.ErrStreamSilent):
		return MediaErrorSilent
	}
	return MediaErrorNetwork
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
	return client.StartMediaStream(offer, &mediaEvents{session: s})
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
			s.handler.OnMediaStopped(classifyMediaError(err), err.Error())
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
	session *Session
}

func (e *mediaEvents) VideoFormat(format media.VideoFormat) {
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
	message := ""
	if err != nil {
		message = err.Error()
	}
	e.session.handler.OnMediaStopped(classifyMediaError(err), message)
}
