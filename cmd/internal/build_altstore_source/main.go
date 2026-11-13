package main

import (
	"archive/zip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"flag"
	"io"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/sagernet/sing/common"
	"howett.net/plist"
)

const (
	ipaPath    = "build/OpenScreenSharing.ipa"
	outputPath = "build/altstore"
)

type source struct {
	Name       string `json:"name"`
	Identifier string `json:"identifier"`
	SourceURL  string `json:"sourceURL"`
	IconURL    string `json:"iconURL"`
	Website    string `json:"website"`
	Apps       []app  `json:"apps"`
	News       []any  `json:"news"`
}

type app struct {
	Name                 string      `json:"name"`
	BundleIdentifier     string      `json:"bundleIdentifier"`
	DeveloperName        string      `json:"developerName"`
	LocalizedDescription string      `json:"localizedDescription"`
	IconURL              string      `json:"iconURL"`
	Category             string      `json:"category"`
	Versions             []version   `json:"versions"`
	AppPermissions       permissions `json:"appPermissions"`
}

type version struct {
	Version      string `json:"version"`
	BuildVersion string `json:"buildVersion"`
	Date         string `json:"date"`
	DownloadURL  string `json:"downloadURL"`
	Size         int64  `json:"size"`
	SHA256       string `json:"sha256"`
	MinOSVersion string `json:"minOSVersion"`
}

type permissions struct {
	Entitlements []string          `json:"entitlements"`
	Privacy      map[string]string `json:"privacy"`
}

var infoPlistName = regexp.MustCompile(`^Payload/[^/]+\.app/Info\.plist$`)

func main() {
	tag := flag.String("tag", "nightly", "release tag")
	flag.Parse()
	repository := os.Getenv("GITHUB_REPOSITORY")
	if repository == "" {
		repository = "nekohasekai/open-screen-sharing"
	}
	websiteURL := "https://github.com/" + repository
	releaseURL := websiteURL + "/releases/download/" + *tag + "/"
	info := readInfoPlist()
	name := "Open Screen Sharing Nightly"
	identifier := info["CFBundleIdentifier"].(string)
	sourceURL := releaseURL + "altstore.json"
	if *tag != "nightly" {
		name = "Open Screen Sharing"
		identifier += ".stable"
		sourceURL = websiteURL + "/releases/latest/download/altstore.json"
	}
	content, err := os.ReadFile(ipaPath)
	if err != nil {
		log.Fatal(err)
	}
	hash := sha256.Sum256(content)
	privacy := make(map[string]string)
	for key, value := range info {
		description, isString := value.(string)
		if isString && strings.Contains(key, "UsageDescription") {
			privacy[key] = description
		}
	}
	err = os.MkdirAll(outputPath, 0o755)
	if err != nil {
		log.Fatal(err)
	}
	appSource := source{
		Name:       name,
		Identifier: identifier,
		SourceURL:  sourceURL,
		IconURL:    releaseURL + "icon.png",
		Website:    websiteURL,
		Apps: []app{{
			Name:                 info["CFBundleDisplayName"].(string),
			BundleIdentifier:     info["CFBundleIdentifier"].(string),
			DeveloperName:        "nekohasekai",
			LocalizedDescription: "A remote desktop client that doesn't suck.",
			IconURL:              releaseURL + "icon.png",
			Category:             "utilities",
			Versions: []version{{
				Version:      info["CFBundleShortVersionString"].(string),
				BuildVersion: info["CFBundleVersion"].(string),
				Date:         time.Now().UTC().Format(time.RFC3339),
				DownloadURL:  releaseURL + filepath.Base(ipaPath),
				Size:         int64(len(content)),
				SHA256:       hex.EncodeToString(hash[:]),
				MinOSVersion: info["MinimumOSVersion"].(string),
			}},
			AppPermissions: permissions{
				Entitlements: []string{},
				Privacy:      privacy,
			},
		}},
		News: []any{},
	}
	output, err := json.MarshalIndent(appSource, "", "  ")
	if err != nil {
		log.Fatal(err)
	}
	err = os.WriteFile(filepath.Join(outputPath, "altstore.json"), append(output, '\n'), 0o644)
	if err != nil {
		log.Fatal(err)
	}
}

func readInfoPlist() map[string]any {
	archive, err := zip.OpenReader(ipaPath)
	if err != nil {
		log.Fatal(err)
	}
	defer archive.Close()
	file := common.Find(archive.File, func(file *zip.File) bool {
		return infoPlistName.MatchString(file.Name)
	})
	if file == nil {
		log.Fatal("missing Info.plist in ", ipaPath)
	}
	reader, err := file.Open()
	if err != nil {
		log.Fatal(err)
	}
	content, err := io.ReadAll(reader)
	reader.Close()
	if err != nil {
		log.Fatal(err)
	}
	var info map[string]any
	_, err = plist.Unmarshal(content, &info)
	if err != nil {
		log.Fatal(err)
	}
	return info
}
