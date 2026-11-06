package libsharing

import (
	"github.com/nekohasekai/open-screen-sharing/internal/rfb"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	GesturePhaseBegan     int32 = 1
	GesturePhaseChanged   int32 = 2
	GesturePhaseEnded     int32 = 4
	GesturePhaseCancelled int32 = 8

	GesturePhaseMayBegin int32 = 0x80
)

const (
	SystemCommandMissionControl int32 = 1
	SystemCommandAppWindows     int32 = 2
	SystemCommandShowDesktop    int32 = 3
	SystemCommandLaunchpad      int32 = 4
)

var systemCommands = map[int32]rfb.SystemCommand{
	SystemCommandMissionControl: rfb.SystemCommandMissionControl,
	SystemCommandAppWindows:     rfb.SystemCommandAppWindows,
	SystemCommandShowDesktop:    rfb.SystemCommandShowDesktop,
	SystemCommandLaunchpad:      rfb.SystemCommandLaunchpad,
}

func gesturePhase(phase int32) (rfb.GesturePhase, error) {
	switch phase {
	case GesturePhaseBegan, GesturePhaseChanged, GesturePhaseEnded, GesturePhaseCancelled, GesturePhaseMayBegin:
		return rfb.GesturePhase(phase), nil
	default:
		return 0, E.New("unknown gesture phase ", phase)
	}
}

func (s *Session) SendMagnify(x int32, y int32, magnification float64, phase int32) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	gesture, err := gesturePhase(phase)
	if err != nil {
		return err
	}
	return client.SendMagnify(int(x), int(y), magnification, gesture)
}

func (s *Session) SendRotate(x int32, y int32, degrees float64, phase int32) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	gesture, err := gesturePhase(phase)
	if err != nil {
		return err
	}
	return client.SendRotate(int(x), int(y), degrees, gesture)
}

func (s *Session) SendScrollPhase(x int32, y int32, deltaX float64, deltaY float64, phase int32) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	gesture, err := gesturePhase(phase)
	if err != nil {
		return err
	}
	return client.SendScrollPhase(int(x), int(y), deltaX, deltaY, gesture)
}

func (s *Session) StartScrollMomentum(x int32, y int32, velocityX float64, velocityY float64) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.StartScrollMomentum(int(x), int(y), velocityX, velocityY)
}

func (s *Session) StopScrollMomentum() {
	client, err := s.connected()
	if err == nil {
		client.StopScrollMomentum()
	}
}

func (s *Session) SendSystemCommand(command int32) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	systemCommand, found := systemCommands[command]
	if !found {
		return E.New("unknown system command ", command)
	}
	return client.SendSystemCommand(systemCommand)
}

func (s *Session) SetAssistCursor(x int32, y int32, pressed bool) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.SetAssistCursor(int(x), int(y), rfb.AssistPointerHighlight, pressed)
}

func (s *Session) HideAssistCursor() error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	return client.HideAssistCursor()
}

func (i *ServerInfo) setSystemCommands(commands []rfb.SystemCommand) {
	for _, command := range commands {
		switch command {
		case rfb.SystemCommandMissionControl:
			i.SupportsMissionControl = true
		case rfb.SystemCommandAppWindows:
			i.SupportsAppWindows = true
		case rfb.SystemCommandShowDesktop:
			i.SupportsShowDesktop = true
		case rfb.SystemCommandLaunchpad:
			i.SupportsLaunchpad = true
		}
	}
}

func (e *sessionEvents) SystemCommandsChanged([]rfb.SystemCommand) {
	connected, err := e.session.connectedRemote()
	if err != nil {
		return
	}
	e.session.reportServerInfo(connected)
}
