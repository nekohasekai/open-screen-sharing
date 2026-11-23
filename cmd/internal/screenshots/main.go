package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"image/png"
	"log"
	"maps"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
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
	project     = "OpenScreenSharing.xcodeproj"
	scheme      = "OpenScreenSharing"
	derivedData = "build/DerivedData-screenshots"
	catalog     = "OpenScreenSharing/Resources/Localizable.xcstrings"
	bundle      = "io.nekohasekai.screensharing"
)

type page struct {
	demoName  string
	fileName  string
	localized bool
}

var (
	connectionsPage = page{demoName: "02-Connections", fileName: "01-Connections.png", localized: true}
	sessionPage     = page{demoName: "01-Session", fileName: "02-Session.png"}
)

const appTimeout = 2 * time.Minute

type size struct {
	width  int
	height int
}

type device struct {
	name        string
	deviceType  string
	displayType string
	display     string
	pose        string
	orientation string
	sizes       []size
	preferences map[string]map[string]any
}

var devices = []device{
	{
		name:       "iphone",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro",
		sizes:      []size{{1206, 2622}, {1179, 2556}},
	},
	{
		name:       "iphone-large",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro-Max",
		sizes:      []size{{1320, 2868}, {1290, 2796}, {1260, 2736}},
	},
	{
		name:       "iphone-faceid-large",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-14-Plus",
		sizes:      []size{{1284, 2778}, {1242, 2688}},
	},
	{
		name:       "iphone-faceid-medium",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-14",
		sizes:      []size{{1170, 2532}, {1125, 2436}, {1080, 2340}},
	},
	{
		name:        "iphone-home-large",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation",
		displayType: "com.apple.CoreSimulator.SimDeviceType.iPhone-8-Plus",
		sizes:       []size{{1242, 2208}},
	},
	{
		name:       "iphone-home-medium",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation",
		sizes:      []size{{750, 1334}},
	},
	{
		name:        "iphone-4",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPhone-SE-3rd-generation",
		displayType: "com.apple.CoreSimulator.SimDeviceType.iPhone-SE",
		sizes:       []size{{640, 1136}},
	},
	{
		name:       "duo-inner",
		deviceType: "com.apple.CoreSimulator.SimDeviceType.iPhone-Duo",
		sizes:      []size{{2007, 2853}},
		pose:       "open",
		display:    "primary-1",
	},
	{
		name:        "ipad",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-13-inch-M5-12GB",
		sizes:       []size{{2064, 2752}, {2048, 2732}},
		orientation: "landscapeLeft",
		preferences: tabletPreferences,
	},
	{
		name:        "ipad-12.9",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-12-9-inch-6th-generation-8GB",
		sizes:       []size{{2048, 2732}},
		orientation: "landscapeLeft",
		preferences: tabletPreferences,
	},
	{
		name:        "ipad-11",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPad-Pro-11-inch-M5-12GB",
		sizes:       []size{{1668, 2420}, {1668, 2388}, {1640, 2360}, {1488, 2266}, {1512, 2316}},
		orientation: "landscapeLeft",
		preferences: tabletPreferences,
	},
	{
		name:        "ipad-10.5",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPad-Air--3rd-generation-",
		sizes:       []size{{1668, 2224}},
		orientation: "landscapeLeft",
		preferences: tabletPreferences,
	},
	{
		name:        "ipad-9.7",
		deviceType:  "com.apple.CoreSimulator.SimDeviceType.iPad-mini--5th-generation-",
		sizes:       []size{{1536, 2048}},
		orientation: "landscapeLeft",
		preferences: tabletPreferences,
	},
}

// Full Screen Apps in Settings › Multitasking & Gestures; windowed apps get a resize grabber and the app name in the status bar.
var tabletPreferences = map[string]map[string]any{
	"com.apple.springboard": {"SBMedusaMultitaskingEnabled": false, "SBChamoisWindowingEnabled": false},
}

var simulatorPreferences = map[string]map[string]any{
	".GlobalPreferences": {"AppleLanguages": []any{"en-US"}, "AppleLocale": "en_US"},
}

var locales = []string{
	"ar-SA", "bn-BD", "ca", "cs", "da", "de-DE", "el", "en-AU", "en-CA", "en-GB", "en-US", "es-ES", "es-MX", "fi", "fr-CA", "fr-FR", "gu-IN", "he", "hi",
	"hr", "hu", "id", "it", "ja", "kn-IN", "ko", "ml-IN", "mr-IN", "ms", "nl-NL", "no", "or-IN", "pa-IN", "pl", "pt-BR",
	"pt-PT", "ro", "ru", "sk", "sl-SI", "sv", "ta-IN", "te-IN", "th", "tr", "uk", "ur-PK", "vi", "zh-Hans", "zh-Hant",
}

