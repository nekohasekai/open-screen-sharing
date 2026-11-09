package filecopy

import (
	"context"
	"io"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

type Source struct {
	fsys fs.FS
	name string
}

func PathSource(localPath string) Source {
	absolute, err := filepath.Abs(localPath)
	if err != nil {
		absolute = localPath
	}
	return Source{fsys: os.DirFS(filepath.Dir(absolute)), name: filepath.Base(absolute)}
}

type plannedItem struct {
	sourcePath  string
	displayPath string
	wire        wireItem
}

type remoteResult struct {
	status int16
	name   string
}

type upload struct {
	transfer    *Transfer
	source      Source
	destination string
	items       []plannedItem
	info        totals
	remote      chan remoteResult
	buffer      []byte
}

func (m *Manager) Upload(ctx context.Context, source Source, destination string, onProgress func(Progress)) (*Transfer, error) {
	if destination == "" || !(strings.HasPrefix(destination, "/") || strings.HasPrefix(destination, "~")) {
		return nil, E.New("filecopy: destination ", strconv.Quote(destination), " is not an absolute path")
	}
	job := &upload{source: source, destination: destination, remote: make(chan remoteResult, 1)}
	err := job.plan()
	if err != nil {
		return nil, err
	}
	transfer := newTransfer(m, ToMac, onProgress)
	transfer.handler = job
	transfer.progress.Total = int64(job.info.logical)
	job.transfer = transfer
	err = m.register(transfer)
	if err != nil {
		return nil, err
	}
	go transfer.notifyProgress()
	go job.run(ctx)
	return transfer, nil
}

func (u *upload) plan() error {
	if !fs.ValidPath(u.source.name) || u.source.name == "." {
		return E.New("filecopy: invalid source ", strconv.Quote(u.source.name))
	}
	root := u.source.name
	rootName := path.Base(root)
	err := fs.WalkDir(u.source.fsys, root, func(name string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative := strings.TrimPrefix(strings.TrimPrefix(name, root), "/")
		displayPath := rootName
		level := 0
		if relative != "" {
			displayPath = rootName + "/" + relative
			level = strings.Count(relative, "/") + 1
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		switch {
		case entry.IsDir():
			u.addItem(name, displayPath, wireItem{
				itemType:  itemTypeFolder,
				nodeFlags: nodeFlagDirectory,
				level:     uint16(level),
				mode:      posixMode(info.Mode(), itemTypeFolder),
				name:      carbonName(path.Base(displayPath)),
			}, info.ModTime())
			u.info.folders++
		case entry.Type()&fs.ModeSymlink != 0:
			var target string
			target, err = fs.ReadLink(u.source.fsys, name)
			if err != nil {
				return err
			}
			u.addItem(name, displayPath, wireItem{
				itemType:   itemTypeLink,
				level:      uint16(level),
				mode:       posixMode(info.Mode(), itemTypeLink),
				dataSize:   uint64(len(target)),
				name:       path.Base(displayPath),
				linkTarget: target,
			}, info.ModTime())
			u.info.files++
		case info.Mode().IsRegular():
			size := uint64(info.Size())
			u.addItem(name, displayPath, wireItem{
				itemType: itemTypeFile,
				level:    uint16(level),
				mode:     posixMode(info.Mode(), itemTypeFile),
				dataSize: size,
				name:     carbonName(path.Base(displayPath)),
			}, info.ModTime())
			u.info.files++
			u.info.logical += size
			u.info.physical += (size + 4095) &^ 4095
		}
		return nil
	})
	if err != nil {
		return err
	}
	if len(u.items) == 0 {
		return E.New("filecopy: ", strconv.Quote(root), " is not a file, folder or link")
	}
	u.info.singleFile = u.items[0].wire.itemType != itemTypeFolder
	return nil
}

func (u *upload) addItem(sourcePath, displayPath string, item wireItem, modified time.Time) {
	if modified.IsZero() {
		modified = time.Now()
	}
	item.created = modified
	item.modified = modified
	item.attributesModified = modified
	item.accessed = modified
	u.items = append(u.items, plannedItem{sourcePath: sourcePath, displayPath: displayPath, wire: item})
}

func carbonName(name string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case ':':
			return '/'
		case '/':
			return ':'
		default:
			return r
		}
	}, name)
}

