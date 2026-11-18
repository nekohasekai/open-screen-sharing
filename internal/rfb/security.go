package rfb

import (
	"context"
	"crypto/des"
	"iter"
	"math/bits"
	"net"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/nekohasekai/open-screen-sharing/internal/gss"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
)

type SecurityType uint32

const (
	SecurityNone                 SecurityType = 1
	SecurityVNCPassword          SecurityType = 2
	SecurityRSAAES               SecurityType = 5
	SecurityRSAAESUnencrypted    SecurityType = 6
	SecurityVeNCrypt             SecurityType = 19
	SecurityAppleDH              SecurityType = 30
	SecurityAskObserve           SecurityType = 31
	SecurityAskControl           SecurityType = 32
	SecurityAppleRSA             SecurityType = 33
	SecurityApplePSK             SecurityType = 34
	SecurityKerberos             SecurityType = 35
	SecurityAppleSRP             SecurityType = 36
	SecurityRSAAES256            SecurityType = 129
	SecurityRSAAES256Unencrypted SecurityType = 130
	SecurityPlain                SecurityType = 256
	SecurityTLSNone              SecurityType = 257
	SecurityTLSVNC               SecurityType = 258
	SecurityTLSPlain             SecurityType = 259
	SecurityX509None             SecurityType = 260
	SecurityX509VNC              SecurityType = 261
	SecurityX509Plain            SecurityType = 262
)

var securityNames = map[SecurityType]string{
	SecurityNone:                 "none",
	SecurityVNCPassword:          "vnc-password",
	SecurityRSAAES:               "rsa-aes",
	SecurityRSAAESUnencrypted:    "rsa-aes-unencrypted",
	SecurityVeNCrypt:             "vencrypt",
	SecurityAppleDH:              "apple-dh",
	SecurityAskObserve:           "ask-observe",
	SecurityAskControl:           "ask-control",
	SecurityAppleRSA:             "apple-rsa-srp",
	SecurityApplePSK:             "apple-psk",
	SecurityKerberos:             "kerberos",
	SecurityAppleSRP:             "apple-srp",
	SecurityRSAAES256:            "rsa-aes-256",
	SecurityRSAAES256Unencrypted: "rsa-aes-256-unencrypted",
	SecurityPlain:                "plain",
	SecurityTLSNone:              "tls-none",
	SecurityTLSVNC:               "tls-vnc",
	SecurityTLSPlain:             "tls-plain",
	SecurityX509None:             "x509-none",
	SecurityX509VNC:              "x509-vnc",
	SecurityX509Plain:            "x509-plain",
}

func (t SecurityType) String() string {
	name, found := securityNames[t]
	if found {
		return name + "(" + strconv.Itoa(int(t)) + ")"
	}
	return "security(" + strconv.Itoa(int(t)) + ")"
}

type Method struct {
	Type          SecurityType
	RSAAESSubtype uint8
}

func (m Method) String() string {
	return m.Type.String()
}

var rsaAESTypes = []SecurityType{SecurityRSAAES256, SecurityRSAAES, SecurityRSAAES256Unencrypted, SecurityRSAAESUnencrypted}

func methodsOf(rsaAESSubtype uint8, securityTypes ...SecurityType) []Method {
	return common.Map(securityTypes, func(securityType SecurityType) Method {
		if slices.Contains(rsaAESTypes, securityType) {
			return Method{Type: securityType, RSAAESSubtype: rsaAESSubtype}
		}
		return Method{Type: securityType}
	})
}

var (
	AskPermissionMethods  = methodsOf(0, SecurityAskControl, SecurityAskObserve)
	ObserveRequestMethods = methodsOf(0, SecurityAskObserve, SecurityAskControl)
	PasswordMethods       = methodsOf(RSAAESPassword, SecurityX509VNC, SecurityRSAAES256, SecurityRSAAES, SecurityRSAAES256Unencrypted, SecurityRSAAESUnencrypted, SecurityVNCPassword)
	NoPasswordMethods     = methodsOf(0, SecurityX509None, SecurityNone)
)

// AccountMethods orders account logins the way ScreenSharing does: it
// tries Kerberos first only for .local host names (ScreenSharing 776.27,
// macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
// -[SSSessionView shouldPreferKerberosWithAuthTypes:wasBTMM:] 0x1cc0894bc,
// -[SSSessionView orderedArrayOfAuthTypesForMethod:preferKerberos:serverAllowedTypes:] 0x1cc089768).
func AccountMethods(host string) []Method {
	if strings.HasSuffix(strings.ToLower(strings.TrimSuffix(host, ".")), ".local") {
		return methodsOf(RSAAESUserPassword,
			SecurityKerberos, SecurityAppleRSA, SecurityAppleSRP,
			SecurityX509Plain, SecurityRSAAES256, SecurityRSAAES, SecurityRSAAES256Unencrypted, SecurityRSAAESUnencrypted,
			SecurityAppleDH, SecurityPlain,
		)
	}
	return methodsOf(RSAAESUserPassword,
		SecurityAppleRSA, SecurityAppleSRP,
		SecurityX509Plain, SecurityRSAAES256, SecurityRSAAES, SecurityRSAAES256Unencrypted, SecurityRSAAESUnencrypted,
		SecurityAppleDH, SecurityKerberos, SecurityPlain,
	)
}

type authenticator func(h *handshake) ([]byte, error)

