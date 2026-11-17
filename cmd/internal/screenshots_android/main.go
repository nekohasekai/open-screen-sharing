package main

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"flag"
	"fmt"
	"image/png"
	"log"
	"maps"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	androidPkg   = "io.nekohasekai.screensharing"
	mainActivity = androidPkg + "/.app.MainActivity"
	resDir       = "OpenScreenSharingAndroid/src/main/res"
	apkDir       = "OpenScreenSharingAndroid/build/outputs/apk/demo"
	appTimeout   = time.Minute
)

type page struct {
	name      string
	localized bool
}

var (
	connectionsPage = page{"01-Connections", true}
	sessionPage     = page{"02-Session", false}
	pages           = []page{connectionsPage, sessionPage}
)

type androidDevice struct {
	name               string
	wmWidth            int
	wmHeight           int
	density            int
	sessionRotation    int
	connectionRotation int
}

var androidDevices = []androidDevice{
	{
		name:               "phone",
		wmWidth:            1080,
		wmHeight:           2160,
		density:            420,
		sessionRotation:    1,
		connectionRotation: 0,
	},
	{
		name:               "tablet-7",
		wmWidth:            1200,
		wmHeight:           1920,
		density:            320,
		sessionRotation:    1,
		connectionRotation: 1,
	},
	{
		name:               "tablet-10",
		wmWidth:            1600,
		wmHeight:           2560,
		density:            320,
		sessionRotation:    1,
		connectionRotation: 1,
	},
}

var storeLocales = []string{
	"ar-SA", "bn-BD", "ca", "cs", "da", "de-DE", "el", "en-AU", "en-CA", "en-GB", "en-US", "es-ES", "es-MX", "fi", "fr-CA", "fr-FR", "gu-IN", "he", "hi",
	"hr", "hu", "id", "it", "ja", "kn-IN", "ko", "ml-IN", "mr-IN", "ms", "nl-NL", "no", "or-IN", "pa-IN", "pl", "pt-BR",
	"pt-PT", "ro", "ru", "sk", "sl-SI", "sv", "ta-IN", "te-IN", "th", "tr", "uk", "ur-PK", "vi", "zh-Hans", "zh-Hant",
}

var localeLanguages = map[string]string{
	"es-MX": "es-419",
	"no":    "nb",
}

type shot struct {
	page     string
	language string
	locales  []string
}

type screenshotLanguage struct {
	language string
	locales  []string
}

