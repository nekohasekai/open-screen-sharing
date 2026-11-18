package rfb

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/binary"
	"io"
	"math"
	"math/big"
	"net"
	"slices"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	RSAAESUserPassword uint8 = 1
	RSAAESPassword     uint8 = 2
)

// neatvnc decrypts an RSA-AES message only into the space left in its
// 4096-byte message buffer, or in the rest of a ClientCutText it reads in
// pieces, and takes 8192 bytes of messages it cannot place as a hang-up
// (neatvnc 0.9.1, src/stream/rsa-aes.c stream_rsa_aes_read,
// src/server.c on_client_event, process_big_cut_text).
const rsaAESMessageSize = 4096

const rsaAESClientKeyBits = 2048

var errRSAAESMessage = E.New("rfb: RSA-AES message authentication failed")

type eax struct {
	block  cipher.Block
	double [2][16]byte
}

func newEAX(key []byte) (*eax, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	mode := &eax{block: block}
	var value [16]byte
	block.Encrypt(value[:], value[:])
	for i := range mode.double {
		carry := value[0] >> 7
		for j := range 15 {
			value[j] = value[j]<<1 | value[j+1]>>7
		}
		value[15] = value[15]<<1 ^ carry*0x87
		mode.double[i] = value
	}
	return mode, nil
}

func (e *eax) omac(tag byte, data []byte) [16]byte {
	var mac [16]byte
	mac[15] = tag
	if len(data) == 0 {
		subtle.XORBytes(mac[:], mac[:], e.double[0][:])
		e.block.Encrypt(mac[:], mac[:])
		return mac
	}
	e.block.Encrypt(mac[:], mac[:])
	for len(data) > 16 {
		subtle.XORBytes(mac[:], mac[:], data[:16])
		e.block.Encrypt(mac[:], mac[:])
		data = data[16:]
	}
	var last [16]byte
	copy(last[:], data)
	if len(data) == 16 {
		subtle.XORBytes(last[:], last[:], e.double[0][:])
	} else {
		last[len(data)] = 0x80
		subtle.XORBytes(last[:], last[:], e.double[1][:])
	}
	subtle.XORBytes(mac[:], mac[:], last[:])
	e.block.Encrypt(mac[:], mac[:])
	return mac
}

func (e *eax) seal(dst, nonce, plaintext, header []byte) []byte {
	counter := e.omac(0, nonce)
	tag := e.omac(1, header)
	start := len(dst)
	dst = slices.Grow(dst, len(plaintext)+16)[:start+len(plaintext)]
	cipher.NewCTR(e.block, counter[:]).XORKeyStream(dst[start:], plaintext)
	mac := e.omac(2, dst[start:])
	subtle.XORBytes(tag[:], tag[:], counter[:])
	subtle.XORBytes(tag[:], tag[:], mac[:])
	return append(dst, tag[:]...)
}

func (e *eax) open(dst, nonce, sealed, header []byte) ([]byte, error) {
	if len(sealed) < 16 {
		return nil, errRSAAESMessage
	}
	ciphertext, received := sealed[:len(sealed)-16], sealed[len(sealed)-16:]
	counter := e.omac(0, nonce)
	tag := e.omac(1, header)
	mac := e.omac(2, ciphertext)
	subtle.XORBytes(tag[:], tag[:], counter[:])
	subtle.XORBytes(tag[:], tag[:], mac[:])
	if !hmac.Equal(tag[:], received) {
		return nil, errRSAAESMessage
	}
	start := len(dst)
	dst = slices.Grow(dst, len(ciphertext))[:start+len(ciphertext)]
	cipher.NewCTR(e.block, counter[:]).XORKeyStream(dst[start:], ciphertext)
	return dst, nil
}

func incrementNonce(nonce *[16]byte) {
	for i := range nonce {
		nonce[i]++
		if nonce[i] != 0 {
			return
		}
	}
}

