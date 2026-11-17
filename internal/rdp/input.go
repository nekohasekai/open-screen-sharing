package rdp

import (
	"encoding/binary"
	"log/slog"
	"maps"
	"math"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode/utf16"

	E "github.com/sagernet/sing/common/exceptions"
)

const (
	inputSynchronize uint16 = 0
	inputScancode    uint16 = 4
	inputUnicode     uint16 = 5
	inputMouse       uint16 = 0x8001

	keyExtended  uint16 = 0x0100
	keyExtended1 uint16 = 0x0200
	keyRelease   uint16 = 0x8000

	pointerWheel        uint16 = 0x0200
	pointerHWheel       uint16 = 0x0400
	pointerMove         uint16 = 0x0800
	pointerLeft         uint16 = 0x1000
	pointerRight        uint16 = 0x2000
	pointerMiddle       uint16 = 0x4000
	pointerDown         uint16 = 0x8000
	pointerRotationMask uint16 = 0x01ff
	wheelStepMaximum           = 255
	wheelStepMinimum           = -256
	maximumWheelUnits          = 120 * 64
	wheelUnitsPerPixel         = 6

	inputFlagUnicode     = 0x0010
	inputFlagFastPath2   = 0x0020
	inputFlagMouseHWheel = 0x0100

	inputEventLength    = 12
	inputHeaderLength   = 4
	maximumInputEvents  = 256
	maximumPendingInput = 16384

	fastPathInputScancode   = 0
	fastPathInputMouse      = 1
	fastPathInputSync       = 3
	fastPathInputUnicode    = 4
	fastPathInputCodeShift  = 5
	fastPathInputFlagsMask  = 0x1f
	fastPathInputRelease    = 0x01
	fastPathInputExtended   = 0x02
	fastPathInputExtended1  = 0x04
	fastPathInputCountShift = 2
	fastPathInputCountLimit = 15
	maximumFastPathEvents   = 255

	// Windows 11 26100 replaces Unicode input events that reach an application before it has read the
	// previous one with the newest character; Notepad still lost characters at 10 ms and none at 15 ms.
	typingInterval = 20 * time.Millisecond
	// Windows 11 26100 handed a Unicode key sent right after a mouse button to the application before
	// the button (12 ms ahead in a WinForms test); a 20 ms gap between pointer and keyboard keeps the order.
	switchInterval = 20 * time.Millisecond

	keepAliveInterval = 30 * time.Second

	scancodeEnter        = 0x1c
	scancodeTab          = 0x0f
	scancodeBackspace    = 0x0e
	scancodeControl      = 0x1d
	scancodeLeftShift    = 0x2a
	scancodeRightShift   = 0x36
	scancodeAlt          = 0x38
	scancodeLeftWindows  = 0x5b
	scancodeRightWindows = 0x5c
	scancodeV            = 0x2f
	scancodeNumLock      = 0x45
	scancodeScroll       = 0x46
	scancodeCapsLock     = 0x3a
)

type lockKeys uint16

const (
	scrollLock lockKeys = 1
	numLock    lockKeys = 2
	capsLock   lockKeys = 4
	kanaLock   lockKeys = 8
)

var pointerButtons = [...]struct {
	mask uint8
	flag uint16
}{
	{ButtonLeft, pointerLeft},
	{ButtonMiddle, pointerMiddle},
	{ButtonRight, pointerRight},
}

type inputEvent struct {
	kind  uint16
	flags uint16
	a     uint16
	b     uint16
}

type inputGroup struct {
	events              []inputEvent
	paced               bool
	motion              bool
	gate                <-chan error
	clipboard           *clipboardChannel
	clipboardGeneration uint64
}

type inputQueue struct {
	send      func(events []inputEvent, motion bool) error
	done      <-chan struct{}
	wake      chan struct{}
	logger    *slog.Logger
	keepAlive bool

	access    sync.Mutex
	pending   []inputGroup
	next      time.Time
	last      time.Time
	pointer   bool
	unicode   bool
	locks     lockKeys
	locksDown lockKeys
	pressed   map[uint16]bool
	typed     map[uint32]bool
	buttons   uint8
	scrollX   float64
	scrollY   float64
}

func newInputQueue(send func([]inputEvent, bool) error, done <-chan struct{}, logger *slog.Logger, keepAlive bool) *inputQueue {
	return &inputQueue{
		send:      send,
		done:      done,
		wake:      make(chan struct{}, 1),
		logger:    logger,
		keepAlive: keepAlive,
		locks:     numLock,
		pressed:   make(map[uint16]bool),
		typed:     make(map[uint32]bool),
	}
}

