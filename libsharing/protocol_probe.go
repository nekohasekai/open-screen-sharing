package libsharing

import (
	"context"
	"errors"
	"net"
	"strconv"

	"github.com/nekohasekai/open-screen-sharing/internal/rdp"
)

const (
	defaultRFBPort = 5900
	defaultRDPPort = 3389
)

func (r *ProbeRequest) ProbeProtocol(host string, port int32) *ServerProbe {
	if port != 0 {
		if r.CheckOwnScreen {
			err := checkOwnScreen(r.ctx, host, port)
			if err != nil {
				return failedProbe(port, err)
			}
		}
		return r.probeRDP(r.ctx, host, port, true)
	}
	ctx, cancel := context.WithCancel(r.ctx)
	defer cancel()
	rfbResults, rdpResults := make(chan *ServerProbe, 1), make(chan *ServerProbe, 1)
	go func() { rfbResults <- r.probeServer(ctx, host, defaultRFBPort) }()
	go func() { rdpResults <- r.probeRDP(ctx, host, defaultRDPPort, false) }()
	var rfbResult, rdpResult *ServerProbe
	for rfbResult == nil || rdpResult == nil {
		select {
		case rfbResult = <-rfbResults:
			if rfbResult.ErrorCode == ErrorNone {
				return rfbResult
			}
		case rdpResult = <-rdpResults:
			if rdpResult.ErrorCode == ErrorNone {
				return rdpResult
			}
		case <-ctx.Done():
			return failedProbe(port, ctx.Err())
		}
	}
	for _, code := range []int32{ErrorOwnScreen, ErrorHostNotFound} {
		if rfbResult.ErrorCode == code {
			return rfbResult
		}
		if rdpResult.ErrorCode == code {
			return rdpResult
		}
	}
	if rfbResult.ErrorCode == ErrorNetwork && rdpResult.ErrorCode != ErrorNetwork {
		return rdpResult
	}
	return rfbResult
}

func (r *ProbeRequest) probeRDP(ctx context.Context, host string, port int32, rfbFallback bool) *ServerProbe {
	conn, err := new(net.Dialer).DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(int(port))))
	if err != nil {
		return failedProbe(port, err)
	}
	defer conn.Close()
	fingerprint, err := rdp.Probe(ctx, conn)
	if rfbFallback && errors.Is(err, rdp.ErrRFBServer) {
		_ = conn.Close()
		return r.probeServer(ctx, host, port)
	}
	if err != nil {
		return failedProbe(port, err)
	}
	return &ServerProbe{Protocol: ProtocolRDP, Port: port, Fingerprint: fingerprint, SupportsAccount: true}
}

func failedProbe(port int32, err error) *ServerProbe {
	code, message := classifyError(err)
	return &ServerProbe{Protocol: -1, Port: port, ErrorCode: code, ErrorMessage: message}
}
