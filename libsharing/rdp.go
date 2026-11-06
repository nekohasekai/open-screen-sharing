package libsharing

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"log/slog"
	"math"
	"net"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/nekohasekai/open-screen-sharing/internal/bgra"
	"github.com/nekohasekai/open-screen-sharing/internal/rdp"
	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
	E "github.com/sagernet/sing/common/exceptions"
)

func rdpOptions(o *SessionOptions, seen func(fingerprint string)) (rdp.Options, error) {
	if o.LoginType != LoginAccount {
		return rdp.Options{}, E.New("RDP signs in only with an account, not login type ", o.LoginType)
	}
	trusted := o.TrustedFingerprint
	address := net.JoinHostPort(o.Host, strconv.Itoa(int(o.Port)))
	var graphicsCache string
	if o.CacheDirectory != "" {
		name := sha256.Sum256([]byte(address))
		graphicsCache = filepath.Join(o.CacheDirectory, "RDP", hex.EncodeToString(name[:8])+".gfxcache")
	}
	return rdp.Options{
		Address:        address,
		Username:       o.Username,
		Password:       o.Password,
		ClientName:     o.ClientName,
		KeyboardLayout: uint32(o.KeyboardLayout),
		Clipboard:      o.SharedClipboard,
		Audio:          o.Audio,
		Microphone:     o.Microphone,
		KeepAlive:      o.KeepAlive,
		DisableUDP:     o.DisableUDP,
		GraphicsCache:  graphicsCache,
		VerifyCertificate: func(fingerprint string, authenticated bool) error {
			seen(fingerprint)
			if !authenticated && trusted != "" && !strings.EqualFold(fingerprint, trusted) {
				return E.Extend(rdp.ErrCertificateChanged, "fingerprint ", fingerprint, " is not the trusted ", trusted)
			}
			return nil
		},
		Logger: newLogger(),
	}, nil
}

func rdpLayout(width, height int, density float64) (int, int, int) {
	return int(math.Round(float64(width) * density)), int(math.Round(float64(height) * density)), int(math.Round(density * 100))
}

func checkRDPLogin(ctx context.Context, options *SessionOptions) (string, error) {
	var fingerprint string
	rdpOptions, err := rdpOptions(options, func(seen string) { fingerprint = seen })
	if err != nil {
		return "", err
	}
	err = rdp.CheckLogin(ctx, rdpOptions)
	return fingerprint, err
}

func newRDPServerInfo(info rdp.Info) *ServerInfo {
	return &ServerInfo{
		Width:                    int32(info.Width),
		Height:                   int32(info.Height),
		Dialect:                  DialectRDP,
		Platform:                 PlatformPC,
		CursorPixelsPerPoint:     float64(info.Scale) / 100,
		SupportsVirtualDisplay:   info.Resize,
		SupportsClipboard:        info.Clipboard,
		SupportsClipboardRequest: info.Clipboard,
		SupportsKeyCodes:         true,
		SupportsAudio:            true,
		SupportsUnicodeText:      info.Unicode,
		SupportsTouch:            info.Touch,
	}
}

func newRDPDisplayList(width, height, scale int) *DisplayList {
	density := float64(scale) / 100
	return &DisplayList{
		Current:          AllDisplays,
		FramebufferWidth: int32(width),
		DesktopWidth:     int32(width),
		displays: []*Display{{
			DisplayID: 1, IsMain: true,
			LogicalWidth: int32(math.Round(float64(width) / density)), LogicalHeight: int32(math.Round(float64(height) / density)),
		}},
	}
}

type rdpRemote struct {
	*rdp.Client
	logger *slog.Logger
}

func (r *rdpRemote) serverInfo() *ServerInfo {
	return newRDPServerInfo(r.Info())
}

func (r *rdpRemote) apply(setup sessionSetup) {
	if display := setup.display; display != nil {
		err := r.SetVirtualDisplay(int(display.width), int(display.height), display.density, 0, 0)
		if err != nil {
			r.logger.Warn("apply the virtual display requested before connecting", "error", err)
		}
	}
	if setup.visible != nil {
		err := r.setVisible(*setup.visible)
		if err != nil {
			r.logger.Warn("apply the visibility set before connecting", "error", err)
		}
	}
}

func (r *rdpRemote) statistics(statistics *Statistics) {
	clientStatistics := r.Statistics()
	statistics.Encoding = clientStatistics.Encoding
	statistics.ReceivedBytes = int64(clientStatistics.BytesRead)
	statistics.RoundTripMilliseconds = float64(clientStatistics.RoundTrip.Microseconds()) / 1000
	statistics.UDP = clientStatistics.UDP
	statistics.Packets = int64(clientStatistics.PacketsExpected - clientStatistics.PacketsMissed)
	statistics.PacketsExpected = int64(clientStatistics.PacketsExpected)
}

func (r *rdpRemote) setVisible(visible bool) error {
	return r.SuppressOutput(!visible)
}

func (r *rdpRemote) setKeepsAwake(bool) error {
	return nil
}

func (r *rdpRemote) SetVirtualDisplay(width, height int, density float64, _, _ int) error {
	return r.SetDisplay(rdpLayout(width, height, density))
}

