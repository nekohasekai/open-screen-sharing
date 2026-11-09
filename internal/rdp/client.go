package rdp

import (
	"context"
	"encoding/binary"
	"errors"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/nekohasekai/open-screen-sharing/internal/bgra"
	"github.com/nekohasekai/open-screen-sharing/internal/outbox"

	E "github.com/sagernet/sing/common/exceptions"
)

var (
	ErrSessionEnded    = E.New("rdp: the remote session ended")
	ErrSessionReplaced = E.New("rdp: another connection took over the remote session")
	errDisconnected    = E.New("rdp: the server closed the session")
)

const (
	refreshInterval  = time.Second
	defaultWidth     = 1280
	defaultHeight    = 800
	defaultLayout    = 0x0409
	defaultPort      = "3389"
	maxCredentialLen = 65534

	errorInfoNone                  = 0x0
	errorInfoAdminDisconnect       = 0x1
	errorInfoAdminLogoff           = 0x2
	errorInfoIdleTimeout           = 0x3
	errorInfoLogonTimeout          = 0x4
	errorInfoReplaced              = 0x5
	errorInfoServerDenied          = 0x7
	errorInfoInsufficientPrivilege = 0x9
	errorInfoFreshCredentials      = 0xA
	errorInfoUserDisconnect        = 0xB
	errorInfoUserLogoff            = 0xC

	staticChannelClipboard   = "cliprdr"
	staticChannelDynamic     = "drdynvc"
	staticChannelAudio       = "rdpsnd"
	staticChannelDevice      = "rdpdr"
	dynamicChannelGraphics   = "Microsoft::Windows::RDS::Graphics"
	dynamicChannelDisplay    = "Microsoft::Windows::RDS::DisplayControl"
	dynamicChannelTouch      = "Microsoft::Windows::RDS::Input"
	dynamicChannelAudio      = "AUDIO_PLAYBACK_DVC"
	dynamicChannelMicrophone = "AUDIO_INPUT"

	refreshAreaCount       = 1
	suppressDisplayUpdates = 0
	allowDisplayUpdates    = 1
)

type Client struct {
	events       Events
	logger       *slog.Logger
	address      string
	transport    *transport
	outbox       *outbox.Outbox
	writer       *outbox.Writer
	writerDone   chan struct{}
	writeErr     error
	ctx          context.Context
	cancel       context.CancelCauseFunc
	mcs          *mcsLayer
	autodetect   *autodetect
	session      *session
	channels     *channelRegistry
	dynamic      *drdynvc
	graphics     *graphics
	input        *inputQueue
	clipboard    *clipboardChannel
	display      *displayChannel
	touch        *touchChannel
	audio        *audioChannel
	dynamicAudio *audioChannel
	microphone   *microphoneChannel

	processAccess sync.Mutex
	roundTrip     atomic.Pointer[time.Duration]
	tunnel        atomic.Pointer[tunnel]
	tunnels       sync.WaitGroup

	// mstscax.dll of Windows 11 26200 counts only auto-detect PDUs and static virtual channel data
	// toward the bandwidth byteCount over TCP.
	received uint64

	infoAccess sync.Mutex
	reported   Info

	refreshAccess  sync.Mutex
	refreshPending bool
	lastRefresh    time.Time

	lifecycle sync.Mutex
	running   bool
	closed    bool
	tunnelErr error
}

func normalizeOptions(opts Options) (Options, error) {
	if opts.Address == "" {
		return opts, E.New("rdp: server address is required")
	}
	_, _, err := net.SplitHostPort(opts.Address)
	if err != nil {
		if strings.Contains(opts.Address, ":") && net.ParseIP(opts.Address) == nil {
			return opts, E.Cause(err, "rdp: parse server address")
		}
		opts.Address = net.JoinHostPort(opts.Address, defaultPort)
	}
	if opts.ClientName == "" {
		opts.ClientName = "OpenScreen"
	}
	if opts.KeyboardLayout == 0 {
		opts.KeyboardLayout = defaultLayout
	}
	if opts.Logger == nil {
		opts.Logger = slog.New(slog.DiscardHandler)
	}
	if opts.Domain == "" {
		domain, user, found := strings.Cut(opts.Username, "\\")
		if found {
			opts.Domain, opts.Username = domain, user
		}
	}
	for _, value := range []string{opts.Username, opts.Domain, opts.Password} {
		if length := len(utf16le(value)); length > maxCredentialLen {
			return opts, E.New("rdp: a credential of ", length, " UTF-16 bytes exceeds the ", maxCredentialLen, "-byte protocol limit")
		}
	}
	return opts, nil
}

