package libsharing

import (
	"context"
	"net"
	"slices"
	"strconv"

	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
)

type ServerProbe struct {
	Protocol int32
	Port     int32

	Fingerprint string

	SupportsAccount bool

	SupportsRequestControl bool
	SupportsRequestObserve bool

	SupportsVNCPassword bool

	SupportsNoPassword bool

	GrantsRequests bool

	ErrorCode    int32
	ErrorMessage string
}

type ProbeRequest struct {
	CheckOwnScreen bool

	ctx    context.Context
	cancel context.CancelFunc
}

func NewProbeRequest() *ProbeRequest {
	ctx, cancel := context.WithCancel(context.Background())
	return &ProbeRequest{ctx: ctx, cancel: cancel}
}

func (r *ProbeRequest) Cancel() {
	r.cancel()
}

func (r *ProbeRequest) ProbeServer(host string, port int32) *ServerProbe {
	return r.probeServer(r.ctx, host, port)
}

func (r *ProbeRequest) ProbeRDPServer(host string, port int32) *ServerProbe {
	return r.probeRDP(r.ctx, host, port, false)
}

func (r *ProbeRequest) probeServer(ctx context.Context, host string, port int32) *ServerProbe {
	if r.CheckOwnScreen {
		err := checkOwnScreen(ctx, host, port)
		if err != nil {
			code, message := classifyError(err)
			return &ServerProbe{ErrorCode: code, ErrorMessage: message}
		}
	}
	info, err := rfb.Probe(ctx, rfb.Options{
		Address: net.JoinHostPort(host, strconv.Itoa(int(port))),
		Logger:  newLogger(),
	})
	if err != nil {
		code, message := classifyError(err)
		return &ServerProbe{ErrorCode: code, ErrorMessage: message}
	}
	offered := info.SecurityTypes
	accountTypes := slices.ContainsFunc(rfb.SecurityAccount, func(securityType rfb.SecurityType) bool {
		return slices.Contains(offered, securityType)
	})
	return &ServerProbe{
		Protocol:               ProtocolRFB,
		Port:                   port,
		SupportsAccount:        accountTypes,
		SupportsRequestControl: slices.Contains(offered, rfb.SecurityAskControl),
		SupportsRequestObserve: slices.Contains(offered, rfb.SecurityAskObserve),
		SupportsVNCPassword:    slices.Contains(offered, rfb.SecurityVNCPassword),
		SupportsNoPassword:     slices.Contains(offered, rfb.SecurityNone),
		GrantsRequests:         rfb.GrantsAsking(offered),
	}
}

type LoginCheck struct {
	SupportsHighPerformance bool

	Fingerprint string

	ErrorCode    int32
	ErrorMessage string
}

func (r *ProbeRequest) CheckLogin(options *SessionOptions) *LoginCheck {
	if options.Protocol == ProtocolRDP {
		fingerprint, err := checkRDPLogin(r.ctx, options)
		code, message := classifyError(err)
		return &LoginCheck{Fingerprint: fingerprint, ErrorCode: code, ErrorMessage: message}
	}
	var fingerprint string
	rfbOptions, err := rfbOptions(options, func(seen string) { fingerprint = seen })
	if err != nil {
		code, message := classifyError(err)
		return &LoginCheck{ErrorCode: code, ErrorMessage: message}
	}
	info, err := rfb.CheckLogin(r.ctx, rfbOptions)
	if err != nil {
		code, message := classifyError(err)
		return &LoginCheck{Fingerprint: fingerprint, ErrorCode: code, ErrorMessage: message}
	}
	return &LoginCheck{SupportsHighPerformance: newServerInfo(info, options.Platform).SupportsHighPerformance, Fingerprint: fingerprint}
}