type rsaAESConn struct {
	net.Conn
	reader   io.Reader
	in       *eax
	out      *eax
	inNonce  [16]byte
	outNonce [16]byte
	header   [2]byte
	message  []byte
	pending  []byte
	frames   []byte
}

func (c *rsaAESConn) Read(data []byte) (int, error) {
	for len(c.pending) == 0 {
		_, err := io.ReadFull(c.reader, c.header[:])
		if err != nil {
			return 0, err
		}
		length := int(binary.BigEndian.Uint16(c.header[:])) + 16
		c.message = slices.Grow(c.message[:0], length)[:length]
		_, err = io.ReadFull(c.reader, c.message)
		if err == io.EOF {
			err = io.ErrUnexpectedEOF
		}
		if err != nil {
			return 0, err
		}
		c.pending, err = c.in.open(c.message[:0], c.inNonce[:], c.message, c.header[:])
		if err != nil {
			return 0, err
		}
		incrementNonce(&c.inNonce)
	}
	n := copy(data, c.pending)
	c.pending = c.pending[n:]
	return n, nil
}

func (c *rsaAESConn) Write(data []byte) (int, error) {
	_, err := c.WriteMessages([][]byte{data})
	if err != nil {
		return 0, err
	}
	return len(data), nil
}

func (c *rsaAESConn) WriteMessages(messages [][]byte) (int, error) {
	c.frames = c.frames[:0]
	for _, message := range messages {
		for chunk := range slices.Chunk(message, rsaAESMessageSize) {
			var header [2]byte
			binary.BigEndian.PutUint16(header[:], uint16(len(chunk)))
			c.frames = append(c.frames, header[:]...)
			c.frames = c.out.seal(c.frames, c.outNonce[:], chunk, header[:])
			incrementNonce(&c.outNonce)
		}
	}
	return c.Conn.Write(c.frames)
}