func CheckLogin(ctx context.Context, opts Options) error {
	opts, err := normalizeOptions(opts)
	if err != nil {
		return err
	}
	t, err := negotiateTransport(ctx, opts, nil)
	if err != nil {
		return err
	}
	return t.conn.Close()
}

func Dial(ctx context.Context, opts Options, events Events) (*Client, error) {
	opts, err := normalizeOptions(opts)
	if err != nil {
		return nil, err
	}
	events.StateChanged(StateConnecting)
	t, err := negotiateTransport(ctx, opts, func() { events.StateChanged(StateAuthenticating) })
	if err != nil {
		return nil, err
	}
	width, height, scale, err := opts.Display(ctx)
	if err != nil {
		_ = t.conn.Close()
		return nil, err
	}
	if width == 0 || height == 0 {
		width, height = defaultWidth, defaultHeight
	}
	width, height, scale = normalizeLayout(width, height, scale)
	lifetime, cancelLifetime := context.WithCancelCause(context.Background())
	t.startReceiver(lifetime)
	c := &Client{
		ctx:        lifetime,
		cancel:     cancelLifetime,
		events:     events,
		logger:     opts.Logger,
		address:    opts.Address,
		transport:  t,
		outbox:     outbox.New(),
		writer:     outbox.NewWriter(t.conn),
		writerDone: make(chan struct{}),
	}
	c.mcs = &mcsLayer{transport: t, outbox: c.outbox, fragments: make(map[uint16][]byte)}
	c.autodetect = &autodetect{logger: c.logger, roundTrip: &c.roundTrip, respond: func(response []byte) error {
		b := binary.LittleEndian.AppendUint16(nil, securityAutodetectResponse)
		b = binary.LittleEndian.AppendUint16(b, 0)
		return c.mcs.send(c.mcs.message, append(b, response...))
	}}
	c.graphics = newGraphics(events, c.logger, width, height, c)
	c.clipboard = &clipboardChannel{events: events, changed: c.refreshInfo, shared: opts.Clipboard}
	c.display = &displayChannel{logger: c.logger, changed: c.refreshInfo, width: width, height: height, scale: scale, sentScale: scale}
	c.touch = &touchChannel{logger: c.logger, changed: c.refreshInfo}
	c.audio = &audioChannel{events: events, changed: c.refreshInfo}
	c.dynamicAudio = &audioChannel{events: events, changed: c.refreshInfo}
	c.microphone = &microphoneChannel{events: events, logger: c.logger}
	c.session = &session{
		mcs:            c.mcs,
		graphics:       c.graphics,
		display:        c.display,
		events:         events,
		logger:         c.logger,
		changed:        c.refreshInfo,
		license:        &licenseState{username: opts.Username, hostname: opts.ClientName},
		keyboardLayout: opts.KeyboardLayout,
	}
	c.input = newInputQueue(c.session.sendInput, c.writerDone, c.logger, opts.KeepAlive)
	c.session.input = c.input
	c.dynamic = &drdynvc{logger: c.logger, switched: make(chan struct{}), listeners: []*dynamicListener{
		{name: dynamicChannelGraphics, handler: &rdpgfx{g: c.graphics, cachePath: opts.GraphicsCache}, required: true},
		{name: dynamicChannelDisplay, handler: c.display},
		{name: dynamicChannelTouch, handler: c.touch},
	}}
	// rdpserverbase.dll of Windows 11 26200 compresses graphics updates and static channel data with
	// one shared compressor, so both go through one decompressor in arrival order.
	c.channels = &channelRegistry{mcs: c.mcs, logger: c.logger, decompressor: c.graphics.bulk, channels: []*staticChannel{
		{name: staticChannelClipboard, options: channelOptionInitialized | channelOptionCompressRDP | channelOptionShowProtocol, handler: c.clipboard},
		{name: staticChannelDynamic, options: channelOptionInitialized, handler: c.dynamic},
	}}
	if opts.Audio {
		c.dynamic.listeners = append(c.dynamic.listeners, &dynamicListener{name: dynamicChannelAudio, handler: c.dynamicAudio})
		c.channels.channels = append(c.channels.channels,
			&staticChannel{name: staticChannelAudio, options: channelOptionInitialized | channelOptionCompressRDP, handler: c.audio},
			&staticChannel{name: staticChannelDevice, options: channelOptionInitialized | channelOptionCompressRDP, handler: newDeviceChannel(opts.ClientName)},
		)
	}
	if opts.Microphone {
		c.dynamic.listeners = append(c.dynamic.listeners, &dynamicListener{name: dynamicChannelMicrophone, handler: c.microphone})
	}
	go func() {
		err := c.writer.Run(c.ctx, c.outbox)
		if err != nil {
			c.writeErr = E.Cause(err, "rdp: send data")
			_ = c.transport.conn.Close()
		}
		close(c.writerDone)
	}()
	go c.input.run()
	stop := context.AfterFunc(ctx, func() { _ = t.tcp.Close() })
	err = c.connect(opts, width, height, scale)
	stop()
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		c.abort(err)
		c.shutdown()
		c.cleanup()
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		err = c.result(err)
		if err == nil {
			err = ErrSessionEnded
		}
		return nil, err
	}
	return c, nil
}

