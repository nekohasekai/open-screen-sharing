package rfb

import (
	"encoding/binary"
	"math"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	ButtonLeft   uint8 = 0x01
	ButtonMiddle uint8 = 0x02
	ButtonRight  uint8 = 0x04
	wheelUp      uint8 = 0x08
	wheelDown    uint8 = 0x10
	wheelLeft    uint8 = 0x20
	wheelRight   uint8 = 0x40
)

const (
	KeysymShiftLeft    uint32 = 0xffe1
	KeysymShiftRight   uint32 = 0xffe2
	KeysymControlLeft  uint32 = 0xffe3
	KeysymControlRight uint32 = 0xffe4
	KeysymCapsLock     uint32 = 0xffe5
	KeysymMetaLeft     uint32 = 0xffe7
	KeysymMetaRight    uint32 = 0xffe8
	KeysymAltLeft      uint32 = 0xffe9
	KeysymAltRight     uint32 = 0xffea
	KeysymSuperLeft    uint32 = 0xffeb
	KeysymSuperRight   uint32 = 0xffec
)

func (c *Client) sendInput(message []byte, motion bool) error {
	c.lastInput.Store(time.Now().UnixNano())
	c.access.Lock()
	if !c.inputReady {
		if motion && c.heldMotion {
			c.heldInput[len(c.heldInput)-1] = message
		} else {
			c.heldInput = append(c.heldInput, message)
		}
		c.heldMotion = motion
		c.access.Unlock()
		return nil
	}
	c.access.Unlock()
	return c.outbox.Push(message, motion)
}

func (c *Client) releaseInput() error {
	c.access.Lock()
	defer c.access.Unlock()
	held := c.heldInput
	c.heldInput = nil
	c.inputReady = true
	for _, message := range held {
		err := c.send(message)
		if err != nil {
			return err
		}
	}
	return nil
}

func (c *Client) SendPointer(x, y int, buttons uint8, clickCount int) error {
	c.access.Lock()
	if c.mode == ModeObserve {
		c.access.Unlock()
		return E.New("rfb: pointer events are not sent while observing")
	}
	motion := buttons == c.buttons
	c.buttons = buttons
	wireX, wireY := c.wirePositionLocked(x, y)
	c.access.Unlock()
	if !motion && c.info.Accepts(0x10) {
		message, err := encryptedPointerMessage(c.wrapKey, c.wireButtons(buttons), clickCount, wireX, wireY)
		if err != nil {
			return err
		}
		return c.sendInput(message, false)
	}
	return c.sendInput(pointerEventMessage(c.wireButtons(buttons), wireX, wireY), motion)
}

// screensharingd sends a drag's messages only to the viewer that sent the last encrypted or V2
// pointer event with button mask exactly 1; a message 5 never marks a viewer. Its encrypted
// event handler reads a pointer from a block with byte 0 clear and byte 10 set
// (screensharingd 756.36.5.2, macOS 27.0.1 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D,
// sub_100036680 0x10003df9c..0x10003dfd4, 0x10003f51c..0x10003f528 and 0x10003e5fc..0x10003e61c,
// sub_100041964 0x100041f3c..0x100041f48, sub_1000415bc 0x100041644..0x100041650,
// sub_100007630 0x100007740..0x100007764).
// It takes the click count from bits 1-3 of message byte 1 when bit 0 is set. Otherwise
// ScreensharingAgent counts a press at the previous event's exact position as the next click
// whenever the times in block bytes 6-9 of the press and the last release sum to at most the
// double-click interval, so zero there makes every press at an unmoved pointer a multi-click
// (screensharingd sub_100036680 0x10003dfd8..0x10003dfe4, 0x10003f3fc..0x10003f40c and
// 0x10003f548; ScreensharingAgent 756.36.5.2, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
// sub_100024c78 0x100025050..0x1000250fc).
func encryptedPointerMessage(key [16]byte, buttons uint8, clickCount, x, y int) ([]byte, error) {
	message := make([]byte, 18)
	message[0] = 0x10
	if clickCount > 0 {
		message[1] = 1 | byte(min(clickCount, 7))<<1
	}
	block := message[2:]
	block[10] = 0xff
	block[11] = buttons
	binary.BigEndian.PutUint16(block[12:], clampUint16(x))
	binary.BigEndian.PutUint16(block[14:], clampUint16(y))
	err := encryptECB(key[:], block)
	if err != nil {
		return nil, err
	}
	return message, nil
}

func (c *Client) wirePositionLocked(x, y int) (int, int) {
	if c.info.Dialect != DialectApple || c.scale == 1 {
		return x, y
	}
	return int(math.Round(float64(x) / c.scale)), int(math.Round(float64(y) / c.scale))
}

// ScreensharingAgent passes each button-mask bit index as the CGMouseButton
// (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3,
// sub_1000231dc 0x10002328c..0x1000232f8, 0x1000233d0..0x1000233d4).
func (c *Client) wireButtons(buttons uint8) uint8 {
	if c.info.Dialect != DialectApple {
		return buttons
	}
	swapped := buttons &^ (ButtonMiddle | ButtonRight)
	if buttons&ButtonMiddle != 0 {
		swapped |= ButtonRight
	}
	if buttons&ButtonRight != 0 {
		swapped |= ButtonMiddle
	}
	return swapped
}

const (
	applePointsPerPulse    = 2
	standardPointsPerPulse = 20
	maximumPulses          = 64
)

