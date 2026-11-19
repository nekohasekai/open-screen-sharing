package rfb

import (
	"encoding/binary"
	"math"

	E "github.com/sagernet/sing/common/exceptions"
)

const accessibilityServerHeaderSize = 14

func (c *Client) receiveAccessibility() error {
	header, err := c.stream.bytes(accessibilityServerHeaderSize)
	if err != nil {
		return err
	}
	feature := binary.LittleEndian.Uint32(header[0:])
	message := binary.LittleEndian.Uint32(header[4:])
	replyTo := binary.LittleEndian.Uint32(header[8:])
	length := int(binary.LittleEndian.Uint16(header[12:]))
	c.logger.Debug("accessibility message", "feature", feature, "message", message, "reply-to", replyTo, "bytes", length)
	data, err := c.stream.bytes(length)
	if err != nil {
		return err
	}
	c.handler.Accessibility(feature, message, replyTo, data)
	return nil
}

func (c *Client) SendAccessibilityViewerMessage(feature, message, messageID uint32, data []byte) error {
	packet, err := c.AccessibilityViewerMessage(feature, message, messageID, data)
	if err != nil {
		return err
	}
	return c.send(packet)
}

func (c *Client) AccessibilityViewerMessage(feature, message, messageID uint32, data []byte) ([]byte, error) {
	// ScreenSharing gates message 37 on the message 0x24 bit of the ServerInit command bitmap
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// _RFBSendAccessibilityViewerMessage 0x1cc149cf4..0x1cc149cf8).
	if !c.info.Accepts(0x24) {
		return nil, E.New("rfb: the server does not take accessibility messages")
	}
	if len(data) > math.MaxUint16 {
		return nil, E.New("rfb: accessibility message of ", len(data), " bytes exceeds 65535")
	}
	packet := make([]byte, 0, 17+len(data))
	packet = append(packet, 37)
	packet = binary.LittleEndian.AppendUint32(packet, feature)
	packet = binary.LittleEndian.AppendUint32(packet, message)
	packet = binary.LittleEndian.AppendUint32(packet, messageID)
	packet = binary.LittleEndian.AppendUint16(packet, 0)
	packet = binary.LittleEndian.AppendUint16(packet, uint16(len(data)))
	return append(packet, data...), nil
}
