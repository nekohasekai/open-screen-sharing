package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"image"
	"image/draw"
	"image/png"
	"log"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/sagernet/sing/common"
	E "github.com/sagernet/sing/common/exceptions"
)

const (
	project     = "OpenScreenSharing.xcodeproj"
	scheme      = "OpenScreenSharing"
	derivedData = "build/DerivedData-screenshots"
	outputPath  = "screenshots"
	resultsPath = "build/screenshots"
	testsToRun  = "OpenScreenSharingUITests/ScreenshotTests"
	macPage     = "01-Connections"
)

type size struct {
	width  int
	height int
}

type preference struct {
	domain string
	key    string
	kind   string
	value  string
}

type device struct {
	name        string
	platform    string
	simulator   string
	deviceType  string
	pose        string
	sizes       []size
	preferences []preference
}

var devices = []device{
	{
		name:       "iphone",
		platform:   "iOS Simulator",
		simulator:  "iphone",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
		sizes:      []size{{1206, 2622}, {1179, 2556}},
	},
	{
		name:       "duo-outer",
		platform:   "iOS Simulator",
		simulator:  "duo",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-Duo",
		pose:       "Closed",
		sizes:      []size{{1398, 2034}},
	},
	{
		name:       "duo-inner",
		platform:   "iOS Simulator",
		simulator:  "duo",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-Duo",
		pose:       "Open",
		sizes:      []size{{2007, 2853}},
	},
	{
		name:       "ipad",
		platform:   "iOS Simulator",
		simulator:  "ipad",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB",
		sizes:      []size{{2064, 2752}, {2048, 2732}},
		// Full Screen Apps in Settings › Multitasking & Gestures; windowed apps get a resize grabber and the app name in the status bar.
		preferences: []preference{
			{"com.apple.springboard", "SBMedusaMultitaskingEnabled", "-bool", "false"},
			{"com.apple.springboard", "SBChamoisWindowingEnabled", "-bool", "false"},
		},
	},
	{
		name:     "mac",
		platform: "macOS",
		sizes:    []size{{1280, 800}, {1440, 900}, {2560, 1600}, {2880, 1800}},
	},
}

var buildSettings = []string{
	"SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEMO",
	"EXCLUDED_SOURCE_FILE_NAMES=",
}

var simulatorPreferences = []preference{
	{"-g", "AppleLanguages", "-array", "en-US"},
	{"-g", "AppleLocale", "-string", "en_US"},
}

var regions = map[string]string{
	"en":      "US",
	"zh-Hans": "CN",
}

var screenshotName = regexp.MustCompile(`^([A-Za-z-]+)_(\d\d-[A-Za-z]+)_`)

func main() {
	languages := flag.String("languages", "en", "the languages to take the screenshots in")
	deviceNames := flag.String("devices", "iphone,duo-outer,duo-inner,ipad,mac", "the devices to take the screenshots on")
	appearance := flag.String("appearance", "light", "light or dark")
	skipBuild := flag.Bool("skip-build", false, "use the apps and tests as last built")
	flag.Parse()

	var chosenDevices []device
	for _, name := range strings.Split(*deviceNames, ",") {
		index := slices.IndexFunc(devices, func(device device) bool { return device.name == name })
		if index < 0 {
			log.Fatal("unknown device: ", name)
		}
		chosenDevices = append(chosenDevices, devices[index])
	}
	chosenLanguages := strings.Split(*languages, ",")
	for _, language := range chosenLanguages {
		if regions[language] == "" {
			log.Fatal("unknown language: ", language)
		}
	}
	if !*skipBuild {
		platforms := common.Uniq(common.Map(chosenDevices, func(device device) string { return device.platform }))
		for _, platform := range platforms {
			action := "build-for-testing"
			if platform == "macOS" {
				action = "build"
			}
			run(exec.Command("xcodebuild", append([]string{
				action, "-quiet",
				"-project", project,
				"-scheme", scheme,
				"-configuration", "Release",
				"-destination", "generic/platform=" + platform,
				"-derivedDataPath", derivedData,
				"ARCHS=$(NATIVE_ARCH_ACTUAL)",
				"ENABLE_CODE_COVERAGE=NO",
			}, buildSettings...)...))
		}
	}
	for _, device := range chosenDevices {
		if device.platform == "macOS" {
			takeMacScreenshots(device, chosenLanguages, *appearance)
			continue
		}
		runtime := latestRuntime(device.deviceType)
		log.Print(device.name, " on ", runtime)
		simulator := startSimulator(device, runtime, *appearance)
		takeScreenshots(device, simulator, chosenLanguages)
		run(exec.Command("xcrun", "simctl", "shutdown", simulator))
	}
}