func (c *Client) connect(opts Options, width, height, scale int) error {
	err := c.mcs.connect(opts, width, height, scale, c.channels.channels)
	if err != nil {
		return err
	}
	err = c.mcs.send(c.mcs.global, clientInfo(opts))
	if err != nil {
		return err
	}
	c.channels.open()
	for !c.session.active.Load() {
		err = c.readOne()
		if err != nil {
			return err
		}
	}
	return nil
}

func (c *Client) readOne() error {
	fastPath, header, payload, err := c.transport.readPacket()
	if err != nil {
		return err
	}
	c.processAccess.Lock()
	defer c.processAccess.Unlock()
	if fastPath {
		if header&fastPathEncryptionMask != 0 {
			return E.New("rdp: encrypted fast-path packet in TLS session")
		}
		return c.graphics.processFastPathUpdates(payload)
	}
	channel, data, err := c.mcs.receive(payload)
	if err != nil || data == nil {
		return err
	}
	switch channel {
	case c.mcs.global:
		return c.session.receive(data)
	case c.mcs.message:
		r := &reader{data: data}
		flags := r.u16()
		r.take(2)
		switch {
		case r.err != nil:
			return E.Cause(r.err, "rdp: parse message channel PDU")
		case flags&securityTransportRequest != 0:
			return c.receiveMultitransport(r)
		case flags&securityAutodetectRequest != 0:
			c.received += uint64(r.remaining())
			return c.autodetect.receive(r, c.transport.arrival(), c.received)
		}
		c.logger.Debug("ignore message channel PDU", "flags", flags)
		return nil
	}
	c.received += uint64(len(data))
	return c.channels.receive(channel, data)
}

func (c *Client) Run() error {
	c.lifecycle.Lock()
	c.running = true
	closed := c.closed
	c.lifecycle.Unlock()
	if closed {
		<-c.writerDone
		return c.writeErr
	}
	defer c.cleanup()
	for {
		err := c.readOne()
		if err != nil {
			c.lifecycle.Lock()
			closed := c.closed
			c.lifecycle.Unlock()
			if !closed {
				c.abort(err)
			}
			c.shutdown()
			return c.result(err)
		}
	}
}

func (c *Client) result(err error) error {
	c.lifecycle.Lock()
	closed := c.closed
	tunnelErr := c.tunnelErr
	c.lifecycle.Unlock()
	if c.writeErr != nil && !errors.Is(c.writeErr, net.ErrClosed) {
		return c.writeErr
	}
	if closed {
		return nil
	}
	if tunnelErr != nil {
		return tunnelErr
	}
	if !E.IsClosed(err) && !errors.Is(err, errDisconnected) {
		return err
	}
	switch c.session.errorInfo {
	case errorInfoNone:
		if errors.Is(err, errDisconnected) {
			return ErrSessionEnded
		}
		return err
	case errorInfoAdminDisconnect:
		return E.Extend(ErrSessionEnded, "disconnected by an administrator")
	case errorInfoAdminLogoff:
		return E.Extend(ErrSessionEnded, "signed out by an administrator")
	case errorInfoIdleTimeout:
		return E.Extend(ErrSessionEnded, "idle time limit reached")
	case errorInfoLogonTimeout:
		return E.Extend(ErrSessionEnded, "sign-in time limit reached")
	case errorInfoReplaced:
		return ErrSessionReplaced
	case errorInfoServerDenied, errorInfoInsufficientPrivilege:
		return ErrRemoteLogonDenied
	case errorInfoFreshCredentials:
		return E.Extend(ErrAuthenticationFailed, "the server requires fresh credentials")
	case errorInfoUserDisconnect, errorInfoUserLogoff:
		return nil
	default:
		return E.New("rdp: server error 0x", strconv.FormatUint(uint64(c.session.errorInfo), 16))
	}
}