func main() {
	languageNames := flag.String("languages", "", "comma-separated app languages (default: all supported languages)")
	localeNames := flag.String("locales", "", "comma-separated store locales; cannot be combined with -languages")
	deviceNames := flag.String("devices", strings.Join(common.Map(androidDevices, func(d androidDevice) string { return d.name }), ","), "comma-separated devices")
	appearance := flag.String("appearance", "light", "light or dark (dark writes to screenshots/android/dark)")
	skipBuild := flag.Bool("skip-build", false, "use the demo APK as last built")
	resume := flag.Bool("resume", false, "reuse existing valid screenshots")
	serial := flag.String("serial", "", "adb serial of running emulator/device (default: first running emulator, or start -avd)")
	avdName := flag.String("avd", "oss-api37", "AVD name to start headless if no emulator is running")
	macVM := flag.String("mac-vm", "", "Parallels macOS virtual machine whose desktop the session pages show")
	macSSH := flag.String("mac-ssh", "", "SSH destination of the console user of -mac-vm")
	flag.Parse()

	if *appearance != "light" && *appearance != "dark" {
		log.Fatal("-appearance must be light or dark")
	}
	if (*macVM == "") != (*macSSH == "") {
		log.Fatal("-mac-vm and -mac-ssh go together")
	}
	var remote *macRemote
	if *macVM != "" {
		remote = &macRemote{vm: *macVM, ssh: *macSSH}
	}

	outputDirectory := filepath.Join("screenshots", "android")
	if *appearance == "dark" {
		outputDirectory = filepath.Join(outputDirectory, "dark")
	}

	shots := planShots(selectLanguages(*languageNames, *localeNames))
	type captureJob struct {
		device androidDevice
		shots  []shot
	}
	var jobs []captureJob
	for _, name := range common.Uniq(strings.Split(*deviceNames, ",")) {
		idx := slices.IndexFunc(androidDevices, func(d androidDevice) bool { return d.name == name })
		if idx < 0 {
			log.Fatal("unknown device: ", name)
		}
		dev := androidDevices[idx]
		pending := shots
		if *resume {
			pending = remainingScreenshots(outputDirectory, dev, shots)
		}
		if len(pending) > 0 {
			jobs = append(jobs, captureJob{dev, pending})
		}
	}
	if len(jobs) == 0 {
		log.Print("all requested Android screenshots are present")
		return
	}

	frames := newFrameCache(*appearance, remote)
	if !*skipBuild {
		if err := run(exec.Command("./gradlew", ":OpenScreenSharingAndroid:assembleDemo", "--console=plain", "-q")); err != nil {
			log.Fatal(err)
		}
	}

	targetSerial, startedEmulator, err := ensureEmulator(*serial, *avdName)
	if err != nil {
		log.Fatal(err)
	}
	if startedEmulator != nil {
		defer func() {
			_ = adb(targetSerial, "emu", "kill").Run()
			_ = startedEmulator.Wait()
		}()
	}

	apkPath, err := findDemoAPK(targetSerial)
	if err != nil {
		log.Fatal(err)
	}
	if err := run(adb(targetSerial, "install", "-r", "-t", apkPath)); err != nil {
		log.Fatal(err)
	}

	if err := setupEmulator(targetSerial, *appearance); err != nil {
		log.Fatal(err)
	}
	defer restoreEmulator(targetSerial)

	saver := newScreenshotSaver()

	for _, job := range jobs {
		var sessionShots []shot
		for _, s := range job.shots {
			if s.page == sessionPage.name {
				sessionShots = append(sessionShots, s)
			}
		}
		if len(sessionShots) == 0 {
			continue
		}
		if err := configureDisplay(targetSerial, job.device, job.device.sessionRotation); err != nil {
			log.Fatal(err)
		}
		for _, planned := range sessionShots {
			if err := takeSessionShot(targetSerial, outputDirectory, job.device, planned, frames, saver); err != nil {
				log.Fatal(E.Cause(err, "shoot ", job.device.name, " ", planned.page))
			}
		}
	}
	if remote != nil {
		if err := remote.close(); err != nil {
			log.Print("stop ", remote.vm, ": ", err)
		}
	}

	for _, job := range jobs {
		var connShots []shot
		for _, s := range job.shots {
			if s.page == connectionsPage.name {
				connShots = append(connShots, s)
			}
		}
		if len(connShots) == 0 {
			continue
		}
		if err := configureDisplay(targetSerial, job.device, job.device.connectionRotation); err != nil {
			log.Fatal(err)
		}
		if err := takeConnectionsBatch(targetSerial, outputDirectory, job.device, connShots, frames, saver); err != nil {
			log.Fatal(E.Cause(err, "shoot ", job.device.name, " ", connectionsPage.name))
		}
	}

	if errs := saver.wait(); len(errs) > 0 {
		for _, e := range errs {
			log.Print(e)
		}
		log.Fatal(len(errs), " screenshot saves failed")
	}
}

func appLanguages() (string, []string) {
	entries, err := os.ReadDir(resDir)
	if err != nil {
		log.Fatal(err)
	}
	known := map[string]bool{"en": true}
	for _, entry := range entries {
		if !entry.IsDir() || !strings.HasPrefix(entry.Name(), "values-") {
			continue
		}
		if _, err := os.Stat(filepath.Join(resDir, entry.Name(), "strings_translated.xml")); err != nil {
			continue
		}
		q := strings.TrimPrefix(entry.Name(), "values-")
		var tag string
		if strings.HasPrefix(q, "b+") {
			tag = strings.ReplaceAll(strings.TrimPrefix(q, "b+"), "+", "-")
		} else if idx := strings.Index(q, "-r"); idx >= 0 {
			tag = q[:idx] + "-" + q[idx+2:]
		} else {
			tag = q
		}
		known[tag] = true
	}
	return "en", slices.Sorted(maps.Keys(known))
}

