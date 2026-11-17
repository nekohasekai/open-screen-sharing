package rfb

import (
	"encoding/binary"
	"strconv"
	"strings"
)

type Status uint16

// Apple's viewer handles status commands 1 through 17 in a jump table, logging
// "user paused connection" for 7 and "user resumed connection" for 8 (ScreenSharing 776.27,
// macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
// _HandleServerMessage 0x1cc062684..0x1cc0626b0, 0x1cc063728, 0x1cc063488;
// table 0x1cc06455c).
const (
	StatusSessionEnding       Status = 1
	StatusPasteboardChanged   Status = 2
	StatusPasteboardNeedsData Status = 3
	StatusHeartbeat           Status = 4
	StatusDisplaysSleep       Status = 5
	StatusDisplaysWake        Status = 6
	StatusConnectionPaused    Status = 7
	StatusConnectionResumed   Status = 8
	StatusControlAllowed      Status = 9
	StatusObserveOnly         Status = 10
	StatusPointerHidden       Status = 11
	StatusPointerShown        Status = 12
	StatusBusyCursorStart     Status = 13
	StatusBusyCursorEnd       Status = 14
	StatusUserSessionChanged  Status = 17
)

var statusNames = map[Status]string{
	StatusSessionEnding:       "session-ending",
	StatusPasteboardChanged:   "pasteboard-changed",
	StatusPasteboardNeedsData: "pasteboard-needs-data",
	StatusHeartbeat:           "heartbeat",
	StatusDisplaysSleep:       "displays-sleep",
	StatusDisplaysWake:        "displays-wake",
	StatusConnectionPaused:    "connection-paused",
	StatusConnectionResumed:   "connection-resumed",
	StatusControlAllowed:      "control-allowed",
	StatusObserveOnly:         "observe-only",
	StatusPointerHidden:       "pointer-hidden",
	StatusPointerShown:        "pointer-shown",
	StatusBusyCursorStart:     "busy-cursor-start",
	StatusBusyCursorEnd:       "busy-cursor-end",
	StatusUserSessionChanged:  "user-session-changed",
}

func (s Status) String() string {
	name, found := statusNames[s]
	if found {
		return name
	}
	return "status(" + strconv.Itoa(int(s)) + ")"
}

func (c *Client) receiveStatus() error {
	_, err := c.stream.u8()
	if err != nil {
		return err
	}
	length, err := c.stream.u16()
	if err != nil {
		return err
	}
	body, err := c.stream.bytes(int(length))
	if err != nil {
		return err
	}
	if len(body) < 4 {
		return nil
	}
	status := Status(binary.BigEndian.Uint16(body[2:]))
	c.logger.Debug("status", "command", status.String())
	switch status {
	case StatusPasteboardChanged:
		c.access.Lock()
		sharedClipboard := c.sharedClipboard
		own := c.takeOwnPasteboardEchoLocked()
		c.access.Unlock()
		if sharedClipboard && c.running && !own {
			err = c.RequestClipboard()
			if err != nil {
				return err
			}
		}
	case StatusObserveOnly:
		c.access.Lock()
		c.mode = ModeObserve
		c.access.Unlock()
	case StatusDisplaysSleep, StatusDisplaysWake:
		asleep := status == StatusDisplaysSleep
		c.access.Lock()
		if c.media != nil {
			c.media.stream.SetDisplaysAsleep(asleep)
			// When the displays wake, screensharingd asks ScreensharingAgent to monitor
			// screen changes again only for connections that do not use the media stream
			// encoding, so nothing restarts the media sender of this connection
			// (screensharingd 756.36.5.2, macOS 27.0.1 26A434,
			// UUID 44908B06-F788-3822-ABC4-2285001F6A8D, SendFrameBuffer
			// 0x1000217b0..0x100021828, 0x1000218d4).
			if !asleep {
				err = c.reconfigureVirtualDisplayLocked()
			}
		}
		c.access.Unlock()
		if err != nil {
			return err
		}
	}
	c.handler.StatusReceived(status)
	return nil
}

type KeyboardSource struct {
	ID          string
	SecureInput bool
}

func (c *Client) receiveKeyboardSource() error {
	length, err := c.stream.u16()
	if err != nil {
		return err
	}
	body, err := c.stream.bytes(int(length))
	if err != nil {
		return err
	}
	if len(body) < 8 {
		return nil
	}
	idLength := int(binary.BigEndian.Uint16(body[6:]))
	if 8+idLength > len(body) {
		return nil
	}
	c.handler.KeyboardSourceChanged(KeyboardSource{
		ID:          string(body[8 : 8+idLength]),
		SecureInput: binary.BigEndian.Uint32(body[2:])&1 != 0,
	})
	return nil
}

type DeviceInfo struct {
	Model          string
	DeviceColor    string
	EnclosureColor string
	DynamicDrag    bool
}

func (c *Client) receiveDeviceInfo() error {
	length, err := c.stream.u16()
	if err != nil {
		return err
	}
	body, err := c.stream.bytes(int(length))
	if err != nil {
		return err
	}
	if len(body) < 16 {
		return nil
	}
	info := DeviceInfo{DynamicDrag: binary.BigEndian.Uint32(body[2:])&1 != 0}
	offset := 16
	fields := []*string{&info.Model, &info.DeviceColor, &info.EnclosureColor}
	for i, field := range fields {
		size := int(binary.BigEndian.Uint16(body[10+i*2:]))
		if offset+size > len(body) {
			break
		}
		*field = strings.TrimRight(string(body[offset:offset+size]), "\x00")
		offset += size
	}
	c.handler.DeviceInfoReceived(info)
	return nil
}
