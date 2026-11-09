package rdp

import (
	"encoding/binary"
	"log/slog"
	"strconv"
	"sync/atomic"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	securityAutodetectRequest  = 0x1000
	securityAutodetectResponse = 0x2000

	autodetectHeaderLength = 6
	autodetectRequest      = 0x00
	autodetectResponse     = 0x01

	autodetectRTTContinuous              = 0x0001
	autodetectRTTConnectTime             = 0x1001
	autodetectBandwidthStartContinuous   = 0x0014
	autodetectBandwidthStartLossy        = 0x0114
	autodetectBandwidthStartConnectTime  = 0x1014
	autodetectBandwidthPayload           = 0x0002
	autodetectBandwidthStopConnectTime   = 0x002b
	autodetectBandwidthStopContinuous    = 0x0429
	autodetectBandwidthStopLossy         = 0x0629
	autodetectNetcharRTT                 = 0x0840
	autodetectNetcharBandwidth           = 0x0880
	autodetectNetcharAll                 = 0x08c0
	autodetectRTTResponse                = 0x0000
	autodetectBandwidthResultConnectTime = 0x0003
	autodetectBandwidthResultContinuous  = 0x000b
)

type autodetect struct {
	logger    *slog.Logger
	roundTrip *atomic.Pointer[time.Duration]
	respond   func(response []byte) error

	start     time.Time
	startRead uint64
}

func (a *autodetect) receive(r *reader, arrival time.Time, read uint64) error {
	headerLength := int(r.u8())
	kind := r.u8()
	sequence := r.u16()
	request := r.u16()
	if r.err != nil {
		return r.err
	}
	if kind != autodetectRequest || headerLength < autodetectHeaderLength {
		return E.New("rdp: auto-detect request of type ", kind, " with a ", headerLength, "-byte header")
	}
	fields := r.sub(headerLength - autodetectHeaderLength)
	if r.err != nil {
		return r.err
	}
	switch request {
	case autodetectRTTContinuous, autodetectRTTConnectTime:
		return a.reply(sequence, autodetectRTTResponse, nil)
	case autodetectBandwidthStartContinuous, autodetectBandwidthStartLossy, autodetectBandwidthStartConnectTime:
		a.start, a.startRead = arrival, read
	case autodetectBandwidthPayload:
	case autodetectBandwidthStopConnectTime, autodetectBandwidthStopContinuous, autodetectBandwidthStopLossy:
		if a.start.IsZero() {
			a.logger.Debug("ignore auto-detect bandwidth stop without a start")
			return nil
		}
		result := binary.LittleEndian.AppendUint32(nil, uint32(min(arrival.Sub(a.start).Milliseconds(), int64(^uint32(0)))))
		result = binary.LittleEndian.AppendUint32(result, uint32(min(read-a.startRead, uint64(^uint32(0)))))
		a.start = time.Time{}
		response := uint16(autodetectBandwidthResultContinuous)
		if request == autodetectBandwidthStopConnectTime {
			response = autodetectBandwidthResultConnectTime
		}
		return a.reply(sequence, response, result)
	case autodetectNetcharRTT, autodetectNetcharBandwidth, autodetectNetcharAll:
		var baseRTT, bandwidth uint32
		if request != autodetectNetcharBandwidth {
			baseRTT = fields.u32()
		}
		if request != autodetectNetcharRTT {
			bandwidth = fields.u32()
		}
		averageRTT := fields.u32()
		if fields.err != nil {
			return E.Cause(fields.err, "rdp: parse network characteristics result")
		}
		roundTrip := time.Duration(averageRTT) * time.Millisecond
		a.roundTrip.Store(&roundTrip)
		a.logger.Debug("network characteristics", "base_rtt_ms", baseRTT, "average_rtt_ms", averageRTT, "bandwidth_kbps", bandwidth)
	default:
		a.logger.Debug("ignore auto-detect request", "type", "0x"+strconv.FormatUint(uint64(request), 16))
	}
	return nil
}

func (a *autodetect) reply(sequence uint16, response uint16, fields []byte) error {
	b := []byte{byte(autodetectHeaderLength + len(fields)), autodetectResponse}
	b = binary.LittleEndian.AppendUint16(b, sequence)
	b = binary.LittleEndian.AppendUint16(b, response)
	return a.respond(append(b, fields...))
}
