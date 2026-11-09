package filecopy

import (
	"context"
	"fmt"
	"io/fs"
	"sync"
	"time"
)

type Direction int

const (
	ToMac Direction = iota
	FromMac
)

type Progress struct {
	Total       int64
	Transferred int64
	Item        string
	Paused      bool
}

type Result struct {
	Name string
}

type RemoteError struct {
	Status int16
}

func (e *RemoteError) Error() string {
	switch e.Status {
	case 1:
		return "filecopy: the Mac does not allow file transfer"
	case 2:
		return "filecopy: the item is not available"
	case 3:
		return "filecopy: the Mac cannot write to the destination"
	case 4:
		return "filecopy: the Mac's disk is full"
	default:
		return fmt.Sprint("filecopy: the Mac failed the transfer (status ", e.Status, ")")
	}
}

type SourceError struct {
	Status int32
}

func (e *SourceError) Error() string {
	return fmt.Sprint("filecopy: the Mac could not read the item (OSStatus ", e.Status, ")")
}

func (e *SourceError) Unwrap() error {
	switch e.Status {
	case -43, -35, -120:
		return fs.ErrNotExist
	case -54, -61, -5000:
		return fs.ErrPermission
	default:
		return nil
	}
}

type Transfer struct {
	manager    *Manager
	session    uint32
	direction  Direction
	onProgress func(Progress)

	startAccess sync.Mutex
	started     bool
	stopSent    bool

	access     sync.Mutex
	progress   Progress
	wake       chan struct{}
	stopOnce   sync.Once
	stop       chan struct{}
	stopError  error
	doneResult Result
	doneError  error
	done       chan struct{}

	notify       chan struct{}
	finishing    chan struct{}
	notifierDone chan struct{}

	handler interface {
		handle(command uint16, body []byte)
	}
}

func newTransfer(manager *Manager, direction Direction, onProgress func(Progress)) *Transfer {
	transfer := &Transfer{
		manager:      manager,
		direction:    direction,
		onProgress:   onProgress,
		wake:         make(chan struct{}),
		stop:         make(chan struct{}),
		done:         make(chan struct{}),
		notify:       make(chan struct{}, 1),
		finishing:    make(chan struct{}),
		notifierDone: make(chan struct{}),
	}
	return transfer
}

func (t *Transfer) ID() uint32 {
	return t.session
}

func (t *Transfer) Progress() Progress {
	t.access.Lock()
	defer t.access.Unlock()
	return t.progress
}

func (t *Transfer) Pause() error {
	t.access.Lock()
	if t.progress.Paused || t.stopped() {
		t.access.Unlock()
		return nil
	}
	t.progress.Paused = true
	t.access.Unlock()
	t.report()
	return t.manager.conn.SendMessage(sessionStateMessage(commandPause, t.session))
}

func (t *Transfer) Resume() error {
	t.access.Lock()
	if !t.progress.Paused || t.stopped() {
		t.access.Unlock()
		return nil
	}
	t.progress.Paused = false
	close(t.wake)
	t.wake = make(chan struct{})
	t.access.Unlock()
	t.report()
	return t.manager.conn.SendMessage(sessionStateMessage(commandContinue, t.session))
}

func (t *Transfer) Cancel() error {
	if !t.halt(ErrCanceled) {
		return nil
	}
	return t.stopRemote()
}

func (t *Transfer) Wait(ctx context.Context) (Result, error) {
	select {
	case <-t.done:
		return t.doneResult, t.doneError
	case <-ctx.Done():
		return Result{}, ctx.Err()
	}
}

func (t *Transfer) start(message []byte) error {
	t.startAccess.Lock()
	defer t.startAccess.Unlock()
	if t.stopped() {
		return t.stopError
	}
	err := t.manager.addSender(t)
	if err != nil {
		return err
	}
	err = t.manager.conn.SendMessage(message)
	if err != nil {
		t.manager.removeSender(t)
		return err
	}
	t.started = true
	return nil
}

// screensharingd sends nothing back for Stop: it marks the session state 5 and shuts its tool
// down, and a sender already waiting on the viewer lock still forwards one record before it exits
// (screensharingd 756.36.5.2, macOS 27.0.1 26A434, ServerProcessFileCopyCmd 0x100061554,
// FileCopy_SetSessionState 0x10006075c, ServerFileSenderThread 0x100062bac).
const stopGrace = 500 * time.Millisecond

func (t *Transfer) stopRemote() error {
	t.startAccess.Lock()
	defer t.startAccess.Unlock()
	if !t.started || t.stopSent {
		return nil
	}
	t.stopSent = true
	err := t.manager.conn.SendMessage(sessionStateMessage(commandStop, t.session))
	if err != nil {
		t.manager.removeSender(t)
		return err
	}
	time.AfterFunc(stopGrace, func() {
		t.manager.removeSender(t)
	})
	return nil
}

func (t *Transfer) halt(err error) bool {
	stopped := false
	t.stopOnce.Do(func() {
		t.stopError = err
		close(t.stop)
		stopped = true
	})
	return stopped
}

func (t *Transfer) stopped() bool {
	select {
	case <-t.stop:
		return true
	default:
		return false
	}
}

func (t *Transfer) waitWhilePaused() error {
	for {
		t.access.Lock()
		paused, wake := t.progress.Paused, t.wake
		t.access.Unlock()
		select {
		case <-t.stop:
			return t.stopError
		default:
		}
		if !paused {
			return nil
		}
		select {
		case <-wake:
		case <-t.stop:
			return t.stopError
		}
	}
}

func (t *Transfer) update(fn func(progress *Progress)) {
	t.access.Lock()
	fn(&t.progress)
	t.access.Unlock()
	t.report()
}

func (t *Transfer) report() {
	select {
	case t.notify <- struct{}{}:
	default:
	}
}

func (t *Transfer) notifyProgress() {
	defer close(t.notifierDone)
	for {
		select {
		case <-t.notify:
		case <-t.finishing:
			select {
			case <-t.notify:
				t.onProgress(t.Progress())
			default:
			}
			return
		}
		t.onProgress(t.Progress())
	}
}

func (t *Transfer) finish(result Result, err error) {
	t.halt(err)
	t.manager.unregister(t)
	close(t.finishing)
	<-t.notifierDone
	t.doneResult, t.doneError = result, err
	close(t.done)
}
