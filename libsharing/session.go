package libsharing

import (
	"context"
	"errors"
	"io"
	"math"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unsafe"

	"github.com/nekohasekai/open-screen-sharing/internal/bgra"
	"github.com/nekohasekai/open-screen-sharing/internal/rdp"
	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
	"github.com/nekohasekai/open-screen-sharing/internal/rfb/media"
	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

type SessionHandler interface {
	OnStateChanged(state int32, errorCode int32, message string)
	OnServerInfo(info *ServerInfo)
	OnServerCertificate(fingerprint string)

	OnFramebufferResize(width int32, height int32)

	OnFramebufferUpdate(update *FramebufferUpdate)
	OnCursorImage(cursor *CursorImage)
	OnCursorPosition(x int32, y int32)
	OnDisplays(displays *DisplayList)
	OnClipboard(clipboard *Clipboard)
	OnClipboardProblem(kind int32, message string)
	OnBell()
	OnKeyboardSource(sourceID string, secureInput bool)

	OnStatus(status int32)

	OnPrompt(prompt *Prompt)

	OnVideoFormat(format *VideoFormat)
	OnVideoAccessUnit(unit *VideoAccessUnit)
	OnAudioFormat(format *AudioFormat)
	OnAudioPacket(packet *AudioPacket)
	OnMicrophone(format *AudioFormat)

	OnMediaStopped(errorCode int32, message string)

	OnFileTransferProgress(transferID int64, transferred int64, total int64)

	OnFileTransferFinished(transferID int64, name string, errorCode int32, message string)

	OnDropDestination(dragID int64, path string)
	OnRemoteDrag(drag *RemoteDrag)

	OnDeviceInfo(model string, deviceColor string, enclosureColor string)
}

type Session struct {
	options SessionOptions
	handler SessionHandler

	access        sync.Mutex
	remote        remote
	cancel        context.CancelFunc
	resizing      bool
	attached      *bgra.Image
	prompts       map[int64]chan int32
	nextPrompt    int64
	ending        bool
	disconnecting bool
	finished      bool
	setup         sessionSetup

	displayRequested chan struct{}

	resizeWidth  int
	resizeHeight int

	pictureEncoding atomic.Int64
	supportsTouch   atomic.Bool
}

func NewSession(options *SessionOptions, handler SessionHandler) (*Session, error) {
	if options.Host == "" {
		return nil, E.New("host is required")
	}
	if options.Port <= 0 || options.Port > 65535 {
		return nil, E.New("invalid port ", options.Port, "; it must be 1 to 65535")
	}
	if options.Protocol != ProtocolRFB && options.Protocol != ProtocolRDP {
		return nil, E.New("unknown protocol ", options.Protocol)
	}
	if options.Platform < PlatformUnknown || options.Platform > PlatformPC {
		return nil, E.New("unknown platform ", options.Platform)
	}
	if options.UpdateRate < 0 {
		return nil, E.New("invalid update rate ", options.UpdateRate, "; it must not be negative")
	}
	return &Session{options: *options, handler: handler, prompts: make(map[int64]chan int32), displayRequested: make(chan struct{})}, nil
}

type remote interface {
	Run() error
	Close() error
	Abort() error
	serverInfo() *ServerInfo
	statistics(statistics *Statistics)
	apply(setup sessionSetup)
	setVisible(visible bool) error
	setKeepsAwake(enabled bool) error

	SetFramebuffer(framebuffer *bgra.Image) error
	SetVirtualDisplay(width, height int, density float64, maximumWidth, maximumHeight int) error
	RequestRefresh() error

	SendPointer(x, y int, buttons uint8, clickCount int) error
	SendScroll(x, y int, deltaX, deltaY float64) error
	SendKey(keysym uint32, down bool) error
	SendKeyCode(keyCode uint16, keyboardType int32, keysym uint32, down bool) error
	SendText(text string) error
	SendTouches(touches []rdp.Touch) error

	SetClipboard(clipboard *Clipboard) error
	PasteClipboard(clipboard *Clipboard) error
	SetSharedClipboard(enabled bool) error
	RequestClipboard() error
}

type rfbRemote struct {
	*rfb.Client
	session *Session
}

func (r rfbRemote) serverInfo() *ServerInfo {
	info := newServerInfo(r.Info(), r.session.options.Platform)
	width, height := r.FramebufferSize()
	info.Width, info.Height = int32(width), int32(height)
	info.SupportsTouch = r.TouchAvailable()
	info.setSystemCommands(r.SystemCommands())
	return info
}

func (r rfbRemote) apply(setup sessionSetup) {
	info := r.serverInfo()
	if display := setup.display; display != nil && info.SupportsVirtualDisplay {
		err := r.Client.SetVirtualDisplay(int(display.width), int(display.height), display.density, int(display.maximumWidth), int(display.maximumHeight))
		if err != nil {
			r.Logger().Warn("apply the virtual display requested before connecting", "error", err)
		}
	}
	if setup.hdr != nil && info.SupportsVirtualDisplay {
		err := r.SetHDR(*setup.hdr)
		if err != nil {
			r.Logger().Warn("apply the HDR setting made before connecting", "error", err)
		}
	}
	if setup.quality != nil {
		err := r.SetQuality(*setup.quality)
		if err != nil {
			r.Logger().Warn("apply the quality chosen before connecting", "error", err)
		}
	}
	if setup.highPerformance != nil && info.SupportsHighPerformance && r.MediaStream() == nil {
		err := r.StartMediaStream(*setup.highPerformance, &mediaEvents{session: r.session})
		if err != nil {
			r.session.handler.OnMediaStopped(classifyMediaError(err), err.Error())
		}
	}
	if setup.updateRate != nil {
		err := r.SetUpdateInterval(updateInterval(*setup.updateRate))
		if err != nil {
			r.Logger().Warn("apply the update rate set before connecting", "error", err)
		}
	}
	if setup.keepsAwake != nil {
		r.SetKeepsAwake(*setup.keepsAwake)
	}
}

func (r rfbRemote) setVisible(bool) error {
	return nil
}

func (r rfbRemote) setKeepsAwake(enabled bool) error {
	r.SetKeepsAwake(enabled)
	return nil
}

func (r rfbRemote) SendKeyCode(keyCode uint16, keyboardType int32, keysym uint32, down bool) error {
	return r.Client.SendKeyCode(keyCode, rfb.KeyboardType(keyboardType), keysym, down)
}

func (r rfbRemote) SendTouches(touches []rdp.Touch) error {
	return r.Client.SendTouches(common.Map(touches, func(touch rdp.Touch) rfb.Touch {
		return rfb.Touch{ID: touch.ID, Phase: rfb.TouchPhase(touch.Phase), X: touch.X, Y: touch.Y}
	}))
}

func (r rfbRemote) SetClipboard(clipboard *Clipboard) error {
	return r.Client.SetClipboard(clipboard.toRFB())
}

func (r rfbRemote) PasteClipboard(clipboard *Clipboard) error {
	return r.Paste(clipboard.toRFB(), r.serverInfo().Platform == PlatformMac)
}

func (s *Session) Connect() error {
	var dial func(ctx context.Context) (remote, error)
	switch s.options.Protocol {
	case ProtocolRDP:
		options, err := rdpOptions(&s.options, s.handler.OnServerCertificate)
		if err != nil {
			return err
		}
		options.Display = func(ctx context.Context) (int, int, int, error) {
			err := s.waitForDisplay(ctx)
			if err != nil {
				return 0, 0, 0, err
			}
			s.access.Lock()
			display := s.setup.display
			s.setup.display = nil
			s.access.Unlock()
			if display == nil {
				return 0, 0, 0, nil
			}
			width, height, scale := rdpLayout(int(display.width), int(display.height), display.density)
			return width, height, scale, nil
		}
		dial = func(ctx context.Context) (remote, error) {
			client, err := rdp.Dial(ctx, options, &rdpSessionEvents{session: s})
			if err != nil {
				return nil, err
			}
			return &rdpRemote{Client: client, logger: options.Logger}, nil
		}
	default:
		options, err := rfbOptions(&s.options, s.handler.OnServerCertificate)
		if err != nil {
			return err
		}
		dial = func(ctx context.Context) (remote, error) {
			options.Prepare = func(client *rfb.Client) error {
				connected := rfbRemote{Client: client, session: s}
				if connected.serverInfo().SupportsVirtualDisplay {
					err := s.waitForDisplay(ctx)
					if err != nil {
						return err
					}
				}
				connected.apply(s.takeSetup())
				return nil
			}
			client, err := rfb.Dial(ctx, options, &sessionEvents{session: s})
			if err != nil {
				return nil, err
			}
			return rfbRemote{Client: client, session: s}, nil
		}
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.access.Lock()
	s.cancel = cancel
	s.access.Unlock()
	go s.run(ctx, dial)
	return nil
}

func rfbOptions(o *SessionOptions, seen func(fingerprint string)) (rfb.Options, error) {
	trusted := o.TrustedFingerprint
	options := rfb.Options{
		Address:              net.JoinHostPort(o.Host, strconv.Itoa(int(o.Port))),
		Username:             o.Username,
		Password:             o.Password,
		ClientName:           o.ClientName,
		AllowUnverifiedLogin: o.AllowUnverifiedLogin,
		VerifyServerKey: func(fingerprint string) error {
			seen(fingerprint)
			if trusted != "" && !strings.EqualFold(fingerprint, trusted) {
				return E.Extend(rfb.ErrServerKeyChanged, "fingerprint ", fingerprint, " is not the trusted ", trusted)
			}
			return nil
		},
		Quality:             rfb.Quality(o.Quality),
		Mode:                rfb.Mode(o.Mode),
		Clipboard:           o.SharedClipboard,
		DecoderMaxPixelRate: o.DecoderMaxPixelRate,
		MaximumFrameRate:    int(o.MaximumFrameRate),
		AutomaticUpdates:    true,
		UpdateInterval:      updateInterval(o.UpdateRate),
		Logger:              newLogger(),
	}
	switch o.LoginType {
	case LoginAccount:
		options.SecurityTypes = rfb.AccountSecurityTypes(o.Host)
	case LoginRequestControl:
		options.SecurityTypes = rfb.SecurityAskPermission
	case LoginRequestObserve:
		options.SecurityTypes = rfb.SecurityObserveRequest
	case LoginVNCPassword:
		options.SecurityTypes = []rfb.SecurityType{rfb.SecurityVNCPassword}
	case LoginNone:
		options.SecurityTypes = []rfb.SecurityType{rfb.SecurityNone}
	default:
		return options, E.New("unknown login type ", o.LoginType)
	}
	if options.Quality < rfb.QualityAdaptive || options.Quality > rfb.QualityLow {
		return options, E.New("unknown quality ", o.Quality, "; it must be ", QualityAdaptive, " to ", QualityLow)
	}
	if options.Mode < rfb.ModeObserve || options.Mode > rfb.ModeExclusiveControl {
		return options, E.New("unknown mode ", o.Mode, "; it must be ", ModeObserve, " to ", ModeExclusiveControl)
	}
	switch options.MaximumFrameRate {
	case 0, 30, 60:
	default:
		return options, E.New("unsupported maximum frame rate ", o.MaximumFrameRate, "; it must be 0, 30 or 60")
	}
	return options, nil
}

func (s *Session) run(ctx context.Context, dial func(ctx context.Context) (remote, error)) {
	err := checkOwnScreen(ctx, s.options.Host, s.options.Port)
	if err != nil {
		s.finish(err)
		return
	}
	connected, err := dial(ctx)
	if err != nil {
		s.finish(err)
		return
	}
	s.access.Lock()
	cancelled := ctx.Err() != nil
	if !cancelled {
		s.remote = connected
	}
	late := s.setup
	s.setup = sessionSetup{}
	s.access.Unlock()
	if cancelled {
		_ = connected.Abort()
		_ = connected.Run()
		s.finish(context.Canceled)
		return
	}
	connected.apply(late)
	s.reportServerInfo(connected)
	s.handler.OnStateChanged(StateConnected, ErrorNone, "")
	err = connected.Run()
	if ctx.Err() != nil {
		if code, _ := classifyError(err); code == ErrorNone || code == ErrorNetwork || code == ErrorProtocol {
			err = context.Canceled
		}
	}
	s.finish(err)
}

func (s *Session) reportServerInfo(connected remote) {
	info := connected.serverInfo()
	s.supportsTouch.Store(info.SupportsTouch)
	s.handler.OnServerInfo(info)
}

type sessionSetup struct {
	hdr             *bool
	quality         *rfb.Quality
	display         *virtualDisplay
	highPerformance *media.OfferOptions
	updateRate      *int32
	visible         *bool
	keepsAwake      *bool
}

type virtualDisplay struct {
	width         int32
	height        int32
	density       float64
	maximumWidth  int32
	maximumHeight int32
}

func (s *Session) pending(keep func(*sessionSetup)) (remote, bool) {
	s.access.Lock()
	defer s.access.Unlock()
	if s.disconnecting {
		return nil, false
	}
	if s.remote == nil && !s.finished {
		keep(&s.setup)
		return nil, true
	}
	return s.remote, false
}

func (s *Session) waitForDisplay(ctx context.Context) error {
	if !s.options.AwaitVirtualDisplay {
		return nil
	}
	select {
	case <-s.displayRequested:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (s *Session) takeSetup() sessionSetup {
	s.access.Lock()
	defer s.access.Unlock()
	setup := s.setup
	s.setup = sessionSetup{}
	return setup
}

func (s *Session) finish(err error) {
	s.access.Lock()
	s.finished = true
	s.setup = sessionSetup{}
	s.remote = nil
	s.attached = nil
	s.cancelPromptsLocked()
	s.access.Unlock()
	code, message := classifyError(err)
	if s.ending && (code == ErrorNetwork || code == ErrorProtocol) {
		code, message = ErrorSessionEnded, ""
	}
	s.handler.OnStateChanged(StateDisconnected, code, message)
}

func classifyError(err error) (int32, string) {
	var ownScreen *ownScreenError
	switch {
	case err == nil:
		return ErrorNone, ""
	case E.IsMulti(err, context.Canceled, rfb.ErrSessionCancelled):
		return ErrorCancelled, err.Error()
	case E.IsMulti(err, rfb.ErrAuthenticationFailed, rdp.ErrAuthenticationFailed):
		return ErrorAuthentication, err.Error()
	case E.IsMulti(err, rfb.ErrPermissionDenied, rdp.ErrRemoteLogonDenied):
		return ErrorPermissionDenied, err.Error()
	case E.IsMulti(err, rfb.ErrUnsupportedServer, rdp.ErrNotRDP, rdp.ErrUnsupportedSecurity):
		return ErrorUnsupportedServer, err.Error()
	case errors.Is(err, rfb.ErrUnverifiedServer):
		return ErrorUnverifiedServer, err.Error()
	case E.IsMulti(err, rdp.ErrCertificateChanged, rfb.ErrServerKeyChanged):
		return ErrorCertificateChanged, err.Error()
	case E.IsMulti(err, rdp.ErrSessionEnded, rdp.ErrSessionReplaced):
		return ErrorSessionEnded, err.Error()
	case errors.As(err, &ownScreen):
		return ErrorOwnScreen, err.Error()
	case E.IsMulti(err, io.EOF, io.ErrUnexpectedEOF):
		return ErrorNetwork, err.Error()
	}
	var dnsError *net.DNSError
	if errors.As(err, &dnsError) && dnsError.IsNotFound {
		return ErrorHostNotFound, err.Error()
	}
	var networkError net.Error
	if errors.As(err, &networkError) {
		return ErrorNetwork, err.Error()
	}
	return ErrorProtocol, err.Error()
}

func (s *Session) cancelPromptsLocked() {
	for _, answer := range s.prompts {
		close(answer)
	}
	clear(s.prompts)
}

func (s *Session) Disconnect() {
	s.access.Lock()
	if s.finished || s.disconnecting {
		s.access.Unlock()
		return
	}
	s.disconnecting = true
	cancel, connected := s.cancel, s.remote
	s.cancelPromptsLocked()
	s.access.Unlock()
	if connected != nil {
		go connected.Close()
	} else if cancel != nil {
		cancel()
	}
}

func (s *Session) Abort() {
	s.access.Lock()
	s.disconnecting = true
	cancel, connected := s.cancel, s.remote
	s.cancelPromptsLocked()
	s.access.Unlock()
	if cancel != nil {
		cancel()
	}
	if connected != nil {
		_ = connected.Abort()
	}
}

func (s *Session) AttachFramebuffer(address int64, width int32, height int32, bytesPerRow int32) error {
	if address == 0 {
		return E.New("the framebuffer address is null")
	}
	if width <= 0 || height <= 0 || bytesPerRow < width*4 {
		return E.New("invalid framebuffer of ", width, "x", height, " with ", bytesPerRow, " bytes per row")
	}
	length := int(bytesPerRow) * int(height)

	base := *(*unsafe.Pointer)(unsafe.Pointer(&address))
	framebuffer := &bgra.Image{
		Pix:    unsafe.Slice((*byte)(base), length),
		Stride: int(bytesPerRow),
		Width:  int(width),
		Height: int(height),
	}
	s.access.Lock()
	if s.resizing {
		if framebuffer.Width != s.resizeWidth || framebuffer.Height != s.resizeHeight {
			s.access.Unlock()
			return E.New("framebuffer is ", width, "x", height, ", the screen is being resized to ", s.resizeWidth, "x", s.resizeHeight)
		}
		s.attached = framebuffer
		s.access.Unlock()
		return nil
	}
	connected := s.remote
	s.access.Unlock()
	if connected == nil {
		return E.New("not connected")
	}
	return connected.SetFramebuffer(framebuffer)
}

func (s *Session) connectedRemote() (remote, error) {
	s.access.Lock()
	defer s.access.Unlock()
	if s.remote == nil || s.disconnecting {
		return nil, E.New("not connected")
	}
	return s.remote, nil
}

func (s *Session) connected() (*rfb.Client, error) {
	connected, err := s.connectedRemote()
	if err != nil {
		return nil, err
	}
	return rfbClient(connected)
}

func rfbClient(connected remote) (*rfb.Client, error) {
	if connected == nil {
		return nil, E.New("not connected")
	}
	client, isRFB := connected.(rfbRemote)
	if !isRFB {
		return nil, E.New("not supported over RDP")
	}
	return client.Client, nil
}

func (s *Session) SendPointer(x int32, y int32, buttons int32, clickCount int32) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendPointer(int(x), int(y), uint8(buttons), int(clickCount))
}

func (s *Session) SendScroll(x int32, y int32, deltaX float64, deltaY float64) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendScroll(int(x), int(y), deltaX, deltaY)
}

func (s *Session) SendKey(keysym int32, down bool) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendKey(uint32(keysym), down)
}

func (s *Session) SendKeyCode(keyCode int32, keyboardType int32, keysym int32, down bool) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendKeyCode(uint16(keyCode), keyboardType, uint32(keysym), down)
}

