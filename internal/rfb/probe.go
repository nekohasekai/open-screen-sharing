package rfb

import (
	"context"
	"encoding/binary"
	"fmt"
	"log/slog"
	"slices"

	"github.com/sagernet/sing/common"
)

func Probe(ctx context.Context, options Options) ([]Method, error) {
	rsaAESIn := func(securityTypes []SecurityType) SecurityType {
		return common.Find(rsaAESTypes, func(securityType SecurityType) bool {
			return slices.Contains(securityTypes, securityType)
		})
	}
	var subtypes []SecurityType
	var rsaAESSubtype uint8
	extend := func(h *handshake, direct bool) error {
		var variant SecurityType
		if !direct && slices.Contains(h.info.SecurityTypes, SecurityVeNCrypt) {
			err := h.writer.Write([][]byte{{byte(SecurityVeNCrypt)}})
			if err != nil {
				return err
			}
			subtypes, err = h.negotiateVeNCrypt()
			if err != nil {
				return err
			}
			variant = rsaAESIn(subtypes)
			if variant == 0 {
				return nil
			}
			err = h.writer.Write([][]byte{binary.BigEndian.AppendUint32(nil, uint32(variant))})
			if err != nil {
				return err
			}
		} else {
			variant = rsaAESIn(h.info.SecurityTypes)
			if variant == 0 {
				return nil
			}
			err := h.writer.Write([][]byte{{byte(variant)}})
			if err != nil {
				return err
			}
		}
		var err error
		rsaAESSubtype, _, err = h.startRSAAES(variant)
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
	if err != nil {
		return nil, err
	}
	if slices.Contains(info.SecurityTypes, SecurityVeNCrypt) && rsaAESIn(info.SecurityTypes) != 0 && rsaAESIn(subtypes) == 0 {
		_, err = exchange(ctx, options, steps(true))
		if err != nil {
			return nil, err
		}
	}
	offered := common.Filter(info.SecurityTypes, func(securityType SecurityType) bool {
		return securityType != SecurityVeNCrypt
	})
	return methodsOf(rsaAESSubtype, append(offered, subtypes...)...), nil
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
	var info ServerInfo
	err := connect(ctx, &options, logger, func(h *handshake) error {
		err := steps(h)
		h.conn.Close()
		info = h.info
		return err
	})
	if err != nil {
		return ServerInfo{}, err
	}
	return info, nil
}
