package media

import (
	"context"
	"net"
	"net/netip"
	"syscall"

	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"golang.org/x/sys/unix"
)

// ScreensharingAgent writes the same UDP port into both endpoint sockaddrs
// of each media socket before it binds and connects the socket
// (ScreensharingAgent 756.36.5.2, macOS 27.0.1 26A434, UUID
// DA1CD9BA-97A1-3758-B425-00FA34B428A3,
// -[SSUDPSender sendToRemoteAddress:srcAddress:startingUDPPort:audioEncryptionKeyViewerToServer:audioEncryptionKeyServerToViewer:video1EncryptionKeyViewerToServer:video1EncryptionKeyServerToViewer:video2EncryptionKeyViewerToServer:video2EncryptionKeyServerToViewer:sessionID:supports60FPS:sendCursor:avcClientName:]
// 0x100008f50..0x100008f58, 0x1000092c8..0x1000092d8, 0x100009534..0x100009544,
// sub_100009aa8 0x100009b2c..0x100009b4c; ScreensharingAgent 766.8,
// macOS 27.2 26B5101f, UUID D8791A89-6EDB-3A0A-A720-EC3F5FB1AD4F,
// -[SSUDPSender sendToRemoteAddress:srcAddress:startingUDPPort:audioEncryptionKeyViewerToServer:audioEncryptionKeyServerToViewer:video1EncryptionKeyViewerToServer:video1EncryptionKeyServerToViewer:video2EncryptionKeyViewerToServer:video2EncryptionKeyServerToViewer:sessionID:supports60FPS:sendCursor:avcClientName:]
// 0x100008f50..0x100008f58, 0x1000092c8..0x1000092d8, 0x100009534..0x100009544,
// sub_100009aa8 0x100009b2c..0x100009b4c).
func dialLeg(local netip.Addr, remote netip.AddrPort, receiveBuffer int) (*net.UDPConn, error) {
	dialer := net.Dialer{
		LocalAddr: net.UDPAddrFromAddrPort(netip.AddrPortFrom(local, remote.Port())),
		Control: func(network string, address string, raw syscall.RawConn) error {
			var optionErr error
			err := raw.Control(func(fd uintptr) {
				optionErr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_REUSEADDR, 1)
				if optionErr == nil {
					optionErr = unix.SetsockoptInt(int(fd), unix.SOL_SOCKET, unix.SO_REUSEPORT, 1)
				}
			})
			return E.Errors(err, optionErr)
		},
	}
	conn, err := dialer.DialContext(context.Background(), "udp", remote.String())
	if err != nil {
		return nil, err
	}
	udpConn := conn.(*net.UDPConn)
	_ = udpConn.SetReadBuffer(receiveBuffer)
	return udpConn, nil
}

func connectLeg(conn *net.UDPConn, address netip.AddrPort) (N.ConnectedPacketBatchWriter, error) {
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return nil, err
	}
	var connectErr error
	err = rawConn.Control(func(fd uintptr) {
		var local unix.Sockaddr
		local, connectErr = unix.Getsockname(int(fd))
		if connectErr != nil {
			return
		}
		_, localInet6 := local.(*unix.SockaddrInet6)
		var remote unix.Sockaddr
		switch {
		case localInet6:
			inet6 := &unix.SockaddrInet6{Port: int(address.Port()), Addr: address.Addr().As16()}
			if zone := address.Addr().Zone(); zone != "" {
				var networkInterface *net.Interface
				networkInterface, connectErr = net.InterfaceByName(zone)
				if connectErr != nil {
					return
				}
				inet6.ZoneId = uint32(networkInterface.Index)
			}
			remote = inet6
		case address.Addr().Unmap().Is4():
			remote = &unix.SockaddrInet4{Port: int(address.Port()), Addr: address.Addr().Unmap().As4()}
		default:
			connectErr = E.New("media: an IPv4 socket cannot connect to ", address)
			return
		}
		connectErr = unix.Connect(int(fd), remote)
	})
	err = E.Errors(err, connectErr)
	if err != nil {
		return nil, err
	}
	writer, created := bufio.CreateConnectedPacketBatchWriter(bufio.NewUnbindPacketConnWithAddr(conn, M.SocksaddrFromNetIP(address)))
	if !created {
		return nil, E.New("media: batched UDP writes are unsupported on this platform")
	}
	return writer, nil
}