var authenticators = map[SecurityType]authenticator{
	SecurityNone:                 authenticateNone,
	SecurityVNCPassword:          authenticateVNCPassword,
	SecurityRSAAES:               authenticateRSAAES,
	SecurityRSAAESUnencrypted:    authenticateRSAAES,
	SecurityAppleDH:              authenticateAppleDH,
	SecurityAskObserve:           authenticateAppleDH,
	SecurityAskControl:           authenticateAppleDH,
	SecurityAppleRSA:             authenticateAppleRSA,
	SecurityKerberos:             authenticateKerberos,
	SecurityAppleSRP:             authenticateAppleSRP,
	SecurityRSAAES256:            authenticateRSAAES,
	SecurityRSAAES256Unencrypted: authenticateRSAAES,
	SecurityPlain:                authenticatePlain,
	SecurityX509None:             overTLS(authenticateNone),
	SecurityX509VNC:              overTLS(authenticateVNCPassword),
	SecurityX509Plain:            overTLS(authenticatePlain),
}

var ErrUnsupportedServer = E.New("rfb: unsupported server")

var ErrUnverifiedServer = E.New("rfb: the server offers only account logins that do not verify its identity")

var ErrServerKeyChanged = E.New("rfb: the server key changed")

const kerberosTimeout = 5 * time.Second

var errSecurityFallback = E.New("rfb: retry with another security type")

type securityFallback struct {
	excluded   []Method
	unverified bool
}

func (h *handshake) permitted(candidate Method) bool {
	if h.options.AllowUnverifiedLogin || candidate.Type != SecurityAppleDH && candidate.Type != SecurityPlain {
		return true
	}
	h.fallback.unverified = true
	return false
}

func (h *handshake) offeredMethods() iter.Seq[Method] {
	return func(yield func(Method) bool) {
		for _, candidate := range h.preferred {
			if !slices.Contains(h.info.SecurityTypes, carrier(candidate.Type)) || !h.permitted(candidate) {
				continue
			}
			if !yield(candidate) {
				return
			}
		}
	}
}

func (h *handshake) fallBack(excluded func(Method) bool, cause error) error {
	h.fallback.excluded = append(h.fallback.excluded, common.Filter(h.preferred, excluded)...)
	h.preferred = common.Filter(h.preferred, func(method Method) bool {
		return !excluded(method)
	})
	for range h.offeredMethods() {
		return E.Cause1(errSecurityFallback, cause)
	}
	if h.fallback.unverified {
		return E.Extend(ErrUnverifiedServer, cause)
	}
	return cause
}

func (h *handshake) chooseSecurityType() (Method, error) {
	offered := h.info.SecurityTypes
	var kerberosErr error
	for candidate := range h.offeredMethods() {
		_, implemented := authenticators[candidate.Type]
		if !implemented {
			continue
		}
		switch candidate.Type {
		case SecurityAppleRSA:
			// ScreenSharing drops type 33 from an Apple server that offers
			// neither 35 nor 36 (ScreenSharing 776.27, macOS 27.0.1 26A434,
			// UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, 0x1cc08a038..0x1cc08a09c).
			if h.info.Dialect == DialectApple && !slices.Contains(offered, SecurityAppleSRP) && !slices.Contains(offered, SecurityKerberos) {
				continue
			}
		case SecurityKerberos:
			kdc, _, _ := net.SplitHostPort(h.options.Address)
			if address, isTCP := h.conn.RemoteAddr().(*net.TCPAddr); isTCP {
				kdc = netip.AddrPortFrom(address.AddrPort().Addr().Unmap(), 88).String()
			}
			ctx, cancel := context.WithTimeout(h.ctx, kerberosTimeout)
			var err error
			h.kerberos, err = gss.NewInitiator(ctx, h.options.Username, h.options.Password, kdc, "vnc")
			cancel()
			if err != nil {
				kerberosErr = err
				h.logger.Debug("skip kerberos", "error", err)
				continue
			}
		}
		return candidate, nil
	}
	offeredNames := strings.Join(F.MapToString(offered), ", ")
	if h.fallback.unverified {
		return Method{}, E.Extend(ErrUnverifiedServer, "it offers security types ", offeredNames)
	}
	err := E.Extend(ErrUnsupportedServer, "it offers security types ", offeredNames, "; none of ", strings.Join(F.MapToString(h.preferred), ", "))
	if kerberosErr != nil {
		return Method{}, E.Errors(err, kerberosErr)
	}
	return Method{}, err
}

func authenticateNone(*handshake) ([]byte, error) {
	return nil, nil
}

func authenticateVNCPassword(h *handshake) ([]byte, error) {
	challenge, err := h.stream.bytes(16)
	if err != nil {
		return nil, err
	}
	var key [8]byte
	copy(key[:], h.options.Password)
	for i := range key {
		key[i] = bits.Reverse8(key[i])
	}
	block, err := des.NewCipher(key[:])
	if err != nil {
		return nil, err
	}
	response := make([]byte, 16)
	block.Encrypt(response[:8], challenge[:8])
	block.Encrypt(response[8:], challenge[8:])
	return nil, h.writer.Write([][]byte{response})
}

func credentialBlock(username, password string) ([]byte, error) {
	// ScreenSharing's type-33 credential block randomizes 128 bytes, then copies
	// the username and password into separate 64-byte slots before encryption
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBAuthenticateCore 0x1cc13faf4..0x1cc13fb44).
	if len(username) > 63 {
		return nil, E.New("rfb: user name of ", len(username), " bytes is over the 63-byte limit")
	}
	if len(password) > 63 {
		return nil, E.New("rfb: password is over the 63-byte limit")
	}
	if strings.IndexByte(username, 0) >= 0 || strings.IndexByte(password, 0) >= 0 {
		return nil, E.New("rfb: user name or password contains NUL")
	}
	block := randomBytes(128)
	copy(block, username)
	block[len(username)] = 0
	copy(block[64:], password)
	block[64+len(password)] = 0
	return block, nil
}