func latestTestRun() string {
	paths, err := filepath.Glob(filepath.Join(derivedData, "Build/Products/*.xctestrun"))
	if err != nil {
		log.Fatal(err)
	}
	if len(paths) == 0 {
		log.Fatal("no .xctestrun in ", derivedData, "; build for testing first")
	}
	var latest string
	var latestTime int64
	for _, path := range paths {
		info, err := os.Stat(path)
		if err != nil {
			log.Fatal(err)
		}
		if info.ModTime().UnixNano() > latestTime {
			latest = path
			latestTime = info.ModTime().UnixNano()
		}
	}
	return latest
}

func latestRuntime(deviceType string) string {
	type supportedDeviceType struct {
		Identifier string `json:"identifier"`
	}
	var list struct {
		Runtimes []struct {
			Identifier           string                `json:"identifier"`
			Platform             string                `json:"platform"`
			Version              string                `json:"version"`
			IsAvailable          bool                  `json:"isAvailable"`
			SupportedDeviceTypes []supportedDeviceType `json:"supportedDeviceTypes"`
		} `json:"runtimes"`
	}
	decode(output(exec.Command("xcrun", "simctl", "list", "runtimes", "--json")), &list)
	var latest string
	var latestVersion []int
	for _, runtime := range list.Runtimes {
		if runtime.Platform != "iOS" || !runtime.IsAvailable || !slices.ContainsFunc(runtime.SupportedDeviceTypes, func(supported supportedDeviceType) bool {
			return supported.Identifier == deviceType
		}) {
			continue
		}
		version := parseVersion(runtime.Version)
		if slices.Compare(version, latestVersion) > 0 {
			latest = runtime.Identifier
			latestVersion = version
		}
	}
	if latest == "" {
		log.Fatal("no iOS simulator runtime for ", deviceType)
	}
	return latest
}

func parseVersion(version string) []int {
	var parts []int
	for _, part := range strings.Split(version, ".") {
		number, err := strconv.Atoi(part)
		if err != nil {
			log.Fatal("bad version: ", version)
		}
		parts = append(parts, number)
	}
	return parts
}

type simulator struct {
	UDID                 string `json:"udid"`
	Name                 string `json:"name"`
	State                string `json:"state"`
	DeviceTypeIdentifier string `json:"deviceTypeIdentifier"`
	DataPath             string `json:"dataPath"`
}

func findSimulator(device device, runtime string) (simulator, bool) {
	var list struct {
		Devices map[string][]simulator `json:"devices"`
	}
	decode(output(exec.Command("xcrun", "simctl", "list", "devices", "--json")), &list)
	simulators := list.Devices[runtime]
	index := slices.IndexFunc(simulators, func(existing simulator) bool {
		return existing.Name == "Screenshots "+device.simulator && existing.DeviceTypeIdentifier == device.deviceType
	})
	if index < 0 {
		return simulator{}, false
	}
	return simulators[index], true
}