func (c *Client) Close() error {
	c.lifecycle.Lock()
	if c.closed {
		c.lifecycle.Unlock()
		return nil
	}
	c.closed = true
	running := c.running
	c.lifecycle.Unlock()
	_ = c.outbox.Push(tpkt([]byte{mcsDisconnectProviderUltimatum<<2 | mcsReasonUserRequested>>1, (mcsReasonUserRequested & 1) << 7}), false)
	c.shutdown()
	if !running {
		c.cleanup()
	}
	return c.writeErr
}

func (c *Client) shutdown() {
	c.outbox.Close(net.ErrClosed)
	<-c.writerDone
	_ = c.transport.conn.Close()
	c.cancel(nil)
}

func (c *Client) Abort() error {
	c.abort(context.Canceled)
	return nil
}

func (c *Client) abort(err error) {
	c.cancel(err)
	c.outbox.Abort(err)
	_ = c.transport.tcp.Close()
}

func (c *Client) cleanup() {
	c.tunnels.Wait()
	c.channels.close()
	c.graphics.close()
}

func (c *Client) SetFramebuffer(buffer *bgra.Image) error {
	err := c.graphics.attach(buffer)
	if err != nil {
		return err
	}
	return c.RequestRefresh()
}

func (c *Client) Info() Info {
	return Info{
		Width:     int(c.graphics.width.Load()),
		Height:    int(c.graphics.height.Load()),
		Scale:     c.display.layoutScale(),
		LoggedOn:  c.session.loggedOn.Load(),
		Clipboard: c.clipboard.enabled(),
		Resize:    c.display.enabled(),
		Audio:     c.audio.available.Load() || c.dynamicAudio.available.Load(),
		Unicode:   c.session.inputFlags.Load()&inputFlagUnicode != 0,
		Touch:     c.touch.enabled(),
	}
}

func (c *Client) refreshInfo() {
	select {
	case <-c.writerDone:
		return
	default:
	}
	c.infoAccess.Lock()
	defer c.infoAccess.Unlock()
	info := c.Info()
	if info == c.reported {
		return
	}
	c.reported = info
	c.events.InfoChanged(info)
}

func (c *Client) Statistics() Statistics {
	statistics := Statistics{
		BytesRead:    c.transport.read.Load(),
		BytesWritten: c.transport.written + c.writer.Written(),
		Frames:       c.graphics.frames.Load(),
		Encoding:     encodingNames[c.graphics.encoding.Load()],
	}
	if roundTrip := c.roundTrip.Load(); roundTrip != nil {
		statistics.RoundTrip = *roundTrip
		statistics.RoundTripKnown = true
	}
	if t := c.tunnel.Load(); t != nil {
		statistics.BytesRead += t.read.Load()
		statistics.BytesWritten += t.written.Load()
		statistics.UDP = true
		if roundTrip := t.udp.RoundTrip(); !statistics.RoundTripKnown && roundTrip > 0 {
			statistics.RoundTrip = roundTrip
			statistics.RoundTripKnown = true
		}
		statistics.PacketsExpected, statistics.PacketsMissed = t.udp.Packets()
	}
	return statistics
}

func (c *Client) desktopArea() []byte {
	b := binary.LittleEndian.AppendUint16(nil, 0)
	b = binary.LittleEndian.AppendUint16(b, 0)
	b = binary.LittleEndian.AppendUint16(b, uint16(c.graphics.width.Load()-1))
	return binary.LittleEndian.AppendUint16(b, uint16(c.graphics.height.Load()-1))
}

func (c *Client) RequestRefresh() error {
	if !c.session.active.Load() {
		return nil
	}
	c.refreshAccess.Lock()
	c.lastRefresh = time.Now()
	c.refreshAccess.Unlock()
	return c.session.sendData(pduRefreshRect, append([]byte{refreshAreaCount, 0, 0, 0}, c.desktopArea()...))
}

func (c *Client) scheduleRefresh() {
	c.refreshAccess.Lock()
	defer c.refreshAccess.Unlock()
	if c.refreshPending {
		return
	}
	c.refreshPending = true
	time.AfterFunc(max(0, time.Until(c.lastRefresh.Add(refreshInterval))), func() {
		c.refreshAccess.Lock()
		c.refreshPending = false
		c.refreshAccess.Unlock()
		err := c.RequestRefresh()
		if err != nil {
			c.logger.Debug("request refresh", "error", err)
		}
	})
}

func (c *Client) SuppressOutput(suppress bool) error {
	c.session.suppressed.Store(suppress)
	if !c.session.active.Load() {
		return nil
	}
	if suppress {
		return c.session.sendData(pduSuppressOutput, []byte{suppressDisplayUpdates, 0, 0, 0})
	}
	return c.session.sendData(pduSuppressOutput, append([]byte{allowDisplayUpdates, 0, 0, 0}, c.desktopArea()...))
}
