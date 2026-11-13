package media

import (
	"encoding/binary"
	"slices"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	protoVarint  = 0
	protoFixed64 = 1
	protoBytes   = 2
	protoFixed32 = 5
)

type protoField struct {
	number   uint64
	wireType uint8
	value    uint64
	bytes    []byte
}

type protoMessage []protoField

func (m *protoMessage) addUint(number uint64, value uint64) {
	*m = append(*m, protoField{number: number, wireType: protoVarint, value: value})
}

func (m *protoMessage) addBytes(number uint64, value []byte) {
	*m = append(*m, protoField{number: number, wireType: protoBytes, bytes: value})
}

func (m *protoMessage) addMessage(number uint64, value protoMessage) {
	m.addBytes(number, value.encode())
}

func (m protoMessage) encode() []byte {
	var out []byte
	for _, field := range m {
		out = binary.AppendUvarint(out, field.number<<3|uint64(field.wireType))
		switch field.wireType {
		case protoVarint:
			out = binary.AppendUvarint(out, field.value)
		case protoBytes:
			out = binary.AppendUvarint(out, uint64(len(field.bytes)))
			out = append(out, field.bytes...)
		}
	}
	return out
}

func parseProto(data []byte) (protoMessage, error) {
	var message protoMessage
	total := len(data)
	for len(data) > 0 {
		tag, n := binary.Uvarint(data)
		if n <= 0 {
			return nil, E.New("media: invalid protobuf field tag at offset ", total-len(data), " of ", total)
		}
		data = data[n:]
		field := protoField{number: tag >> 3, wireType: uint8(tag & 7)}
		switch field.wireType {
		case protoVarint:
			field.value, n = binary.Uvarint(data)
			if n <= 0 {
				return nil, E.New("media: invalid protobuf varint in field ", field.number)
			}
			data = data[n:]
		case protoFixed64:
			if len(data) < 8 {
				return nil, E.New("media: truncated protobuf fixed64 field ", field.number, ": ", len(data), " of 8 bytes")
			}
			field.value = binary.LittleEndian.Uint64(data)
			data = data[8:]
		case protoFixed32:
			if len(data) < 4 {
				return nil, E.New("media: truncated protobuf fixed32 field ", field.number, ": ", len(data), " of 4 bytes")
			}
			field.value = uint64(binary.LittleEndian.Uint32(data))
			data = data[4:]
		case protoBytes:
			length, lengthSize := binary.Uvarint(data)
			if lengthSize <= 0 {
				return nil, E.New("media: invalid protobuf length varint in field ", field.number)
			}
			if length > uint64(len(data)-lengthSize) {
				return nil, E.New("media: truncated protobuf field ", field.number, ": ", len(data)-lengthSize, " of ", length, " bytes")
			}
			field.bytes = data[lengthSize : lengthSize+int(length)]
			data = data[lengthSize+int(length):]
		default:
			return nil, E.New("media: unsupported protobuf wire type ", field.wireType, " in field ", field.number)
		}
		message = append(message, field)
	}
	return message, nil
}

func (m protoMessage) uint(number uint64) (uint64, bool) {
	index := slices.IndexFunc(m, func(field protoField) bool {
		return field.number == number && field.wireType == protoVarint
	})
	if index < 0 {
		return 0, false
	}
	return m[index].value, true
}

func (m protoMessage) messages(number uint64) []protoMessage {
	var result []protoMessage
	for _, field := range m {
		if field.number != number || field.wireType != protoBytes {
			continue
		}
		nested, err := parseProto(field.bytes)
		if err == nil {
			result = append(result, nested)
		}
	}
	return result
}
