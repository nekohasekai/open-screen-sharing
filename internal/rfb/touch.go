package rfb

import (
	"encoding/binary"
	"slices"

	E "github.com/sagernet/sing/common/exceptions"
)

type TouchPhase uint8

const (
	TouchBegan TouchPhase = iota + 1
	TouchMoved
	TouchEnded
	TouchCancelled
)

type Touch struct {
	ID    int64
	Phase TouchPhase
	X     int
	Y     int
}

const (
	messageHIDReport = 0x26

	digitizerReportID     = 9
	digitizerReportSize   = 58
	digitizerContactCount = 5
	digitizerContactSize  = 5
	digitizerCoordinate   = 65535

	digitizerContactsOffset  = 3
	digitizerIdentityOffset  = 40
	digitizerContactCancel   = 454
	digitizerContactInRange  = 0x80
	digitizerContactTouching = 0x40
)

type touchContact struct {
	id        int64
	identity  uint8
	x         uint16
	y         uint16
	cancelled bool
}

type touchState struct {
	contacts     [digitizerContactCount]*touchContact
	display      uint32
	area         Rectangle
	lastIdentity uint8
}

func (s *touchState) idle() bool {
	return !slices.ContainsFunc(s.contacts[:], func(contact *touchContact) bool { return contact != nil })
}

func touchCoordinate(position, size int) uint16 {
	if size <= 0 {
		return 0
	}
	return uint16(min(max(position, 0), size) * digitizerCoordinate / size)
}

// UniversalHID's digitizer report 9 has fixed offsets whatever the contact count. A contact record
// is its index in bits 0-4, resting in bit 5, touch in bit 6 and range in bit 7, then little-endian
// X and Y of 0 for the top left to 65535. The identity is indexed by contact index and the cancel
// flags by record position. A contact missing from the next report is released
// (UniversalHID 94.2.1, macOS 27.3 26C5035r, UUID FEA7258E-2578-3BE6-92DC-90573A479B21,
// DigitizerReport.init(_report:) 0x23717596c, contactRange(index) 0x237176160,
// setContactCancel(_:atIndex:) 0x237176db4, DigitizerGesture 0x23716b954 and 0x23716c0c0).
func digitizerReport(contacts []*touchContact) []byte {
	report := make([]byte, digitizerReportSize)
	report[0] = digitizerReportID
	report[2] = digitizerContactCount
	position := 0
	for index, contact := range contacts {
		if contact == nil {
			continue
		}
		record := report[digitizerContactsOffset+position*digitizerContactSize:]
		record[0] = byte(index)
		if contact.cancelled {
			bit := digitizerContactCancel + position
			report[bit/8] |= 1 << (bit % 8)
		} else {
			record[0] |= digitizerContactInRange | digitizerContactTouching
		}
		binary.LittleEndian.PutUint16(record[1:], contact.x)
		binary.LittleEndian.PutUint16(record[3:], contact.y)
		report[digitizerIdentityOffset+index] = contact.identity
		position++
	}
	report[1] = byte(position)
	return report
}

// Unlike the rest of the protocol, message 0x26 is little-endian. screensharingd hands the report
// unread to the digitizer that ScreensharingAgent keeps for the display ID
// (ScreenSharing 786.5, macOS 27.3 26C5035r, UUID 66328DDB-F81D-37BC-8D31-6BF1A28A3E87,
// _RFBSendHIDReport 0x22f3107c8..0x22f310814; screensharingd 768.2.15.1,
// UUID C204A2B3-CA54-3094-BF76-DD1A007EDF34, HandleHIDReport 0x100037434..0x100037524,
// 0x10003ab34..0x10003ab5c).
func hidReportMessage(display uint32, report []byte) []byte {
	message := make([]byte, 20, 20+len(report))
	message[0] = messageHIDReport
	binary.LittleEndian.PutUint16(message[2:], uint16(16+len(report)))
	binary.LittleEndian.PutUint16(message[4:], 1)
	binary.LittleEndian.PutUint32(message[14:], display)
	binary.LittleEndian.PutUint16(message[18:], uint16(len(report)))
	return append(message, report...)
}