func encodeInputEvents(events []inputEvent) []byte {
	payload := make([]byte, inputHeaderLength+inputEventLength*len(events))
	binary.LittleEndian.PutUint16(payload, uint16(len(events)))
	for i, event := range events {
		wire := payload[inputHeaderLength+inputEventLength*i:]
		binary.LittleEndian.PutUint16(wire[4:], event.kind)
		binary.LittleEndian.PutUint16(wire[6:], event.flags)
		binary.LittleEndian.PutUint16(wire[8:], event.a)
		binary.LittleEndian.PutUint16(wire[10:], event.b)
	}
	return payload
}

func encodeFastPathInput(events []inputEvent) []byte {
	var body []byte
	if len(events) > fastPathInputCountLimit {
		body = append(body, byte(len(events)))
	}
	for _, event := range events {
		var flags byte
		if event.flags&keyRelease != 0 {
			flags |= fastPathInputRelease
		}
		switch event.kind {
		case inputSynchronize:
			body = append(body, fastPathInputSync<<fastPathInputCodeShift|byte(event.a)&fastPathInputFlagsMask)
		case inputScancode:
			if event.flags&keyExtended != 0 {
				flags |= fastPathInputExtended
			}
			if event.flags&keyExtended1 != 0 {
				flags |= fastPathInputExtended1
			}
			body = append(body, fastPathInputScancode<<fastPathInputCodeShift|flags, byte(event.a))
		case inputUnicode:
			body = binary.LittleEndian.AppendUint16(append(body, fastPathInputUnicode<<fastPathInputCodeShift|flags), event.a)
		case inputMouse:
			body = append(body, fastPathInputMouse<<fastPathInputCodeShift)
			body = binary.LittleEndian.AppendUint16(body, event.flags)
			body = binary.LittleEndian.AppendUint16(body, event.a)
			body = binary.LittleEndian.AppendUint16(body, event.b)
		}
	}
	var header byte
	if len(events) <= fastPathInputCountLimit {
		header = byte(len(events)) << fastPathInputCountShift
	}
	if length := len(body) + 2; length < fastPathLengthLong {
		return append([]byte{header, byte(length)}, body...)
	}
	length := len(body) + 3
	return append([]byte{header, fastPathLengthLong | byte(length>>8), byte(length)}, body...)
}

func scancodeEvent(code uint16, down bool) inputEvent {
	flags := code & (keyExtended | keyExtended1)
	if !down {
		flags |= keyRelease
	}
	return inputEvent{kind: inputScancode, flags: flags, a: code & 0xff}
}

func appendUnicodeEvents(events []inputEvent, character rune, down bool) []inputEvent {
	flags := uint16(0)
	if !down {
		flags = keyRelease
	}
	for _, unit := range utf16.AppendRune(nil, character) {
		events = append(events, inputEvent{kind: inputUnicode, flags: flags, a: unit})
	}
	return events
}

func appendWheelEvents(events []inputEvent, flag uint16, units int, x, y uint16) []inputEvent {
	for units = max(-maximumWheelUnits, min(units, maximumWheelUnits)); units != 0; {
		step := max(wheelStepMinimum, min(units, wheelStepMaximum))
		events = append(events, inputEvent{kind: inputMouse, flags: flag | uint16(step)&pointerRotationMask, a: x, b: y})
		units -= step
	}
	return events
}

func (q *inputQueue) delay(group inputGroup) time.Duration {
	ready := q.next
	if pointer := group.events[0].kind == inputMouse; pointer != q.pointer {
		if switched := q.last.Add(switchInterval); switched.After(ready) {
			ready = switched
		}
	}
	return time.Until(ready)
}

func (q *inputQueue) deliver(group inputGroup) error {
	if clipboard := group.clipboard; clipboard != nil {
		clipboard.access.Lock()
		defer clipboard.access.Unlock()
		if group.clipboardGeneration != clipboard.generation {
			q.logger.Warn("drop paste after the clipboard changed")
			return nil
		}
	}
	err := q.send(group.events, group.motion)
	if err != nil {
		return err
	}
	q.last, q.pointer = time.Now(), group.events[0].kind == inputMouse
	if group.paced {
		q.next = q.last.Add(typingInterval)
	}
	return nil
}

