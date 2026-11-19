package libsharing

import (
	"maps"
	"slices"

	E "github.com/sagernet/sing/common/exceptions"
	"howett.net/plist"
)

func archiveDictionary(values map[string]string) ([]byte, error) {
	objects := []any{"$null", nil}
	var keys, references []plist.UID
	for _, key := range slices.Sorted(maps.Keys(values)) {
		keys = append(keys, plist.UID(len(objects)))
		objects = append(objects, key)
		references = append(references, plist.UID(len(objects)))
		objects = append(objects, values[key])
	}
	class := plist.UID(len(objects))
	objects = append(objects, map[string]any{
		"$classname": "NSDictionary",
		"$classes":   []string{"NSDictionary", "NSObject"},
	})
	objects[1] = map[string]any{"NS.keys": keys, "NS.objects": references, "$class": class}
	return plist.Marshal(map[string]any{
		"$archiver": "NSKeyedArchiver",
		"$version":  uint64(100000),
		"$top":      map[string]any{"root": plist.UID(1)},
		"$objects":  objects,
	}, plist.BinaryFormat)
}

func unarchiveDictionary(data []byte) (map[string]string, error) {
	var document struct {
		Objects []any          `plist:"$objects"`
		Top     map[string]any `plist:"$top"`
	}
	_, err := plist.Unmarshal(data, &document)
	if err != nil {
		return nil, err
	}
	resolve := func(reference any) (any, error) {
		index, isUID := reference.(plist.UID)
		if !isUID || uint64(index) >= uint64(len(document.Objects)) {
			return nil, E.New("keyed archive: invalid reference")
		}
		return document.Objects[index], nil
	}
	root, err := resolve(document.Top["root"])
	if err != nil {
		return nil, err
	}
	dictionary, isDictionary := root.(map[string]any)
	if !isDictionary {
		return nil, E.New("keyed archive: the root is not a dictionary")
	}
	keys, hasKeys := dictionary["NS.keys"].([]any)
	references, hasObjects := dictionary["NS.objects"].([]any)
	if !hasKeys || !hasObjects || len(keys) != len(references) {
		return nil, E.New("keyed archive: the dictionary keys do not match its objects")
	}
	values := make(map[string]string, len(keys))
	for i, reference := range keys {
		key, err := resolve(reference)
		if err != nil {
			return nil, err
		}
		name, isString := key.(string)
		if !isString {
			return nil, E.New("keyed archive: a dictionary key is not a string")
		}
		value, err := resolve(references[i])
		if err != nil {
			return nil, err
		}
		if text, isText := value.(string); isText {
			values[name] = text
		}
	}
	return values, nil
}