const (
	TouchPhaseBegan     = int32(rdp.TouchBegan)
	TouchPhaseMoved     = int32(rdp.TouchMoved)
	TouchPhaseEnded     = int32(rdp.TouchEnded)
	TouchPhaseCancelled = int32(rdp.TouchCancelled)
)

type TouchFrame struct {
	touches []rdp.Touch
}

func (f *TouchFrame) Add(id int64, phase int32, x int32, y int32) {
	f.touches = append(f.touches, rdp.Touch{ID: id, Phase: rdp.TouchPhase(phase), X: int(x), Y: int(y)})
}

func (s *Session) SendTouches(frame *TouchFrame) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendTouches(frame.touches)
}

func (s *Session) SendText(text string) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SendText(text)
}

func (s *Session) PasteText(text string) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.PasteText(text)
}

func (s *Session) PasteClipboard(clipboard *Clipboard) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.PasteClipboard(clipboard)
}

func (s *Session) SetClipboard(clipboard *Clipboard) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SetClipboard(clipboard)
}

func (s *Session) SetSharedClipboard(enabled bool) error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.SetSharedClipboard(enabled)
}

func (s *Session) RequestClipboard() error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.RequestClipboard()
}

func (s *Session) SetMode(mode int32) error {
	if rfb.Mode(mode) < rfb.ModeObserve || rfb.Mode(mode) > rfb.ModeExclusiveControl {
		return E.New("unknown mode ", mode, "; it must be ", ModeObserve, " to ", ModeExclusiveControl)
	}
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.SetMode(rfb.Mode(mode))
}