var localeLanguages = map[string]string{
	"es-MX": "es-419",
	"no":    "nb",
}

func main() {
	languageNames := flag.String("languages", "", "comma-separated app languages (default: all supported languages)")
	localeNames := flag.String("locales", "", "comma-separated App Store locales; cannot be combined with -languages")
	deviceNames := flag.String("devices", strings.Join(common.Map(devices, func(device device) string { return device.name }), ","), "comma-separated devices")
	appearance := flag.String("appearance", "light", "light or dark (dark writes to screenshots/dark)")
	skipBuild := flag.Bool("skip-build", false, "use the apps as last built")
	parallel := flag.Int("parallel", 1, "maximum number of simulators running at once")
	resume := flag.Bool("resume", false, "reuse existing screenshots with valid device dimensions")
	macVM := flag.String("mac-vm", "", "Parallels macOS virtual machine whose desktop the session pages show; captures the frames build/screenshots/frames lacks")
	macSSH := flag.String("mac-ssh", "", "SSH destination of the console user of -mac-vm")
	flag.Parse()
	if *parallel < 1 {
		log.Fatal("-parallel must be positive")
	}
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
	outputDirectory := "screenshots"
	if *appearance == "dark" {
		outputDirectory = filepath.Join(outputDirectory, "dark")
	}
	shots := planShots(selectLanguages(*languageNames, *localeNames))
	type captureJob struct {
		device device
		shots  []shot
	}
	var jobs []captureJob
	for _, name := range common.Uniq(strings.Split(*deviceNames, ",")) {
		index := slices.IndexFunc(devices, func(device device) bool { return device.name == name })
		if index < 0 {
			log.Fatal("unknown device: ", name)
		}
		device := devices[index]
		if err := migrateScreenshotNames(outputDirectory, device, shots); err != nil {
			log.Fatal(err)
		}
		pending := shots
		if *resume {
			pending = remainingScreenshots(outputDirectory, device, shots)
		}
		if len(pending) > 0 {
			jobs = append(jobs, captureJob{device, pending})
		}
	}
	if len(jobs) == 0 {
		log.Print("all requested screenshots are present")
		return
	}
	frames := newFrameCache(*appearance, remote)
	if !*skipBuild {
		err := run(exec.Command("xcodebuild",
			"build", "-quiet",
			"-project", project,
			"-scheme", scheme,
			"-configuration", "Release",
			"-destination", "generic/platform=iOS Simulator",
			"-derivedDataPath", derivedData,
			"ARCHS=$(NATIVE_ARCH_ACTUAL)",
			"ENABLE_CODE_COVERAGE=NO",
			"SWIFT_ACTIVE_COMPILATION_CONDITIONS=DEMO",
		))
		if err != nil {
			log.Fatal(err)
		}
	}
	saver := newScreenshotSaver()
	runJobs := func(batch []captureJob, concurrency int) []error {
		var taking sync.WaitGroup
		var failing sync.Mutex
		var failures []error
		workers := make(chan struct{}, concurrency)
		for _, job := range batch {
			workers <- struct{}{}
			taking.Go(func() {
				defer func() { <-workers }()
				err := shootDevice(outputDirectory, job.device, job.shots, *appearance, frames, saver)
				if err != nil {
					failing.Lock()
					failures = append(failures, E.Cause(err, "shoot ", job.device.name))
					failing.Unlock()
				}
			})
		}
		taking.Wait()
		return failures
	}
	var failures []error
	hasSingleShotVMWork := remote != nil && slices.ContainsFunc(jobs, func(job captureJob) bool {
		return slices.ContainsFunc(job.shots, func(s shot) bool { return s.page != connectionsPage })
	})
	maxConnectionsPerDevice := 0
	for _, job := range jobs {
		count := 0
		for _, s := range job.shots {
			if s.page == connectionsPage {
				count++
			}
		}
		if count > maxConnectionsPerDevice {
			maxConnectionsPerDevice = count
		}
	}
	if hasSingleShotVMWork && maxConnectionsPerDevice > 2 {
		var phase1, phase2 []captureJob
		for _, job := range jobs {
			var p1, p2 []shot
			for _, s := range job.shots {
				if s.page != connectionsPage {
					p1 = append(p1, s)
				} else {
					p2 = append(p2, s)
				}
			}
			if len(p1) > 0 {
				phase1 = append(phase1, captureJob{job.device, p1})
			}
			if len(p2) > 0 {
				phase2 = append(phase2, captureJob{job.device, p2})
			}
		}
		failures = append(failures, runJobs(phase1, 1)...)
		frames.writing.Wait()
		if err := remote.close(); err != nil {
			log.Print("stop ", remote.vm, ": ", err)
		}
		if len(failures) == 0 && len(phase2) > 0 {
			failures = append(failures, runJobs(phase2, *parallel)...)
		}
	} else if hasSingleShotVMWork {
		failures = append(failures, runJobs(jobs, 1)...)
		frames.writing.Wait()
		if err := remote.close(); err != nil {
			log.Print("stop ", remote.vm, ": ", err)
		}
	} else {
		failures = append(failures, runJobs(jobs, *parallel)...)
	}
	frames.writing.Wait()
	failures = append(failures, saver.wait()...)
	if len(failures) > 0 {
		for _, failure := range failures {
			log.Print(failure)
		}
		log.Fatal(len(failures), " devices/saves failed")
	}
}

