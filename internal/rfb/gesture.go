package rfb

import (
	"encoding/binary"
	"fmt"
	"math"
	"slices"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

type GesturePhase uint32

const (
	PhaseNone      GesturePhase = 0
	PhaseBegan     GesturePhase = 1
	PhaseChanged   GesturePhase = 2
	PhaseEnded     GesturePhase = 4
	PhaseCancelled GesturePhase = 8
	PhaseMayBegin  GesturePhase = 0x80
)

func (p GesturePhase) finished() bool {
	return p == PhaseEnded || p == PhaseCancelled
}

const (
	momentumBegin    uint32 = 1
	momentumContinue uint32 = 2
	momentumEnd      uint32 = 3
)

type SystemCommand uint32

const (
	SystemCommandLaunchpad      SystemCommand = 0x1008fd00
	SystemCommandShowDesktop    SystemCommand = 0x1008fd01
	SystemCommandMissionControl SystemCommand = 0x1008fd02
	SystemCommandAppWindows     SystemCommand = 0x1008fd03
)

func (s SystemCommand) String() string {
	switch s {
	case SystemCommandLaunchpad:
		return "launchpad"
	case SystemCommandShowDesktop:
		return "show-desktop"
	case SystemCommandMissionControl:
		return "mission-control"
	case SystemCommandAppWindows:
		return "app-windows"
	default:
		return fmt.Sprintf("system-command(%#x)", uint32(s))
	}
}

const (
	eventGestureStart   = 1
	eventGestureEnd     = 2
	eventGestureMagnify = 3
	eventGestureRotate  = 5
	eventGestureScroll  = 8
	eventScrollWheel    = 11
	// screensharingd declares user activity for every V2 event from a controlling viewer
	// before dispatching its subtype and forwards subtype 10 to ScreensharingAgent, whose
	// gesture handler frees it without posting an event (screensharingd 756.36.5.2,
	// macOS 27.0.1 26A434, UUID 44908B06-F788-3822-ABC4-2285001F6A8D, sub_100036680
	// 0x10003c104..0x10003c174 and 0x10003fce0; ScreensharingAgent 756.36.5.2,
	// UUID DA1CD9BA-97A1-3758-B425-00FA34B428A3, agent_SSAgent_PostGestureEvent_rpc
	// 0x1000146ac..0x1000146b8, jump table 0x100015d40 entry 9 to 0x100015d0c).
	eventKeepAwake = 10
)

const keepAwakeInterval = 30 * time.Second

const maskMagnify uint64 = 4

const (
	gestureMagnify uint8 = 1 << iota
	gestureRotate
)

type gestureState struct {
	systemCommands   []SystemCommand
	active           uint8
	momentum         *scrollMomentum
	scrollRemainderX float64
	scrollRemainderY float64
}

type scrollMomentum struct {
	stop chan struct{}
	done chan struct{}
}

func eventMessage(version, subtype uint16, body []byte) []byte {
	message := []byte{0x17, 0}
	message = binary.BigEndian.AppendUint16(message, uint16(4+len(body)))
	message = binary.BigEndian.AppendUint16(message, version)
	message = binary.BigEndian.AppendUint16(message, subtype)
	return append(message, body...)
}

func appendPosition(body []byte, x, y int) []byte {
	body = binary.BigEndian.AppendUint16(body, clampUint16(x))
	return binary.BigEndian.AppendUint16(body, clampUint16(y))
}

func gestureBoundaryMessage(subtype uint16, seriesType uint32, x, y int) []byte {
	body := binary.LittleEndian.AppendUint32(nil, seriesType)
	return eventMessage(1, subtype, appendPosition(body, x, y))
}

func amountMessage(subtype uint16, amount float64, x, y int, phase GesturePhase, mask uint64) []byte {
	body := binary.BigEndian.AppendUint64(nil, math.Float64bits(amount))
	body = appendPosition(body, x, y)
	body = binary.BigEndian.AppendUint64(body, uint64(phase))
	body = binary.BigEndian.AppendUint64(body, mask)
	return eventMessage(2, subtype, body)
}

func gestureScrollMessage(deltaX, deltaY float64, phase GesturePhase, x, y int) []byte {
	body := binary.BigEndian.AppendUint32(nil, math.Float32bits(float32(deltaX)))
	body = binary.BigEndian.AppendUint32(body, math.Float32bits(float32(deltaY)))
	body = binary.BigEndian.AppendUint32(body, 0)
	body = binary.BigEndian.AppendUint32(body, 0)
	body = binary.BigEndian.AppendUint32(body, uint32(phase))
	body = binary.BigEndian.AppendUint32(body, 0)
	return eventMessage(1, eventGestureScroll, appendPosition(body, x, y))
}

func assistCursorMessage(visible bool, x, y int, flags uint32) []byte {
	message := []byte{0x16, 0}
	message = binary.BigEndian.AppendUint16(message, 12)
	message = binary.BigEndian.AppendUint16(message, 1)
	message = append(message, boolByte(visible), 0)
	message = appendPosition(message, x, y)
	return binary.BigEndian.AppendUint32(message, flags)
}

func (c *Client) gestureTargetLocked(x, y int) (int, int, error) {
	if c.mode == ModeObserve {
		return 0, 0, E.New("rfb: gestures are not sent while observing")
	}
	if !c.info.Accepts(0x17) {
		return 0, 0, E.New("rfb: the server does not take Apple's gesture messages")
	}
	wireX, wireY := c.wirePositionLocked(x, y)
	return wireX, wireY, nil
}

func (c *Client) sendSeriesGesture(kind uint8, subtype uint16, amount float64, x, y int, phase GesturePhase, mask uint64) error {
	c.access.Lock()
	wireX, wireY, err := c.gestureTargetLocked(x, y)
	if err != nil {
		c.access.Unlock()
		return err
	}
	var messages []byte
	if phase == PhaseBegan && c.gestures.active == 0 {
		messages = append(messages, gestureBoundaryMessage(eventGestureStart, 0, wireX, wireY)...)
	}
	if phase == PhaseBegan {
		c.gestures.active |= kind
	}
	messages = append(messages, amountMessage(subtype, amount, wireX, wireY, phase, mask)...)
	if phase.finished() && c.gestures.active&kind != 0 {
		c.gestures.active &^= kind
		if c.gestures.active == 0 {
			messages = append(messages, gestureBoundaryMessage(eventGestureEnd, 0, wireX, wireY)...)
		}
	}
	c.access.Unlock()
	return c.sendInput(messages, false)
}

func (c *Client) SetKeepsAwake(enabled bool) {
	c.access.Lock()
	c.keepsAwake = enabled
	c.access.Unlock()
}

func (c *Client) keepAwake() {
	c.access.Lock()
	send := c.keepsAwake && c.inputReady && c.mode != ModeObserve
	c.access.Unlock()
	if !send || !c.info.Accepts(0x17) || time.Since(time.Unix(0, c.lastInput.Load())) < keepAwakeInterval {
		return
	}
	_ = c.sendInput(eventMessage(1, eventKeepAwake, nil), false)
}

func (c *Client) SendMagnify(x, y int, magnification float64, phase GesturePhase) error {
	return c.sendSeriesGesture(gestureMagnify, eventGestureMagnify, magnification, x, y, phase, maskMagnify)
}

func (c *Client) SendRotate(x, y int, degrees float64, phase GesturePhase) error {
	return c.sendSeriesGesture(gestureRotate, eventGestureRotate, degrees, x, y, phase, 0)
}

func (c *Client) SendScrollPhase(x, y int, deltaX, deltaY float64, phase GesturePhase) error {
	if phase == PhaseMayBegin || phase == PhaseBegan {
		c.StopScrollMomentum()
	}
	c.access.Lock()
	wireX, wireY, err := c.gestureTargetLocked(x, y)
	if err != nil {
		c.access.Unlock()
		return err
	}
	if phase == PhaseMayBegin || phase == PhaseBegan {
		c.gestures.scrollRemainderX, c.gestures.scrollRemainderY = 0, 0
	}
	pointsX := math.Round(deltaX + c.gestures.scrollRemainderX)
	pointsY := math.Round(deltaY + c.gestures.scrollRemainderY)
	c.gestures.scrollRemainderX += deltaX - pointsX
	c.gestures.scrollRemainderY += deltaY - pointsY
	c.access.Unlock()
	message := scrollWheelMessage(wireX, wireY, pointsX, pointsY, scrollEvent{phase: phase, continuous: true})
	message = append(message, gestureScrollMessage(deltaX, deltaY, phase, wireX, wireY)...)
	return c.sendInput(message, false)
}

const (
	momentumFrame        = time.Second / 60
	momentumDecayPerMs   = 0.994
	momentumStopVelocity = 10
)

func (c *Client) StartScrollMomentum(x, y int, velocityX, velocityY float64) error {
	c.StopScrollMomentum()
	c.access.Lock()
	wireX, wireY, err := c.gestureTargetLocked(x, y)
	if err != nil || math.Hypot(velocityX, velocityY) < momentumStopVelocity {
		c.access.Unlock()
		return err
	}
	momentum := &scrollMomentum{stop: make(chan struct{}), done: make(chan struct{})}
	c.gestures.momentum = momentum
	c.access.Unlock()
	go c.runMomentum(momentum, wireX, wireY, velocityX, velocityY)
	return nil
}

func (c *Client) StopScrollMomentum() {
	c.access.Lock()
	momentum := c.gestures.momentum
	c.gestures.momentum = nil
	c.access.Unlock()
	if momentum != nil {
		close(momentum.stop)
		<-momentum.done
	}
}

func (c *Client) runMomentum(momentum *scrollMomentum, x, y int, velocityX, velocityY float64) {
	defer close(momentum.done)
	ticker := time.NewTicker(momentumFrame)
	defer ticker.Stop()
	decay := math.Pow(momentumDecayPerMs, float64(momentumFrame)/float64(time.Millisecond))
	seconds := momentumFrame.Seconds()
	momentumPhase := momentumBegin
	var remainderX, remainderY float64
	for {
		stopped := momentumPhase != momentumBegin && math.Hypot(velocityX, velocityY) < momentumStopVelocity
		if !stopped && momentumPhase != momentumBegin {
			select {
			case <-momentum.stop:
				stopped = true
			case <-ticker.C:
			}
		}
		if stopped {
			c.sendInput(scrollWheelMessage(x, y, 0, 0, scrollEvent{momentum: momentumEnd, continuous: true}), false)
			c.access.Lock()
			if c.gestures.momentum == momentum {
				c.gestures.momentum = nil
			}
			c.access.Unlock()
			return
		}
		deltaX := math.Round(velocityX*seconds + remainderX)
		deltaY := math.Round(velocityY*seconds + remainderY)
		remainderX += velocityX*seconds - deltaX
		remainderY += velocityY*seconds - deltaY
		message := scrollWheelMessage(x, y, deltaX, deltaY, scrollEvent{momentum: momentumPhase, continuous: true})
		err := c.sendInput(message, false)
		if err != nil {
			return
		}
		momentumPhase = momentumContinue
		velocityX *= decay
		velocityY *= decay
	}
}

func (c *Client) receiveVendorKeysyms() error {
	length, err := c.stream.u16()
	if err != nil {
		return err
	}
	body, err := c.stream.bytes(int(length))
	if err != nil {
		return err
	}
	var commands []SystemCommand
	if len(body) >= 4 {
		count := int(binary.BigEndian.Uint16(body[2:]))
		for i := 0; i < count && 4+i*4+4 <= len(body); i++ {
			keysym := SystemCommand(binary.BigEndian.Uint32(body[4+i*4:]))
			switch keysym {
			case SystemCommandLaunchpad, SystemCommandShowDesktop, SystemCommandMissionControl, SystemCommandAppWindows:
				commands = append(commands, keysym)
			}
		}
	}
	if !c.info.Accepts(0x10) {
		commands = nil
	}
	c.access.Lock()
	changed := !slices.Equal(commands, c.gestures.systemCommands)
	c.gestures.systemCommands = commands
	c.access.Unlock()
	if changed {
		c.handler.SystemCommandsChanged(slices.Clone(commands))
	}
	return nil
}

func (c *Client) SystemCommands() []SystemCommand {
	c.access.Lock()
	defer c.access.Unlock()
	return slices.Clone(c.gestures.systemCommands)
}

func (c *Client) SendSystemCommand(command SystemCommand) error {
	c.access.Lock()
	if c.mode == ModeObserve {
		c.access.Unlock()
		return E.New("rfb: system command ", command, " is not sent while observing")
	}
	supported := slices.Contains(c.gestures.systemCommands, command)
	c.access.Unlock()
	if !supported {
		return E.New("rfb: the server does not offer system command ", command)
	}
	message, err := encryptedKeyMessage(c.wrapKey, 0, uint32(command), true, 0, 0)
	if err != nil {
		return err
	}
	return c.sendInput(message, false)
}

const (
	AssistPointerHighlight uint32 = 1
	AssistPointerArrow     uint32 = 2
	assistPressed          uint32 = 1 << 31
)

func (c *Client) SetAssistCursor(x, y int, pointer uint32, pressed bool) error {
	flags := pointer & 7
	if pressed {
		flags |= assistPressed
	}
	c.access.Lock()
	if !c.info.Accepts(0x16) {
		c.access.Unlock()
		return E.New("rfb: the server does not take the assist cursor")
	}
	wireX, wireY := c.wirePositionLocked(x, y)
	c.access.Unlock()
	return c.sendInput(assistCursorMessage(true, wireX, wireY, flags), false)
}

func (c *Client) HideAssistCursor() error {
	if !c.info.Accepts(0x16) {
		return E.New("rfb: the server does not take the assist cursor")
	}
	return c.sendInput(assistCursorMessage(false, 0, 0, 0), false)
}