func (s *Session) SelectDisplay(displayID int64) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.SelectDisplay(uint32(displayID))
}

func (s *Session) SetQuality(quality int32) error {
	if rfb.Quality(quality) < rfb.QualityAdaptive || rfb.Quality(quality) > rfb.QualityLow {
		return E.New("unknown quality ", quality, "; it must be ", QualityAdaptive, " to ", QualityLow)
	}
	if s.options.Protocol != ProtocolRFB {
		return E.New("the quality is not adjustable over RDP")
	}
	value := rfb.Quality(quality)
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.quality = &value
	})
	if kept {
		return nil
	}
	client, err := rfbClient(connected)
	if err != nil {
		return err
	}
	return client.SetQuality(value)
}

func (s *Session) SetUpdateRate(rate int32) error {
	if rate < 0 {
		return E.New("invalid update rate ", rate, "; it must not be negative")
	}
	if s.options.Protocol != ProtocolRFB {
		return E.New("the update rate is not adjustable over RDP")
	}
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.updateRate = &rate
	})
	if kept {
		return nil
	}
	client, err := rfbClient(connected)
	if err != nil {
		return err
	}
	return client.SetUpdateInterval(updateInterval(rate))
}

func updateInterval(rate int32) time.Duration {
	if rate <= 0 {
		return 0
	}
	return time.Second / time.Duration(rate)
}

