package rfb

import (
	"bytes"
	"crypto/aes"
	"crypto/hmac"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/sha512"
	"crypto/subtle"
	"crypto/x509"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"math/big"
	"reflect"
	"slices"
	"strings"
	"sync"

	E "github.com/sagernet/sing/common/exceptions"
)

func randomBytes(length int) []byte {
	data := make([]byte, length)
	rand.Read(data)
	return data
}

func encryptECB(key []byte, data []byte) error {
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	for offset := 0; offset+16 <= len(data); offset += 16 {
		block.Encrypt(data[offset:offset+16], data[offset:offset+16])
	}
	return nil
}

func authenticateAppleDH(h *handshake) ([]byte, error) {
	generator, err := h.stream.u16()
	if err != nil {
		return nil, err
	}
	keyLength, err := h.stream.u16()
	if err != nil {
		return nil, err
	}
	if keyLength < 64 || keyLength > 1024 {
		return nil, E.New("rfb: Diffie-Hellman key length ", keyLength, ", want 64 to 1024")
	}
	primeBytes, err := h.stream.bytes(int(keyLength))
	if err != nil {
		return nil, err
	}
	serverPublicBytes, err := h.stream.bytes(int(keyLength))
	if err != nil {
		return nil, err
	}
	h.logger.Debug("diffie-hellman group", "generator", generator, "bits", int(keyLength)*8)
	prime := new(big.Int).SetBytes(primeBytes)
	serverPublic := new(big.Int).SetBytes(serverPublicBytes)
	one := big.NewInt(1)
	upper := new(big.Int).Sub(prime, one)
	if generator < 2 {
		return nil, E.New("rfb: Diffie-Hellman generator ", generator, ", want at least 2")
	}
	if serverPublic.Cmp(one) <= 0 || serverPublic.Cmp(upper) >= 0 {
		return nil, E.New("rfb: the server's Diffie-Hellman value is outside 2 to p-2")
	}
	private := new(big.Int).SetBytes(randomBytes(2 * int(keyLength)))
	clientPublic := new(big.Int).Exp(big.NewInt(int64(generator)), private, prime)
	shared := new(big.Int).Exp(serverPublic, private, prime)
	key := md5.Sum(shared.FillBytes(make([]byte, keyLength)))

	username, password := h.options.Username, h.options.Password
	if h.securityType != SecurityAppleDH {
		username, password = h.options.ClientName, ""
	}
	credentials, err := credentialBlock(username, password)
	if err != nil {
		return nil, err
	}
	err = encryptECB(key[:], credentials)
	if err != nil {
		return nil, err
	}
	response := append(credentials, clientPublic.FillBytes(make([]byte, keyLength))...)
	_, err = h.conn.Write(response)
	if err != nil {
		return nil, err
	}
	return key[:], nil
}

func rsaEnvelope(kind uint16, body []byte) []byte {
	// ScreenSharing prefixes the RSA body with 01 00, "RSA1", and a big-endian
	// kind, then prefixes that envelope with its big-endian u32 length
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBAuthenticateCore 0x1cc13fa20..0x1cc13fa44, 0x1cc140420..0x1cc140440).
	message := binary.BigEndian.AppendUint32(nil, uint32(8+len(body)))
	message = append(message, 0x01, 0x00, 'R', 'S', 'A', '1')
	message = binary.BigEndian.AppendUint16(message, kind)
	return append(message, body...)
}

func readSizedMessage(h *handshake, limit int) ([]byte, error) {
	size, err := h.stream.u32()
	if err != nil {
		return nil, err
	}
	if int(size) > limit {
		return nil, E.New("rfb: security message of ", size, " bytes is over the ", limit, "-byte limit")
	}
	return h.stream.bytes(int(size))
}

