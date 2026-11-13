package media

import E "github.com/sagernet/sing/common/exceptions"

const (
	TransferPQ  = 16
	TransferHLG = 18
)

type Color struct {
	BitDepth     int
	ChromaFormat int
	FullRange    bool
	Primaries    int
	Transfer     int
	Matrix       int
}

func (c Color) HDR() bool {
	return c.Transfer == TransferPQ || c.Transfer == TransferHLG
}

func parseColor(codec Codec, sets [][]byte) (Color, error) {
	for _, set := range sets {
		if len(set) == 0 {
			continue
		}
		switch {
		case codec == CodecHEVC && len(set) > 2 && hevcNALType(set) == hevcNALSPS:
			return parseHEVCColor(&bitReader{data: unescapeRBSP(set[2:])})
		case codec == CodecH264 && set[0]&0x1f == 7:
			return parseH264Color(&bitReader{data: unescapeRBSP(set[1:])})
		}
	}
	return Color{}, E.New("media: no ", codec, " sequence parameter set among ", len(sets), " parameter sets")
}

func unescapeRBSP(data []byte) []byte {
	output := make([]byte, 0, len(data))
	zeros := 0
	for _, value := range data {
		if zeros >= 2 && value == 3 {
			zeros = 0
			continue
		}
		output = append(output, value)
		if value == 0 {
			zeros++
		} else {
			zeros = 0
		}
	}
	return output
}

type bitReader struct {
	data     []byte
	position int
	overrun  bool
}

func (r *bitReader) bit() uint {
	if r.position >= len(r.data)*8 {
		r.overrun = true
		return 0
	}
	value := uint(r.data[r.position/8]>>(7-r.position%8)) & 1
	r.position++
	return value
}

func (r *bitReader) bits(count int) uint {
	var value uint
	for i := 0; i < count; i++ {
		value = value<<1 | r.bit()
	}
	return value
}

func (r *bitReader) flag() bool {
	return r.bit() == 1
}

func (r *bitReader) unsignedGolomb() uint {
	zeros := 0
	for r.bit() == 0 {
		if r.overrun || zeros > 31 {
			r.overrun = true
			return 0
		}
		zeros++
	}
	return 1<<zeros - 1 + r.bits(zeros)
}

func (r *bitReader) signedGolomb() int {
	value := r.unsignedGolomb()
	if value&1 == 1 {
		return int(value+1) / 2
	}
	return -int(value / 2)
}

func parseHEVCColor(r *bitReader) (Color, error) {
	var color Color
	r.bits(4)
	maxSubLayersMinus1 := int(r.bits(3))
	r.bits(1)
	skipHEVCProfileTierLevel(r, maxSubLayersMinus1)
	r.unsignedGolomb()
	color.ChromaFormat = int(r.unsignedGolomb())
	if color.ChromaFormat == 3 {
		r.bits(1)
	}
	r.unsignedGolomb()
	r.unsignedGolomb()
	if r.flag() {
		for i := 0; i < 4; i++ {
			r.unsignedGolomb()
		}
	}
	color.BitDepth = 8 + int(r.unsignedGolomb())
	r.unsignedGolomb()
	log2MaxPictureOrderCount := 4 + int(r.unsignedGolomb())
	orderingInfoForAll := r.flag()
	first := maxSubLayersMinus1
	if orderingInfoForAll {
		first = 0
	}
	for i := first; i <= maxSubLayersMinus1; i++ {
		r.unsignedGolomb()
		r.unsignedGolomb()
		r.unsignedGolomb()
	}
	for i := 0; i < 6; i++ {
		r.unsignedGolomb()
	}
	if r.flag() && r.flag() {
		skipHEVCScalingListData(r)
	}
	r.bits(2)
	if r.flag() {
		r.bits(8)
		r.unsignedGolomb()
		r.unsignedGolomb()
		r.bits(1)
	}
	shortTermSets := int(r.unsignedGolomb())
	if shortTermSets > 64 {
		return color, E.New("media: HEVC sequence parameter set has ", shortTermSets, " short-term reference picture sets, more than 64")
	}
	deltaPictureCounts := make([]int, shortTermSets)
	for index := 0; index < shortTermSets; index++ {
		deltaPictureCounts[index] = skipHEVCShortTermReferenceSet(r, index, deltaPictureCounts)
		if r.overrun {
			return color, E.New("media: truncated or malformed HEVC short-term reference picture set ", index, " in a sequence parameter set with ", len(r.data), " payload bytes")
		}
	}
	if r.flag() {
		longTermPictures := int(r.unsignedGolomb())
		for i := 0; i < longTermPictures && !r.overrun; i++ {
			r.bits(log2MaxPictureOrderCount)
			r.bits(1)
		}
	}
	r.bits(2)
	if r.flag() {
		readVUIColor(r, &color)
	}
	if r.overrun {
		return color, E.New("media: truncated or malformed HEVC sequence parameter set with ", len(r.data), " payload bytes")
	}
	return color, nil
}