func (q *inputQueue) enqueue(groups ...inputGroup) error {
	if len(q.pending)+len(groups) > maximumPendingInput {
		return E.New("rdp: ", len(groups), " input groups do not fit behind ", len(q.pending), " waiting ones, limit ", maximumPendingInput)
	}
	for len(q.pending) == 0 && len(groups) > 0 && groups[0].gate == nil && q.delay(groups[0]) <= 0 {
		err := q.deliver(groups[0])
		if err != nil {
			return err
		}
		groups = groups[1:]
	}
	if len(groups) == 0 {
		return nil
	}
	for _, group := range groups {
		last := len(q.pending) - 1
		if group.motion && last >= 0 && q.pending[last].motion {
			q.pending[last] = group
		} else {
			q.pending = append(q.pending, group)
		}
	}
	select {
	case q.wake <- struct{}{}:
	default:
	}
	return nil
}

func (q *inputQueue) run() {
	for {
		q.access.Lock()
		var timer <-chan time.Time
		var gate <-chan error
		if len(q.pending) == 0 {
			q.pending = nil
			if q.keepAlive {
				idle := time.Since(q.last)
				if idle >= keepAliveInterval {
					// Windows 11 26200 resets both the idle session limit and GetLastInputInfo on a synchronize
					// event that leaves the lock keys unchanged.
					err := q.deliver(inputGroup{events: []inputEvent{{kind: inputSynchronize, a: uint16(q.locks)}}})
					q.access.Unlock()
					if err != nil {
						return
					}
					continue
				}
				timer = time.After(keepAliveInterval - idle)
			}
		} else if head := q.pending[0]; head.gate != nil {
			gate = head.gate
		} else if wait := q.delay(head); wait > 0 {
			timer = time.After(wait)
		} else {
			q.pending = q.pending[1:]
			err := q.deliver(head)
			q.access.Unlock()
			if err != nil {
				return
			}
			continue
		}
		q.access.Unlock()
		select {
		case <-q.wake:
		case <-timer:
		case err := <-gate:
			q.open(gate, err)
		case <-q.done:
			return
		}
	}
}

func (q *inputQueue) open(gate <-chan error, err error) {
	q.access.Lock()
	defer q.access.Unlock()
	if len(q.pending) == 0 || q.pending[0].gate != gate {
		return
	}
	if err != nil {
		q.pending = q.pending[1:]
		q.logger.Warn("drop input that waited for the server", "error", err)
		return
	}
	q.pending[0].gate = nil
}

func (q *inputQueue) reactivate(unicode bool) []inputEvent {
	q.access.Lock()
	defer q.access.Unlock()
	if len(q.pending) > 0 {
		q.logger.Warn("drop input queued before reactivation", "groups", len(q.pending))
	}
	q.pending = nil
	q.locksDown = 0
	q.unicode = unicode
	return q.synchronize(q.locks)
}

func (q *inputQueue) synchronize(locks lockKeys) []inputEvent {
	maps.DeleteFunc(q.pressed, func(code uint16, _ bool) bool { return !slices.Contains(modifierScancodes, code) })
	events := []inputEvent{{kind: inputSynchronize, a: uint16(locks)}}
	for _, code := range modifierScancodes {
		if q.pressed[code] {
			events = append(events, scancodeEvent(code, true))
		}
	}
	return events
}

func (q *inputQueue) setLocks(locks lockKeys) {
	q.access.Lock()
	q.locks = locks
	q.access.Unlock()
}

func (q *inputQueue) setLock(lock lockKeys, on bool, active bool) error {
	locks := q.locks &^ lock
	if on {
		locks |= lock
	}
	if active {
		err := q.enqueue(inputGroup{events: q.synchronize(locks)})
		if err != nil {
			return err
		}
	}
	q.locks = locks
	return nil
}

func (q *inputQueue) scancode(code uint16, down bool) error {
	err := q.enqueue(inputGroup{events: []inputEvent{scancodeEvent(code, down)}})
	if err != nil {
		return err
	}
	var lock lockKeys
	switch code {
	case scancodeScroll:
		lock = scrollLock
	case scancodeNumLock:
		lock = numLock
	case scancodeCapsLock:
		lock = capsLock
	}
	if down {
		if q.locksDown&lock == 0 {
			q.locks ^= lock
		}
		q.locksDown |= lock
		q.pressed[code] = true
	} else {
		q.locksDown &^= lock
		delete(q.pressed, code)
	}
	return nil
}

func (q *inputQueue) movePointer(x, y uint16, buttons uint8) error {
	changed := q.buttons ^ buttons
	if changed == 0 {
		return q.enqueue(inputGroup{events: []inputEvent{{kind: inputMouse, flags: pointerMove, a: x, b: y}}, motion: true})
	}
	var events []inputEvent
	for _, button := range pointerButtons {
		if changed&button.mask == 0 {
			continue
		}
		flags := button.flag
		if buttons&button.mask != 0 {
			flags |= pointerDown
		}
		events = append(events, inputEvent{kind: inputMouse, flags: flags, a: x, b: y})
	}
	err := q.enqueue(inputGroup{events: events})
	if err != nil {
		return err
	}
	q.buttons = buttons
	return nil
}

