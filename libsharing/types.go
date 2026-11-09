package libsharing

import (
	"bytes"

	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
	"github.com/sagernet/sing/common"
)

const (
	ProtocolRFB int32 = 0
	ProtocolRDP int32 = 1
)

const (
	PlatformUnknown int32 = 0
	PlatformMac     int32 = 1
	PlatformPC      int32 = 2
)

const (
	LoginAccount int32 = 0

	LoginRequestControl int32 = 1

	LoginRequestObserve int32 = 2

	LoginVNCPassword int32 = 3

	LoginNone int32 = 4
)

const (
	QualityAdaptive int32 = 0
	QualityFull     int32 = 1
	QualityHigh     int32 = 2
	QualityMedium   int32 = 3
	QualityLow      int32 = 4
)

const (
	ModeObserve          int32 = 0
	ModeControl          int32 = 1
	ModeExclusiveControl int32 = 2
)

const (
	DialectApple    int32 = 1
	DialectStandard int32 = 2
	DialectRDP      int32 = 3
)

const (
	KeyboardANSI = int32(rfb.KeyboardANSI)
	KeyboardISO  = int32(rfb.KeyboardISO)
	KeyboardJIS  = int32(rfb.KeyboardJIS)
)

const (
	StateConnecting         int32 = 1
	StateAuthenticating     int32 = 2
	StateWaitingForApproval int32 = 3
	StateChoosingSession    int32 = 4
	StateConnected          int32 = 5
	StateDisconnected       int32 = 6
	StateAwaitingDisplay    int32 = 7
)

const (
	ErrorNone              int32 = 0
	ErrorAuthentication    int32 = 1
	ErrorPermissionDenied  int32 = 2
	ErrorNetwork           int32 = 3
	ErrorProtocol          int32 = 4
	ErrorCancelled         int32 = 5
	ErrorUnsupportedServer int32 = 6

	ErrorUnverifiedServer int32 = 7

	ErrorSessionEnded int32 = 8

	ErrorHostNotFound int32 = 9

	ErrorOwnScreen int32 = 10

	ErrorCertificateChanged int32 = 11
)

const (
	ButtonLeft   int32 = 1
	ButtonMiddle int32 = 2
	ButtonRight  int32 = 4
)

const ClipboardProblemNotRestored = int32(rfb.ClipboardNotRestored)

const (
	SessionChoiceCancel       int32 = -1
	SessionChoiceShareConsole int32 = 0
	SessionChoiceOwnSession   int32 = 2
)

const (
	StatusControlAllowed int32 = 9
	StatusObserveOnly    int32 = 10
	StatusPointerHidden  int32 = 11
	StatusPointerShown   int32 = 12
)

const AllDisplays int64 = 0

type SessionOptions struct {
	Protocol int32

	Host     string
	Port     int32
	Username string
	Password string

	TrustedFingerprint string

	AllowUnverifiedLogin bool

	KeyboardLayout int32

	Audio bool

	Microphone bool

	LoginType int32

	Quality int32

	Mode int32

	SharedClipboard bool

	ClientName string

	Platform int32

	DecoderMaxPixelRate int64

	MaximumFrameRate int32

	UpdateRate int32

	AwaitVirtualDisplay bool

	KeepAlive bool

	DisableUDP bool

	CacheDirectory string
}

func NewSessionOptions() *SessionOptions {
	return &SessionOptions{
		Port:            5900,
		LoginType:       LoginAccount,
		Quality:         QualityAdaptive,
		Mode:            ModeControl,
		SharedClipboard: true,
	}
}

type ServerInfo struct {
	Width  int32
	Height int32

	Dialect  int32
	Platform int32

	CursorPixelsPerPoint float64

	ObserveOnly bool

	SupportsDisplaySelection bool
	SupportsVirtualDisplay   bool
	SupportsHighPerformance  bool
	SupportsClipboard        bool
	SupportsClipboardRequest bool
	SupportsKeyboardSource   bool
	SupportsQuality          bool
	SupportsObserve          bool
	SupportsAudio            bool
	SupportsUnicodeText      bool

	SupportsKeyCodes bool

	SupportsFileTransfer bool

	SupportsDragToRemote   bool
	SupportsDragFromRemote bool

	SupportsGestures bool
	SupportsTouch    bool

	SupportsAssistCursor bool

	SupportsMissionControl bool
	SupportsAppWindows     bool
	SupportsShowDesktop    bool
	SupportsLaunchpad      bool
}