func (s *Session) SetVirtualDisplay(width int32, height int32, density float64, maximumWidth int32, maximumHeight int32) error {
	var err error
	switch s.options.Protocol {
	case ProtocolRDP:
		if width <= 0 || height <= 0 || !(density > 0) || math.IsInf(density, 1) {
			err = E.New("rdp: display of ", width, "x", height, " points at density ", density)
		}
	default:
		err = rfb.CheckVirtualDisplay(int(width), int(height), density)
	}
	if err != nil {
		return err
	}
	client, kept := s.pending(func(setup *sessionSetup) {
		setup.display = &virtualDisplay{width: width, height: height, density: density, maximumWidth: maximumWidth, maximumHeight: maximumHeight}
		select {
		case <-s.displayRequested:
		default:
			close(s.displayRequested)
		}
	})
	if kept {
		return nil
	}
	if client == nil {
		return E.New("not connected")
	}
	return client.SetVirtualDisplay(int(width), int(height), density, int(maximumWidth), int(maximumHeight))
}

func (s *Session) DisplayWidthMultiple() int32 {
	if s.options.Protocol == ProtocolRDP {
		return rdp.DisplayWidthMultiple
	}
	return 1
}

func (s *Session) SetVisible(visible bool) error {
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.visible = &visible
	})
	if kept {
		return nil
	}
	if connected == nil {
		return E.New("not connected")
	}
	return connected.setVisible(visible)
}

