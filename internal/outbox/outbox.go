package outbox

import (
	"context"
	"net"
	"sync"
	"sync/atomic"
)

const writeChunk = 64 << 10

type Outbox struct {
	access        sync.Mutex
	messages      [][]byte
	waiters       []chan error
	pendingMotion int
	wake          chan struct{}
	closed        bool
	err           error
}

func New() *Outbox {
	return &Outbox{pendingMotion: -1, wake: make(chan struct{}, 1)}
}

func (o *Outbox) PushWait(message []byte) error {
	written := make(chan error, 1)
	o.access.Lock()
	if o.closed {
		err := o.err
		o.access.Unlock()
		if err == nil {
			err = net.ErrClosed
		}
		return err
	}
	o.messages = append(o.messages, message)
	o.waiters = append(o.waiters, written)
	o.pendingMotion = -1
	o.access.Unlock()
	o.signal()
	return <-written
}

func (o *Outbox) Push(message []byte, motion bool) error {
	o.access.Lock()
	if o.closed {
		err := o.err
		o.access.Unlock()
		if err == nil {
			err = net.ErrClosed
		}
		return err
	}
	if motion && o.pendingMotion >= 0 {
		o.messages[o.pendingMotion] = message
	} else {
		o.messages = append(o.messages, message)
		if motion {
			o.pendingMotion = len(o.messages) - 1
		} else {
			o.pendingMotion = -1
		}
	}
	o.access.Unlock()
	o.signal()
	return nil
}

func (o *Outbox) Close(err error) {
	o.access.Lock()
	if !o.closed {
		o.closed = true
		o.err = err
	}
	o.access.Unlock()
	o.signal()
}

func (o *Outbox) Abort(err error) {
	o.access.Lock()
	o.closed, o.err = true, err
	waiters := o.waiters
	o.messages, o.waiters = nil, nil
	o.access.Unlock()
	for _, waiter := range waiters {
		waiter <- err
	}
	o.signal()
}

func (o *Outbox) signal() {
	select {
	case o.wake <- struct{}{}:
	default:
	}
}

func (o *Outbox) take() ([][]byte, []chan error, bool) {
	o.access.Lock()
	defer o.access.Unlock()
	messages, waiters := o.messages, o.waiters
	o.messages, o.waiters = nil, nil
	o.pendingMotion = -1
	return messages, waiters, o.closed
}

type Writer struct {
	conn    net.Conn
	encode  func(wire []byte, data []byte) []byte
	data    []byte
	wire    []byte
	written atomic.Uint64
}

func NewWriter(conn net.Conn) *Writer {
	return &Writer{conn: conn}
}

func (w *Writer) SetEncoder(encode func(wire []byte, data []byte) []byte) {
	w.encode = encode
}

func (w *Writer) Written() uint64 {
	return w.written.Load()
}

func (w *Writer) send(data []byte) error {
	for len(data) > 0 {
		piece := data[:min(len(data), writeChunk)]
		written, err := w.conn.Write(piece)
		w.written.Add(uint64(written))
		data = data[written:]
		if err != nil {
			return err
		}
	}
	return nil
}

type MessageWriter interface {
	WriteMessages(messages [][]byte) (int, error)
}

func (w *Writer) Write(messages [][]byte) error {
	if framed, isFramed := w.conn.(MessageWriter); isFramed && w.encode == nil {
		written, err := framed.WriteMessages(messages)
		w.written.Add(uint64(written))
		return err
	}
	w.data = w.data[:0]
	for _, message := range messages {
		w.data = append(w.data, message...)
	}
	if w.encode == nil {
		return w.send(w.data)
	}
	w.wire = w.encode(w.wire[:0], w.data)
	return w.send(w.wire)
}

func (w *Writer) Run(ctx context.Context, box *Outbox) error {
	stop := context.AfterFunc(ctx, func() {
		box.Abort(context.Cause(ctx))
		w.conn.Close()
	})
	defer stop()
	for range box.wake {
		messages, waiters, closed := box.take()
		var err error
		if ctx.Err() == nil && len(messages) > 0 {
			err = w.Write(messages)
		}
		cause := context.Cause(ctx)
		if cause != nil {
			err = cause
		}
		for _, waiter := range waiters {
			waiter <- err
		}
		if err != nil {
			box.Abort(err)
			if cause != nil {
				return nil
			}
			return err
		}
		if closed {
			return nil
		}
	}
	return nil
}
