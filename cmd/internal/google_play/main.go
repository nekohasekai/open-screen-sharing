package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	E "github.com/sagernet/sing/common/exceptions"
	F "github.com/sagernet/sing/common/format"
	"google.golang.org/api/androidpublisher/v3"
	"google.golang.org/api/option"
)

const (
	packageName     = "io.nekohasekai.screensharing"
	credentialsFile = "service-account-credentials.json"
)

func main() {
	if len(os.Args) < 2 {
		log.Fatal("usage: google_play next_version_code")
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	var err error
	switch os.Args[1] {
	case "next_version_code":
		err = printNextVersionCode(ctx)
	default:
		log.Fatal("unknown action: ", os.Args[1])
	}
	if err != nil {
		log.Fatal(err)
	}
}

func createService(ctx context.Context) (*androidpublisher.Service, error) {
	var credOption option.ClientOption
	if credentialsJSON := os.Getenv("ANDROID_PUBLISHER_CREDENTIALS"); credentialsJSON != "" {
		credOption = option.WithAuthCredentialsJSON(option.ServiceAccount, []byte(credentialsJSON))
	} else {
		credOption = option.WithAuthCredentialsFile(option.ServiceAccount, credentialsFile)
	}
	return androidpublisher.NewService(ctx, credOption, option.WithScopes(androidpublisher.AndroidpublisherScope))
}

func printNextVersionCode(ctx context.Context) error {
	service, err := createService(ctx)
	if err != nil {
		return E.Cause(err, "create androidpublisher service")
	}
	edit, err := service.Edits.Insert(packageName, &androidpublisher.AppEdit{}).Context(ctx).Do()
	if err != nil {
		return E.Cause(err, "insert edit")
	}
	defer func() {
		_ = service.Edits.Delete(packageName, edit.Id).Context(context.Background()).Do()
	}()

	nextVersionCode := int64(1)

	bundles, err := service.Edits.Bundles.List(packageName, edit.Id).Context(ctx).Do()
	if err != nil {
		return E.Cause(err, "list bundles")
	}
	for _, bundle := range bundles.Bundles {
		nextVersionCode = max(nextVersionCode, bundle.VersionCode+1)
	}

	apks, err := service.Edits.Apks.List(packageName, edit.Id).Context(ctx).Do()
	if err != nil {
		return E.Cause(err, "list apks")
	}
	for _, apk := range apks.Apks {
		nextVersionCode = max(nextVersionCode, apk.VersionCode+1)
	}

	tracks, err := service.Edits.Tracks.List(packageName, edit.Id).Context(ctx).Do()
	if err != nil {
		return E.Cause(err, "list tracks")
	}
	for _, track := range tracks.Tracks {
		for _, release := range track.Releases {
			for _, versionCode := range release.VersionCodes {
				nextVersionCode = max(nextVersionCode, versionCode+1)
			}
		}
	}

	os.Stdout.WriteString(F.ToString(nextVersionCode, "\n"))
	return nil
}
