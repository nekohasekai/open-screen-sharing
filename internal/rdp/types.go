package rdp

import (
	"context"
	"image"
	"log/slog"
	"time"

	"github.com/nekohasekai/open-screen-sharing/internal/bgra"
)

type State int

const (
	StateConnecting State = iota
	StateAuthenticating
)

type Options struct {
	Address           string
	Username          string
	Password          string
	Domain            string
	ClientName        string
	Display           func(ctx context.Context) (width, height, scale int, err error)
	KeyboardLayout    uint32
	Clipboard         bool
	Audio             bool
	Microphone        bool
	VerifyCertificate func(fingerprint string, authenticated bool) error
	KeepAlive         bool
	DisableUDP        bool
	GraphicsCache     string
	Logger            *slog.Logger
}

type AudioFormat struct {
	Channels      int
	SampleRate    int
	BitsPerSample int
}

type Update struct {
	Rectangle image.Rectangle
	Hold      time.Duration
}

type Events interface {
	StateChanged(State)
	InfoChanged(Info)
	ResizeFramebuffer(width, height int) (*bgra.Image, error)
	FramebufferUpdated(Update)
	CursorChanged(*bgra.Cursor)
	CursorMoved(x, y int)
	ClipboardReceived(string)
	Bell()
	AudioFormatChanged(AudioFormat)
	AudioPacketReceived([]byte, uint64)
	MicrophoneOpened(AudioFormat)
	MicrophoneClosed()
}

type Info struct {
	Width     int
	Height    int
	Scale     int
	LoggedOn  bool
	Clipboard bool
	Resize    bool
	Audio     bool
	Unicode   bool
	Touch     bool
}

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

type Statistics struct {
	BytesRead       uint64
	BytesWritten    uint64
	Frames          uint64
	Encoding        string
	RoundTrip       time.Duration
	RoundTripKnown  bool
	UDP             bool
	PacketsExpected uint64
	PacketsMissed   uint64
}

const (
	ButtonLeft   uint8 = 1
	ButtonMiddle uint8 = 2
	ButtonRight  uint8 = 4
)
