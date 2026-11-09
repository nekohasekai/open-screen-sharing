package libsharing

import (
	"context"
	"errors"
	"io/fs"
	"net"
	"strings"
	"sync/atomic"
	"time"

	"github.com/nekohasekai/open-screen-sharing/internal/rfb/filecopy"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	FileErrorNone        int32 = 0
	FileErrorCancelled   int32 = 1
	FileErrorNotAllowed  int32 = 2
	FileErrorNotFound    int32 = 3
	FileErrorNotWritable int32 = 4
	FileErrorDiskFull    int32 = 5
	FileErrorPermission  int32 = 6
	FileErrorLocal       int32 = 7
	FileErrorConnection  int32 = 8
	FileErrorFailed      int32 = 9
	FileErrorObserveOnly int32 = 10
	FileErrorUnsupported int32 = 11
	defaultUploadFolder        = "~/Desktop"
)

func classifyFileError(err error) int32 {
	var remote *filecopy.RemoteError
	var source *filecopy.SourceError
	var pathError *fs.PathError
	switch {
	case err == nil:
		return FileErrorNone
	case E.IsMulti(err, filecopy.ErrCanceled, context.Canceled):
		return FileErrorCancelled
	case errors.Is(err, filecopy.ErrObserveOnly):
		return FileErrorObserveOnly
	case errors.Is(err, filecopy.ErrUnsupported):
		return FileErrorUnsupported
	case errors.As(err, &remote):
		switch remote.Status {
		case 1:
			return FileErrorNotAllowed
		case 2:
			return FileErrorNotFound
		case 3:
			return FileErrorNotWritable
		case 4:
			return FileErrorDiskFull
		}
		return FileErrorFailed
	case errors.As(err, &source):
		switch {
		case errors.Is(err, fs.ErrNotExist):
			return FileErrorNotFound
		case errors.Is(err, fs.ErrPermission):
			return FileErrorPermission
		}
		return FileErrorFailed
	case errors.As(err, &pathError):
		return FileErrorLocal
	case errors.Is(err, net.ErrClosed):
		return FileErrorConnection
	}
	var networkError net.Error
	if errors.As(err, &networkError) {
		return FileErrorConnection
	}
	return FileErrorFailed
}

type FileTransfer struct {
	transfer *filecopy.Transfer
	cancel   context.CancelFunc
}

func (t *FileTransfer) TransferID() int64 {
	return int64(t.transfer.ID())
}

func (t *FileTransfer) Cancel() error {
	go t.transfer.Cancel()
	t.cancel()
	return nil
}

func (s *Session) UploadFile(localPath string, remoteFolder string) (*FileTransfer, error) {
	client, err := s.connected()
	if err != nil {
		return nil, err
	}
	if remoteFolder == "" {
		remoteFolder = defaultUploadFolder
	}
	ctx, cancel := context.WithCancel(context.Background())
	transferID := new(atomic.Int64)
	transfer, err := client.FileCopy().Upload(ctx, filecopy.PathSource(localPath), remoteFolder, s.fileProgress(transferID))
	return s.startTransfer(transfer, transferID, cancel, err)
}

func (s *Session) DownloadFile(remotePath string, localFolder string) (*FileTransfer, error) {
	client, err := s.connected()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	transferID := new(atomic.Int64)
	transfer, err := client.FileCopy().Download(ctx, remotePath, localFolder, s.fileProgress(transferID))
	return s.startTransfer(transfer, transferID, cancel, err)
}

const downloadFilesIdleTimeout = 30 * time.Second

func (s *Session) DownloadFiles(remotePaths string, localFolder string) error {
	client, err := s.connected()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithCancelCause(context.Background())
	defer cancel(nil)
	idle := time.AfterFunc(downloadFilesIdleTimeout, func() {
		cancel(E.New("no data for ", downloadFilesIdleTimeout))
	})
	defer idle.Stop()
	for _, source := range strings.Split(remotePaths, "\n") {
		var transfer *filecopy.Transfer
		transfer, err = client.FileCopy().Download(ctx, source, localFolder, func(filecopy.Progress) {
			idle.Reset(downloadFilesIdleTimeout)
		})
		if err == nil {
			_, err = transfer.Wait(context.Background())
		}
		if err != nil {
			return E.Cause(E.Errors(context.Cause(ctx), err), "download ", source)
		}
	}
	return nil
}

func (s *Session) fileProgress(transferID *atomic.Int64) func(filecopy.Progress) {
	return func(progress filecopy.Progress) {
		id := transferID.Load()
		if id != 0 {
			s.handler.OnFileTransferProgress(id, progress.Transferred, progress.Total)
		}
	}
}

func (s *Session) startTransfer(transfer *filecopy.Transfer, transferID *atomic.Int64, cancel context.CancelFunc, err error) (*FileTransfer, error) {
	if err != nil {
		cancel()
		return nil, err
	}
	transferID.Store(int64(transfer.ID()))
	go func() {
		result, waitErr := transfer.Wait(context.Background())
		cancel()
		message := ""
		if waitErr != nil {
			message = waitErr.Error()
		}
		s.handler.OnFileTransferFinished(int64(transfer.ID()), result.Name, classifyFileError(waitErr), message)
	}()
	return &FileTransfer{transfer: transfer, cancel: cancel}, nil
}
