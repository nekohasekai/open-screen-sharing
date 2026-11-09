package libsharing

import (
	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
)

type Statistics struct {
	RoundTripMilliseconds float64
	RoundTripKnown        bool

	ReceivedBytes int64

	Encoding string

	HighPerformance bool

	UDP             bool
	Packets         int64
	PacketsExpected int64

	VideoLosses int64
}

func (s *Session) Statistics() *Statistics {
	statistics := &Statistics{}
	if encoding := s.pictureEncoding.Load(); encoding != 0 {
		statistics.Encoding = encodingName(rfb.Encoding(encoding - 1))
	}
	connected, err := s.connectedRemote()
	if err != nil {
		return statistics
	}
	connected.statistics(statistics)
	return statistics
}

func (r rfbRemote) statistics(statistics *Statistics) {
	info, known := r.ConnectionInfo()
	if known {
		statistics.RoundTripMilliseconds = float64(info.RoundTrip.Microseconds()) / 1000
		statistics.RoundTripKnown = true
		statistics.ReceivedBytes = int64(info.ReceivedBytes)
	}
	stream := r.MediaStream()
	if stream == nil {
		return
	}
	streamStatistics := stream.Stats()
	statistics.ReceivedBytes += int64(streamStatistics.VideoBytes + streamStatistics.AudioBytes)
	statistics.HighPerformance = true
	statistics.UDP = true
	statistics.Packets = int64(streamStatistics.VideoPackets)
	statistics.PacketsExpected = int64(streamStatistics.VideoPacketsExpected)
	statistics.VideoLosses = int64(streamStatistics.VideoLosses)
}

func encodingName(encoding rfb.Encoding) string {
	switch encoding {
	case rfb.EncodingRaw:
		return "Raw"
	case rfb.EncodingCopyRect:
		return "CopyRect"
	case rfb.EncodingHextile:
		return "Hextile"
	case rfb.EncodingZlib:
		return "Zlib"
	case rfb.EncodingZRLE:
		return "ZRLE"
	case rfb.EncodingAppleHalftone:
		return "Halftone"
	case rfb.EncodingAppleGray:
		return "Gray"
	case rfb.EncodingAppleThousands:
		return "Thousands"
	case rfb.EncodingMVS:
		return "MVS"
	default:
		return encoding.String()
	}
}