func requestRSAKey(h *handshake) (*rsa.PublicKey, []byte, error) {
	_, err := h.conn.Write(append([]byte{byte(SecurityAppleRSA)}, rsaEnvelope(0, []byte{0, 0})...))
	if err != nil {
		return nil, nil, err
	}
	reply, err := readSizedMessage(h, 1<<16)
	if err != nil {
		return nil, nil, err
	}
	if len(reply) < 6 {
		return nil, nil, E.New("rfb: RSA key reply of ", len(reply), " bytes, want at least 6")
	}
	length := int(binary.BigEndian.Uint16(reply[4:6]))
	if 6+length > len(reply) {
		return nil, nil, E.New("rfb: truncated RSA key reply: ", 6+length, " bytes needed, ", len(reply), " received")
	}
	der := reply[6 : 6+length]
	publicKey, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		rsaKey, pkcs1Err := x509.ParsePKCS1PublicKey(der)
		if pkcs1Err != nil {
			return nil, nil, E.Cause(err, "parse RSA key")
		}
		return rsaKey, der, nil
	}
	rsaKey, isRSA := publicKey.(*rsa.PublicKey)
	if !isRSA {
		return nil, nil, E.New("rfb: server key is ", reflect.TypeOf(publicKey))
	}
	return rsaKey, der, nil
}

func authenticateAppleRSA(h *handshake) ([]byte, error) {
	publicKey, der, err := requestRSAKey(h)
	if err != nil {
		return nil, err
	}
	sum := sha256.Sum256(der)
	fingerprint := strings.ToUpper(hex.EncodeToString(sum[:]))
	h.logger.Debug("server RSA key", "bits", publicKey.N.BitLen(), "fingerprint", fingerprint)
	if !slices.Contains(h.info.SecurityTypes, SecurityAppleSRP) {
		if h.options.VerifyServerKey != nil {
			err = h.options.VerifyServerKey(fingerprint)
			if err != nil {
				return nil, err
			}
		}
		return authenticateAppleRSAPlain(h, publicKey)
	}
	identity := srpIdentityMessage(h.options.Username)
	encrypted, err := rsa.EncryptPKCS1v15(rand.Reader, publicKey, identity)
	if err != nil {
		return nil, err
	}
	body := binary.BigEndian.AppendUint16(nil, uint16(len(encrypted)))
	body = append(body, encrypted...)
	body = append(body, make([]byte, 384)...)
	_, err = h.conn.Write(rsaEnvelope(2, body))
	if err != nil {
		return nil, err
	}
	return runSRP(h, func() ([]byte, error) {
		reply, readErr := readSizedMessage(h, 1<<16)
		if readErr != nil {
			return nil, readErr
		}
		// screensharingd ends a rejected SRP exchange with an empty envelope
		// followed by the security result (observed on macOS 27.0.1 26A434).
		if len(reply) == 6 && binary.BigEndian.Uint16(reply[4:6]) == 0 {
			result, readErr := h.stream.u32()
			if readErr != nil {
				return nil, readErr
			}
			return nil, h.securityResult(result)
		}
		return unwrapRSAReply(reply)
	}, func(fields []byte) error {
		envelope := binary.BigEndian.AppendUint16(nil, uint16(4+len(fields)))
		envelope = binary.BigEndian.AppendUint32(envelope, uint32(len(fields)))
		envelope = append(envelope, fields...)
		if len(envelope) < 1076-8 {
			envelope = append(envelope, make([]byte, 1076-8-len(envelope))...)
		}
		_, writeErr := h.conn.Write(rsaEnvelope(2, envelope))
		return writeErr
	})
}

func unwrapRSAReply(reply []byte) ([]byte, error) {
	if len(reply) < 10 {
		return nil, E.New("rfb: type 33 reply of ", len(reply), " bytes, want at least 10")
	}
	length := int(binary.BigEndian.Uint16(reply[4:6]))
	if length < 4 {
		return nil, E.New("rfb: type 33 reply body of ", length, " bytes, want at least 4")
	}
	if 6+length > len(reply) {
		return nil, E.New("rfb: truncated type 33 reply: ", 6+length, " bytes needed, ", len(reply), " received")
	}
	return reply[10 : 6+length], nil
}

func authenticateAppleRSAPlain(h *handshake, publicKey *rsa.PublicKey) ([]byte, error) {
	key := randomBytes(16)
	credentials, err := credentialBlock(h.options.Username, h.options.Password)
	if err != nil {
		return nil, err
	}
	err = encryptECB(key, credentials)
	if err != nil {
		return nil, err
	}
	encryptedKey, err := rsa.EncryptPKCS1v15(rand.Reader, publicKey, key)
	if err != nil {
		return nil, err
	}
	body := append(credentials, 0, 0)
	binary.LittleEndian.PutUint16(body[len(credentials):], uint16(len(encryptedKey)))
	body = append(body, encryptedKey...)
	_, err = h.conn.Write(rsaEnvelope(1, body))
	if err != nil {
		return nil, err
	}
	_, err = h.stream.u32()
	if err != nil {
		return nil, err
	}
	return key, nil
}