func startSimulator(device device, runtime string, appearance string) string {
	existing, found := findSimulator(device, runtime)
	if !found {
		output(exec.Command("xcrun", "simctl", "create", "Screenshots "+device.simulator, device.deviceType, runtime))
		existing, _ = findSimulator(device, runtime)
	}
	simulator := existing.UDID
	if existing.State == "Shutdown" {
		boot(simulator)
	}
	output(exec.Command("xcrun", "simctl", "bootstatus", simulator, "-b"))
	var changed bool
	for _, preference := range append(slices.Clone(simulatorPreferences), device.preferences...) {
		file := preference.domain
		if file == "-g" {
			file = ".GlobalPreferences"
		}
		keyPath := preference.key
		if preference.kind == "-array" {
			keyPath += ".0"
		}
		current, _ := exec.Command("plutil", "-extract", keyPath, "raw", "-o", "-", filepath.Join(existing.DataPath, "Library/Preferences", file+".plist")).Output()
		if strings.TrimSpace(string(current)) != preference.value {
			run(exec.Command("xcrun", "simctl", "spawn", simulator, "defaults", "write", preference.domain, preference.key, preference.kind, preference.value))
			changed = true
		}
	}
	if changed {
		run(exec.Command("xcrun", "simctl", "shutdown", simulator))
		boot(simulator)
		output(exec.Command("xcrun", "simctl", "bootstatus", simulator, "-b"))
	}
	run(exec.Command("xcrun", "simctl", "ui", simulator, "appearance", appearance))
	run(exec.Command("xcrun", "simctl", "status_bar", simulator, "override",
		"--dataNetwork", "wifi",
		"--wifiMode", "active",
		"--wifiBars", "3",
		"--cellularMode", "active",
		"--cellularBars", "4",
		"--batteryState", "charged",
		"--batteryLevel", "100",
	))
	run(exec.Command("xcrun", "simctl", "status_bar", simulator, "override",
		"--time", time.Date(2007, time.January, 9, 9, 41, 0, 0, time.Local).Format("2006-01-02T15:04:05.000Z07:00"),
	))
	if device.pose != "" {
		setPose(existing, device.pose)
	}
	return simulator
}

// CoreSimulatorService can refuse to boot an installed runtime with "runtime path not found" until it restarts, and
// restarting it shuts down every booted simulator.
func boot(udid string) {
	command := exec.Command("xcrun", "simctl", "boot", udid)
	start := time.Now()
	message, err := command.CombinedOutput()
	if err == nil {
		logTime(command, start)
		return
	}
	var list struct {
		Devices map[string][]simulator `json:"devices"`
	}
	decode(output(exec.Command("xcrun", "simctl", "list", "devices", "booted", "--json")), &list)
	for _, booted := range list.Devices {
		if len(booted) > 0 {
			log.Fatal("[xcrun simctl boot ", udid, "]: ", err, "\n", string(message), "shut down ", booted[0].Name, " to let CoreSimulatorService restart")
		}
	}
	log.Print(strings.TrimSpace(string(message)), "\nrestarting CoreSimulatorService")
	_ = exec.Command("killall", "com.apple.CoreSimulator.CoreSimulatorService").Run()
	run(exec.Command("xcrun", "simctl", "boot", udid))
}

const pressPose = `on run {deviceName, pose}
	tell application "System Events"
		if UI elements enabled is false then error "The terminal needs Accessibility access"
		repeat
			if exists (first process whose bundle identifier is "com.apple.dt.Devices") then
				tell (first process whose bundle identifier is "com.apple.dt.Devices")
					if exists (first window whose name starts with deviceName) then
						tell (first window whose name starts with deviceName)
							if exists (first button whose description is pose) then
								click (first button whose description is pose)
								return
							end if
						end tell
					end if
				end tell
			end if
			delay 0.3
		end repeat
	end tell
end run`

// Only Device Hub folds a simulated iPhone Duo, and it opens on the device it showed last.
func setPose(booted simulator, pose string) {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	if exec.CommandContext(ctx, "pgrep", "-x", "DeviceHub").Run() == nil {
		run(exec.CommandContext(ctx, "osascript", "-e", `quit app id "com.apple.dt.Devices"`))
		for exec.CommandContext(ctx, "pgrep", "-x", "DeviceHub").Run() == nil {
			select {
			case <-ctx.Done():
				log.Fatal(ctx.Err())
			case <-time.After(100 * time.Millisecond):
			}
		}
	}
	run(exec.CommandContext(ctx, "defaults", "write", "com.apple.dt.Devices", "lastSelectedDeviceIdentifier", "-data", hex.EncodeToString([]byte(strconv.Quote(booted.UDID)))))
	developer := strings.TrimSpace(string(output(exec.CommandContext(ctx, "xcode-select", "-p"))))
	run(exec.CommandContext(ctx, "open", "-g", "-a", filepath.Join(developer, "../Applications/DeviceHub.app")))
	run(exec.CommandContext(ctx, "osascript", "-e", pressPose, booted.Name, pose))
}