func selectLanguages(languageNames, localeNames string) []screenshotLanguage {
	if languageNames != "" && localeNames != "" {
		log.Fatal("use either -languages or -locales")
	}
	source, languages := appLanguages()
	destinations := make(map[string][]string)
	storeByLocale := make(map[string]string)
	for _, locale := range storeLocales {
		lang := localeLanguages[locale]
		if lang == "" {
			lang = locale
		}
		if !slices.Contains(languages, lang) {
			lang, _, _ = strings.Cut(lang, "-")
		}
		if slices.Contains(languages, lang) {
			storeByLocale[locale] = lang
			destinations[lang] = append(destinations[lang], locale)
		}
	}
	for _, lang := range languages {
		if len(destinations[lang]) == 0 {
			destinations[lang] = []string{lang}
		}
	}
	if localeNames != "" {
		clear(destinations)
		for _, locale := range common.Uniq(strings.Split(localeNames, ",")) {
			lang := storeByLocale[locale]
			if lang == "" {
				log.Fatal("the app has no language for ", locale)
			}
			destinations[lang] = append(destinations[lang], locale)
		}
		languages = slices.Sorted(maps.Keys(destinations))
	} else if languageNames != "" {
		languages = common.Uniq(strings.Split(languageNames, ","))
		for _, lang := range languages {
			if _, ok := destinations[lang]; !ok {
				log.Fatal("unknown app language: ", lang)
			}
		}
	}
	if idx := slices.Index(languages, source); idx > 0 {
		languages = slices.Insert(slices.Delete(languages, idx, idx+1), 0, source)
	}
	return common.Map(languages, func(lang string) screenshotLanguage {
		return screenshotLanguage{lang, destinations[lang]}
	})
}

func planShots(languages []screenshotLanguage) []shot {
	var shots []shot
	for _, page := range pages {
		groups := make(map[string]int)
		for _, language := range languages {
			var group string
			if page.localized {
				group = language.language
			}
			index, grouped := groups[group]
			if !grouped {
				index = len(shots)
				groups[group] = index
				shots = append(shots, shot{page: page.name, language: language.language})
			}
			shots[index].locales = append(shots[index].locales, language.locales...)
		}
	}
	return shots
}

func expectedDims(dev androidDevice, pageName string) (int, int) {
	rot := dev.connectionRotation
	if pageName == sessionPage.name {
		rot = dev.sessionRotation
	}
	if rot == 1 || rot == 3 {
		return dev.wmHeight, dev.wmWidth
	}
	return dev.wmWidth, dev.wmHeight
}

func remainingScreenshots(outputDirectory string, dev androidDevice, shots []shot) []shot {
	var remaining []shot
	w, h := 0, 0
	for _, planned := range shots {
		w, h = expectedDims(dev, planned.page)
		var reusable []byte
		var missing []string
		for _, locale := range planned.locales {
			data, err := os.ReadFile(filepath.Join(outputDirectory, locale, dev.name, planned.page+".png"))
			if err == nil {
				_, err = png.Decode(bytes.NewReader(data))
			}
			if err == nil {
				err = checkScreenshot(data, w, h)
			}
			if err != nil {
				missing = append(missing, locale)
			} else if reusable == nil {
				reusable = data
			}
		}
		if len(missing) == 0 {
			continue
		}
		if reusable != nil {
			if err := save(outputDirectory, reusable, dev, missing, planned.page, time.Now()); err != nil {
				log.Fatal(err)
			}
		} else {
			planned.locales = missing
			remaining = append(remaining, planned)
		}
	}
	return remaining
}

func adbBin() string {
	if p, err := exec.LookPath("adb"); err == nil {
		return p
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "Android", "sdk", "platform-tools", "adb")
}