func (s *Session) SetKeepsAwake(enabled bool) error {
	connected, kept := s.pending(func(setup *sessionSetup) {
		setup.keepsAwake = &enabled
	})
	if kept {
		return nil
	}
	if connected == nil {
		return E.New("not connected")
	}
	return connected.setKeepsAwake(enabled)
}

func (s *Session) SelectKeyboardSource(sourceID string) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.SelectKeyboardSource(sourceID)
}

func (s *Session) RequestRefresh() error {
	client, err := s.connectedRemote()
	if err != nil {
		return err
	}
	return client.RequestRefresh()
}

func (s *Session) AnswerPrompt(promptID int64, answer int32) error {
	s.access.Lock()
	channel, found := s.prompts[promptID]
	delete(s.prompts, promptID)
	s.access.Unlock()
	if !found {
		return E.New("no prompt ", promptID, " is waiting for an answer")
	}
	channel <- answer
	return nil
}

type sessionEvents struct {
	session *Session
}

func (e *sessionEvents) StateChanged(state rfb.State) {
	switch state {
	case rfb.StateConnecting:
		e.session.handler.OnStateChanged(StateConnecting, ErrorNone, "")
	case rfb.StateAuthenticating:
		e.session.handler.OnStateChanged(StateAuthenticating, ErrorNone, "")
	case rfb.StateWaitingForApproval:
		e.session.handler.OnStateChanged(StateWaitingForApproval, ErrorNone, "")
	case rfb.StateChoosingSession:
		e.session.handler.OnStateChanged(StateChoosingSession, ErrorNone, "")
	}
}