type screenshotLanguage struct {
	language string
	locales  []string
}

type shot struct {
	page     page
	language string
	locales  []string
}

func selectLanguages(languageNames, localeNames string) []screenshotLanguage {
	if languageNames != "" && localeNames != "" {
		log.Fatal("use either -languages or -locales")
	}
	source, languages := appLanguages()
	destinations := make(map[string][]string)
	storeLanguages := make(map[string]string)
	for _, locale := range locales {
		language := localeLanguages[locale]
		if language == "" {
			language = locale
		}
		if !slices.Contains(languages, language) {
			language, _, _ = strings.Cut(language, "-")
		}
		if slices.Contains(languages, language) {
			storeLanguages[locale] = language
			destinations[language] = append(destinations[language], locale)
		}
	}
	for _, language := range languages {
		if len(destinations[language]) == 0 {
			destinations[language] = []string{language}
		}
	}
	if localeNames != "" {
		clear(destinations)
		for _, locale := range common.Uniq(strings.Split(localeNames, ",")) {
			if !slices.Contains(locales, locale) {
				log.Fatal("unknown App Store locale: ", locale)
			}
			language := storeLanguages[locale]
			if language == "" {
				log.Fatal("the app has no language for ", locale)
			}
			destinations[language] = append(destinations[language], locale)
		}
		languages = slices.Sorted(maps.Keys(destinations))
	} else if languageNames != "" {
		languages = common.Uniq(strings.Split(languageNames, ","))
		for _, language := range languages {
			if _, exists := destinations[language]; !exists {
				log.Fatal("unknown app language: ", language)
			}
		}
	}
	if index := slices.Index(languages, source); index > 0 {
		languages = slices.Insert(slices.Delete(languages, index, index+1), 0, source)
	}
	return common.Map(languages, func(language string) screenshotLanguage {
		return screenshotLanguage{language, destinations[language]}
	})
}

func planShots(languages []screenshotLanguage) []shot {
	var shots []shot
	for _, page := range []page{connectionsPage, sessionPage} {
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
				shots = append(shots, shot{page: page, language: language.language})
			}
			shots[index].locales = append(shots[index].locales, language.locales...)
		}
	}
	return shots
}

func migrateScreenshotNames(outputDirectory string, device device, shots []shot) error {
	for _, planned := range shots {
		for _, locale := range planned.locales {
			directory := filepath.Join(outputDirectory, locale, device.name)
			path := filepath.Join(directory, planned.page.fileName)
			if _, err := os.Lstat(path); err == nil {
				continue
			} else if !os.IsNotExist(err) {
				return err
			}
			previousPath := filepath.Join(directory, planned.page.demoName+".png")
			if err := os.Rename(previousPath, path); err != nil && !os.IsNotExist(err) {
				return err
			}
		}
	}
	return nil
}

func remainingScreenshots(outputDirectory string, device device, shots []shot) []shot {
	var remaining []shot
	for _, planned := range shots {
		var reusable []byte
		var missing []string
		for _, locale := range planned.locales {
			data, err := os.ReadFile(filepath.Join(outputDirectory, locale, device.name, planned.page.fileName))
			if err == nil {
				_, err = png.Decode(bytes.NewReader(data))
			}
			if err == nil {
				err = checkScreenshot(data, device.sizes)
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
			err := save(outputDirectory, reusable, device, missing, planned.page.fileName, time.Now())
			if err != nil {
				log.Fatal(err)
			}
		} else {
			planned.locales = missing
			remaining = append(remaining, planned)
		}
	}
	return remaining
}

func appLanguages() (source string, languages []string) {
	var content struct {
		SourceLanguage string `json:"sourceLanguage"`
		Strings        map[string]struct {
			Localizations map[string]json.RawMessage `json:"localizations"`
		} `json:"strings"`
	}
	data, err := os.ReadFile(catalog)
	if err != nil {
		log.Fatal(err)
	}
	err = json.Unmarshal(data, &content)
	if err != nil {
		log.Fatal(E.Cause(err, "read ", catalog))
	}
	known := map[string]bool{content.SourceLanguage: true}
	for _, entry := range content.Strings {
		for language := range entry.Localizations {
			known[language] = true
		}
	}
	return content.SourceLanguage, slices.Sorted(maps.Keys(known))
}

var simctlPath = sync.OnceValues(func() (string, error) {
	data, err := output(exec.Command("xcrun", "-f", "simctl"))
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(data)), nil
})

