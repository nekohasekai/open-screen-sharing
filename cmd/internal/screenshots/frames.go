package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	_ "embed"
	"encoding/hex"
	"errors"
	"fmt"
	"image/png"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	E "github.com/sagernet/sing/common/exceptions"
)

//go:embed desktop.js
var macDesktop string

const (
	stagingTimeout = 5 * time.Minute
	remoteDisplay  = "/private/tmp/screenshots-display"
	remoteFrame    = "/private/tmp/screenshots-frame.png"
)

type macRemote struct {
	vm        string
	ssh       string
	capturing sync.Mutex
	prepared  bool
	startedVM bool
}

func (r *macRemote) frame(width, height, density int, appearance string) ([]byte, error) {
	if density != 2 {
		return nil, E.New("remote frames are captured at 2 pixels per point, not ", density)
	}
	r.capturing.Lock()
	defer r.capturing.Unlock()
	if !r.prepared {
		err := r.prepare()
		if err != nil {
			return nil, err
		}
		r.prepared = true
	}
	display := exec.Command("ssh", "-o", "BatchMode=yes", r.ssh, remoteDisplay, strconv.Itoa(width), strconv.Itoa(height))
	display.Stderr = os.Stderr
	closing, err := display.StdinPipe()
	if err != nil {
		return nil, err
	}
	replies, err := display.StdoutPipe()
	if err != nil {
		return nil, err
	}
	err = display.Start()
	if err != nil {
		return nil, E.Cause(err, "start the display of ", r.vm)
	}
	defer func() {
		_ = closing.Close()
		_ = display.Wait()
	}()
	reply := bufio.NewScanner(replies)
	if !reply.Scan() {
		return nil, E.New("the display helper of ", r.vm, " exited")
	}
	if reply.Text() != "ok" {
		return nil, E.New(r.vm, ": ", reply.Text())
	}
	ctx, cancel := context.WithTimeout(context.Background(), stagingTimeout)
	defer cancel()
	stage := exec.CommandContext(ctx, "ssh", "-o", "BatchMode=yes", r.ssh, "osascript", "-l", "JavaScript", "-", appearance, remoteFrame)
	stage.Stdin = strings.NewReader(macDesktop)
	stage.Stderr = os.Stderr
	start := time.Now()
	err = stage.Run()
	if err != nil {
		return nil, E.Cause(err, "stage the desktop of ", r.vm)
	}
	logTime(stage, start)
	frame, err := output(exec.Command("ssh", "-o", "BatchMode=yes", r.ssh, "cat", remoteFrame))
	if err != nil {
		return nil, err
	}
	config, err := png.DecodeConfig(bytes.NewReader(frame))
	if err != nil {
		return nil, E.Cause(err, "decode the frame of ", r.vm)
	}
	if config.Width != width*density || config.Height != height*density {
		return nil, E.New(r.vm, " shows ", config.Width, "x", config.Height, " pixels instead of ", width*density, "x", height*density)
	}
	return frame, nil
}

func (r *macRemote) prepare() error {
	status, err := output(exec.Command("prlctl", "status", r.vm))
	if err != nil {
		return err
	}
	if !strings.Contains(string(status), "running") {
		err = run(exec.Command("prlctl", "start", r.vm))
		if err != nil {
			return err
		}
		r.startedVM = true
	}
	for deadline := time.Now().Add(2 * time.Minute); exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=2", r.ssh, "pgrep -x Dock >/dev/null").Run() != nil; time.Sleep(time.Second) {
		if time.Now().After(deadline) {
			return E.New("no logged-in desktop session at ", r.ssh)
		}
	}
	path, err := buildHelper("display", "macosx", displaySource, "-framework", "AppKit")
	if err != nil {
		return err
	}
	helper, err := os.Open(path)
	if err != nil {
		return err
	}
	defer helper.Close()
	upload := exec.Command("ssh", "-o", "BatchMode=yes", r.ssh, "cat > "+remoteDisplay+" && chmod +x "+remoteDisplay)
	upload.Stdin = helper
	return run(upload)
}

func (r *macRemote) close() error {
	r.capturing.Lock()
	defer r.capturing.Unlock()
	if !r.startedVM {
		return nil
	}
	r.startedVM = false
	return run(exec.Command("prlctl", "suspend", r.vm))
}