func emulatorBin() string {
	if p, err := exec.LookPath("emulator"); err == nil {
		return p
	}
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "Android", "sdk", "emulator", "emulator")
}

func adb(serial string, args ...string) *exec.Cmd {
	if serial != "" {
		return exec.Command(adbBin(), append([]string{"-s", serial}, args...)...)
	}
	return exec.Command(adbBin(), args...)
}

func ensureEmulator(serial, avdName string) (string, *exec.Cmd, error) {
	if serial != "" {
		return serial, nil, waitForBoot(serial)
	}
	out, err := output(exec.Command(adbBin(), "devices"))
	if err == nil {
		for _, line := range strings.Split(string(out), "\n") {
			fields := strings.Fields(line)
			if len(fields) == 2 && fields[1] == "device" && strings.HasPrefix(fields[0], "emulator-") {
				return fields[0], nil, waitForBoot(fields[0])
			}
		}
	}
	cmd := exec.Command(emulatorBin(), "-avd", avdName, "-no-window", "-no-audio", "-no-boot-anim", "-gpu", "host")
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return "", nil, E.Cause(err, "start emulator ", avdName)
	}
	deadline := time.Now().Add(2 * time.Minute)
	for time.Now().Before(deadline) {
		out, err := exec.Command(adbBin(), "devices").Output()
		if err == nil {
			for _, line := range strings.Split(string(out), "\n") {
				fields := strings.Fields(line)
				if len(fields) == 2 && fields[1] == "device" && strings.HasPrefix(fields[0], "emulator-") {
					if err := waitForBoot(fields[0]); err != nil {
						_ = cmd.Process.Kill()
						return "", nil, err
					}
					return fields[0], cmd, nil
				}
			}
		}
		time.Sleep(time.Second)
	}
	_ = cmd.Process.Kill()
	return "", nil, E.New("emulator ", avdName, " did not connect via adb")
}

func waitForBoot(serial string) error {
	for deadline := time.Now().Add(2 * time.Minute); time.Now().Before(deadline); time.Sleep(time.Second) {
		out, err := adb(serial, "shell", "getprop", "sys.boot_completed").Output()
		if err == nil && strings.TrimSpace(string(out)) == "1" {
			return nil
		}
	}
	return E.New("emulator ", serial, " did not finish booting")
}

func findDemoAPK(serial string) (string, error) {
	abiOut, err := output(adb(serial, "shell", "getprop", "ro.product.cpu.abi"))
	if err != nil {
		return "", err
	}
	abi := strings.TrimSpace(string(abiOut))
	candidates := []string{
		filepath.Join(apkDir, "OpenScreenSharing-"+abi+"-demo.apk"),
		filepath.Join(apkDir, "OpenScreenSharing-universal-demo.apk"),
	}
	for _, p := range candidates {
		if _, err := os.Stat(p); err == nil {
			return p, nil
		}
	}
	matches, _ := filepath.Glob(filepath.Join(apkDir, "*.apk"))
	if len(matches) > 0 {
		return matches[0], nil
	}
	return "", E.New("no demo APK found in ", apkDir)
}

func setupEmulator(serial, appearance string) error {
	night := "no"
	if appearance == "dark" {
		night = "yes"
	}
	cmds := [][]string{
		{"shell", "settings", "put", "global", "sysui_demo_allowed", "1"},
		{"shell", "cmd", "statusbar", "send-disable-flag", "notification-icons"},
		{"shell", "settings", "put", "system", "accelerometer_rotation", "0"},
		{"shell", "settings", "put", "global", "window_animation_scale", "0"},
		{"shell", "settings", "put", "global", "transition_animation_scale", "0"},
		{"shell", "settings", "put", "global", "animator_duration_scale", "0"},
		{"shell", "settings", "put", "secure", "immersive_mode_confirmations", "confirmed"},
		{"shell", "cmd", "uimode", "night", night},
		{"shell", "input", "keyevent", "KEYCODE_WAKEUP"},
		{"shell", "wm", "dismiss-keyguard"},
	}
	for _, args := range cmds {
		if err := adb(serial, args...).Run(); err != nil {
			return E.Cause(err, "adb ", strings.Join(args, " "))
		}
	}
	return nil
}