func simctlCommand(args ...string) (*exec.Cmd, error) {
	bin, err := simctlPath()
	if err != nil {
		return nil, err
	}
	return exec.Command(bin, args...), nil
}

func simctlCommandContext(ctx context.Context, args ...string) (*exec.Cmd, error) {
	bin, err := simctlPath()
	if err != nil {
		return nil, err
	}
	return exec.CommandContext(ctx, bin, args...), nil
}

type simRuntime struct {
	Identifier           string `json:"identifier"`
	Platform             string `json:"platform"`
	Version              string `json:"version"`
	IsAvailable          bool   `json:"isAvailable"`
	SupportedDeviceTypes []struct {
		Identifier string `json:"identifier"`
	} `json:"supportedDeviceTypes"`
}

var cachedRuntimes = sync.OnceValues(func() ([]simRuntime, error) {
	cmd, err := simctlCommand("list", "runtimes", "--json")
	if err != nil {
		return nil, err
	}
	var list struct {
		Runtimes []simRuntime `json:"runtimes"`
	}
	err = decodeOutput(cmd, &list)
	if err != nil {
		return nil, err
	}
	return list.Runtimes, nil
})

func latestRuntime(deviceType string) (string, error) {
	runtimes, err := cachedRuntimes()
	if err != nil {
		return "", err
	}
	var latest string
	var latestVersion []int
	for _, runtime := range runtimes {
		if runtime.Platform != "iOS" || !runtime.IsAvailable || !slices.ContainsFunc(runtime.SupportedDeviceTypes, func(supported struct {
			Identifier string `json:"identifier"`
		},
		) bool {
			return supported.Identifier == deviceType
		}) {
			continue
		}
		version, err := parseVersion(runtime.Version)
		if err != nil {
			return "", err
		}
		if slices.Compare(version, latestVersion) > 0 {
			latest = runtime.Identifier
			latestVersion = version
		}
	}
	if latest == "" {
		return "", E.New("no iOS simulator runtime for ", deviceType)
	}
	return latest, nil
}

func parseVersion(version string) ([]int, error) {
	var parts []int
	for _, part := range strings.Split(version, ".") {
		number, err := strconv.Atoi(part)
		if err != nil {
			return nil, E.Cause(err, "parse version ", version)
		}
		parts = append(parts, number)
	}
	return parts, nil
}

type simulator struct {
	UDID                 string `json:"udid"`
	Name                 string `json:"name"`
	State                string `json:"state"`
	DeviceTypeIdentifier string `json:"deviceTypeIdentifier"`
	DataPath             string `json:"dataPath"`
}

var (
	devicesCacheMu sync.Mutex
	devicesCache   = make(map[string]map[string][]simulator)
)

func listDevices(profiles string, refresh bool) (map[string][]simulator, error) {
	devicesCacheMu.Lock()
	defer devicesCacheMu.Unlock()
	if !refresh {
		if cached, ok := devicesCache[profiles]; ok {
			return cached, nil
		}
	}
	args := []string{}
	if profiles != "" {
		args = append(args, "--profiles", profiles)
	}
	args = append(args, "list", "devices", "--json")
	cmd, err := simctlCommand(args...)
	if err != nil {
		return nil, err
	}
	var list struct {
		Devices map[string][]simulator `json:"devices"`
	}
	err = decodeOutput(cmd, &list)
	if err != nil {
		return nil, err
	}
	devicesCache[profiles] = list.Devices
	return list.Devices, nil
}

func findSimulator(profiles string, device device, runtime string, refresh bool) (simulator, bool, error) {
	byRuntime, err := listDevices(profiles, refresh)
	if err != nil {
		return simulator{}, false, err
	}
	simulators := byRuntime[runtime]
	index := slices.IndexFunc(simulators, func(existing simulator) bool {
		return existing.Name == "Screenshots "+device.name && existing.DeviceTypeIdentifier == device.deviceType
	})
	if index < 0 {
		return simulator{}, false, nil
	}
	return simulators[index], true, nil
}