func skipHEVCProfileTierLevel(r *bitReader, maxSubLayersMinus1 int) {
	r.bits(8)
	r.bits(32)
	r.bits(48)
	r.bits(8)
	profilePresent := make([]bool, maxSubLayersMinus1)
	levelPresent := make([]bool, maxSubLayersMinus1)
	for i := 0; i < maxSubLayersMinus1; i++ {
		profilePresent[i] = r.flag()
		levelPresent[i] = r.flag()
	}
	if maxSubLayersMinus1 > 0 {
		for i := maxSubLayersMinus1; i < 8; i++ {
			r.bits(2)
		}
	}
	for i := 0; i < maxSubLayersMinus1; i++ {
		if profilePresent[i] {
			r.bits(88)
		}
		if levelPresent[i] {
			r.bits(8)
		}
	}
}

func skipHEVCScalingListData(r *bitReader) {
	for sizeID := 0; sizeID < 4; sizeID++ {
		step := 1
		if sizeID == 3 {
			step = 3
		}
		for matrixID := 0; matrixID < 6; matrixID += step {
			if !r.flag() {
				r.unsignedGolomb()
				continue
			}
			coefficients := min(64, 1<<(4+sizeID<<1))
			if sizeID > 1 {
				r.signedGolomb()
			}
			for i := 0; i < coefficients; i++ {
				r.signedGolomb()
			}
		}
	}
}

func skipHEVCShortTermReferenceSet(r *bitReader, index int, deltaPictureCounts []int) int {
	if index != 0 && r.flag() {
		r.bits(1)
		r.unsignedGolomb()
		reference := deltaPictureCounts[index-1]
		count := 0
		for j := 0; j <= reference; j++ {
			used := r.flag()
			if used || r.flag() {
				count++
			}
		}
		return count
	}
	negative := int(r.unsignedGolomb())
	positive := int(r.unsignedGolomb())
	if negative > 16 || positive > 16 {
		r.overrun = true
		return 0
	}
	for i := 0; i < negative+positive; i++ {
		r.unsignedGolomb()
		r.bits(1)
	}
	return negative + positive
}

func parseH264Color(r *bitReader) (Color, error) {
	color := Color{BitDepth: 8, ChromaFormat: 1}
	profile := r.bits(8)
	r.bits(16)
	r.unsignedGolomb()
	switch profile {
	case 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135:
		color.ChromaFormat = int(r.unsignedGolomb())
		if color.ChromaFormat == 3 {
			r.bits(1)
		}
		color.BitDepth = 8 + int(r.unsignedGolomb())
		r.unsignedGolomb()
		r.bits(1)
		if r.flag() {
			lists := 8
			if color.ChromaFormat == 3 {
				lists = 12
			}
			for i := 0; i < lists; i++ {
				if !r.flag() {
					continue
				}
				size := 16
				if i >= 6 {
					size = 64
				}
				last, next := 8, 8
				for j := 0; j < size; j++ {
					if next != 0 {
						next = (last + r.signedGolomb() + 256) % 256
					}
					if next != 0 {
						last = next
					}
				}
			}
		}
	}
	r.unsignedGolomb()
	switch r.unsignedGolomb() {
	case 0:
		r.unsignedGolomb()
	case 1:
		r.bits(1)
		r.signedGolomb()
		r.signedGolomb()
		cycle := int(r.unsignedGolomb())
		if cycle > 255 {
			return color, E.New("media: H.264 sequence parameter set has ", cycle, " reference frames in the picture order count cycle, more than 255")
		}
		for i := 0; i < cycle; i++ {
			r.signedGolomb()
		}
	}
	r.unsignedGolomb()
	r.bits(1)
	r.unsignedGolomb()
	r.unsignedGolomb()
	if !r.flag() {
		r.bits(1)
	}
	r.bits(1)
	if r.flag() {
		for i := 0; i < 4; i++ {
			r.unsignedGolomb()
		}
	}
	if r.flag() {
		readVUIColor(r, &color)
	}
	if r.overrun {
		return color, E.New("media: truncated or malformed H.264 sequence parameter set with ", len(r.data), " payload bytes")
	}
	return color, nil
}

func readVUIColor(r *bitReader, color *Color) {
	if r.flag() && r.bits(8) == 255 {
		r.bits(32)
	}
	if r.flag() {
		r.bits(1)
	}
	if !r.flag() {
		return
	}
	r.bits(3)
	color.FullRange = r.flag()
	if r.flag() {
		color.Primaries = int(r.bits(8))
		color.Transfer = int(r.bits(8))
		color.Matrix = int(r.bits(8))
	}
}