func restoreEmulator(serial string) {
	cmds := [][]string{
		{"shell", "am", "force-stop", androidPkg},
		{"shell", "wm", "size", "reset"},
		{"shell", "wm", "density", "reset"},
		{"shell", "wm", "fixed-to-user-rotation", "default"},
		{"shell", "wm", "user-rotation", "free"},
		{"shell", "settings", "put", "system", "accelerometer_rotation", "1"},
		{"shell", "settings", "put", "global", "window_animation_scale", "1"},
		{"shell", "settings", "put", "global", "transition_animation_scale", "1"},
		{"shell", "settings", "put", "global", "animator_duration_scale", "1"},
		{"shell", "cmd", "statusbar", "send-disable-flag", "none"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit"},
	}
	for _, args := range cmds {
		_ = adb(serial, args...).Run()
	}
}

func configureDisplay(serial string, dev androidDevice, rotation int) error {
	cmds := [][]string{
		{"shell", "am", "force-stop", androidPkg},
		{"shell", "wm", "size", fmt.Sprintf("%dx%d", dev.wmWidth, dev.wmHeight)},
		{"shell", "wm", "density", strconv.Itoa(dev.density)},
		{"shell", "wm", "fixed-to-user-rotation", "enabled"},
		{"shell", "wm", "user-rotation", "lock", strconv.Itoa(rotation)},
	}
	for _, args := range cmds {
		if err := run(adb(serial, args...)); err != nil {
			return err
		}
	}
	wantW, wantH := dev.wmWidth, dev.wmHeight
	if rotation == 1 || rotation == 3 {
		wantW, wantH = dev.wmHeight, dev.wmWidth
	}
	wantCur := fmt.Sprintf("cur=%dx%d", wantW, wantH)
	settled := false
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(150 * time.Millisecond) {
		out, err := adb(serial, "shell", "dumpsys", "window", "displays").Output()
		if err == nil && strings.Contains(string(out), wantCur) {
			settled = true
			break
		}
	}
	if !settled {
		return E.New("display did not settle to ", wantCur)
	}
	// SystemUI adds another demo Wi-Fi icon whenever the density changes inside demo mode;
	// re-entering demo mode rebuilds the icons.
	demoCmds := [][]string{
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "enter"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "clock", "-e", "hhmm", "0941"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true", "-e", "mobile", "hide", "-e", "nosim", "hide"},
		{"shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "notifications", "-e", "visible", "false"},
	}
	for _, args := range demoCmds {
		if err := run(adb(serial, args...)); err != nil {
			return err
		}
	}
	return nil
}

func launchDemo(serial string, port int, pageName string) error {
	_ = adb(serial, "shell", "am", "force-stop", androidPkg).Run()
	if err := run(adb(serial, "reverse", fmt.Sprintf("tcp:%d", port), fmt.Sprintf("tcp:%d", port))); err != nil {
		return err
	}
	return run(adb(serial, "shell", "am", "start", "-W", "-n", mainActivity,
		"--ez", "demo", "true",
		"--es", "demo_screenshot", pageName,
		"--ei", "demo_control_port", strconv.Itoa(port),
	))
}

func serveFrame(conn net.Conn, reader *bufio.Reader, frames *frameCache) (string, error) {
	for {
		_ = conn.SetDeadline(time.Now().Add(appTimeout))
		line, err := reader.ReadString('\n')
		if err != nil {
			return "", err
		}
		line = strings.TrimSpace(line)
		if req, ok := strings.CutPrefix(line, "frame "); ok {
			data, err := frames.get(req)
			if err != nil {
				return "", err
			}
			_ = conn.SetDeadline(time.Now().Add(appTimeout))
			if _, err := fmt.Fprintf(conn, "%d\n", len(data)); err != nil {
				return "", err
			}
			if _, err := conn.Write(data); err != nil {
				return "", err
			}
			continue
		}
		return line, nil
	}
}

func takeSessionShot(serial, outputDirectory string, dev androidDevice, planned shot, frames *frameCache, saver *screenshotSaver) error {
	start := time.Now()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	defer func() {
		_ = adb(serial, "reverse", "--remove", fmt.Sprintf("tcp:%d", port)).Run()
	}()

	if err := launchDemo(serial, port, planned.page); err != nil {
		return err
	}
	_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(appTimeout))
	conn, err := ln.Accept()
	if err != nil {
		return err
	}
	defer conn.Close()

	reader := bufio.NewReader(conn)
	msg, err := serveFrame(conn, reader, frames)
	if err != nil {
		return err
	}
	if msg != "shown "+planned.page {
		return E.New("unexpected session message: ", msg)
	}
	data, err := screencapStable(serial)
	if err != nil {
		return err
	}
	saver.submit(saveRequest{
		outputDirectory: outputDirectory,
		rawPNG:          data,
		device:          dev,
		locales:         planned.locales,
		page:            planned.page,
		start:           start,
	})
	return nil
}

func takeConnectionsBatch(serial, outputDirectory string, dev androidDevice, shots []shot, frames *frameCache, saver *screenshotSaver) error {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port
	defer func() {
		_ = adb(serial, "reverse", "--remove", fmt.Sprintf("tcp:%d", port)).Run()
	}()

	if err := launchDemo(serial, port, connectionsPage.name); err != nil {
		return err
	}
	_ = ln.(*net.TCPListener).SetDeadline(time.Now().Add(appTimeout))
	conn, err := ln.Accept()
	if err != nil {
		return err
	}
	defer conn.Close()

	reader := bufio.NewReader(conn)
	msg, err := serveFrame(conn, reader, frames)
	if err != nil {
		return err
	}
	if msg != "ready" {
		return E.New("unexpected connections handshake: ", msg)
	}

	for i, planned := range shots {
		shotStart := time.Now()
		_ = conn.SetDeadline(time.Now().Add(appTimeout))
		if _, err := fmt.Fprintln(conn, planned.language); err != nil {
			return err
		}
		reply, err := serveFrame(conn, reader, frames)
		if err != nil {
			return err
		}
		if reply != "ok" {
			return E.New("unexpected reply for ", planned.language, ": ", reply)
		}
		var data []byte
		if i == 0 {
			data, err = screencapStable(serial)
		} else {
			data, err = screencapOnce(serial)
		}
		if err != nil {
			return err
		}
		saver.submit(saveRequest{
			outputDirectory: outputDirectory,
			rawPNG:          data,
			device:          dev,
			locales:         planned.locales,
			page:            planned.page,
			start:           shotStart,
		})
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = fmt.Fprintln(conn, "quit")
	return nil
}

func screencapOnce(serial string) ([]byte, error) {
	return adb(serial, "exec-out", "screencap", "-p").Output()
}

func screencapStable(serial string) ([]byte, error) {
	var prev []byte
	for i := 0; i < 10; i++ {
		frame, err := screencapOnce(serial)
		if err != nil {
			return nil, err
		}
		if bytes.Equal(frame, prev) {
			return frame, nil
		}
		prev = frame
		time.Sleep(100 * time.Millisecond)
	}
	return prev, nil
}

type saveRequest struct {
	outputDirectory string
	rawPNG          []byte
	device          androidDevice
	locales         []string
	page            string
	start           time.Time
}

type screenshotSaver struct {
	queue  chan saveRequest
	wg     sync.WaitGroup
	mu     sync.Mutex
	errors []error
}

func newScreenshotSaver() *screenshotSaver {
	s := &screenshotSaver{queue: make(chan saveRequest, 64)}
	for range max(2, runtime.NumCPU()) {
		s.wg.Go(func() {
			for req := range s.queue {
				if err := processAndSave(req); err != nil {
					s.mu.Lock()
					s.errors = append(s.errors, err)
					s.mu.Unlock()
				}
			}
		})
	}
	return s
}

func (s *screenshotSaver) submit(req saveRequest) {
	s.queue <- req
}

func (s *screenshotSaver) wait() []error {
	close(s.queue)
	s.wg.Wait()
	return s.errors
}

var fastPNG = png.Encoder{CompressionLevel: png.BestSpeed}

func processAndSave(req saveRequest) error {
	data := req.rawPNG
	if hasPNGAlpha(data) {
		img, err := png.Decode(bytes.NewReader(data))
		if err != nil {
			return E.Cause(err, "decode the screenshot")
		}
		var opaque bytes.Buffer
		if err := fastPNG.Encode(&opaque, img); err != nil {
			return err
		}
		data = opaque.Bytes()
	}
	return save(req.outputDirectory, data, req.device, req.locales, req.page, req.start)
}

func save(outputDirectory string, data []byte, dev androidDevice, locales []string, pageName string, start time.Time) error {
	w, h := expectedDims(dev, pageName)
	if err := checkScreenshot(data, w, h); err != nil {
		return E.Cause(err, "check ", pageName)
	}
	for _, locale := range locales {
		path := filepath.Join(outputDirectory, locale, dev.name, pageName+".png")
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(path, data, 0o644); err != nil {
			return err
		}
		log.Printf("%5.1fs %s", time.Since(start).Seconds(), path)
	}
	return nil
}

func hasPNGAlpha(data []byte) bool {
	signature := []byte("\x89PNG\r\n\x1a\n")
	if !bytes.HasPrefix(data, signature) {
		return true
	}
	for rest := data[len(signature):]; len(rest) >= 12; {
		length := int(binary.BigEndian.Uint32(rest))
		if len(rest) < 12+length {
			return true
		}
		chunk, content := string(rest[4:8]), rest[8:8+length]
		switch chunk {
		case "IHDR":
			if len(content) == 13 && (content[9] == 4 || content[9] == 6) {
				return true
			}
		case "tRNS":
			return true
		}
		rest = rest[12+length:]
	}
	return false
}

func checkScreenshot(data []byte, wantW, wantH int) error {
	signature := []byte("\x89PNG\r\n\x1a\n")
	if !bytes.HasPrefix(data, signature) {
		return E.New("not a PNG")
	}
	var width, height int
	var colorType byte
	for rest := data[len(signature):]; len(rest) >= 12; {
		length := int(binary.BigEndian.Uint32(rest))
		if len(rest) < 12+length {
			return E.New("truncated PNG")
		}
		chunk, content := string(rest[4:8]), rest[8:8+length]
		switch chunk {
		case "IHDR":
			if len(content) != 13 {
				return E.New("invalid PNG header")
			}
			width = int(binary.BigEndian.Uint32(content))
			height = int(binary.BigEndian.Uint32(content[4:]))
			colorType = content[9]
		case "tRNS":
			return E.New("transparent")
		}
		rest = rest[12+length:]
	}
	if colorType == 4 || colorType == 6 {
		return E.New("has an alpha channel (PNG color type ", colorType, ")")
	}
	if width != wantW || height != wantH {
		return E.New(width, "x", height, " != expected ", wantW, "x", wantH)
	}
	return nil
}

func output(command *exec.Cmd) ([]byte, error) {
	command.Stderr = os.Stderr
	start := time.Now()
	data, err := command.Output()
	if err != nil {
		return nil, E.Cause(err, strings.Join(command.Args, " "))
	}
	logTime(command, start)
	return data, nil
}

func run(command *exec.Cmd) error {
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	start := time.Now()
	err := command.Run()
	if err != nil {
		return E.Cause(err, strings.Join(command.Args, " "))
	}
	logTime(command, start)
	return nil
}

func logTime(command *exec.Cmd, start time.Time) {
	log.Printf("%5.1fs %s", time.Since(start).Seconds(), strings.Join(command.Args, " "))
}
