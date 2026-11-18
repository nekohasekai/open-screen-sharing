package rfb

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"slices"
	"time"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

func dial(ctx context.Context, options Options) (net.Conn, error) {
	dialer := net.Dialer{
		KeepAliveConfig: net.KeepAliveConfig{
			Enable:   true,
			Idle:     5 * time.Second,
			Interval: 5 * time.Second,
			Count:    3,
		},
	}
	return dialer.DialContext(ctx, "tcp", options.Address)
}

func Probe(ctx context.Context, options Options) (ServerInfo, error) {
	rsaAESIn := func(securityTypes []SecurityType) SecurityType {
		return common.Find(rsaAESTypes, func(securityType SecurityType) bool {
			return slices.Contains(securityTypes, securityType)
		})
	}
	extend := func(h *handshake, direct bool) error {
		var variant SecurityType
		if !direct && slices.Contains(h.info.SecurityTypes, SecurityVeNCrypt) {
			_, err := h.conn.Write([]byte{byte(SecurityVeNCrypt)})
			if err != nil {
				return err
			}
			err = h.readVeNCryptSubtypes()
			if err != nil {
				return err
			}
			variant = rsaAESIn(h.info.VeNCryptSubtypes)
			if variant == 0 {
				return nil
			}
			_, err = h.conn.Write(binary.BigEndian.AppendUint32(nil, uint32(variant)))
			if err != nil {
				return err
			}
		} else {
			variant = rsaAESIn(h.info.SecurityTypes)
			if variant == 0 {
				return nil
			}
			_, err := h.conn.Write([]byte{byte(variant)})
			if err != nil {
				return err
			}
		}
		var err error
		h.info.RSAAESSubtype, _, err = h.startRSAAES(variant)
		return err
	}
	steps := func(direct bool) func(h *handshake) error {
		return func(h *handshake) error {
			err := h.negotiateVersion()
			if err != nil {
				return err
			}
			err = h.readSecurityTypes()
			if err != nil {
				return err
			}
			h.logger.Debug("probe", "version", h.info.Version.String(), "offered", fmt.Sprint(h.info.SecurityTypes))
			if h.info.Version.Minor == 3 {
				return nil
			}
			err = extend(h, direct)
			if err != nil {
				h.logger.Debug("probe security types", "error", err)
			}
			return nil
		}
	}
	info, err := exchange(ctx, options, steps(false))
	if err != nil || !slices.Contains(info.SecurityTypes, SecurityVeNCrypt) || rsaAESIn(info.SecurityTypes) == 0 || rsaAESIn(info.VeNCryptSubtypes) != 0 {
		return info, err
	}
	direct, err := exchange(ctx, options, steps(true))
	if err != nil {
		return ServerInfo{}, err
	}
	info.RSAAESSubtype = direct.RSAAESSubtype
	return info, nil
}

func CheckLogin(ctx context.Context, options Options) (ServerInfo, error) {
	return exchange(ctx, options, func(h *handshake) error {
		err := h.negotiateVersion()
		if err != nil {
			return err
		}
		err = h.authenticate()
		if err != nil {
			return err
		}
		return h.initialize()
	})
}

func exchange(ctx context.Context, options Options, steps func(h *handshake) error) (ServerInfo, error) {
	logger := options.Logger
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	var fallback securityFallback
	for {
		conn, err := dial(ctx, options)
		if err != nil {
			return ServerInfo{}, err
		}
		stop := context.AfterFunc(ctx, func() {
			conn.Close()
		})
		h := &handshake{ctx: ctx, conn: conn, stream: newStream(conn), options: &options, logger: logger, fallback: &fallback}
		err = steps(h)
		conn.Close()
		if !stop() {
			err = E.Errors(ctx.Err(), err)
		}
		if errors.Is(err, errSecurityFallback) && ctx.Err() == nil {
			logger.Debug("retry with another security type", "error", err)
			continue
		}
		if err != nil {
			return ServerInfo{}, err
		}
		return h.info, nil
	}
}