func shootDevice(outputDirectory string, device device, shots []shot, appearance string, frames *frameCache, saver *screenshotSaver) (err error) {
	booted, profileArgs, err := prepareSimulator(device)
	if err != nil {
		return err
	}
	if booted.State == "Shutdown" {
		err = boot(profileArgs, booted.UDID)
		if err != nil {
			if cmd, cmdErr := simctlCommand("shutdown", booted.UDID); cmdErr == nil {
				_ = cmd.Run()
			}
			return err
		}
	}
	defer func() {
		cmd, cmdErr := simctlCommand("shutdown", booted.UDID)
		if cmdErr != nil {
			err = E.Append(err, cmdErr, func(e error) error { return E.Cause(e, "shut down ", booted.Name) })
			return
		}
		err = E.Append(err, run(cmd), func(err error) error {
			return E.Cause(err, "shut down ", booted.Name)
		})
	}()
	err = setUpSimulator(device, booted.UDID, profileArgs, appearance)
	if err != nil {
		return err
	}
	var singleShots, connectionsShots []shot
	for _, planned := range shots {
		if planned.page == connectionsPage {
			connectionsShots = append(connectionsShots, planned)
		} else {
			singleShots = append(singleShots, planned)
		}
	}
	for _, planned := range singleShots {
		err = takeScreenshot(outputDirectory, device, booted, planned, frames, saver)
		if err != nil {
			return E.Cause(err, "take ", planned.page.fileName, " in ", planned.language)
		}
	}
	if len(connectionsShots) > 1 {
		err = takeConnectionsBatch(outputDirectory, device, booted, connectionsShots, frames, saver)
		if err != nil {
			return E.Cause(err, "take connection lists")
		}
	} else if len(connectionsShots) == 1 {
		err = takeScreenshot(outputDirectory, device, booted, connectionsShots[0], frames, saver)
		if err != nil {
			return E.Cause(err, "take ", connectionsPage.fileName, " in ", connectionsShots[0].language)
		}
	}
	return nil
}

func prepareSimulator(device device) (simulator, []string, error) {
	deviceType, profiles, err := screenshotDeviceType(device)
	if err != nil {
		return simulator{}, nil, err
	}
	runtime, err := latestRuntime(device.deviceType)
	if err != nil {
		return simulator{}, nil, err
	}
	device.deviceType = deviceType
	log.Print(device.name, " on ", runtime)
	var profileArgs []string
	if profiles != "" {
		profileArgs = []string{"--profiles", profiles}
	}
	existing, found, err := findSimulator(profiles, device, runtime, false)
	if err != nil {
		return simulator{}, nil, err
	}
	if !found {
		cmd, err := simctlCommand(slices.Concat(profileArgs, []string{"create", "Screenshots " + device.name, device.deviceType, runtime})...)
		if err != nil {
			return simulator{}, nil, err
		}
		_, err = output(cmd)
		if err != nil {
			return simulator{}, nil, err
		}
		existing, found, err = findSimulator(profiles, device, runtime, true)
		if err != nil {
			return simulator{}, nil, err
		}
		if !found {
			return simulator{}, nil, E.New("the created simulator Screenshots ", device.name, " is not listed")
		}
	}
	stale := make(map[string]map[string]any)
	for _, preferences := range []map[string]map[string]any{simulatorPreferences, device.preferences} {
		for file, values := range preferences {
			path := filepath.Join(existing.DataPath, "Library/Preferences", file+".plist")
			content := make(map[string]any)
			if _, err = os.Stat(path); err == nil {
				content, err = readPlist(path)
				if err != nil {
					return simulator{}, nil, err
				}
			}
			for key, value := range values {
				if !reflect.DeepEqual(content[key], value) {
					content[key] = value
					stale[path] = content
				}
			}
		}
	}
	if len(stale) > 0 && existing.State != "Shutdown" {
		cmd, err := simctlCommand("shutdown", existing.UDID)
		if err != nil {
			return simulator{}, nil, err
		}
		err = run(cmd)
		if err != nil {
			return simulator{}, nil, err
		}
		existing.State = "Shutdown"
	}
	for path, content := range stale {
		err = os.MkdirAll(filepath.Dir(path), 0o755)
		if err != nil {
			return simulator{}, nil, err
		}
		err = writePlist(path, content)
		if err != nil {
			return simulator{}, nil, err
		}
	}
	return existing, profileArgs, nil
}

func setUpSimulator(device device, udid string, profileArgs []string, appearance string) error {
	apps, err := filepath.Glob(filepath.Join(derivedData, "Build/Products/Release-iphonesimulator/*.app"))
	if err != nil {
		return err
	}
	if len(apps) != 1 {
		return E.New("no single iOS app in ", derivedData, "; build it first")
	}
	bootStatusCmd, err := simctlCommand(slices.Concat(profileArgs, []string{"bootstatus", udid, "-b"})...)
	if err != nil {
		return err
	}
	if _, err = output(bootStatusCmd); err != nil {
		return err
	}
	var setupGroup sync.WaitGroup
	var installErr, configErr error
	setupGroup.Go(func() {
		installCmd, err := simctlCommand("install", udid, apps[0])
		if err != nil {
			installErr = err
			return
		}
		installErr = run(installCmd)
	})
	setupGroup.Go(func() {
		uiCmd, err := simctlCommand("ui", udid, "appearance", appearance)
		if err != nil {
			configErr = err
			return
		}
		if _, err = output(uiCmd); err != nil {
			configErr = err
			return
		}
		statusBarCmd, err := simctlCommand("status_bar", udid, "override",
			"--dataNetwork", "wifi",
			"--wifiMode", "active",
			"--wifiBars", "3",
			"--cellularMode", "active",
			"--cellularBars", "4",
			"--batteryState", "charged",
			"--batteryLevel", "100",
			"--time", time.Date(2007, time.January, 9, 9, 41, 0, 0, time.Local).Format("2006-01-02T15:04:05.000Z07:00"),
		)
		if err != nil {
			configErr = err
			return
		}
		if _, err = output(statusBarCmd); err != nil {
			configErr = err
			return
		}
		if device.pose != "" {
			if err = setPose(udid, device.pose); err != nil {
				configErr = err
				return
			}
		}
		if device.orientation != "" {
			if err = run(exec.Command("xcrun", "devicectl", "--quiet", "device", "orientation", "set", "--device", udid, device.orientation)); err != nil {
				configErr = err
				return
			}
		}
	})
	setupGroup.Wait()
	return E.Errors(installErr, configErr)
}

