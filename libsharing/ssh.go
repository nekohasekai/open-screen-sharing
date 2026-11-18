package libsharing

import (
	"context"
	"net"
	"slices"
	"strconv"
	"strings"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
	"golang.org/x/crypto/ssh"
)

const (
	sshPort              = 22
	sshTimeout           = 15 * time.Second
	sshDisconnectCommand = "killall SSMenuAgent"
)

func (r *ProbeRequest) CloseExistingConnectionOverSSH(host, username, password string) error {
	if username == "" {
		return E.New("ssh: account user name is required")
	}
	ctx, cancel := context.WithTimeout(r.ctx, sshTimeout)
	defer cancel()
	address := net.JoinHostPort(host, strconv.Itoa(sshPort))
	err := disconnectOverSSH(ctx, address, username, password, sshDisconnectCommand)
	if err != nil {
		if r.ctx.Err() != nil {
			return r.ctx.Err()
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return err
	}
	return nil
}

func disconnectOverSSH(ctx context.Context, address, username, password, command string) error {
	conn, err := new(net.Dialer).DialContext(ctx, "tcp", address)
	if err != nil {
		return err
	}
	stop := context.AfterFunc(ctx, func() {
		_ = conn.Close()
	})
	defer func() {
		stop()
		_ = conn.Close()
	}()
	config := &ssh.ClientConfig{
		User: username,
		Auth: []ssh.AuthMethod{
			ssh.Password(password),
			ssh.KeyboardInteractive(func(name, instruction string, questions []string, echos []bool) ([]string, error) {
				return slices.Repeat([]string{password}, len(questions)), nil
			}),
		},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         sshTimeout,
	}
	sshConn, channels, requests, err := ssh.NewClientConn(conn, address, config)
	if err != nil {
		return err
	}
	client := ssh.NewClient(sshConn, channels, requests)
	defer client.Close()
	session, err := client.NewSession()
	if err != nil {
		return err
	}
	defer session.Close()
	// Terminating SSMenuAgent (com.apple.screensharing.menuextra) closes its XPC connection
	// from ScreensharingAgent (MenuExtraHelper), whose interruption handler invokes
	// SSDaemon_DisconnectViewersSilently_rpc to drop active viewers and release UDP media
	// streaming without root privileges (ScreensharingAgent 27.0.1 26A434 / 27.2.1 26B131).
	output, err := session.CombinedOutput(command)
	if err != nil {
		text := strings.TrimSpace(string(output))
		if text != "" {
			return E.Cause(err, text)
		}
		return err
	}
	return nil
}