func (u *upload) run(ctx context.Context) {
	stop := context.AfterFunc(ctx, func() {
		u.transfer.Cancel()
	})
	result, err := u.send()
	stop()
	u.transfer.finish(result, err)
}

func (u *upload) send() (Result, error) {
	conn := u.transfer.manager.conn
	session := u.transfer.session
	err := u.transfer.start(startMessage(commandStartReceive, session, 0, u.destination))
	if err != nil {
		return Result{}, err
	}
	err = conn.SendMessage(itemInfoMessage(session, u.info))
	if err != nil {
		return Result{}, err
	}
	for i := range u.items {
		item := &u.items[i]
		err = u.checkpoint()
		if err != nil {
			return Result{}, err
		}
		u.transfer.update(func(progress *Progress) {
			progress.Item = item.displayPath
		})
		var message []byte
		message, err = newItemMessage(session, &item.wire)
		if err != nil {
			return Result{}, u.fail(err)
		}
		err = conn.SendMessage(message)
		if err != nil {
			return Result{}, err
		}
		if item.wire.itemType == itemTypeFile && item.wire.dataSize > 0 {
			err = u.sendContents(item)
			if err != nil {
				return Result{}, err
			}
		}
	}
	err = conn.SendMessage(endMessage(session, 0))
	if err != nil {
		return Result{}, err
	}
	select {
	case remote := <-u.remote:
		return remote.result()
	case <-u.transfer.stop:
		return Result{}, u.transfer.stopError
	}
}

func (u *upload) sendContents(item *plannedItem) error {
	reader, err := u.source.fsys.Open(item.sourcePath)
	if err != nil {
		return u.fail(err)
	}
	defer reader.Close()
	if u.buffer == nil {
		u.buffer = make([]byte, chunkSize)
	}
	remaining := int64(item.wire.dataSize)
	for remaining > 0 {
		err := u.checkpoint()
		if err != nil {
			return err
		}
		chunk := u.buffer[:min(remaining, chunkSize)]
		_, err = io.ReadFull(reader, chunk)
		if E.IsMulti(err, io.EOF, io.ErrUnexpectedEOF) {
			err = E.New("filecopy: ", item.displayPath, " is shorter than ", item.wire.dataSize, " bytes")
		}
		if err != nil {
			return u.fail(err)
		}
		err = u.transfer.manager.conn.SendMessage(dataMessage(u.transfer.session, chunk))
		if err != nil {
			return err
		}
		remaining -= int64(len(chunk))
		u.transfer.update(func(progress *Progress) {
			progress.Transferred += int64(len(chunk))
		})
	}
	return nil
}

func (u *upload) checkpoint() error {
	err := u.transfer.waitWhilePaused()
	if err != nil {
		return err
	}
	select {
	case remote := <-u.remote:
		_, err = remote.result()
		if err == nil {
			err = E.New("filecopy: the Mac finished before the upload did")
		}
		return err
	default:
		return nil
	}
}

func (u *upload) fail(err error) error {
	u.transfer.manager.conn.SendMessage(endMessage(u.transfer.session, 1))
	return err
}

func (r remoteResult) result() (Result, error) {
	if r.status != 0 {
		return Result{Name: r.name}, &RemoteError{Status: r.status}
	}
	return Result{Name: r.name}, nil
}

func (u *upload) handle(command uint16, body []byte) {
	if command != commandReceiverDone {
		return
	}
	status, name, err := parseReceiverDone(body)
	if err != nil {
		status = -1
	}
	select {
	case u.remote <- remoteResult{status: status, name: name}:
	default:
	}
}