func (h *handshake) startRSAAES(variant SecurityType) (uint8, string, error) {
	serverBits, err := h.stream.u32()
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES server key")
	}
	if serverBits < 1024 || serverBits > 8192 {
		return 0, "", E.New("rfb: RSA-AES server key of ", serverBits, " bits, want 1024 to 8192")
	}
	serverSize := int(serverBits+7) / 8
	serverPublic, err := h.stream.bytes(2 * serverSize)
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES server key")
	}
	exponent := new(big.Int).SetBytes(serverPublic[serverSize:])
	if !exponent.IsInt64() || exponent.Int64() < 3 || exponent.Int64() > math.MaxInt32 {
		return 0, "", E.New("rfb: RSA-AES server key has an unusable public exponent")
	}
	serverKey := &rsa.PublicKey{N: new(big.Int).SetBytes(serverPublic[:serverSize]), E: int(exponent.Int64())}
	der, err := x509.MarshalPKIXPublicKey(serverKey)
	if err != nil {
		return 0, "", E.Cause(err, "encode RSA-AES server key")
	}
	serverFingerprint := fingerprint(der)
	h.logger.Debug("server RSA-AES key", "bits", serverBits, "fingerprint", serverFingerprint)
	serverPublic = append(binary.BigEndian.AppendUint32(nil, serverBits), serverPublic...)

	clientKey, err := rsa.GenerateKey(rand.Reader, rsaAESClientKeyBits)
	if err != nil {
		return 0, "", err
	}
	clientSize := rsaAESClientKeyBits / 8
	clientPublic := binary.BigEndian.AppendUint32(nil, rsaAESClientKeyBits)
	clientPublic = append(clientPublic, clientKey.N.FillBytes(make([]byte, clientSize))...)
	clientPublic = append(clientPublic, big.NewInt(int64(clientKey.E)).FillBytes(make([]byte, clientSize))...)

	randomSize, keySize, newHash := 16, 16, sha1.New
	if variant == SecurityRSAAES256 || variant == SecurityRSAAES256Unencrypted {
		randomSize, keySize, newHash = 32, 32, sha256.New
	}
	digest := func(parts ...[]byte) []byte {
		state := newHash()
		for _, part := range parts {
			state.Write(part)
		}
		return state.Sum(nil)
	}
	clientRandom := randomBytes(randomSize)
	encryptedRandom, err := rsa.EncryptPKCS1v15(rand.Reader, serverKey, clientRandom)
	if err != nil {
		return 0, "", E.Cause(err, "encrypt RSA-AES client random")
	}
	message := slices.Clone(clientPublic)
	message = binary.BigEndian.AppendUint16(message, uint16(len(encryptedRandom)))
	message = append(message, encryptedRandom...)
	_, err = h.conn.Write(message)
	if err != nil {
		return 0, "", err
	}

	length, err := h.stream.u16()
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES server random")
	}
	if int(length) != clientSize {
		return 0, "", E.New("rfb: RSA-AES server random of ", length, " bytes, want ", clientSize)
	}
	encryptedRandom, err = h.stream.bytes(int(length))
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES server random")
	}
	serverRandom, err := rsa.DecryptPKCS1v15(nil, clientKey, encryptedRandom)
	if err != nil {
		return 0, "", E.Cause(err, "decrypt RSA-AES server random")
	}
	if len(serverRandom) != randomSize {
		return 0, "", E.New("rfb: RSA-AES server random of ", len(serverRandom), " bytes, want ", randomSize)
	}
	in, err := newEAX(digest(clientRandom, serverRandom)[:keySize])
	if err != nil {
		return 0, "", err
	}
	out, err := newEAX(digest(serverRandom, clientRandom)[:keySize])
	if err != nil {
		return 0, "", err
	}
	h.upgrade(&rsaAESConn{Conn: h.conn, reader: h.stream.raw, in: in, out: out})

	_, err = h.conn.Write(digest(clientPublic, serverPublic))
	if err != nil {
		return 0, "", err
	}
	serverHash, err := h.stream.bytes(newHash().Size())
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES server hash")
	}
	if !hmac.Equal(serverHash, digest(serverPublic, clientPublic)) {
		return 0, "", E.New("rfb: the server's RSA-AES hash does not match the exchanged keys")
	}
	subtype, err := h.stream.u8()
	if err != nil {
		return 0, "", E.Cause(err, "read RSA-AES subtype")
	}
	if subtype != RSAAESUserPassword && subtype != RSAAESPassword {
		return 0, "", E.New("rfb: unknown RSA-AES subtype ", subtype)
	}
	return subtype, serverFingerprint, nil
}

func authenticateRSAAES(h *handshake) ([]byte, error) {
	conn, stream := h.conn, h.stream
	subtype, serverFingerprint, err := h.startRSAAES(h.method)
	if err != nil {
		return nil, err
	}
	isRSAAES := func(securityType SecurityType) bool {
		return slices.Contains(rsaAESTypes, securityType)
	}
	username, password := h.options.Username, h.options.Password
	switch {
	case subtype == RSAAESUserPassword && username == "":
		return nil, h.fallBack(isRSAAES, E.Extend(ErrUnsupportedServer, "its RSA-AES login asks for a user name"))
	case subtype == RSAAESPassword && username != "":
		return nil, h.fallBack(isRSAAES, E.Extend(ErrUnsupportedServer, "its RSA-AES login asks for only a password"))
	}
	if h.options.VerifyServerKey != nil {
		err = h.options.VerifyServerKey(serverFingerprint, false)
		if err != nil {
			return nil, err
		}
	}
	if len(username) > 255 {
		return nil, E.New("rfb: user name of ", len(username), " bytes is over the 255-byte limit")
	}
	if len(password) > 255 {
		return nil, E.New("rfb: password is over the 255-byte limit")
	}
	credentials := append([]byte{byte(len(username))}, username...)
	credentials = append(credentials, byte(len(password)))
	credentials = append(credentials, password...)
	_, err = h.conn.Write(credentials)
	if err != nil {
		return nil, err
	}
	if h.method == SecurityRSAAESUnencrypted || h.method == SecurityRSAAES256Unencrypted {
		h.conn, h.stream = conn, stream
	}
	return nil, nil
}