// CGVirtualDisplay is CoreGraphics SPI. hiDPI = 1 backs every mode with twice its size in pixels.
const displaySource = `
#import <AppKit/AppKit.h>

@interface CGVirtualDisplayDescriptor : NSObject
@property (retain) dispatch_queue_t queue;
@property (retain) NSString *name;
@property uint32_t maxPixelsWide;
@property uint32_t maxPixelsHigh;
@property CGSize sizeInMillimeters;
@property uint32_t productID;
@property uint32_t vendorID;
@property uint32_t serialNum;
@end

@interface CGVirtualDisplayMode : NSObject
- (instancetype)initWithWidth:(NSUInteger)width height:(NSUInteger)height refreshRate:(double)refreshRate;
@end

@interface CGVirtualDisplaySettings : NSObject
@property (retain) NSArray<CGVirtualDisplayMode *> *modes;
@property uint32_t hiDPI;
@end

@interface CGVirtualDisplay : NSObject
- (instancetype)initWithDescriptor:(CGVirtualDisplayDescriptor *)descriptor;
- (BOOL)applySettings:(CGVirtualDisplaySettings *)settings;
@property (readonly) CGDirectDisplayID displayID;
@end

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        if (argc != 3) return 2;
        int width = atoi(argv[1]), height = atoi(argv[2]);
        CGVirtualDisplayDescriptor *descriptor = [CGVirtualDisplayDescriptor new];
        descriptor.queue = dispatch_get_main_queue();
        descriptor.name = @"Screenshots";
        descriptor.maxPixelsWide = width * 2;
        descriptor.maxPixelsHigh = height * 2;
        descriptor.sizeInMillimeters = CGSizeMake(width * 25.4 / 110, height * 25.4 / 110);
        descriptor.productID = 1;
        descriptor.vendorID = 1;
        descriptor.serialNum = arc4random();
        CGVirtualDisplay *display = [[CGVirtualDisplay alloc] initWithDescriptor:descriptor];
        CGVirtualDisplaySettings *settings = [CGVirtualDisplaySettings new];
        settings.hiDPI = 1;
        settings.modes = @[[[CGVirtualDisplayMode alloc] initWithWidth:width height:height refreshRate:60]];
        if (![display applySettings:settings]) {
            puts("cannot create a virtual display");
            return 1;
        }
        while (!CGDisplayIsActive(display.displayID)) usleep(50000);
        CGDirectDisplayID online[16];
        uint32_t count = 0;
        CGGetOnlineDisplayList(16, online, &count);
        CGDisplayConfigRef configuration;
        CGBeginDisplayConfiguration(&configuration);
        CGConfigureDisplayOrigin(configuration, display.displayID, 0, 0);
        for (uint32_t index = 0; index < count; index++) {
            if (online[index] != display.displayID) CGConfigureDisplayOrigin(configuration, online[index], width, 0);
        }
        if (CGCompleteDisplayConfiguration(configuration, kCGConfigureForSession) != kCGErrorSuccess) {
            puts("cannot make the virtual display the main display");
            return 1;
        }
        puts("ok");
        fflush(stdout);
        while (getchar() != EOF);
        return 0;
    }
}
`

type frameCache struct {
	directory  string
	appearance string
	remote     *macRemote
	access     sync.Mutex
	frames     map[string]*cachedFrame
	writing    sync.WaitGroup
}

type cachedFrame struct {
	done chan struct{}
	err  error
}

func newFrameCache(appearance string, remote *macRemote) *frameCache {
	staging := sha256.Sum256([]byte(macDesktop))
	parent, err := filepath.Abs(filepath.Join("build", "screenshots", "frames", appearance))
	if err != nil {
		log.Fatal(err)
	}
	directory := filepath.Join(parent, hex.EncodeToString(staging[:8]))
	cached, err := os.ReadDir(parent)
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		log.Fatal(err)
	}
	for _, entry := range cached {
		if entry.Name() == filepath.Base(directory) {
			continue
		}
		err = os.RemoveAll(filepath.Join(parent, entry.Name()))
		if err != nil {
			log.Fatal(err)
		}
	}
	err = os.MkdirAll(directory, 0o755)
	if err != nil {
		log.Fatal(err)
	}
	return &frameCache{directory: directory, appearance: appearance, remote: remote, frames: make(map[string]*cachedFrame)}
}

func (c *frameCache) request(name string) (*cachedFrame, error) {
	var width, height, density int
	_, err := fmt.Sscanf(name, "%dx%d@%d", &width, &height, &density)
	if err != nil {
		return nil, E.Cause(err, "parse frame request ", name)
	}
	path := filepath.Join(c.directory, name+".png")
	c.access.Lock()
	defer c.access.Unlock()
	if frame, requested := c.frames[name]; requested {
		return frame, nil
	}
	frame := &cachedFrame{done: make(chan struct{})}
	c.frames[name] = frame
	if _, err = os.Stat(path); err == nil {
		close(frame.done)
		return frame, nil
	}
	c.writing.Go(func() {
		defer close(frame.done)
		frame.err = c.capture(path, width, height, density)
		if frame.err != nil {
			frame.err = E.Cause(frame.err, "capture frame ", name)
		}
	})
	return frame, nil
}

func (c *frameCache) capture(path string, width, height, density int) error {
	if c.remote == nil {
		return E.New("not cached; -mac-vm and -mac-ssh capture frames")
	}
	data, err := c.remote.frame(width, height, density, c.appearance)
	if err != nil {
		return err
	}
	temporary, err := os.CreateTemp(c.directory, ".frame-*")
	if err != nil {
		return err
	}
	_, err = temporary.Write(data)
	err = E.Errors(err, temporary.Close())
	if err == nil {
		err = os.Rename(temporary.Name(), path)
	}
	if err != nil {
		_ = os.Remove(temporary.Name())
		return err
	}
	log.Print("frame ", path)
	return nil
}
