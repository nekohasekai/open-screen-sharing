package main

import (
	"encoding/json"
	"encoding/xml"
	"io"
	"log"
	"maps"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

const (
	resourcesPath = "OpenScreenSharingAndroid/src/main/res"
	outputName    = "strings_translated.xml"
)

var catalogPaths = []string{
	"OpenScreenSharing/Resources/Localizable.xcstrings",
	"OpenScreenSharing/Resources/InfoPlist.xcstrings",
	"OpenScreenSharingAndroid/Localizable.xcstrings",
}

var catalogKeys = map[string]string{
	"app_name":        "CFBundleDisplayName",
	"model_edit_menu": "Edit Menu",
}

var specifierPattern = regexp.MustCompile(`%(?:(\d+)\$)?(?:\.\d+)?(@|lld|ld|d|f|s)`)

type catalog struct {
	SourceLanguage string                  `json:"sourceLanguage"`
	Strings        map[string]catalogEntry `json:"strings"`
}

type catalogEntry struct {
	Localizations map[string]struct {
		StringUnit struct {
			State string `json:"state"`
			Value string `json:"value"`
		} `json:"stringUnit"`
	} `json:"localizations"`
}

type baseString struct {
	name string
	text string
}

func main() {
	translations := make(map[string]map[string]string)
	canonicalKeys := make(map[string]string)
	for _, path := range catalogPaths {
		content, err := os.ReadFile(path)
		if err != nil {
			log.Fatal(err)
		}
		var loaded catalog
		err = json.Unmarshal(content, &loaded)
		if err != nil {
			log.Fatal(path, ": ", err)
		}
		for _, key := range slices.Sorted(maps.Keys(loaded.Strings)) {
			entry := loaded.Strings[key]
			source := key
			if english, found := entry.Localizations[loaded.SourceLanguage]; found && english.StringUnit.Value != "" {
				source = english.StringUnit.Value
			}
			canonical := canonicalize(source)
			if _, found := canonicalKeys[canonical]; !found || source == key {
				canonicalKeys[canonical] = key
			}
			for language, localization := range entry.Localizations {
				if language == loaded.SourceLanguage || localization.StringUnit.State != "translated" {
					continue
				}
				if translations[language] == nil {
					translations[language] = make(map[string]string)
				}
				translations[language][key] = localization.StringUnit.Value
			}
		}
	}

	base := loadBaseStrings()
	for _, language := range slices.Sorted(maps.Keys(translations)) {
		var output strings.Builder
		output.WriteString("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
		count := 0
		for _, item := range base {
			key, found := catalogKeys[item.name]
			if !found {
				key, found = canonicalKeys[canonicalize(item.text)]
				if !found {
					continue
				}
			}
			translated, found := translations[language][key]
			if !found {
				continue
			}
			output.WriteString("    <string name=\"")
			output.WriteString(item.name)
			output.WriteString("\">")
			output.WriteString(escape(androidFormat(translated, strings.Contains(item.text, "$"))))
			output.WriteString("</string>\n")
			count++
		}
		output.WriteString("</resources>\n")
		directory := filepath.Join(resourcesPath, "values-"+qualifier(language))
		err := os.MkdirAll(directory, 0o755)
		if err != nil {
			log.Fatal(err)
		}
		err = os.WriteFile(filepath.Join(directory, outputName), []byte(output.String()), 0o644)
		if err != nil {
			log.Fatal(err)
		}
		log.Print(language, ": ", count, "/", len(base))
	}
}

func loadBaseStrings() []baseString {
	paths, err := filepath.Glob(filepath.Join(resourcesPath, "values", "strings*.xml"))
	if err != nil {
		log.Fatal(err)
	}
	var loaded []baseString
	for _, path := range paths {
		file, err := os.Open(path)
		if err != nil {
			log.Fatal(err)
		}
		decoder := xml.NewDecoder(file)
		for {
			token, err := decoder.Token()
			if err == io.EOF {
				break
			}
			if err != nil {
				log.Fatal(path, ": ", err)
			}
			start, isStart := token.(xml.StartElement)
			if !isStart || start.Name.Local != "string" {
				continue
			}
			var element struct {
				Name         string `xml:"name,attr"`
				Translatable string `xml:"translatable,attr"`
				Text         string `xml:",chardata"`
			}
			err = decoder.DecodeElement(&element, &start)
			if err != nil {
				log.Fatal(path, ": ", err)
			}
			if element.Translatable == "false" {
				continue
			}
			loaded = append(loaded, baseString{name: element.Name, text: unescape(element.Text)})
		}
		file.Close()
	}
	return loaded
}

func canonicalize(text string) string {
	next := 0
	return specifierPattern.ReplaceAllStringFunc(strings.ToLower(text), func(specifier string) string {
		match := specifierPattern.FindStringSubmatch(specifier)
		position := match[1]
		if position == "" {
			next++
			position = strconv.Itoa(next)
		}
		kind := "s"
		switch match[2] {
		case "lld", "ld", "d":
			kind = "d"
		case "f":
			kind = "f"
		}
		return "%" + position + "$" + kind
	})
}

func androidFormat(text string, positional bool) string {
	next := 0
	return specifierPattern.ReplaceAllStringFunc(text, func(specifier string) string {
		match := specifierPattern.FindStringSubmatch(specifier)
		position := match[1]
		if position == "" {
			next++
			position = strconv.Itoa(next)
		}
		kind := "s"
		switch match[2] {
		case "lld", "ld", "d":
			kind = "d"
		case "f":
			return specifier
		}
		if !positional {
			return "%" + kind
		}
		return "%" + position + "$" + kind
	})
}

func unescape(text string) string {
	var output strings.Builder
	text = strings.TrimSpace(text)
	if len(text) >= 2 && text[0] == '"' && text[len(text)-1] == '"' {
		text = text[1 : len(text)-1]
	}
	for index := 0; index < len(text); index++ {
		if text[index] != '\\' || index+1 == len(text) {
			output.WriteByte(text[index])
			continue
		}
		index++
		switch text[index] {
		case 'n':
			output.WriteByte('\n')
		case 't':
			output.WriteByte('\t')
		default:
			output.WriteByte(text[index])
		}
	}
	return output.String()
}

func escape(text string) string {
	replacer := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"\\", "\\\\",
		"'", "\\'",
		"\"", "\\\"",
		"\n", "\\n",
	)
	escaped := replacer.Replace(text)
	if strings.HasPrefix(escaped, "@") || strings.HasPrefix(escaped, "?") {
		escaped = "\\" + escaped
	}
	return escaped
}

func qualifier(language string) string {
	parts := strings.Split(language, "-")
	switch {
	case len(parts) == 1:
		return parts[0]
	case len(parts) == 2 && len(parts[1]) == 2:
		return parts[0] + "-r" + parts[1]
	default:
		return "b+" + strings.Join(parts, "+")
	}
}