func takeScreenshots(device device, simulator string, languages []string) {
	log.Print("taking ", device.name, " screenshots")
	resultBundle := filepath.Join(resultsPath, device.name+".xcresult")
	attachments := filepath.Join(resultsPath, device.name)
	paths := []string{resultBundle, attachments}
	var locales []string
	for _, language := range languages {
		paths = append(paths, filepath.Join(outputPath, language, device.name))
		locales = append(locales, language+"_"+regions[language])
	}
	for _, path := range paths {
		err := os.RemoveAll(path)
		if err != nil {
			log.Fatal(err)
		}
	}
	test := exec.Command("xcodebuild", "test-without-building", "-quiet",
		"-xctestrun", latestTestRun(),
		"-destination", "id="+simulator,
		"-only-testing:"+testsToRun,
		"-parallel-testing-enabled", "NO",
		"-collect-test-diagnostics", "never",
		"-resultBundlePath", resultBundle,
	)
	test.Env = append(os.Environ(), "TEST_RUNNER_SCREENSHOT_LOCALES="+strings.Join(locales, ","))
	run(test)

	run(exec.Command("xcrun", "xcresulttool", "export", "attachments", "--path", resultBundle, "--output-path", attachments))
	var manifest []struct {
		Attachments []struct {
			ExportedFileName           string `json:"exportedFileName"`
			SuggestedHumanReadableName string `json:"suggestedHumanReadableName"`
		} `json:"attachments"`
	}
	manifestData, err := os.ReadFile(filepath.Join(attachments, "manifest.json"))
	if err != nil {
		log.Fatal(err)
	}
	decode(manifestData, &manifest)
	for _, test := range manifest {
		for _, attachment := range test.Attachments {
			match := screenshotName.FindStringSubmatch(attachment.SuggestedHumanReadableName)
			if match == nil {
				continue
			}
			data, err := os.ReadFile(filepath.Join(attachments, attachment.ExportedFileName))
			if err != nil {
				log.Fatal(err)
			}
			save(data, device, match[1], match[2])
		}
	}
}

func takeMacScreenshots(device device, languages []string, appearance string) {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	log.Print("taking ", device.name, " screenshots")
	executables, err := filepath.Glob(filepath.Join(derivedData, "Build/Products/Release/*.app/Contents/MacOS/*"))
	if err != nil {
		log.Fatal(err)
	}
	if len(executables) != 1 {
		log.Fatal("no single macOS app in ", derivedData, "; build it first")
	}
	style := "Light"
	if appearance == "dark" {
		style = "Dark"
	}
	for _, language := range languages {
		err = os.RemoveAll(filepath.Join(outputPath, language, device.name))
		if err != nil {
			log.Fatal(err)
		}
		app := exec.CommandContext(ctx, executables[0],
			"-AppleLanguages", "("+language+")",
			"-AppleLocale", language+"_"+regions[language],
			"-AppleInterfaceStyle", style,
			// 4 is blue (AppKit 2775.10.103.1, macOS 27.0.1 26A434, UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
			// _NSColorGetUserAccentColor 0x184ed5b3c..0x184ed5b70, __NSAppearanceTintColorNameFromUserAccentColor
			// 0x184ed5f5c..0x184ed5fbc).
			"-AppleAccentColor", "4",
			"-DemoScreenshot", macPage,
			// AppKit reads the arguments as -key value pairs and opens any left over as documents, and then no window opens
			// (AppKit 2775.10.103.1, macOS 27.0.1 26A434, UUID 2688AFBC-5C39-3132-BCF5-8D00D32D7615,
			// -[NSApplication finishLaunching] 0x184ef7d90..0x184ef8134,
			// ___58-[NSApplication(NSAppleEventHandling) _handleAEOpenEvent:]_block_invoke 0x1850244ac..0x1850244b4).
			"-Demo", "YES",
		)
		var stdout, stderr bytes.Buffer
		app.Stdout = &stdout
		app.Stderr = &stderr
		start := time.Now()
		err = app.Run()
		if err != nil {
			log.Fatal(app.Args, ": ", err, "\n", stdout.String(), stderr.String())
		}
		logTime(app, start)
		lines := strings.Split(strings.TrimSpace(stdout.String()), "\n")
		captured := lines[len(lines)-1]
		data, err := os.ReadFile(captured)
		if err != nil {
			log.Fatal(err)
		}
		save(data, device, language, macPage)
		err = os.Remove(captured)
		if err != nil {
			log.Fatal(err)
		}
	}
}

