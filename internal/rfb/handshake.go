package rfb

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/nekohasekai/open-screen-sharing/internal/gss"
	"github.com/nekohasekai/open-screen-sharing/internal/outbox"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

var ErrAuthenticationFailed = E.New("rfb: authentication failed")

var ErrPermissionDenied = E.New("rfb: the request to share the screen was declined")

type ProtocolVersion struct {
	Major int
	Minor int
}

func (v ProtocolVersion) String() string {
	return fmt.Sprintf("%03d.%03d", v.Major, v.Minor)
}

func (v ProtocolVersion) apple() bool {
	return v.Major == 3 && (v.Minor == 889 || v.Minor == 888)
}

type ServerInfo struct {
	Version       ProtocolVersion
	Dialect       Dialect
	SecurityTypes []SecurityType
	SecurityType  SecurityType
	Name          string
	Width         int
	Height        int
	PixelFormat   PixelFormat
	Flags         uint32
	Commands      [16]byte
	Extended      bool
}

const (
	ServerFlagObserveOnly         uint32 = 0x01
	ServerFlagControlPrivilege    uint32 = 0x02
	ServerFlagSessionSelect       uint32 = 0x04
	ServerFlagCaptureNotPermitted uint32 = 0x08
	ServerFlagExtended            uint32 = 0x10
)

func (s *ServerInfo) Accepts(messageType uint8) bool {
	index := int(messageType) / 8
	if !s.Extended || index >= len(s.Commands) {
		return false
	}
	return s.Commands[index]&(0x80>>(messageType%8)) != 0
}

func (s *ServerInfo) MaximumVirtualDisplays() int {
	return int(s.Flags >> 5)
}

type handshake struct {
	ctx       context.Context
	conn      net.Conn
	stream    *stream
	writer    *outbox.Writer
	options   *Options
	logger    *slog.Logger
	info      ServerInfo
	method    Method
	preferred []Method
	fallback  *securityFallback
	wrapKey   []byte
	kerberos  *gss.Initiator
	selected  func(SecurityType)
}

func connect(ctx context.Context, options *Options, logger *slog.Logger, attempt func(h *handshake) error) error {
	dialer := net.Dialer{
		KeepAliveConfig: net.KeepAliveConfig{
			Enable:   true,
			Idle:     5 * time.Second,
			Interval: 5 * time.Second,
			Count:    3,
		},
	}
	var fallback securityFallback
	for {
		conn, err := dialer.DialContext(ctx, "tcp", options.Address)
		if err != nil {
			return err
		}
		stop := context.AfterFunc(ctx, func() {
			conn.Close()
		})
		err = attempt(&handshake{ctx: ctx, conn: conn, stream: newStream(conn), writer: outbox.NewWriter(conn), options: options, logger: logger, fallback: &fallback})
		if !stop() {
			err = E.Errors(ctx.Err(), err)
		}
		if err == nil {
			return nil
		}
		conn.Close()
		if !errors.Is(err, errSecurityFallback) || ctx.Err() != nil {
			return err
		}
		logger.Debug("retry with another security type", "error", err)
	}
}

func (h *handshake) negotiateVersion() error {
	banner, err := h.stream.bytes(12)
	if err != nil {
		return E.Cause(err, "read protocol version")
	}
	var server ProtocolVersion
	_, err = fmt.Sscanf(string(banner), "RFB %03d.%03d\n", &server.Major, &server.Minor)
	if err != nil {
		return E.New("rfb: server greeting ", strconv.Quote(string(banner)), " is not RFB")
	}
	dialect := DialectStandard
	var client ProtocolVersion
	switch {
	case server.apple():
		dialect = DialectApple
		client = ProtocolVersion{3, 889}
	case server.Major > 3 || server.Minor >= 8:
		client = ProtocolVersion{3, 8}
	case server.Minor >= 7:
		client = ProtocolVersion{3, 7}
	default:
		client = ProtocolVersion{3, 3}
	}
	err = h.writer.Write([][]byte{fmt.Appendf(nil, "RFB %s\n", client)})
	if err != nil {
		return err
	}
	h.info.Version = client
	h.info.Dialect = dialect
	h.logger.Debug("protocol version", "server", server.String(), "client", client.String())
	return nil
}

func (h *handshake) readReason() error {
	length, err := h.stream.u32()
	if err != nil {
		return err
	}
	reason, err := h.stream.bytes(int(min(length, 1<<16)))
	if err != nil {
		return err
	}
	return E.New(string(reason))
}