func (r *rdpRemote) SendPointer(x, y int, buttons uint8, _ int) error {
	return r.Client.SendPointer(x, y, buttons)
}

func (r *rdpRemote) SendKeyCode(keyCode uint16, keyboardType int32, keysym uint32, down bool) error {
	return r.Client.SendKeyCode(keyCode, keyboardType == KeyboardISO, keysym, down)
}

func (r *rdpRemote) SetClipboard(clipboard *Clipboard) error {
	text, _ := clipboard.text()
	return r.Client.SetClipboard(text)
}

func (r *rdpRemote) PasteClipboard(clipboard *Clipboard) error {
	text, found := clipboard.text()
	if !found {
		return E.New("RDP pastes only text, and the clipboard has none")
	}
	return r.Paste(text)
}

func (s *Session) SendMicrophoneAudio(data []byte) error {
	connected, err := s.connectedRemote()
	if err != nil {
		return err
	}
	client, isRDP := connected.(*rdpRemote)
	if !isRDP {
		return E.New("the microphone is available only over RDP")
	}
	client.SendMicrophoneAudio(data)
	return nil
}

type rdpSessionEvents struct {
	session        *Session
	audioFrameSize int
	audioFrames    int
	audioPending   []byte
	audioTimestamp int64
}

func (e *rdpSessionEvents) StateChanged(state rdp.State) {
	switch state {
	case rdp.StateConnecting:
		e.session.handler.OnStateChanged(StateConnecting, ErrorNone, "")
	case rdp.StateAuthenticating:
		e.session.handler.OnStateChanged(StateAuthenticating, ErrorNone, "")
	}
}

func (e *rdpSessionEvents) InfoChanged(info rdp.Info) {
	e.session.handler.OnDisplays(newRDPDisplayList(info.Width, info.Height, info.Scale))
	_, err := e.session.connectedRemote()
	if err != nil {
		return
	}
	e.session.handler.OnServerInfo(newRDPServerInfo(info))
}

func (e *rdpSessionEvents) ResizeFramebuffer(width, height int) (*bgra.Image, error) {
	return e.session.resizeFramebuffer(width, height)
}

func (e *rdpSessionEvents) FramebufferUpdated(update rdp.Update) {
	e.session.handler.OnFramebufferUpdate(&FramebufferUpdate{
		X:               int32(update.Rectangle.Min.X),
		Y:               int32(update.Rectangle.Min.Y),
		Width:           int32(update.Rectangle.Dx()),
		Height:          int32(update.Rectangle.Dy()),
		HoldNanoseconds: int64(update.Hold),
	})
}

func (e *rdpSessionEvents) CursorChanged(cursor *bgra.Cursor) {
	switch {
	case cursor == nil:
		e.session.handler.OnCursorImage(nil)
		e.session.handler.OnStatus(StatusPointerShown)
	case cursor.Width == 0 || cursor.Height == 0:
		e.session.handler.OnCursorImage(nil)
		e.session.handler.OnStatus(StatusPointerHidden)
	default:
		e.session.handler.OnCursorImage(newCursorImage(cursor))
		e.session.handler.OnStatus(StatusPointerShown)
	}
}

func (e *rdpSessionEvents) CursorMoved(x, y int) {
	e.session.handler.OnCursorPosition(int32(x), int32(y))
}

func (e *rdpSessionEvents) ClipboardReceived(text string) {
	e.session.handler.OnClipboard(newClipboard(rfb.NewTextClipboard(text)))
}

func (e *rdpSessionEvents) Bell() {
	e.session.handler.OnBell()
}

func (e *rdpSessionEvents) AudioFormatChanged(format rdp.AudioFormat) {
	e.audioPending = nil
	e.audioFrameSize = format.Channels * 2
	e.audioFrames = format.SampleRate / 100
	e.session.handler.OnAudioFormat(&AudioFormat{
		FormatID:        0x6c70636d,
		SampleRate:      int32(format.SampleRate),
		Channels:        int32(format.Channels),
		FramesPerPacket: int32(e.audioFrames),
	})
}

func (e *rdpSessionEvents) AudioPacketReceived(data []byte, timestamp uint64) {
	if timestamp > math.MaxInt64 {
		return
	}
	start := int64(timestamp)
	end := e.audioTimestamp + int64(len(e.audioPending)/e.audioFrameSize)
	if e.audioPending == nil || start > end+2*int64(e.audioFrames) {
		e.audioPending = []byte{}
		e.audioTimestamp = start
	}
	e.audioPending = append(e.audioPending, data...)
	size := e.audioFrames * e.audioFrameSize
	for len(e.audioPending) >= size {
		e.session.handler.OnAudioPacket(&AudioPacket{Data: e.audioPending[:size:size], Timestamp: e.audioTimestamp})
		e.audioPending = e.audioPending[size:]
		e.audioTimestamp += int64(e.audioFrames)
	}
}

func (e *rdpSessionEvents) MicrophoneOpened(format rdp.AudioFormat) {
	e.session.handler.OnMicrophone(&AudioFormat{
		FormatID:   0x6c70636d,
		SampleRate: int32(format.SampleRate),
		Channels:   int32(format.Channels),
	})
}

func (e *rdpSessionEvents) MicrophoneClosed() {
	e.session.handler.OnMicrophone(nil)
}