func save(data []byte, device device, language string, name string) {
	data = padScreenshot(data, device.sizes)
	err := checkScreenshot(data, device.sizes)
	if err != nil {
		log.Fatal(name, ": ", err)
	}
	destination := filepath.Join(outputPath, language, device.name)
	err = os.MkdirAll(destination, 0o755)
	if err != nil {
		log.Fatal(err)
	}
	path := filepath.Join(destination, name+".png")
	err = os.WriteFile(path, data, 0o644)
	if err != nil {
		log.Fatal(err)
	}
	log.Print(path)
}

// XCTest captures the inner display of an iPhone Duo without its leftmost column and bottom row.
func padScreenshot(data []byte, sizes []size) []byte {
	config, err := png.DecodeConfig(bytes.NewReader(data))
	if err != nil || !slices.ContainsFunc(sizes, func(accepted size) bool {
		return config.Width+1 == accepted.width && config.Height+1 == accepted.height || config.Width+1 == accepted.height && config.Height+1 == accepted.width
	}) {
		return data
	}
	captured, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		log.Fatal(err)
	}
	bounds := captured.Bounds()
	padded := image.NewRGBA(image.Rect(0, 0, bounds.Dx()+1, bounds.Dy()+1))
	for _, offset := range []image.Point{{0, 1}, {1, 1}, {0, 0}, {1, 0}} {
		draw.Draw(padded, bounds.Sub(bounds.Min).Add(offset), captured, bounds.Min, draw.Src)
	}
	var buffer bytes.Buffer
	err = png.Encode(&buffer, padded)
	if err != nil {
		log.Fatal(err)
	}
	return buffer.Bytes()
}

func checkScreenshot(data []byte, sizes []size) error {
	signature := []byte("\x89PNG\r\n\x1a\n")
	if !bytes.HasPrefix(data, signature) {
		return E.New("not a PNG")
	}
	var width, height int
	var colorType byte
	for rest := data[len(signature):]; len(rest) >= 12; {
		length := int(binary.BigEndian.Uint32(rest))
		if len(rest) < 12+length {
			return E.New("truncated PNG chunk ", strconv.Quote(string(rest[4:8])), ": ", length, " bytes declared, ", len(rest)-12, " present")
		}
		chunk, content := string(rest[4:8]), rest[8:8+length]
		switch chunk {
		case "IHDR":
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
	if !slices.ContainsFunc(sizes, func(accepted size) bool {
		return width == accepted.width && height == accepted.height || width == accepted.height && height == accepted.width
	}) {
		return E.New(width, "×", height, " isn't a size the App Store takes for the device")
	}
	return nil
}

func decode(data []byte, value any) {
	err := json.Unmarshal(data, value)
	if err != nil {
		log.Fatal(err)
	}
}

func output(command *exec.Cmd) []byte {
	command.Stderr = os.Stderr
	start := time.Now()
	data, err := command.Output()
	if err != nil {
		log.Fatal(command.Args, ": ", err)
	}
	logTime(command, start)
	return data
}

func run(command *exec.Cmd) {
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	start := time.Now()
	err := command.Run()
	if err != nil {
		log.Fatal(command.Args, ": ", err)
	}
	logTime(command, start)
}

func logTime(command *exec.Cmd, start time.Time) {
	log.Printf("%5.1fs %s", time.Since(start).Seconds(), strings.Join(command.Args, " "))
}
