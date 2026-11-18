package rfb

import (
	"encoding/binary"
	"errors"
	"net"
	"slices"
	"sync/atomic"

	"github.com/nekohasekai/open-screen-sharing/internal/rfb/media"
	E "github.com/sagernet/sing/common/exceptions"
)

type mediaSession struct {
	offer           *media.Offer
	stream          *media.Stream
	sink            media.Sink
	starting        bool
	reconfiguring   bool
	unanswered      bool
	reoffer         bool
	portsSeen       bool
	portsPayload    []byte
	ports           media.Ports
	videoArrived    atomic.Bool
	suppressStopped atomic.Bool
	stopped         atomic.Bool
}

func (s *mediaSession) notifyStopped(err error) {
	if !s.stopped.Swap(true) {
		s.sink.StreamStopped(err)
	}
}

type mediaSink struct {
	media.Sink
	client  *Client
	session *mediaSession
}

func (s *mediaSink) VideoAccessUnit(unit media.VideoAccessUnit) {
	if !s.session.videoArrived.Swap(true) {
		if err := s.client.releaseInput(); err != nil {
			s.client.logger.Debug("release input", "error", err)
		}
	}
	s.Sink.VideoAccessUnit(unit)
}

func (s *mediaSink) StreamStopped(err error) {
	if s.session.suppressStopped.Load() {
		return
	}
	if err == nil {
		s.session.notifyStopped(nil)
		return
	}
	go s.client.endMediaSession(s.session, err)
}

func canRetryMediaStart(session *mediaSession, err error) bool {
	if session.videoArrived.Load() {
		return false
	}
	refusal, ok := errors.AsType[*media.ServerError](err)
	return ok && (refusal.Type != media.ServerErrorBusy || refusal.SubCode != media.ServerErrorBusySubCode)
}

func (c *Client) endMediaSession(session *mediaSession, cause error) {
	defer session.stream.Close()
	c.mediaLifecycle.Lock()
	c.access.Lock()
	current := c.media == session
	hasPorts := len(session.portsPayload) != 0
	c.access.Unlock()
	if !current {
		c.mediaLifecycle.Unlock()
		return
	}
	if !c.closed.Load() && hasPorts && canRetryMediaStart(session, cause) {
		fallback, err := session.offer.LegacyFallback()
		if err != nil {
			cause = E.Errors(cause, err)
		} else if fallback != nil {
			retry, err := c.startMediaSessionLocked(fallback, session.sink, session)
			if err != nil && retry != nil {
				defer retry.stream.Close()
			}
			if err == nil {
				c.logger.Debug("retry media stream with compatible offer", "error", cause)
				c.mediaLifecycle.Unlock()
				return
			}
			cause = E.Errors(cause, err)
		}
	}
	c.access.Lock()
	c.media = nil
	c.mediaStopping = true
	_ = c.releaseDisplayChangesLocked()
	c.access.Unlock()
	if err := c.SetMediaStream(false); err != nil {
		c.logger.Debug("return to RFB pictures", "error", err)
	}
	c.mediaLifecycle.Unlock()
	c.access.Lock()
	c.mediaStopping = false
	c.access.Unlock()
	session.notifyStopped(cause)
}

func (c *Client) StartMediaStream(options media.OfferOptions, sink media.Sink) error {
	if !c.info.Accepts(media.ClientMessageMediaStream) {
		return E.New("rfb: the server does not offer a media stream")
	}
	if options.DecoderMaxPixelRate == 0 {
		options.DecoderMaxPixelRate = c.options.DecoderMaxPixelRate
	}
	if options.MaximumFrameRate == 0 {
		options.MaximumFrameRate = c.options.MaximumFrameRate
	}
	offer, err := media.NewOffer(options)
	if err != nil {
		return err
	}
	c.mediaLifecycle.Lock()
	session, err := c.startMediaSessionLocked(offer, sink, nil)
	c.mediaLifecycle.Unlock()
	if err != nil && session != nil {
		_ = session.stream.Close()
	}
	return err
}

func (c *Client) startMediaSessionLocked(offer *media.Offer, sink media.Sink, previous *mediaSession) (*mediaSession, error) {
	if c.closed.Load() {
		return nil, net.ErrClosed
	}
	c.access.Lock()
	if c.media != previous || c.mediaStopping {
		c.access.Unlock()
		return nil, E.New("rfb: the media stream is already running")
	}
	var portsPayload []byte
	var ports media.Ports
	if previous != nil {
		portsPayload = append([]byte(nil), previous.portsPayload...)
		ports = previous.ports
	}
	c.access.Unlock()
	remote := c.conn.RemoteAddr().(*net.TCPAddr).AddrPort().Addr()
	local := c.conn.LocalAddr().(*net.TCPAddr).AddrPort().Addr()
	session := &mediaSession{
		offer: offer, sink: sink, starting: true,
		portsPayload: portsPayload, ports: ports, portsSeen: previous != nil, unanswered: previous != nil,
	}
	session.stream = media.NewStream(media.StreamConfig{
		Offer: offer, Remote: remote, Local: local,
		Sink: &mediaSink{Sink: sink, client: c, session: session},
		Restart: func() bool {
			c.access.Lock()
			defer c.access.Unlock()
			if c.media != session || c.virtualDisplay == nil {
				return false
			}
			c.logger.Warn("media stream is silent after the answer, reconfiguring the virtual display")
			return c.reconfigureVirtualDisplayLocked() == nil
		},
	})
	c.access.Lock()
	c.media = session
	restarted := c.mediaStarted
	c.mediaStarted = true
	request, hdr := c.virtualDisplay, c.hdr
	c.access.Unlock()
	var err error
	if previous != nil {
		err = session.stream.HandleRectangle(portsPayload)
		if err == nil {
			c.sizeOfferForDisplay(offer)
			err = c.send(offer.Message())
		}
	} else {
		err = c.SetMediaStream(true)
	}
	if err == nil && previous == nil && restarted && request != nil {
		var message []byte
		message, err = c.displayConfiguration(*request, hdr)
		if err == nil {
			err = c.send(message)
		}
	}
	if err != nil {
		c.access.Lock()
		c.media = nil
		_ = c.releaseDisplayChangesLocked()
		c.access.Unlock()
		session.suppressStopped.Store(true)
		_ = c.SetMediaStream(false)
		return session, err
	}
	return session, nil
}