func (q *inputQueue) scroll(x, y uint16, unitsX, unitsY float64, horizontal bool) error {
	q.scrollX += unitsX
	q.scrollY += unitsY
	wholeX, wholeY := int(q.scrollX), int(q.scrollY)
	q.scrollX -= float64(wholeX)
	q.scrollY -= float64(wholeY)
	if !horizontal {
		wholeX = 0
	}
	if wholeX == 0 && wholeY == 0 {
		return nil
	}
	events := []inputEvent{{kind: inputMouse, flags: pointerMove, a: x, b: y}}
	events = appendWheelEvents(events, pointerWheel, wholeY, x, y)
	events = appendWheelEvents(events, pointerHWheel, wholeX, x, y)
	return q.enqueue(inputGroup{events: events})
}

func (c *Client) position(x, y int) (uint16, uint16) {
	width, height := int(c.graphics.width.Load()), int(c.graphics.height.Load())
	return uint16(max(0, min(x, width-1))), uint16(max(0, min(y, height-1)))
}

func (c *Client) SendPointer(x, y int, buttons uint8) error {
	if !c.session.active.Load() {
		return nil
	}
	wireX, wireY := c.position(x, y)
	c.input.access.Lock()
	defer c.input.access.Unlock()
	return c.input.movePointer(wireX, wireY, buttons&(ButtonLeft|ButtonMiddle|ButtonRight))
}

func (c *Client) SendScroll(x, y int, deltaX, deltaY float64) error {
	if math.IsNaN(deltaX) || math.IsNaN(deltaY) || math.IsInf(deltaX, 0) || math.IsInf(deltaY, 0) {
		return E.New("rdp: scroll delta (", deltaX, ", ", deltaY, ") is not finite")
	}
	if !c.session.active.Load() {
		return nil
	}
	wireX, wireY := c.position(x, y)
	unitsPerPixel := wheelUnitsPerPixel * 100 / float64(c.display.layoutScale())
	horizontal := c.session.inputFlags.Load()&inputFlagMouseHWheel != 0
	c.input.access.Lock()
	defer c.input.access.Unlock()
	return c.input.scroll(wireX, wireY, -deltaX*unitsPerPixel, deltaY*unitsPerPixel, horizontal)
}

func (c *Client) SendText(text string) error {
	if !c.session.active.Load() {
		return nil
	}
	c.input.access.Lock()
	defer c.input.access.Unlock()
	var groups []inputGroup
	var skipped int
	for _, character := range strings.ReplaceAll(text, "\r\n", "\n") {
		var code uint16
		switch character {
		case '\n', '\r':
			code = scancodeEnter
		case '\t':
			code = scancodeTab
		case '\b':
			code = scancodeBackspace
		}
		if code != 0 {
			groups = append(groups, inputGroup{events: []inputEvent{scancodeEvent(code, true), scancodeEvent(code, false)}, paced: true})
			continue
		}
		if c.input.unicode {
			events := appendUnicodeEvents(nil, character, true)
			groups = append(groups, inputGroup{events: appendUnicodeEvents(events, character, false), paced: true})
			continue
		}
		events := c.input.keystroke(character)
		if events == nil {
			skipped++
			continue
		}
		groups = append(groups, inputGroup{events: events, paced: true})
	}
	err := c.input.enqueue(groups...)
	if err != nil {
		return err
	}
	if skipped > 0 {
		return E.New("rdp: the server does not accept Unicode input, and ", skipped, " characters have no key")
	}
	return nil
}

func (c *Client) Paste(text string) error {
	if !c.session.active.Load() {
		return nil
	}
	accepted := make(chan error, 1)
	generation, err := c.clipboard.setText(text, accepted)
	if err != nil {
		return err
	}
	c.input.access.Lock()
	defer c.input.access.Unlock()
	events := []inputEvent{scancodeEvent(scancodeV, true), scancodeEvent(scancodeV, false)}
	if !c.input.pressed[scancodeControl] && !c.input.pressed[keyExtended|scancodeControl] {
		events = []inputEvent{scancodeEvent(scancodeControl, true), events[0], events[1], scancodeEvent(scancodeControl, false)}
	}
	return c.input.enqueue(inputGroup{events: events, gate: accepted, clipboard: c.clipboard, clipboardGeneration: generation})
}