func authenticateAppleSRP(h *handshake) ([]byte, error) {
	// ScreenSharing sends type 36 followed by a big-endian u32 byte count and
	// the SRP mechanism's first message, without the type-33 RSA envelope
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBAuthenticateCore 0x1cc13f8c0..0x1cc13f8f4).
	identity := srpIdentityMessage(h.options.Username)
	message := append([]byte{byte(SecurityAppleSRP)}, binary.BigEndian.AppendUint32(nil, uint32(len(identity)))...)
	_, err := h.conn.Write(append(message, identity...))
	if err != nil {
		return nil, err
	}
	return runSRP(h, func() ([]byte, error) {
		size, readErr := h.stream.u32()
		if readErr != nil {
			return nil, readErr
		}
		// screensharingd sends the security result in place of the sized
		// message after a rejected SRP proof (observed on macOS 27.0.1 26A434).
		if size < 4 {
			return nil, h.securityResult(size)
		}
		if size > 1<<16 {
			return nil, E.New("rfb: security message of ", size, " bytes is over the ", 1<<16, "-byte limit")
		}
		reply, readErr := h.stream.bytes(int(size))
		if readErr != nil {
			return nil, readErr
		}
		h.logger.Debug("type 36 reply", "bytes", len(reply), "head", fmt.Sprintf("%x", reply[:min(len(reply), 16)]))
		return reply[4:], nil
	}, func(fields []byte) error {
		_, writeErr := h.conn.Write(srpEnvelope(fields))
		return writeErr
	})
}

func srpIdentityMessage(username string) []byte {
	var body []byte
	body = binary.BigEndian.AppendUint16(body, 0)
	body = binary.BigEndian.AppendUint16(body, uint16(len(username)))
	body = append(body, username...)
	body = binary.BigEndian.AppendUint16(body, 0)
	body = append(body, 0)
	return append(binary.BigEndian.AppendUint32(nil, uint32(len(body))), body...)
}

func srpEnvelope(fields []byte) []byte {
	message := binary.BigEndian.AppendUint32(nil, uint32(4+len(fields)))
	message = binary.BigEndian.AppendUint32(message, uint32(len(fields)))
	return append(message, fields...)
}

func srpHash(parts ...[]byte) []byte {
	digest := sha512.New()
	for _, part := range parts {
		digest.Write(part)
	}
	return digest.Sum(nil)
}

type fieldReader struct {
	data []byte
	err  error
}

func (r *fieldReader) take(length int) []byte {
	if r.err != nil {
		return nil
	}
	if length > len(r.data) {
		r.err = io.ErrUnexpectedEOF
		return nil
	}
	value := r.data[:length]
	r.data = r.data[length:]
	return value
}

func (r *fieldReader) u8() int {
	value := r.take(1)
	if value == nil {
		return 0
	}
	return int(value[0])
}

func (r *fieldReader) u16() int {
	value := r.take(2)
	if value == nil {
		return 0
	}
	return int(binary.BigEndian.Uint16(value))
}

func (r *fieldReader) u64() uint64 {
	value := r.take(8)
	if value == nil {
		return 0
	}
	return binary.BigEndian.Uint64(value)
}

