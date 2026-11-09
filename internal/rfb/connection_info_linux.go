package rfb

import (
	"net"
	"time"

	"golang.org/x/sys/unix"
)

func tcpConnectionInfo(conn *net.TCPConn) (ConnectionInfo, bool) {
	raw, err := conn.SyscallConn()
	if err != nil {
		return ConnectionInfo{}, false
	}
	var info *unix.TCPInfo
	controlErr := raw.Control(func(fd uintptr) {
		info, err = unix.GetsockoptTCPInfo(int(fd), unix.IPPROTO_TCP, unix.TCP_INFO)
	})
	if controlErr != nil || err != nil {
		return ConnectionInfo{}, false
	}
	return ConnectionInfo{RoundTrip: time.Duration(info.Rtt) * time.Microsecond, ReceivedBytes: info.Bytes_received}, true
}
