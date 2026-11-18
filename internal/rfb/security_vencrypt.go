package rfb

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"slices"
	"strings"

	"github.com/nekohasekai/open-screen-sharing/internal/outbox"
	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
)

var veNCryptTypes = []SecurityType{SecurityPlain, SecurityX509None, SecurityX509VNC, SecurityX509Plain}

var veNCryptSubtypes = slices.Concat([]SecurityType{SecurityNone, SecurityVNCPassword}, veNCryptTypes, rsaAESTypes)

func carrier(securityType SecurityType) SecurityType {
	if slices.Contains(veNCryptTypes, securityType) {
		return SecurityVeNCrypt
	}
	return securityType
}

func (h *handshake) negotiateVeNCrypt() ([]SecurityType, error) {
	version, err := h.stream.bytes(2)
	if err != nil {
		return nil, E.Cause(err, "read VeNCrypt version")
	}
	if binary.BigEndian.Uint16(version) < 2 {
		_ = h.writer.Write([][]byte{{0, 0}})
		return nil, E.Extend(ErrUnsupportedServer, "it offers VeNCrypt ", version[0], ".", version[1], ", not 0.2")
	}
	err = h.writer.Write([][]byte{{0, 2}})
	if err != nil {
		return nil, err
	}
	refused, err := h.stream.u8()
	if err != nil {
		return nil, E.Cause(err, "read VeNCrypt version acknowledgement")
	}
	if refused != 0 {
		return nil, E.Extend(ErrUnsupportedServer, "it refused VeNCrypt 0.2")
	}
	count, err := h.stream.u8()
	if err != nil {
		return nil, E.Cause(err, "read VeNCrypt subtypes")
	}
	if count == 0 {
		return nil, E.Extend(ErrUnsupportedServer, "it offers no VeNCrypt subtypes")
	}
	data, err := h.stream.bytes(4 * int(count))
	if err != nil {
		return nil, E.Cause(err, "read VeNCrypt subtypes")
	}
	subtypes := make([]SecurityType, count)
	for i := range subtypes {
		subtypes[i] = SecurityType(binary.BigEndian.Uint32(data[4*i:]))
	}
	return subtypes, nil
}

func (h *handshake) chooseVeNCryptSubtype() (Method, error) {
	isVeNCrypt := func(method Method) bool {
		return carrier(method.Type) == SecurityVeNCrypt
	}
	offered, err := h.negotiateVeNCrypt()
	if errors.Is(err, ErrUnsupportedServer) {
		return Method{}, h.fallBack(isVeNCrypt, err)
	}
	if err != nil {
		return Method{}, err
	}
	for _, candidate := range h.preferred {
		if !slices.Contains(offered, candidate.Type) || !slices.Contains(veNCryptSubtypes, candidate.Type) || !h.permitted(candidate) {
			continue
		}
		h.logger.Debug("vencrypt", "offered", offered, "selected", candidate)
		return candidate, h.writer.Write([][]byte{binary.BigEndian.AppendUint32(nil, uint32(candidate.Type))})
	}
	return Method{}, h.fallBack(isVeNCrypt, E.Extend(ErrUnsupportedServer, "its VeNCrypt offers subtypes ", strings.Join(F.MapToString(offered), ", ")))
}

func overTLS(inner authenticator) authenticator {
	return func(h *handshake) ([]byte, error) {
		err := h.startTLS()
		if err != nil {
			return nil, err
		}
		return inner(h)
	}
}

func authenticatePlain(h *handshake) ([]byte, error) {
	username, password := h.options.Username, h.options.Password
	message := binary.BigEndian.AppendUint32(nil, uint32(len(username)))
	message = binary.BigEndian.AppendUint32(message, uint32(len(password)))
	message = append(message, username...)
	message = append(message, password...)
	return nil, h.writer.Write([][]byte{message})
}

type layeredConn struct {
	net.Conn
	reader io.Reader
}

func (c *layeredConn) Read(data []byte) (int, error) {
	return c.reader.Read(data)
}

func (h *handshake) startTLS() error {
	accepted, err := h.stream.u8()
	if err != nil {
		return E.Cause(err, "read VeNCrypt subtype acknowledgement")
	}
	if accepted != 1 {
		return E.New("rfb: the server refused VeNCrypt subtype ", h.method)
	}
	host, _, _ := net.SplitHostPort(h.options.Address)
	config := &tls.Config{InsecureSkipVerify: true}
	if net.ParseIP(host) == nil {
		config.ServerName = host
	}
	conn := tls.Client(&layeredConn{Conn: h.conn, reader: h.stream.raw}, config)
	err = conn.HandshakeContext(h.ctx)
	if err != nil {
		return E.Cause(err, "TLS handshake")
	}
	certificates := conn.ConnectionState().PeerCertificates
	intermediates := x509.NewCertPool()
	for _, certificate := range certificates[1:] {
		intermediates.AddCert(certificate)
	}
	_, verifyErr := certificates[0].Verify(x509.VerifyOptions{DNSName: host, Intermediates: intermediates})
	serverFingerprint := fingerprint(certificates[0].Raw)
	h.logger.Debug("server certificate", "fingerprint", serverFingerprint, "version", tls.VersionName(conn.ConnectionState().Version), "chain", verifyErr)
	if h.options.VerifyServerKey != nil {
		err = h.options.VerifyServerKey(serverFingerprint, verifyErr == nil)
		if err != nil {
			return err
		}
	}
	h.stream = newStream(conn)
	h.writer = outbox.NewWriter(conn)
	return nil
}