func newServerInfo(info rfb.ServerInfo, platform int32) *ServerInfo {
	apple := info.Dialect == rfb.DialectApple
	dialect := DialectStandard
	if apple {
		dialect, platform = DialectApple, PlatformMac
	} else if platform == PlatformUnknown {
		platform = PlatformPC
	}
	return &ServerInfo{
		Width:                    int32(info.Width),
		Height:                   int32(info.Height),
		Dialect:                  dialect,
		Platform:                 platform,
		CursorPixelsPerPoint:     1,
		ObserveOnly:              info.Flags&rfb.ServerFlagObserveOnly != 0,
		SupportsDisplaySelection: info.Accepts(0x0d),
		SupportsVirtualDisplay:   info.Accepts(0x1d),
		SupportsHighPerformance:  info.Accepts(0x1c) && info.Accepts(0x1d),
		SupportsClipboard:        !apple || info.Accepts(0x15),
		SupportsClipboardRequest: apple,
		SupportsQuality:          true,
		SupportsObserve:          true,
		SupportsKeyboardSource:   info.Accepts(0x1a),
		SupportsKeyCodes:         info.Accepts(0x10),
		SupportsFileTransfer:     info.Accepts(0x22) && info.Flags&rfb.ServerFlagObserveOnly == 0,
		SupportsDragToRemote:     info.Accepts(0x20) && info.Accepts(0x22) && info.Flags&rfb.ServerFlagObserveOnly == 0,
		SupportsDragFromRemote:   info.Accepts(0x0e) && info.Accepts(0x20) && info.Accepts(0x22) && info.Flags&rfb.ServerFlagObserveOnly == 0,
		SupportsGestures:         info.Accepts(0x17),
		SupportsAssistCursor:     info.Accepts(0x16),
	}
}

type Display struct {
	DisplayID int64

	LogicalWidth  int32
	LogicalHeight int32

	IsMain    bool
	IsVirtual bool
}

type DisplayList struct {
	Current          int64
	FramebufferWidth int32
	DesktopWidth     int32

	displays []*Display
}

func (d *DisplayList) Len() int32 {
	return int32(len(d.displays))
}

func (d *DisplayList) Get(index int32) *Display {
	return d.displays[index]
}

func newDisplayList(layout *rfb.DisplayLayout) *DisplayList {
	list := &DisplayList{
		Current:          int64(layout.Current),
		FramebufferWidth: int32(layout.FramebufferWidth),
		DesktopWidth:     int32(layout.DesktopWidth),
		displays: common.Map(layout.Displays, func(display rfb.Display) *Display {
			return &Display{
				DisplayID:     int64(display.ID),
				LogicalWidth:  int32(display.Logical.Width),
				LogicalHeight: int32(display.Logical.Height),
				IsMain:        display.Flags&rfb.DisplayFlagMain != 0,
				IsVirtual:     display.Flags&rfb.DisplayFlagVirtual != 0,
			}
		}),
	}
	if layout.Current == rfb.AllDisplays {
		list.Current = AllDisplays
	}
	return list
}

type FramebufferUpdate struct {
	X               int32
	Y               int32
	Width           int32
	Height          int32
	HoldNanoseconds int64
}

type CursorImage struct {
	Width    int32
	Height   int32
	HotspotX int32
	HotspotY int32
	Pixels   []byte
	Invert   []byte
}

type Clipboard struct {
	items []*ClipboardItem
}

func NewClipboard() *Clipboard {
	return &Clipboard{}
}

func (c *Clipboard) Len() int32 {
	return int32(len(c.items))
}

func (c *Clipboard) Get(index int32) *ClipboardItem {
	return c.items[index]
}

func (c *Clipboard) AddItem(item *ClipboardItem) {
	c.items = append(c.items, item)
}

func (c *Clipboard) text() (string, bool) {
	for _, item := range c.items {
		for _, flavor := range item.flavors {
			if flavor.Type == rfb.TypeUTF8PlainText && !flavor.Promise {
				return string(flavor.Data), true
			}
		}
	}
	return "", false
}

type ClipboardItem struct {
	flavors []*ClipboardFlavor
}

func NewClipboardItem() *ClipboardItem {
	return &ClipboardItem{}
}

func (i *ClipboardItem) Len() int32 {
	return int32(len(i.flavors))
}

func (i *ClipboardItem) Get(index int32) *ClipboardFlavor {
	return i.flavors[index]
}

// gomobile borrows NSMutableData bytes for a method argument; for
// immutable NSData it allocates a copy and frees it after the call
// (sagernet/gomobile v0.1.13, bind/genobjc.go:695, bind/genobjc.go:798-824,
// bind/genobjc.go:1042-1048, bind/objc/seq_darwin.m.support:184-207).
func (i *ClipboardItem) AddFlavor(typeIdentifier string, data []byte) {
	i.flavors = append(i.flavors, &ClipboardFlavor{Type: typeIdentifier, Data: bytes.Clone(data)})
}

type ClipboardFlavor struct {
	Type string
	Data []byte

	Promise bool
}

func newClipboard(clipboard *rfb.Clipboard) *Clipboard {
	return &Clipboard{items: common.Map(clipboard.Items, func(item rfb.ClipboardItem) *ClipboardItem {
		return &ClipboardItem{flavors: common.Map(item.Flavors, func(flavor rfb.ClipboardFlavor) *ClipboardFlavor {
			return &ClipboardFlavor{Type: flavor.Type, Data: flavor.Data, Promise: flavor.Promise}
		})}
	})}
}

func (c *Clipboard) toRFB() *rfb.Clipboard {
	return &rfb.Clipboard{Items: common.Map(c.items, func(item *ClipboardItem) rfb.ClipboardItem {
		return rfb.ClipboardItem{Flavors: common.Map(item.flavors, func(flavor *ClipboardFlavor) rfb.ClipboardFlavor {
			return rfb.ClipboardFlavor{Type: flavor.Type, Data: flavor.Data, Promise: flavor.Promise}
		})}
	})}
}

type Prompt struct {
	PromptID int64

	ConsoleUser        string
	AllowsShareConsole bool
	AllowsOwnSession   bool
}
