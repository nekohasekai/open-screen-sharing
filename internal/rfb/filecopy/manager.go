package filecopy

import (
	"context"
	"encoding/binary"
	"io"
	"maps"
	"net"
	"slices"
	"sync"

	E "github.com/sagernet/sing/common/exceptions"
)

var (
	ErrUnsupported = E.New("filecopy: the Mac does not accept file copy")
	ErrObserveOnly = E.New("filecopy: the Mac only allows observing")
	ErrCanceled    = E.New("filecopy: transfer canceled")
)

type Conn interface {
	SendMessage(message []byte) error
	SetDownloading(active bool) error
}

type Capabilities struct {
	FileCopy    bool
	ObserveOnly bool
}

type Manager struct {
	conn         Conn
	capabilities Capabilities

	access      sync.Mutex
	nextSession uint32
	transfers   map[uint32]*Transfer
	senders     map[uint32]*Transfer
	drained     chan struct{}
	downloads   int
	closeError  error

	pacingAccess sync.Mutex
	pacing       bool
}

func NewManager(conn Conn, capabilities Capabilities) *Manager {
	return &Manager{conn: conn, capabilities: capabilities, transfers: make(map[uint32]*Transfer), senders: make(map[uint32]*Transfer)}
}

func (m *Manager) ReadMessage(r io.Reader) error {
	message := make([]byte, 6, 64)
	message[0] = MessageType
	_, err := io.ReadFull(r, message[1:6])
	if err != nil {
		return err
	}
	size := binary.BigEndian.Uint32(message[2:])
	if size < headerSize-6 || size > maximumServerMessageSize {
		return E.New("filecopy: server message size ", size, " is not between ", headerSize-6, " and ", maximumServerMessageSize)
	}
	message = slices.Grow(message, int(size))[:6+int(size)]
	_, err = io.ReadFull(r, message[6:])
	if err != nil {
		return err
	}
	command, session := binary.BigEndian.Uint16(message[8:]), binary.BigEndian.Uint32(message[10:])
	m.access.Lock()
	transfer, sender := m.transfers[session], m.senders[session]
	m.access.Unlock()
	if sender != nil && (command == commandEnd || command == commandReceiverDone) {
		m.removeSender(sender)
	}
	if transfer != nil {
		transfer.handler.handle(command, message[headerSize:])
	}
	return nil
}

func (m *Manager) Quiesce(ctx context.Context) error {
	m.access.Lock()
	if m.closeError == nil {
		m.closeError = net.ErrClosed
	}
	idle, drained := len(m.senders) == 0, m.drained
	m.access.Unlock()
	if idle {
		return nil
	}
	select {
	case <-drained:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (m *Manager) Close(err error) {
	m.access.Lock()
	if m.closeError == nil {
		m.closeError = err
	}
	transfers := slices.Collect(maps.Values(m.transfers))
	senders := slices.Collect(maps.Values(m.senders))
	m.access.Unlock()
	for _, transfer := range transfers {
		transfer.halt(err)
	}
	for _, sender := range senders {
		sender.stopRemote()
	}
}

func (m *Manager) register(transfer *Transfer) error {
	m.access.Lock()
	defer m.access.Unlock()
	if m.closeError != nil {
		return m.closeError
	}
	if !m.capabilities.FileCopy {
		return ErrUnsupported
	}
	if m.capabilities.ObserveOnly {
		return ErrObserveOnly
	}
	m.nextSession++
	for m.nextSession == 0 || m.transfers[m.nextSession] != nil || m.senders[m.nextSession] != nil {
		m.nextSession++
	}
	transfer.session = m.nextSession
	m.transfers[transfer.session] = transfer
	if transfer.direction == FromMac {
		m.downloads++
	}
	return nil
}

func (m *Manager) unregister(transfer *Transfer) {
	m.access.Lock()
	if m.transfers[transfer.session] == transfer {
		delete(m.transfers, transfer.session)
		if transfer.direction == FromMac {
			m.downloads--
		}
	}
	m.access.Unlock()
	if transfer.direction == FromMac {
		m.updatePacing()
	}
}

func (m *Manager) addSender(transfer *Transfer) error {
	m.access.Lock()
	defer m.access.Unlock()
	if m.closeError != nil {
		return m.closeError
	}
	if len(m.senders) == 0 {
		m.drained = make(chan struct{})
	}
	m.senders[transfer.session] = transfer
	return nil
}

func (m *Manager) removeSender(transfer *Transfer) {
	m.access.Lock()
	defer m.access.Unlock()
	if m.senders[transfer.session] != transfer {
		return
	}
	delete(m.senders, transfer.session)
	if len(m.senders) == 0 {
		close(m.drained)
	}
}

func (m *Manager) updatePacing() {
	m.pacingAccess.Lock()
	defer m.pacingAccess.Unlock()
	m.access.Lock()
	active := m.downloads > 0
	m.access.Unlock()
	if active == m.pacing {
		return
	}
	m.pacing = active
	m.conn.SetDownloading(active)
}
