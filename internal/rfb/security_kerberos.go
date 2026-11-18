package rfb

import (
	"encoding/binary"

	E "github.com/sagernet/sing/common/exceptions"
)

func authenticateKerberos(h *handshake) ([]byte, error) {
	// screensharingd reads a four-byte client status after type 35 and answers
	// with its own before the token exchange; it then sends the session key
	// wrapped with confidentiality (screensharingd 756.36.5.2, macOS 27.0.1 26A434,
	// 0x100014070..0x100014098, 0x100014a24..0x100014a3c, 0x10001528c..0x1000152c0).
	err := h.writer.Write([][]byte{make([]byte, 4)})
	if err != nil {
		return nil, err
	}
	status, err := h.stream.u32()
	if err != nil {
		return nil, E.Cause(err, "read Kerberos status")
	}
	if status != 0 {
		return nil, E.New("rfb: the server could not start Kerberos: status ", int32(status))
	}
	var input []byte
	for {
		output, complete, err := h.kerberos.Step(input)
		if err != nil {
			return nil, err
		}
		if len(output) == 0 {
			if !complete {
				return nil, E.New("rfb: Kerberos produced no token before completing")
			}
			break
		}
		err = h.writer.Write([][]byte{append(binary.BigEndian.AppendUint32(nil, uint32(len(output))), output...)})
		if err != nil {
			return nil, err
		}
		input, err = readSizedMessage(h, 1<<16)
		if err != nil {
			return nil, E.Cause(err, "read Kerberos token")
		}
		if len(input) == 0 {
			result, err := h.stream.u32()
			if err != nil {
				return nil, E.Cause(err, "read security result")
			}
			return nil, h.securityResult(result)
		}
		if complete {
			break
		}
	}
	wrapped, err := readSizedMessage(h, 1<<16)
	if err != nil {
		return nil, E.Cause(err, "read Kerberos session key")
	}
	key, err := h.kerberos.Unwrap(wrapped)
	if err != nil {
		return nil, err
	}
	if len(key) != 16 {
		return nil, E.New("rfb: Kerberos session key of ", len(key), " bytes, want 16")
	}
	return key, nil
}
