package main

import (
	"archive/zip"
	"debug/buildinfo"
	"flag"
	"go/build"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"

	_ "github.com/sagernet/gomobile"
	"github.com/sagernet/sing/common"
)

const (
	gomobileModule    = "github.com/sagernet/gomobile"
	iosVersion        = "15.0"
	macOSVersion      = "14.0"
	androidAPI        = 24
	appleOutputPath   = "Frameworks/Libsharing.xcframework"
	androidOutputPath = "OpenScreenSharingAndroid/libs/libsharing.aar"
	androidProguard   = "-keep class go.** { *; }\n-keep class io.nekohasekai.libsharing.** { *; }\n"
)

func main() {
	target := flag.String("target", "ios,iossimulator,macos", "gomobile bind target")
	flag.Parse()
	binDirectory := filepath.Join(filepath.SplitList(build.Default.GOPATH)[0], "bin")
	gomobileVersion := pinnedGomobileVersion()
	var missingTools []string
	for _, tool := range []string{"gomobile", "gobind"} {
		if !toolInstalled(filepath.Join(binDirectory, tool), gomobileModule+"/cmd/"+tool, gomobileVersion) {
			missingTools = append(missingTools, gomobileModule+"/cmd/"+tool+"@"+gomobileVersion)
		}
	}
	if len(missingTools) > 0 {
		install := exec.Command("go", append([]string{"install", "-v"}, missingTools...)...)
		install.Env = append(os.Environ(), "GOBIN="+binDirectory)
		run(install)
	}

	arguments := []string{"bind", "-v", "-target", *target}
	outputPath := appleOutputPath
	ldflags := "-s -w -buildid= -checklinkname=0"
	android := strings.HasPrefix(*target, "android")
	if android {
		outputPath = androidOutputPath
		ldflags += " -extldflags=-Wl,-z,max-page-size=16384"
		arguments = append(arguments, "-androidapi", strconv.Itoa(androidAPI), "-javapkg", "io.nekohasekai")
	} else {
		arguments = append(arguments, "-iosversion", iosVersion, "-macosversion", macOSVersion)
	}
	err := os.MkdirAll(filepath.Dir(outputPath), 0o755)
	if err != nil {
		log.Fatal(err)
	}
	arguments = append(arguments,
		"-trimpath",
		"-buildvcs=false",
		"-ldflags", ldflags,
		"-o", outputPath,
		"./libsharing",
	)
	bind := exec.Command(filepath.Join(binDirectory, "gomobile"), arguments...)
	bind.Env = append(os.Environ(), "PATH="+binDirectory+string(os.PathListSeparator)+os.Getenv("PATH"))
	run(bind)
	if android {
		replaceProguardRules(outputPath)
	}
}

func replaceProguardRules(path string) {
	reader, err := zip.OpenReader(path)
	if err != nil {
		log.Fatal(err)
	}
	temporaryPath := path + ".tmp"
	file, err := os.Create(temporaryPath)
	if err != nil {
		log.Fatal(err)
	}
	writer := zip.NewWriter(file)
	for _, entry := range reader.File {
		if entry.Name != "proguard.txt" {
			err = writer.Copy(entry)
			if err != nil {
				log.Fatal(err)
			}
			continue
		}
		rules, err := writer.Create(entry.Name)
		if err != nil {
			log.Fatal(err)
		}
		_, err = io.WriteString(rules, androidProguard)
		if err != nil {
			log.Fatal(err)
		}
	}
	err = writer.Close()
	if err != nil {
		log.Fatal(err)
	}
	err = file.Close()
	if err != nil {
		log.Fatal(err)
	}
	reader.Close()
	err = os.Rename(temporaryPath, path)
	if err != nil {
		log.Fatal(err)
	}
}

func pinnedGomobileVersion() string {
	info, loaded := debug.ReadBuildInfo()
	if !loaded {
		log.Fatal("missing build info")
	}
	module := common.Find(info.Deps, func(module *debug.Module) bool {
		return module.Path == gomobileModule
	})
	if module == nil {
		log.Fatal("missing ", gomobileModule, " in build info")
	}
	return module.Version
}

func toolInstalled(path string, packagePath string, version string) bool {
	info, err := buildinfo.ReadFile(path)
	if err != nil {
		return false
	}
	return info.Path == packagePath && info.Main.Path == gomobileModule && info.Main.Version == version
}

func run(command *exec.Cmd) {
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	err := command.Run()
	if err != nil {
		log.Fatal(err)
	}
}
