package media

import (
	"context"
	"net"
	"net/netip"
	"syscall"

	E "github.com/sagernet/sing/common/exceptions"
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
