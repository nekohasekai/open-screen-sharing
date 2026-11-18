package rfb

import (
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"errors"
	"net"
	"slices"
	"strings"

	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
)

func (h *handshake) readVeNCryptSubtypes() error {
	version, err := h.stream.bytes(2)
	if err != nil {
		return E.Cause(err, "read VeNCrypt version")
	}
	if binary.BigEndian.Uint16(version) < 2 {
		_, _ = h.conn.Write([]byte{0, 0})
		return E.Extend(ErrUnsupportedServer, "it offers VeNCrypt ", version[0], ".", version[1], ", not 0.2")
	}
	_, err = h.conn.Write([]byte{0, 2})
	if err != nil {
		return err
	}
	refused, err := h.stream.u8()
	if err != nil {
		return E.Cause(err, "read VeNCrypt version acknowledgement")
	}
	if refused != 0 {
		return E.Extend(ErrUnsupportedServer, "it refused VeNCrypt 0.2")
	}
	count, err := h.stream.u8()
	if err != nil {
		return E.Cause(err, "read VeNCrypt subtypes")
	}
	if count == 0 {
		return E.Extend(ErrUnsupportedServer, "it offers no VeNCrypt subtypes")
	}
	data, err := h.stream.bytes(4 * int(count))
	if err != nil {
		return E.Cause(err, "read VeNCrypt subtypes")
	}
	h.info.VeNCryptSubtypes = make([]SecurityType, count)
	for i := range h.info.VeNCryptSubtypes {
		h.info.VeNCryptSubtypes[i] = SecurityType(binary.BigEndian.Uint32(data[4*i:]))
	}
	return nil
}

func authenticateVeNCrypt(h *handshake) ([]byte, error) {
	isVeNCrypt := func(securityType SecurityType) bool {
		return securityType >= SecurityPlain
	}
	err := h.readVeNCryptSubtypes()
	if errors.Is(err, ErrUnsupportedServer) {
		return nil, h.fallBack(isVeNCrypt, err)
	}
	if err != nil {
		return nil, err
	}
	offered := h.info.VeNCryptSubtypes
	for _, candidate := range h.preferred {
		if !slices.Contains(offered, candidate) {
			continue
		}
		switch candidate {
		case SecurityNone, SecurityVNCPassword, SecurityPlain, SecurityX509None, SecurityX509VNC, SecurityX509Plain,
			SecurityRSAAES, SecurityRSAAESUnencrypted, SecurityRSAAES256, SecurityRSAAES256Unencrypted:
		default:
			continue
		}
		if h.gated(candidate) {
			h.fallback.unverified = true
			continue
		}
		h.logger.Debug("vencrypt", "offered", offered, "selected", candidate)
		_, err = h.conn.Write(binary.BigEndian.AppendUint32(nil, uint32(candidate)))
		if err != nil {
			return nil, err
		}
		h.method = candidate
		switch candidate {
		case SecurityX509None, SecurityX509VNC, SecurityX509Plain:
			err = h.startTLS()
			if err != nil {
				return nil, err
			}
		}
		switch candidate {
		case SecurityVNCPassword, SecurityX509VNC:
			return authenticateVNCPassword(h)
		case SecurityPlain, SecurityX509Plain:
			return authenticatePlain(h)
		case SecurityRSAAES, SecurityRSAAESUnencrypted, SecurityRSAAES256, SecurityRSAAES256Unencrypted:
			return authenticateRSAAES(h)
		}
		return nil, nil
	}
	return nil, h.fallBack(isVeNCrypt, E.Extend(ErrUnsupportedServer, "its VeNCrypt offers subtypes ", strings.Join(F.MapToString(offered), ", ")))
}

func authenticatePlain(h *handshake) ([]byte, error) {
	username, password := h.options.Username, h.options.Password
	message := binary.BigEndian.AppendUint32(nil, uint32(len(username)))
	message = binary.BigEndian.AppendUint32(message, uint32(len(password)))
	message = append(message, username...)
	message = append(message, password...)
	_, err := h.conn.Write(message)
	return nil, err
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
	h.upgrade(conn)
	return nil
}