var booting sync.Mutex

// CoreSimulatorService can refuse to boot an installed runtime with "runtime path not found" until it restarts, and
// restarting it shuts down every booted simulator.
func boot(profileArgs []string, udid string) error {
	command, err := simctlCommand(slices.Concat(profileArgs, []string{"boot", udid})...)
	if err != nil {
		return err
	}
	start := time.Now()
	message, err := command.CombinedOutput()
	if err == nil {
		logTime(command, start)
		return nil
	}
	booting.Lock()
	defer booting.Unlock()
	listCmd, listCmdErr := simctlCommand("list", "devices", "booted", "--json")
	if listCmdErr != nil {
		return E.Errors(E.Cause(err, "boot ", udid, ": ", strings.TrimSpace(string(message))), listCmdErr)
	}
	var list struct {
		Devices map[string][]simulator `json:"devices"`
	}
	listErr := decodeOutput(listCmd, &list)
	if listErr != nil {
		return E.Errors(E.Cause(err, "boot ", udid, ": ", strings.TrimSpace(string(message))), listErr)
	}
	for _, booted := range list.Devices {
		if len(booted) > 0 {
			return E.Cause(err, "boot ", udid, ": ", strings.TrimSpace(string(message)), "; shut down ", booted[0].Name, " to let CoreSimulatorService restart")
		}
	}
	log.Print(strings.TrimSpace(string(message)), "\nrestarting CoreSimulatorService")
	_ = exec.Command("killall", "com.apple.CoreSimulator.CoreSimulatorService").Run()
	retryCmd, err := simctlCommand(slices.Concat(profileArgs, []string{"boot", udid})...)
	if err != nil {
		return err
	}
	return run(retryCmd)
}

type saveRequest struct {
	outputDirectory string
	capturedPath    string
	device          device
	locales         []string
	fileName        string
	start           time.Time
}

type screenshotSaver struct {
	queue  chan saveRequest
	wg     sync.WaitGroup
	mu     sync.Mutex
	errors []error
}