func (e *sessionEvents) ResizeFramebuffer(width, height int) (*bgra.Image, error) {
	return e.session.resizeFramebuffer(width, height)
}

func (s *Session) resizeFramebuffer(width, height int) (*bgra.Image, error) {
	s.access.Lock()
	s.resizing = true
	s.resizeWidth, s.resizeHeight = width, height
	s.attached = nil
	s.access.Unlock()
	s.handler.OnFramebufferResize(int32(width), int32(height))
	s.access.Lock()
	framebuffer := s.attached
	s.resizing = false
	s.attached = nil
	s.access.Unlock()

	if framebuffer == nil {
		return nil, E.New("no ", width, "x", height, " framebuffer was attached during OnFramebufferResize")
	}
	return framebuffer, nil
}

func (e *sessionEvents) FramebufferUpdated(update rfb.Update) {
	largest := common.MaxBy(update.Rectangles, func(rectangle rfb.Rectangle) int {
		return rectangle.Width * rectangle.Height
	})
	if largest.Width*largest.Height > 0 {
		e.session.pictureEncoding.Store(int64(largest.Encoding) + 1)
	}
	dirty := update.Dirty
	e.session.handler.OnFramebufferUpdate(&FramebufferUpdate{X: int32(dirty.X), Y: int32(dirty.Y), Width: int32(dirty.Width), Height: int32(dirty.Height)})
}

