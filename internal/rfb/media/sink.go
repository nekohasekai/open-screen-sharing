package media

import "time"

type Sink interface {
	VideoFormat(format VideoFormat)
	VideoAccessUnit(unit VideoAccessUnit)
	AudioFormat(format AudioFormat)
	AudioPacket(packet AudioPacket)
	StreamStopped(err error)
}

type VideoFormat struct {
	Codec         Codec
	ParameterSets [][]byte
	Bands         int
	Width         int
	Height        int
	Color         Color
}

type VideoAccessUnit struct {
	Data          []byte
	Timestamp     int64
	ClockRate     int
	FrameID       int64
	DecodeOrder   int64
	FrameEnd      bool
	ChangedBands  uint32
	Discontinuity bool
	Sync          bool
	Band          int
	Bands         int
	ReceivedAt    time.Time
	CompletedAt   time.Time
	ComposedAt    time.Time
}

type AudioFormat struct {
	FormatID            uint32
	SampleRate          int
	Channels            int
	FramesPerPacket     int
	AudioSpecificConfig []byte
	MagicCookie         []byte
}

type AudioPacket struct {
	Data      []byte
	Timestamp int64
	Lost      int
}
