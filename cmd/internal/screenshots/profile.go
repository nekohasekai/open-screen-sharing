package main

import (
	"os"
	"os/exec"
	"path/filepath"

	E "github.com/sagernet/sing/common/exceptions"
	"howett.net/plist"
)

func screenshotDeviceType(device device) (identifier string, profiles string, err error) {
	if device.displayType == "" {
		return device.deviceType, "", nil
	}
	var list struct {
		DeviceTypes []struct {
			Identifier string `json:"identifier"`
			BundlePath string `json:"bundlePath"`
		} `json:"devicetypes"`
	}
	err = decodeOutput(exec.Command("xcrun", "simctl", "list", "devicetypes", "--json"), &list)
	if err != nil {
		return "", "", err
	}
	var source, displaySource string
	for _, entry := range list.DeviceTypes {
		switch entry.Identifier {
		case device.deviceType:
			source = entry.BundlePath
		case device.displayType:
			displaySource = entry.BundlePath
		}
	}
	if source == "" || displaySource == "" {
		return "", "", E.New("missing simulator device profiles for ", device.name)
	}
	profiles, err = filepath.Abs(filepath.Join("build", "screenshots", "profiles"))
	if err != nil {
		return "", "", err
	}
	err = os.MkdirAll(filepath.Join(profiles, "DeviceTypes"), 0o755)
	if err != nil {
		return "", "", err
	}
	staging, err := os.MkdirTemp(profiles, ".profile-")
	if err != nil {
		return "", "", err
	}
	defer os.RemoveAll(staging)
	err = os.CopyFS(staging, os.DirFS(source))
	if err != nil {
		return "", "", err
	}
	identifier = "io.nekohasekai.screensharing.screenshots." + device.name
	infoPath := filepath.Join(staging, "Contents", "Info.plist")
	info, err := readPlist(infoPath)
	if err != nil {
		return "", "", err
	}
	info["CFBundleIdentifier"] = identifier
	info["CFBundleName"] = "Screenshots " + device.name
	err = writePlist(infoPath, info)
	if err != nil {
		return "", "", err
	}
	capabilitiesPath := filepath.Join(staging, "Contents", "Resources", "capabilities.plist")
	capabilities, err := readPlist(capabilitiesPath)
	if err != nil {
		return "", "", err
	}
	displayCapabilities, err := readPlist(filepath.Join(displaySource, "Contents", "Resources", "capabilities.plist"))
	if err != nil {
		return "", "", err
	}
	caps, isMap := capabilities["capabilities"].(map[string]any)
	displayCaps, displayIsMap := displayCapabilities["capabilities"].(map[string]any)
	if !isMap || !displayIsMap {
		return "", "", E.New("unexpected capabilities in the simulator device profiles for ", device.name)
	}
	// CoreSimulator takes its framebuffer geometry from displays, while UIKit also reads ScreenDimensionsCapability and ArtworkTraits.
	for _, key := range []string{"displays", "ScreenDimensionsCapability", "subtype", "IsLargeFormatPhone"} {
		if value, exists := displayCaps[key]; exists {
			caps[key] = value
		} else {
			delete(caps, key)
		}
	}
	artwork, isMap := caps["ArtworkTraits"].(map[string]any)
	displayArtwork, displayIsMap := displayCaps["ArtworkTraits"].(map[string]any)
	if !isMap || !displayIsMap {
		return "", "", E.New("unexpected artwork traits in the simulator device profiles for ", device.name)
	}
	for _, key := range []string{"ArtworkDeviceSubType", "ArtworkDeviceScaleFactor"} {
		artwork[key] = displayArtwork[key]
	}
	err = writePlist(capabilitiesPath, capabilities)
	if err != nil {
		return "", "", err
	}
	destination := filepath.Join(profiles, "DeviceTypes", device.name+".simdevicetype")
	err = os.RemoveAll(destination)
	if err != nil {
		return "", "", err
	}
	err = os.Rename(staging, destination)
	if err != nil {
		return "", "", err
	}
	return identifier, profiles, nil
}

func readPlist(path string) (map[string]any, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var content map[string]any
	_, err = plist.Unmarshal(data, &content)
	if err != nil {
		return nil, E.Cause(err, "read ", path)
	}
	return content, nil
}

func writePlist(path string, content map[string]any) error {
	data, err := plist.Marshal(content, plist.XMLFormat)
	if err != nil {
		return E.Cause(err, "encode ", path)
	}
	return os.WriteFile(path, data, 0o644)
}