func (e *sessionEvents) CursorChanged(cursor *rfb.Cursor) {
	e.session.handler.OnCursorImage(newCursorImage(&cursor.Cursor))
}

func newCursorImage(cursor *bgra.Cursor) *CursorImage {
	return &CursorImage{
		Width:    int32(cursor.Width),
		Height:   int32(cursor.Height),
		HotspotX: int32(cursor.HotspotX),
		HotspotY: int32(cursor.HotspotY),
		Pixels:   cursor.Pixels,
		Invert:   cursor.Invert,
	}
}

func (e *sessionEvents) CursorMoved(x, y int) {
	e.session.handler.OnCursorPosition(int32(x), int32(y))
}

func (e *sessionEvents) DisplaysChanged(layout *rfb.DisplayLayout) {
	e.session.handler.OnDisplays(newDisplayList(layout))
	connected, err := e.session.connectedRemote()
	if err != nil {
		return
	}
	if connected.serverInfo().SupportsTouch != e.session.supportsTouch.Load() {
		e.session.reportServerInfo(connected)
	}
}

func (e *sessionEvents) ClipboardReceived(clipboard *rfb.Clipboard) {
	e.session.handler.OnClipboard(newClipboard(clipboard))
}

func (e *sessionEvents) ClipboardFailed(problem rfb.ClipboardProblem, err error) {
	e.session.handler.OnClipboardProblem(int32(problem), err.Error())
}

func (e *sessionEvents) Bell() {
	e.session.handler.OnBell()
}

func (e *sessionEvents) KeyboardSourceChanged(source rfb.KeyboardSource) {
	e.session.handler.OnKeyboardSource(source.ID, source.SecureInput)
}

func (e *sessionEvents) StatusReceived(status rfb.Status) {
	switch status {
	case rfb.StatusSessionEnding:
		e.session.ending = true
	case rfb.StatusControlAllowed, rfb.StatusObserveOnly, rfb.StatusPointerHidden, rfb.StatusPointerShown:
		e.session.handler.OnStatus(int32(status))
	}
}

func (e *sessionEvents) DeviceInfoReceived(info rfb.DeviceInfo) {
	e.session.handler.OnDeviceInfo(info.Model, info.DeviceColor, info.EnclosureColor)
}

func (e *sessionEvents) RemoteDragChanged(drag *rfb.RemoteDrag) {
	if drag == nil {
		e.session.handler.OnRemoteDrag(nil)
		return
	}
	e.session.handler.OnRemoteDrag(&RemoteDrag{Image: drag.Image, items: drag.Items})
}

func (e *sessionEvents) DropDestinationReceived(id uint32, path string) {
	e.session.handler.OnDropDestination(int64(id), path)
}

func (e *sessionEvents) ChooseSession(request rfb.SessionRequest) (rfb.SessionChoice, error) {
	s := e.session
	prompt := &Prompt{ConsoleUser: request.ConsoleUser}
	for _, choice := range request.Choices {
		switch choice {
		case rfb.SessionShareConsole:
			prompt.AllowsShareConsole = true
		case rfb.SessionOwn:
			prompt.AllowsOwnSession = true
		}
	}
	answer := make(chan int32, 1)
	s.access.Lock()
	s.nextPrompt++
	prompt.PromptID = s.nextPrompt
	s.prompts[prompt.PromptID] = answer
	s.access.Unlock()
	s.handler.OnPrompt(prompt)
	choice, open := <-answer
	if !open {
		return rfb.SessionCancel, rfb.ErrSessionCancelled
	}
	switch choice {
	case SessionChoiceShareConsole:
		return rfb.SessionShareConsole, nil
	case SessionChoiceOwnSession:
		return rfb.SessionOwn, nil
	default:
		return rfb.SessionCancel, rfb.ErrSessionCancelled
	}
}