func (c *Client) SendScroll(x, y int, deltaX, deltaY float64) error {
	c.access.Lock()
	if c.mode == ModeObserve {
		c.access.Unlock()
		return E.New("rfb: scrolling is not sent while observing")
	}
	buttons := c.wireButtons(c.buttons)
	wireX, wireY := c.wirePositionLocked(x, y)
	apple := c.info.Dialect == DialectApple
	if c.info.Accepts(0x17) {
		c.access.Unlock()
		c.StopScrollMomentum()
		return c.sendInput(scrollWheelMessage(wireX, wireY, deltaX, deltaY, scrollEvent{}), false)
	}
	pointsPerPulse := float64(standardPointsPerPulse)
	if apple {
		pointsPerPulse = applePointsPerPulse
	}
	c.scrollRemainderX += deltaX / pointsPerPulse
	c.scrollRemainderY += deltaY / pointsPerPulse
	pulsesX := int(c.scrollRemainderX)
	pulsesY := int(c.scrollRemainderY)
	c.scrollRemainderX -= float64(pulsesX)
	c.scrollRemainderY -= float64(pulsesY)
	c.access.Unlock()
	var messages []byte
	pulse := func(count int, positive, negative uint8) {
		bit := positive
		if count < 0 {
			bit, count = negative, -count
		}
		for i := 0; i < min(count, maximumPulses); i++ {
			if apple {
				// screensharingd scrolls only for masks exactly 8 or 16 (screensharingd 756.36.5.2,
				// macOS 27.0.1 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D,
				// sub_100036680 0x100038d84..0x100038d9c).
				messages = append(messages, pointerEventMessage(bit, wireX, wireY)...)
			} else {
				messages = append(messages, pointerEventMessage(buttons|bit, wireX, wireY)...)
			}
			messages = append(messages, pointerEventMessage(buttons, wireX, wireY)...)
		}
	}
	pulse(pulsesY, wheelUp, wheelDown)
	if !apple {
		pulse(pulsesX, wheelLeft, wheelRight)
	}
	if len(messages) == 0 {
		return nil
	}
	return c.sendInput(messages, false)
}

type scrollEvent struct {
	phase      GesturePhase
	momentum   uint32
	continuous bool
}

func scrollWheelMessage(x, y int, deltaX, deltaY float64, event scrollEvent) []byte {
	const pointsPerLine = 10
	whole := func(points float64) uint16 {
		lines := math.Round(points / pointsPerLine)
		if lines == 0 && points != 0 && !event.continuous {
			lines = math.Copysign(1, points)
		}
		return uint16(int16(clampFloat(lines, math.MinInt16, math.MaxInt16)))
	}
	fixed := func(points float64) uint32 {
		return uint32(int32(clampFloat(points/pointsPerLine*65536, math.MinInt32, math.MaxInt32)))
	}
	rounded := func(points float64) uint32 {
		return uint32(int32(clampFloat(math.Round(points), math.MinInt32, math.MaxInt32)))
	}
	count, flags := uint32(1), uint32(0)
	if event.continuous {
		count, flags = 0, 2
	}
	body := binary.BigEndian.AppendUint16(nil, whole(deltaX))
	body = binary.BigEndian.AppendUint16(body, whole(deltaY))
	body = binary.BigEndian.AppendUint16(body, 0)
	body = binary.BigEndian.AppendUint32(body, fixed(deltaX))
	body = binary.BigEndian.AppendUint32(body, fixed(deltaY))
	body = binary.BigEndian.AppendUint32(body, 0)
	body = binary.BigEndian.AppendUint32(body, rounded(deltaX))
	body = binary.BigEndian.AppendUint32(body, rounded(deltaY))
	body = binary.BigEndian.AppendUint32(body, 0)
	body = binary.BigEndian.AppendUint32(body, uint32(event.phase))
	body = binary.BigEndian.AppendUint32(body, event.momentum)
	body = binary.BigEndian.AppendUint32(body, count)
	body = binary.BigEndian.AppendUint32(body, flags)
	return eventMessage(1, eventScrollWheel, appendPosition(body, x, y))
}

func clampFloat(value, low, high float64) float64 {
	return math.Min(math.Max(value, low), high)
}

func (c *Client) SendKey(keysym uint32, down bool) error {
	if c.observing() {
		return E.New("rfb: key events are not sent while observing")
	}
	c.trackKey(keysym, down)
	if c.info.Dialect == DialectApple {
		keysym = appleKeysym(keysym)
	}
	return c.sendKeyboard(keyEventMessage(down, keysym))
}

func (c *Client) trackKey(keysym uint32, down bool) {
	c.access.Lock()
	defer c.access.Unlock()
	if !down {
		delete(c.heldKeys, keysym)
		return
	}
	if c.heldKeys == nil {
		c.heldKeys = make(map[uint32]bool)
	}
	c.heldKeys[keysym] = true
}

func appleKeysym(keysym uint32) uint32 {
	switch keysym {
	case KeysymAltLeft:
		return KeysymMetaLeft
	case KeysymAltRight:
		return KeysymMetaRight
	default:
		return keysym
	}
}

func (c *Client) SendText(text string) error {
	var messages []byte
	for _, character := range text {
		keysym := uint32(character)
		switch {
		case character == '\n' || character == '\r':
			keysym = 0xff0d
		case character == '\t':
			keysym = 0xff09
		case character == '\b':
			keysym = 0xff08
		case character > 0xff:
			keysym = 0x01000000 | uint32(character)
		}
		messages = append(messages, keyEventMessage(true, keysym)...)
		messages = append(messages, keyEventMessage(false, keysym)...)
	}
	if c.observing() {
		return E.New("rfb: text is not typed while observing")
	}
	return c.sendKeyboard(messages)
}