func (c *Client) StopMediaStream() error {
	c.mediaLifecycle.Lock()
	c.access.Lock()
	session := c.media
	c.media = nil
	_ = c.releaseDisplayChangesLocked()
	c.access.Unlock()
	var err error
	if session != nil {
		err = c.SetMediaStream(false)
	}
	c.mediaLifecycle.Unlock()
	if session != nil {
		_ = session.stream.Close()
		session.notifyStopped(nil)
	}
	return err
}

func (c *Client) MediaStream() *media.Stream {
	c.access.Lock()
	defer c.access.Unlock()
	if c.media == nil {
		return nil
	}
	return c.media.stream
}

func (c *Client) receiveMediaStream() error {
	// ScreenSharing dispatches media subtypes 1/2/3 as ports/answer/error
	// (ScreenSharing 776.27, macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// -[SSSession handleAVCMediaEncoding:] 0x1cc0e47b4..0x1cc0e47cc,
	// 0x1cc0e4fe4..0x1cc0e5024, 0x1cc0e49b8..0x1cc0e49d0, 0x1cc0e4abc..0x1cc0e4ad4).
	length, err := c.stream.u16()
	if err != nil {
		return err
	}
	payload := binary.BigEndian.AppendUint16(make([]byte, 0, 2+int(length)), length)
	payload = payload[:2+int(length)]
	err = c.stream.readFull(payload[2:])
	if err != nil {
		return err
	}
	messageType := uint16(0)
	if len(payload) >= 4 {
		messageType = binary.BigEndian.Uint16(payload[2:])
	}
	var ports *media.Ports
	if messageType == 1 {
		ports, _ = media.ParsePorts(payload)
	}
	c.access.Lock()
	session := c.media
	sendOffer := false
	if session != nil {
		switch messageType {
		case 1:
			session.portsSeen = true
			session.reconfiguring = false
			// ScreenSharing ignores a ports message while mediaStreamMessage1Received is set,
			// and clears the flag on the answer (ScreenSharing 776.27, macOS 27.0.1 26A434,
			// UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50, -[SSSession handleAVCMediaEncoding:]
			// 0x1cc0e48e0..0x1cc0e48e4, 0x1cc0e49d8..0x1cc0e49e8; ScreenSharing 786.3,
			// macOS 27.2 26B5101f, UUID 21DF7A41-0D38-3DF3-B87A-8A6249686735,
			// -[SSSession handleAVCMediaEncoding:] 0x22e527960..0x22e527964,
			// 0x22e527a58..0x22e527a68).
			if session.unanswered {
				if ports == nil || *ports != session.ports {
					session.reoffer = true
				}
			} else {
				session.unanswered, sendOffer = true, true
			}
		case 2, 3:
			if messageType == 2 && !session.portsSeen {
				c.access.Unlock()
				return nil
			}
			session.starting, session.unanswered = false, false
			if session.reoffer {
				session.reoffer, session.unanswered, sendOffer = false, true, true
			} else if messageType == 2 {
				err = c.releaseDisplayChangesLocked()
			}
		}
	}
	c.access.Unlock()
	if err != nil {
		return err
	}
	c.logger.Debug("media stream message", "type", messageType, "bytes", len(payload), "offer", sendOffer)
	if session == nil {
		return nil
	}
	err = session.stream.HandleRectangle(payload)
	if err != nil {
		c.logger.Warn("media stream failed", "error", err)
		return nil
	}
	if messageType == 1 {
		c.access.Lock()
		if c.media == session {
			session.portsPayload = append([]byte(nil), payload...)
			session.ports = *ports
		}
		c.access.Unlock()
	}
	if sendOffer {
		c.sizeOfferForDisplay(session.offer)
		c.access.Lock()
		defer c.access.Unlock()
		if c.media != session {
			return nil
		}
		return c.send(session.offer.Message())
	}
	return nil
}

func (c *Client) sizeOfferForDisplay(offer *media.Offer) {
	// ScreenSharing derives the dimensions it passes to GetHEVCDecoderMaxSupportedFrameRate
	// from each SSScreenInfo.frame size multiplied by UIResolution (ScreenSharing 776.27,
	// macOS 27.0.1 26A434, UUID EC0B2C20-F816-3FCE-822D-F9403C09EB50,
	// -[SSSession stConfigureServerMediaStream] 0x1cc0e9010..0x1cc0e9048,
	// 0x1cc0e91c0..0x1cc0e91f8).
	c.access.Lock()
	layout := c.layout
	c.access.Unlock()
	if layout == nil || len(layout.Displays) == 0 {
		offer.UpdateVideoDimensions(0, 0)
		return
	}
	display := layout.Displays[0]
	if index := slices.IndexFunc(layout.Displays, func(candidate Display) bool {
		return candidate.ID == layout.Current
	}); index >= 0 {
		display = layout.Displays[index]
	}
	offer.UpdateVideoDimensions(int(float64(display.Logical.Width)*display.Density), int(float64(display.Logical.Height)*display.Density))
}