// ScreensharingAgent keeps one digitizer for each display, so all contacts of a report belong to
// one display even when the session shows all displays in one framebuffer
// (ScreensharingAgent 768.2.15.1, macOS 27.3 26C5035r, UUID 868B12DB-A6D8-3667-80D7-45F925D58C5E,
// SSVirtualDigitizerManager 0x100048b58..0x100048b64, 0x1000496a0..0x1000496e4).
func (c *Client) touchTargetLocked(x, y int) (uint32, Rectangle, bool) {
	layout := c.layout
	if layout == nil || len(layout.Displays) == 0 {
		return 0, Rectangle{}, false
	}
	width, height := c.FramebufferSize()
	framebuffer := Rectangle{Width: width, Height: height}
	if layout.Current != AllDisplays {
		return layout.Current, framebuffer, true
	}
	if len(layout.Displays) == 1 {
		return layout.Displays[0].ID, framebuffer, true
	}
	index := slices.IndexFunc(layout.Displays, func(display Display) bool {
		return display.Backing.contains(x, y)
	})
	if index < 0 {
		return 0, Rectangle{}, false
	}
	return layout.Displays[index].ID, layout.Displays[index].Backing, true
}

// screensharingd drops the reports of a viewer whose agent is not the one on the console
// (screensharingd 768.2.15.1, macOS 27.3 26C5035r, UUID C204A2B3-CA54-3094-BF76-DD1A007EDF34,
// HandleHIDReport 0x10003aa8c..0x10003aa94).
func (c *Client) TouchAvailable() bool {
	if !c.info.Accepts(messageHIDReport) || c.info.Flags&ServerFlagObserveOnly != 0 {
		return false
	}
	c.access.Lock()
	defer c.access.Unlock()
	return c.layout != nil && c.layout.onConsole()
}

func (c *Client) SendTouches(touches []Touch) error {
	if !c.info.Accepts(messageHIDReport) {
		return E.New("rfb: the server does not take Apple's HID reports")
	}
	c.access.Lock()
	if c.mode == ModeObserve {
		c.access.Unlock()
		return E.New("rfb: touches are not sent while observing")
	}
	state := &c.touch
	var changed bool
	motion := true
	var leaving []int
	for _, touch := range touches {
		var index int
		if touch.Phase == TouchBegan {
			index = slices.Index(state.contacts[:], nil)
			if index < 0 {
				c.logger.Debug("ignore a touch beyond the contact limit", "limit", digitizerContactCount)
				continue
			}
			if state.idle() {
				display, area, found := c.touchTargetLocked(touch.X, touch.Y)
				if !found {
					continue
				}
				state.display, state.area = display, area
			}
			state.lastIdentity = state.lastIdentity%255 + 1
			state.contacts[index] = &touchContact{id: touch.ID, identity: state.lastIdentity}
		} else {
			index = slices.IndexFunc(state.contacts[:], func(contact *touchContact) bool {
				return contact != nil && contact.id == touch.ID
			})
			if index < 0 {
				continue
			}
		}
		contact := state.contacts[index]
		x := touchCoordinate(touch.X-state.area.X, state.area.Width)
		y := touchCoordinate(touch.Y-state.area.Y, state.area.Height)
		changed = changed || x != contact.x || y != contact.y
		contact.x, contact.y = x, y
		switch touch.Phase {
		case TouchBegan:
			changed, motion = true, false
		case TouchMoved:
		case TouchEnded:
			leaving = append(leaving, index)
		case TouchCancelled:
			contact.cancelled = true
			changed, motion = true, false
			leaving = append(leaving, index)
		default:
			c.access.Unlock()
			return E.New("rfb: unknown touch phase ", int(touch.Phase))
		}
	}
	display := state.display
	var update, release []byte
	if changed {
		update = hidReportMessage(display, digitizerReport(state.contacts[:]))
	}
	if len(leaving) > 0 {
		for _, index := range leaving {
			state.contacts[index] = nil
		}
		release = hidReportMessage(display, digitizerReport(state.contacts[:]))
	}
	c.access.Unlock()
	if update != nil {
		err := c.sendInput(update, motion)
		if err != nil {
			return err
		}
	}
	if release != nil {
		return c.sendInput(release, false)
	}
	return nil
}
