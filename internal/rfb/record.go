package rfb

import (
	"bufio"
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha1"
	"crypto/subtle"
	"encoding/binary"
	"hash"
	"io"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	recordChecksumSize = sha1.Size
	recordMaximumSize  = 65520
	recordMaximumBody  = recordMaximumSize - 2 - recordChecksumSize
	rekeySize          = 36
)

type recordKeys struct {
	key [16]byte
	iv  [16]byte
}

func unwrapRekey(wrapKey [16]byte, body []byte) (uint32, recordKeys, error) {
	if len(body) != rekeySize {
		return 0, recordKeys{}, E.New("rfb: rekey body is ", len(body), " bytes, want ", rekeySize)
	}
	block, err := aes.NewCipher(wrapKey[:])
	if err != nil {
		return 0, recordKeys{}, err
	}
	var keys recordKeys
	block.Decrypt(keys.key[:], body[4:20])
	block.Decrypt(keys.iv[:], body[20:36])
	return binary.BigEndian.Uint32(body), keys, nil
}

type recordDecoder struct {
	mode     cipher.BlockMode
	hash     hash.Hash
	sequence uint32
}

func (d *recordDecoder) rekey(keys recordKeys) error {
	block, err := aes.NewCipher(keys.key[:])
	if err != nil {
		return err
	}
	d.mode = cipher.NewCBCDecrypter(block, keys.iv[:])
	d.hash = sha1.New()
	return nil
}

type recordEncoder struct {
	mode     cipher.BlockMode
	hash     hash.Hash
	sequence uint32
	data     []byte
}

func (e *recordEncoder) rekey(keys recordKeys) error {
	block, err := aes.NewCipher(keys.key[:])
	if err != nil {
		return err
	}
	e.mode = cipher.NewCBCEncrypter(block, keys.iv[:])
	e.hash = sha1.New()
	return nil
}

func (e *recordEncoder) encode(wire []byte, messages [][]byte) []byte {
	e.data = e.data[:0]
	for _, message := range messages {
		e.data = append(e.data, message...)
	}
	data := e.data
	for len(data) > 0 {
		chunk := min(len(data), recordMaximumBody)
		wire = e.seal(wire, data[:chunk])
		data = data[chunk:]
	}
	return wire
}

func (e *recordEncoder) seal(output []byte, body []byte) []byte {
	size := (2 + len(body) + recordChecksumSize + 15) &^ 15
	start := len(output)
	output = append(output, make([]byte, 2+size)...)
	record := output[start:]
	binary.BigEndian.PutUint16(record, uint16(size))
	plaintext := record[2:]
	binary.BigEndian.PutUint16(plaintext, uint16(len(body)))
	copy(plaintext[2:], body)
	checksumOffset := size - recordChecksumSize
	hash := e.hash
	hash.Reset()
	var sequence [4]byte
	binary.BigEndian.PutUint32(sequence[:], e.sequence)
	hash.Write(sequence[:])
	hash.Write(plaintext[:checksumOffset])
	hash.Sum(plaintext[checksumOffset:checksumOffset])
	e.mode.CryptBlocks(plaintext, plaintext)
	e.sequence++
	return output
}

type recordReader struct {
	source    *bufio.Reader
	decoder   recordDecoder
	buffer    []byte
	plaintext []byte
}

func (r *recordReader) Read(data []byte) (int, error) {
	for len(r.plaintext) == 0 {
		err := r.next()
		if err != nil {
			return 0, err
		}
	}
	n := copy(data, r.plaintext)
	r.plaintext = r.plaintext[n:]
	return n, nil
}

func (r *recordReader) next() error {
	var header [2]byte
	_, err := io.ReadFull(r.source, header[:])
	if err != nil {
		return err
	}
	size := int(binary.BigEndian.Uint16(header[:]))
	if size < 32 || size%16 != 0 {
		return E.New("rfb: record of ", size, " bytes, want at least 32 and a multiple of 16")
	}
	if cap(r.buffer) < size {
		r.buffer = make([]byte, recordMaximumSize)
	}
	ciphertext := r.buffer[:size]
	_, err = io.ReadFull(r.source, ciphertext)
	if err != nil {
		return err
	}
	r.decoder.mode.CryptBlocks(ciphertext, ciphertext)
	checksumOffset := size - recordChecksumSize
	hash := r.decoder.hash
	hash.Reset()
	var sequence [4]byte
	binary.BigEndian.PutUint32(sequence[:], r.decoder.sequence)
	hash.Write(sequence[:])
	hash.Write(ciphertext[:checksumOffset])
	var checksum [recordChecksumSize]byte
	hash.Sum(checksum[:0])
	if subtle.ConstantTimeCompare(checksum[:], ciphertext[checksumOffset:size]) != 1 {
		return E.New("rfb: checksum mismatch in record ", r.decoder.sequence, " of ", size, " bytes")
	}
	bodySize := int(binary.BigEndian.Uint16(ciphertext))
	if 2+bodySize > checksumOffset {
		return E.New("rfb: record body of ", bodySize, " bytes in a ", size, " byte record")
	}
	r.decoder.sequence++
	r.plaintext = ciphertext[2 : 2+bodySize]
	return nil
}
