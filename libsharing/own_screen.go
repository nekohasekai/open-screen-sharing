package libsharing

import (
	"context"
	"net"
	"net/netip"
	"os"
	"runtime"
	"slices"

	"howett.net/plist"
)

const macScreenSharingPort = 5900

type ownScreenError struct {
	address netip.Addr
}

func (e *ownScreenError) Error() string {
	return e.address.String() + " is an address of this device"
}

// launchd keeps the enabled state of system daemons in this world-readable override file;
// com.apple.screensharing defaults to disabled and is listed as false once Screen Sharing
// is turned on (observed on Mac16,8, macOS 27.2 26B5101f).
func macScreenSharingEnabled() bool {
	content, err := os.ReadFile("/private/var/db/com.apple.xpc.launchd/disabled.plist")
	if err != nil {
		return true
	}
	var disabled map[string]bool
	_, err = plist.Unmarshal(content, &disabled)
	if err != nil {
		return true
	}
	value, overridden := disabled["com.apple.screensharing"]
	return overridden && !value
}

func checkOwnScreen(ctx context.Context, host string, port int32) error {
	if runtime.GOOS != "darwin" || port != macScreenSharingPort || !macScreenSharingEnabled() {
		return nil
	}
	targets, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return err
	}
	locals, _ := net.InterfaceAddrs()
	for _, target := range targets {
		target = target.WithZone("").Unmap()
		if target.IsLoopback() || target.IsUnspecified() || slices.ContainsFunc(locals, func(local net.Addr) bool {
			prefix, isPrefix := local.(*net.IPNet)
			return isPrefix && prefix.IP.Equal(target.AsSlice())
		}) {
			return &ownScreenError{address: target}
		}
	}
	return nil
}