func newScreenshotSaver() *screenshotSaver {
	s := &screenshotSaver{
		queue: make(chan saveRequest, 64),
	}
	workers := max(2, runtime.NumCPU())
	for range workers {
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
	data, err := os.ReadFile(req.capturedPath)
	_ = os.Remove(req.capturedPath)
	if err != nil {
		return err
	}
	if hasPNGAlpha(data) {
		screenshot, err := png.Decode(bytes.NewReader(data))
		if err != nil {
			return E.Cause(err, "decode the screenshot")
		}
		var opaque bytes.Buffer
		err = fastPNG.Encode(&opaque, screenshot)
		if err != nil {
			return err
		}
		data = opaque.Bytes()
	}
	return save(req.outputDirectory, data, req.device, req.locales, req.fileName, req.start)
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

func captureArgs(udid string, display string, outputPath string) []string {
	args := []string{"io", udid, "screenshot", "--type=png", "--mask=ignored"}
	if display != "" {
		args = append(args, "--display="+display)
	}
	return append(args, outputPath)
}

func captureScreen(ctx context.Context, udid, display, outputPath string, stable bool) error {
	args := captureArgs(udid, display, outputPath)
	var prev []byte
	for {
		cmd, err := simctlCommandContext(ctx, args...)
		if err != nil {
			return err
		}
		message, err := cmd.CombinedOutput()
		if err != nil {
			return E.Cause(err, "capture the screen: ", strings.TrimSpace(string(message)))
		}
		if !stable {
			return nil
		}
		frame, err := os.ReadFile(outputPath)
		if err != nil {
			return err
		}
		if bytes.Equal(frame, prev) {
			return nil
		}
		prev = frame
	}
}

func takeScreenshot(outputDirectory string, device device, booted simulator, planned shot, frames *frameCache, saver *screenshotSaver) error {
	start := time.Now()
	dir, err := filepath.Abs(filepath.Join("build", "screenshots"))
	if err != nil {
		return err
	}
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "screenshot-"+booted.UDID+"-*.png")
	if err != nil {
		return err
	}
	captured := tmp.Name()
	_ = tmp.Close()
	keepFile := false
	defer func() {
		if !keepFile {
			_ = os.Remove(captured)
		}
	}()
	app, err := simctlCommand("launch", "--console-pty", "--terminate-running-process", booted.UDID, bundle,
		"-AppleLanguages", "("+planned.language+")",
		"-AppleLocale", planned.language,
		"-DemoScreenshot", planned.page.demoName,
		"-DemoFrames", frames.directory,
		"-Demo", "YES",
	)
	if err != nil {
		return err
	}
	app.Stderr = os.Stderr
	console, err := app.StdoutPipe()
	if err != nil {
		return err
	}
	err = app.Start()
	if err != nil {
		return E.Cause(err, "launch ", bundle)
	}
	defer func() {
		_ = app.Process.Kill()
		_ = app.Wait()
	}()
	finished := make(chan struct{})
	defer close(finished)
	shown := make(chan struct{})
	exited := make(chan struct{})
	failed := make(chan error, 1)
	pending := make(chan int)
	fail := func(err error) {
		select {
		case failed <- err:
		default:
		}
	}
	track := func(change int) {
		select {
		case pending <- change:
		case <-finished:
		}
	}
	go func() {
		defer close(exited)
		lines := bufio.NewScanner(console)
		for lines.Scan() {
			line := strings.TrimSpace(lines.Text())
			request, isFrame := strings.CutPrefix(line, "frame ")
			switch {
			case line == planned.page.demoName:
				select {
				case <-shown:
				default:
					close(shown)
				}
			case isFrame:
				frame, err := frames.request(request)
				if err != nil {
					fail(err)
					continue
				}
				track(1)
				go func() {
					<-frame.done
					if frame.err != nil {
						fail(frame.err)
					}
					track(-1)
				}()
			}
		}
	}()
	idle := time.NewTimer(appTimeout)
	defer idle.Stop()
	for waiting, showing := 0, true; showing; {
		select {
		case <-shown:
			showing = false
		case err = <-failed:
			return err
		case <-exited:
			return E.New(bundle, " exited before it showed ", planned.page.fileName)
		case change := <-pending:
			waiting += change
			if waiting == 0 {
				idle.Reset(appTimeout)
			} else {
				idle.Stop()
			}
		case <-idle.C:
			return E.New(bundle, " did not show ", planned.page.fileName, " within ", appTimeout, " of its last frame")
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if err = captureScreen(ctx, booted.UDID, device.display, captured, true); err != nil {
		return err
	}
	keepFile = true
	saver.submit(saveRequest{
		outputDirectory: outputDirectory,
		capturedPath:    captured,
		device:          device,
		locales:         planned.locales,
		fileName:        planned.page.fileName,
		start:           start,
	})
	return nil
}

func takeConnectionsBatch(outputDirectory string, device device, booted simulator, shots []shot, frames *frameCache, saver *screenshotSaver) error {
	dir, err := filepath.Abs(filepath.Join("build", "screenshots"))
	if err != nil {
		return err
	}
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	defer ln.Close()
	port := ln.Addr().(*net.TCPAddr).Port

	app, err := simctlCommand("launch", "--console-pty", "--terminate-running-process", booted.UDID, bundle,
		"-DemoScreenshot", connectionsPage.demoName,
		"-DemoFrames", frames.directory,
		"-DemoControlPort", strconv.Itoa(port),
		"-Demo", "YES",
	)
	if err != nil {
		return err
	}
	app.Stderr = os.Stderr
	console, err := app.StdoutPipe()
	if err != nil {
		return err
	}
	if err = app.Start(); err != nil {
		return E.Cause(err, "launch ", bundle)
	}
	defer func() {
		_ = app.Process.Kill()
		_ = app.Wait()
	}()

	finished := make(chan struct{})
	defer close(finished)
	exited := make(chan struct{})
	failed := make(chan error, 1)
	pending := make(chan int)
	accepted := make(chan net.Conn, 1)
	fail := func(err error) {
		select {
		case failed <- err:
		default:
		}
	}
	track := func(change int) {
		select {
		case pending <- change:
		case <-finished:
		}
	}
	go func() {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		select {
		case accepted <- conn:
		case <-finished:
			_ = conn.Close()
		}
	}()
	go func() {
		defer close(exited)
		lines := bufio.NewScanner(console)
		for lines.Scan() {
			line := strings.TrimSpace(lines.Text())
			if request, isFrame := strings.CutPrefix(line, "frame "); isFrame {
				frame, err := frames.request(request)
				if err != nil {
					fail(err)
					continue
				}
				track(1)
				go func() {
					<-frame.done
					if frame.err != nil {
						fail(frame.err)
					}
					track(-1)
				}()
			}
		}
	}()

	var conn net.Conn
	idle := time.NewTimer(appTimeout)
	defer idle.Stop()
	for waiting := 0; conn == nil; {
		select {
		case conn = <-accepted:
		case err = <-failed:
			return err
		case <-exited:
			return E.New(bundle, " exited before connecting on port ", port)
		case change := <-pending:
			waiting += change
			if waiting == 0 {
				idle.Reset(appTimeout)
			} else {
				idle.Stop()
			}
		case <-idle.C:
			return E.New(bundle, " did not connect within ", appTimeout)
		}
	}
	defer conn.Close()
	reply := bufio.NewReader(conn)

	for i, planned := range shots {
		shotStart := time.Now()
		_ = conn.SetDeadline(time.Now().Add(appTimeout))
		if _, err = fmt.Fprintln(conn, planned.language); err != nil {
			return E.Cause(err, "request language ", planned.language)
		}
		line, err := reply.ReadString('\n')
		if err != nil {
			return E.Cause(err, "wait for ", planned.language)
		}
		if strings.TrimSpace(line) != "ok" {
			return E.New("unexpected batch reply for ", planned.language, ": ", strings.TrimSpace(line))
		}
		tmp, err := os.CreateTemp(dir, fmt.Sprintf("screenshot-%s-%d-*.png", booted.UDID, i))
		if err != nil {
			return err
		}
		captured := tmp.Name()
		_ = tmp.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		err = captureScreen(ctx, booted.UDID, device.display, captured, i == 0)
		cancel()
		if err != nil {
			_ = os.Remove(captured)
			return E.Cause(err, "capture ", planned.language)
		}
		saver.submit(saveRequest{
			outputDirectory: outputDirectory,
			capturedPath:    captured,
			device:          device,
			locales:         planned.locales,
			fileName:        planned.page.fileName,
			start:           shotStart,
		})
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	_, _ = fmt.Fprintln(conn, "quit")
	return nil
}

func save(outputDirectory string, data []byte, device device, locales []string, fileName string, start time.Time) error {
	err := checkScreenshot(data, device.sizes)
	if err != nil {
		return E.Cause(err, "check ", fileName)
	}
	for _, locale := range locales {
		path := filepath.Join(outputDirectory, locale, device.name, fileName)
		err = os.MkdirAll(filepath.Dir(path), 0o755)
		if err != nil {
			return err
		}
		err = os.WriteFile(path, data, 0o644)
		if err != nil {
			return err
		}
		log.Printf("%5.1fs %s", time.Since(start).Seconds(), path)
	}
	return nil
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
	if !slices.ContainsFunc(sizes, func(accepted size) bool {
		return width == accepted.width && height == accepted.height || width == accepted.height && height == accepted.width
	}) {
		return E.New(width, "×", height, " isn't a size the App Store takes for the device")
	}
	return nil
}

func decodeOutput(command *exec.Cmd, value any) error {
	data, err := output(command)
	if err != nil {
		return err
	}
	err = json.Unmarshal(data, value)
	if err != nil {
		return E.Cause(err, "decode the output of ", strings.Join(command.Args, " "))
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

func buildHelper(name, sdk, source string, flags ...string) (string, error) {
	sdkOutput, err := output(exec.Command("xcrun", "--sdk", sdk, "--show-sdk-path"))
	if err != nil {
		return "", err
	}
	sdkPath := strings.TrimSpace(string(sdkOutput))
	hash := sha256.Sum256([]byte(sdkPath + "\n" + source))
	path, err := filepath.Abs(filepath.Join("build", "screenshots", name+"-"+hex.EncodeToString(hash[:8])))
	if err != nil {
		return "", err
	}
	if info, err := os.Stat(path); err == nil && info.Mode().IsRegular() && info.Mode()&0o111 != 0 {
		return path, nil
	}
	err = os.MkdirAll(filepath.Dir(path), 0o755)
	if err != nil {
		return "", err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), "."+name+"-*")
	if err != nil {
		return "", err
	}
	err = temporary.Close()
	if err != nil {
		return "", err
	}
	defer os.Remove(temporary.Name())
	command := exec.Command("xcrun", slices.Concat([]string{"--sdk", sdk, "clang", "-isysroot", sdkPath, "-fobjc-arc"}, flags, []string{"-x", "objective-c", "-", "-o", temporary.Name()})...)
	command.Stdin = strings.NewReader(source)
	err = run(command)
	if err != nil {
		return "", E.Cause(err, "build the ", name, " helper")
	}
	err = os.Chmod(temporary.Name(), 0o755)
	if err != nil {
		return "", err
	}
	err = os.Rename(temporary.Name(), path)
	if err != nil {
		return "", err
	}
	return path, nil
}