func (h *handshake) readSecurityTypes() error {
	if h.info.Version.Minor == 3 {
		securityType, err := h.stream.u32()
		if err != nil {
			return E.Cause(err, "read security type")
		}
		if securityType == 0 {
			return E.Cause(h.readReason(), "server refused the connection")
		}
		h.info.SecurityTypes = []SecurityType{SecurityType(securityType)}
		return nil
	}
	count, err := h.stream.u8()
	if err != nil {
		return E.Cause(err, "read security types")
	}
	if count == 0 {
		return E.Cause(h.readReason(), "server refused the connection")
	}
	offered, err := h.stream.bytes(int(count))
	if err != nil {
		return E.Cause(err, "read security types")
	}
	h.info.SecurityTypes = common.Map(offered, func(value byte) SecurityType {
		return SecurityType(value)
	})
	return nil
}

func (h *handshake) authenticate() error {
	preferred := h.options.Methods
	if len(preferred) == 0 {
		host, _, _ := net.SplitHostPort(h.options.Address)
		preferred = AccountMethods(host)
	}
	h.preferred = common.Filter(preferred, func(method Method) bool {
		return !slices.Contains(h.fallback.excluded, method)
	})
	err := h.readSecurityTypes()
	if err != nil {
		return err
	}
	h.method, err = h.chooseSecurityType()
	if h.kerberos != nil {
		defer h.kerberos.Close()
	}
	if err != nil {
		return err
	}
	h.info.SecurityType = carrier(h.method.Type)
	if h.info.Version.Minor != 3 && h.info.SecurityType != SecurityAppleRSA && h.info.SecurityType != SecurityAppleSRP {
		err = h.writer.Write([][]byte{{byte(h.info.SecurityType)}})
		if err != nil {
			return err
		}
	}
	h.logger.Debug("security", "offered", h.info.SecurityTypes, "selected", h.info.SecurityType)
	if h.info.SecurityType == SecurityVeNCrypt {
		h.method, err = h.chooseVeNCryptSubtype()
		if err != nil {
			return E.Cause(err, "authenticate with ", h.info.SecurityType)
		}
	}
	if h.selected != nil {
		h.selected(h.method.Type)
	}
	h.wrapKey, err = authenticators[h.method.Type](h)
	if err != nil {
		return E.Cause(err, "authenticate with ", h.info.SecurityType)
	}
	if h.method.Type == SecurityNone && h.info.Version.Minor < 8 {
		return nil
	}
	result, err := h.stream.u32()
	if err != nil {
		return E.Cause(err, "read security result")
	}
	return h.securityResult(result)
}

func (h *handshake) securityResult(result uint32) error {
	if result == 0 {
		return nil
	}
	cause := ErrAuthenticationFailed
	if h.method.Type == SecurityAskControl || h.method.Type == SecurityAskObserve {
		cause = ErrPermissionDenied
	}
	if h.info.Version.Minor == 8 {
		return E.Cause1(cause, h.readReason())
	}
	return E.Extend(cause, "result ", result)
}

func (h *handshake) initialize() error {
	clientInit := byte(1)
	if h.info.Dialect == DialectApple {
		// ScreenSharing adds 0x80 for its extension and 0x40 for session selection
		// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
		// _RFBAuthenticateCore 0x1cc1407fc, 0x1cc1408b0, 0x1cc140998..0x1cc1409a4).
		clientInit = 0xc1
	}
	err := h.writer.Write([][]byte{{clientInit}})
	if err != nil {
		return err
	}
	header, err := h.stream.bytes(24)
	if err != nil {
		return E.Cause(err, "read ServerInit")
	}
	h.info.Width = int(binary.BigEndian.Uint16(header[0:]))
	h.info.Height = int(binary.BigEndian.Uint16(header[2:]))
	h.info.PixelFormat = parsePixelFormat(header[4:20])
	nameLength := binary.BigEndian.Uint32(header[20:])
	if nameLength > 1<<20 {
		return E.New("rfb: ServerInit name of ", nameLength, " bytes is over the 1 MiB limit")
	}
	name, err := h.stream.bytes(int(nameLength))
	if err != nil {
		return err
	}
	if h.info.Dialect == DialectApple && len(name) >= 22 && name[0] == 0 && name[1] == 0 {
		h.info.Extended = true
		h.info.Flags = binary.BigEndian.Uint32(name[2:])
		copy(h.info.Commands[:], name[6:22])
		name = name[22:]
	}
	h.info.Name = decodeName(name)
	h.logger.Debug("server init", "name", h.info.Name, "width", h.info.Width, "height", h.info.Height,
		"flags", fmt.Sprintf("%#x", h.info.Flags), "commands", fmt.Sprintf("%x", h.info.Commands))
	return nil
}

func decodeName(name []byte) string {
	if utf8.Valid(name) {
		return strings.TrimRight(string(name), "\x00")
	}
	return decodeLatin1(name)
}