func runSRP(h *handshake, readMessage func() ([]byte, error), writeMessage func([]byte) error) ([]byte, error) {
	challengeMessage, err := readMessage()
	if err != nil {
		return nil, err
	}
	challenge := &fieldReader{data: challengeMessage}
	challenge.u8()
	modulusBytes := challenge.take(challenge.u16())
	generatorBytes := challenge.take(challenge.u16())
	salt := challenge.take(challenge.u8())
	serverPublicBytes := challenge.take(challenge.u16())
	iterations := challenge.u64()
	options := challenge.take(challenge.u16())
	if challenge.err != nil {
		return nil, E.Cause(challenge.err, "parse SRP challenge")
	}
	h.logger.Debug("srp challenge", "bits", len(modulusBytes)*8, "iterations", iterations, "options", string(options))
	if len(modulusBytes) < 256 || iterations == 0 || iterations > 10_000_000 {
		return nil, E.New("rfb: unacceptable SRP parameters: ", len(modulusBytes)*8, "-bit group, ", iterations, " iterations")
	}
	modulus := new(big.Int).SetBytes(modulusBytes)
	generator := new(big.Int).SetBytes(generatorBytes)
	serverPublic := new(big.Int).SetBytes(serverPublicBytes)
	if new(big.Int).Mod(serverPublic, modulus).Sign() == 0 {
		return nil, E.New("rfb: invalid SRP server value")
	}
	width := len(modulusBytes)
	pad := func(value *big.Int) []byte {
		return value.FillBytes(make([]byte, width))
	}

	derived := passwordKey(h.options.Password, salt, int(iterations))
	passwordExponent := new(big.Int).SetBytes(srpHash(salt, srpHash([]byte(":"), derived)))
	multiplier := new(big.Int).SetBytes(srpHash(modulusBytes, pad(generator)))
	private := new(big.Int).SetBytes(randomBytes(64))
	clientPublic := new(big.Int).Exp(generator, private, modulus)
	clientPublicBytes := pad(clientPublic)
	serverPublicPadded := pad(serverPublic)
	scrambler := new(big.Int).SetBytes(srpHash(clientPublicBytes, serverPublicPadded))
	base := new(big.Int).Exp(generator, passwordExponent, modulus)
	base.Mul(base, multiplier)
	base.Sub(serverPublic, base)
	base.Mod(base, modulus)
	exponent := new(big.Int).Mul(scrambler, passwordExponent)
	exponent.Add(exponent, private)
	premaster := new(big.Int).Exp(base, exponent, modulus)
	sessionKey := srpHash(pad(premaster))
	modulusHash := srpHash(modulusBytes)
	generatorHash := srpHash(pad(generator))
	for i := range modulusHash {
		modulusHash[i] ^= generatorHash[i]
	}
	proof := srpHash(modulusHash, srpHash(nil), salt, clientPublicBytes, serverPublicPadded, sessionKey)
	expectedServerProof := srpHash(clientPublicBytes, proof, sessionKey)

	var body []byte
	body = binary.BigEndian.AppendUint16(body, uint16(len(clientPublicBytes)))
	body = append(body, clientPublicBytes...)
	body = append(body, byte(len(proof)))
	body = append(body, proof...)
	body = binary.BigEndian.AppendUint16(body, uint16(len(options)))
	body = append(body, options...)
	body = append(body, 16)
	body = append(body, randomBytes(16)...)
	err = writeMessage(body)
	if err != nil {
		return nil, err
	}

	verification, err := readMessage()
	if err != nil {
		return nil, err
	}
	reply := &fieldReader{data: verification}
	serverProof := reply.take(reply.u8())
	if reply.err != nil || !bytes.Equal(serverProof, expectedServerProof) {
		return nil, E.Extend(ErrAuthenticationFailed, "the server's SRP proof does not match")
	}
	wrapKey := sha256.Sum256(sessionKey)
	return wrapKey[:16], nil
}

// ScreenSharing derives 128 bytes with PBKDF2-HMAC-SHA-512 using the iteration count
// from the SRP challenge (ScreenSharing 776.27, macOS 27.0.1 26A434,
// UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, _srp_client_mech_step 0x1cc13a454..0x1cc13a490,
// 0x1cc13ab60..0x1cc13ab98).
func passwordKey(password string, salt []byte, iterations int) []byte {
	key := make([]byte, 128)
	var wait sync.WaitGroup
	for offset := 0; offset < len(key); offset += sha512.Size {
		block := key[offset : offset+sha512.Size]
		index := uint32(offset/sha512.Size + 1)
		wait.Go(func() {
			prf := hmac.New(sha512.New, []byte(password))
			prf.Write(salt)
			prf.Write(binary.BigEndian.AppendUint32(nil, index))
			sum := prf.Sum(nil)
			copy(block, sum)
			for range iterations - 1 {
				prf.Reset()
				prf.Write(sum)
				sum = prf.Sum(sum[:0])
				subtle.XORBytes(block, block, sum)
			}
		})
	}
	wait.Wait()
	return key
}
