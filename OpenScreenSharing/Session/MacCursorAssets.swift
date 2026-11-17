import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

nonisolated final class MacCursorAssets: @unchecked Sendable {
    static let scale: CGFloat = 5

    private static let systemVersion = "/System/Library/CoreServices/SystemVersion.plist"
    private static let cursorFolder =
        "/System/Library/Frameworks/ApplicationServices.framework/Versions/A/Frameworks/HIServices.framework/Versions/A/Resources/cursors"
    private static let cursorAsset = "/System/Library/PrivateFrameworks/SkyLight.framework/Versions/A/Resources/CursorAsset"
    private static let arrow = "arrow"
    private static let directory = AppDirectory.caches.appendingPathComponent("RemoteCursors", isDirectory: true)
    private static let downloads: URL = {
        let downloads = directory.appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.removeItem(at: downloads)
        return downloads
    }()

    // Cursor images of macOS 27.0.1 26A434: the arrow is com.apple.coregraphics.ArrowS (id 100) in SkyLight's
    // CursorAsset, the I-beam is its IBeamS, which HIServices cursors/ibeamhorizontal draws pixel for pixel, and the
    // rest are drawn from HIServices cursors/<name>, or cursors/macos27/<name> for closedhand, openhand and
    // pointinghand while the SwiftUI/Calistoga feature flag is on (HIServices 834, UUID
    // 185A502D-7E89-344D-9718-11FFB39205FF, _setCursorFromBundle 0x187e894d0..0x187e89534,
    // ____CalistogaEnabled_block_invoke 0x187eb0bb8..0x187eb0bd0).
    private static let sources: [UInt64: String] = [
        0x550c_f3e1_2f82_e290: arrow,
        0x892a_7ece_621a_c8c5: "ibeamhorizontal",
        0x9ae3_c16e_5e50_bde8: "ibeamvertical",
        0xa1d0_4e5d_7b36_38c1: "contextualmenu",
        0x794a_89c5_f925_811d: "pointinghand",
        0x5064_4df7_03aa_9698: "openhand",
        0xbee3_f9de_b429_0ac6: "closedhand",
        0xc45e_03a9_8e56_c597: "makealias",
        0x404d_0f11_c243_6172: "copy",
        0x95ad_88c4_cc9e_3d42: "notallowed",
        0xbb6e_1a08_616b_a322: "cross",
        0x099f_7efb_ca83_ccba: "zoomin",
        0x1343_3771_90e9_03aa: "zoomout",
        0xd39b_94f8_38be_4e58: "resizeleft",
        0x42d8_bbc7_a95a_09ca: "resizeright",
        0x95e8_7116_9704_c06d: "resizeleftright",
        0xc462_9ef7_e72b_4692: "resizeup",
        0xddef_b65d_0e8d_7aa3: "resizedown",
        0x99fa_f011_b1d1_672a: "resizeupdown",
        0xc272_dbe8_fb06_c3b0: "resizenorthsouth",
        0x8d1c_c66a_b524_f521: "resizenorth",
        0x2168_3648_e46f_acfc: "resizesouth",
        0xbf87_b07e_efdd_81bf: "resizeeastwest",
        0x96f9_141f_df6a_48f0: "resizewest",
        0x248f_9773_6835_ac0d: "resizeeast",
        0x36a3_3a21_5de7_2d90: "resizenorthwestsoutheast",
        0xe533_934d_212c_e831: "resizenorthwest",
        0x1427_a75f_c243_7d9a: "resizesoutheast",
        0x8c24_bd91_dffa_9649: "resizenortheastsouthwest",
        0x5764_40b0_b8e8_5e5b: "resizenortheast",
        0x1653_1b30_a60c_ab6b: "resizesouthwest",
    ]

    private let directory: URL
    private let lock = NSLock()
    private var images: [String: CGImage] = [:]

    init(session: any RemoteSession) throws {
        let fileManager = FileManager.default
        let download = Self.downloads.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try fileManager.createDirectory(at: download, withIntermediateDirectories: true)
        defer { try? fileManager.removeItem(at: download) }
        try session.downloadFiles([Self.systemVersion], localFolder: download)
        let version = try PropertyListSerialization.propertyList(
            from: Data(contentsOf: download.appendingPathComponent("SystemVersion.plist")), format: nil)
        guard let build = (version as? [String: Any])?["ProductBuildVersion"] as? String, !build.isEmpty,
            build.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) })
        else { throw MessageError("SystemVersion.plist has no build version") }
        directory = Self.directory.appendingPathComponent("\(build)@\(Int(Self.scale))x", isDirectory: true)
        guard !fileManager.fileExists(atPath: directory.path) else { return }
        try session.downloadFiles([Self.cursorFolder, Self.cursorAsset], localFolder: download)
        let rendered = download.appendingPathComponent("rendered", isDirectory: true)
        try fileManager.createDirectory(at: rendered, withIntermediateDirectories: true)
        let cursors = download.appendingPathComponent("cursors", isDirectory: true)
        let asset = try? Data(contentsOf: download.appendingPathComponent("CursorAsset"), options: .mappedIfSafe)
        for name in Set(Self.sources.values) {
            let image: CGImage?
            if name == Self.arrow {
                image = asset.flatMap { Self.arrowImage(in: $0) }
            } else {
                let modern = cursors.appendingPathComponent("macos27", isDirectory: true).appendingPathComponent(name, isDirectory: true)
                image = Self.renderCursor(
                    in: fileManager.fileExists(atPath: modern.path) ? modern : cursors.appendingPathComponent(name, isDirectory: true))
            }
            guard let image else { continue }
            let url = rendered.appendingPathComponent(name).appendingPathExtension("png")
            guard let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
                throw MessageError("CGImageDestinationCreateWithURL failed")
            }
            CGImageDestinationAddImage(destination, image, nil)
            guard CGImageDestinationFinalize(destination) else { throw MessageError("CGImageDestinationFinalize failed") }
        }
        do {
            try fileManager.moveItem(at: rendered, to: directory)
        } catch {
            guard fileManager.fileExists(atPath: directory.path) else { throw error }
        }
    }

    func image(for fingerprint: UInt64) -> CGImage? {
        guard let name = Self.sources[fingerprint] else { return nil }
        lock.lock()
        defer { lock.unlock() }
        if let image = images[name] { return image }
        let url = directory.appendingPathComponent(name).appendingPathExtension("png")
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil), let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
            return nil
        }
        images[name] = image
        return image
    }

    // HIServices _setCursorFromBundle defaults shadowcolor to black at 1/3 alpha, and _createScaledCursor draws
    // cursor.pdf into a DeviceRGB bitmap of the size times the scale rounded up, keeping the shadow offset and blur in
    // device pixels at every scale (HIServices 834, macOS 27.0.1 26A434, UUID 185A502D-7E89-344D-9718-11FFB39205FF,
    // _setCursorFromBundle 0x187e89638..0x187e89644, _createScaledCursor 0x187e89d0c..0x187e89e1c).
    private static func renderCursor(in folder: URL) -> CGImage? {
        guard let document = CGPDFDocument(folder.appendingPathComponent("cursor.pdf") as CFURL), let page = document.page(at: 1),
            let plist = try? Data(contentsOf: folder.appendingPathComponent("info.plist")),
            let info = try? PropertyListSerialization.propertyList(from: plist, format: nil) as? [String: Any],
            (info["frames"] as? NSNumber)?.intValue ?? 1 == 1
        else { return nil }
        let box = page.getBoxRect(.mediaBox)
        let width = Int((box.width * scale).rounded(.up))
        let height = Int((box.height * scale).rounded(.up))
        guard
            let context = CGContext(
                data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        func number(_ key: String) -> CGFloat { CGFloat((info[key] as? NSNumber)?.doubleValue ?? 0) }
        var color: [CGFloat] = [0, 0, 0, 1.0 / 3]
        for (index, component) in ((info["shadowcolor"] as? [NSNumber]) ?? []).prefix(4).enumerated() {
            color[index] = CGFloat(component.doubleValue)
        }
        context.setShadow(
            offset: CGSize(width: number("shadowoffsetx"), height: number("shadowoffsety")), blur: number("blur"),
            color: CGColor(red: color[0], green: color[1], blue: color[2], alpha: color[3]))
        context.scaleBy(x: CGFloat(width) / box.width, y: CGFloat(height) / box.height)
        context.translateBy(x: -box.minX, y: -box.minY)
        context.drawPDFPage(page)
        return context.makeImage()
    }

    // CursorAsset is a fat Mach-O whose only export, _cursors, starts __DATA_CONST,__const: {u32 id, descriptor
    // pointer} pairs up to a null pointer. Descriptor: f64 width, height, hotX, hotY (points), u64 frames,
    // f32 duration, u64 image count, image pointers. Image: u64 pixel width, frame pixel height, f64 hotX, hotY
    // (pixels), then premultiplied BGRA rows with frames stacked vertically. Pointers are
    // DYLD_CHAINED_PTR_ARM64E_USERLAND24 rebases whose low 32 bits are the address (SkyLight 953.5.5, macOS 27.0.1
    // 26A434, UUID 8A3B348E-4637-3685-92D0-6CBC2F36A234, ___cursor_asset_data_block_invoke 0x1870b5d90..0x1870b5dc4,
    // _CGXUpdateHardwareColorCursors 0x1874f53d4..0x1874f54b0).
    private static func arrowImage(in data: Data) -> CGImage? {
        data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) -> CGImage? in
            func load<T: FixedWidthInteger>(_ offset: Int, _: T.Type) -> T? {
                guard offset >= 0, offset + MemoryLayout<T>.size <= bytes.count else { return nil }
                return T(littleEndian: bytes.loadUnaligned(fromByteOffset: offset, as: T.self))
            }
            var slice = 0
            if let magic = load(0, UInt32.self), magic.bigEndian == 0xcafe_babe, let count = load(4, UInt32.self)?.bigEndian {
                guard
                    let entry = (0..<Int(count)).first(where: { load(8 + $0 * 20, UInt32.self)?.bigEndian == 0x0100_000c }),
                    let offset = load(8 + entry * 20 + 8, UInt32.self)?.bigEndian
                else { return nil }
                slice = Int(offset)
            }
            guard load(slice, UInt32.self) == 0xfeed_facf, let commandCount = load(slice + 16, UInt32.self) else { return nil }
            var segments: [(address: Int, size: Int, offset: Int)] = []
            var table: Int?
            var command = slice + 32
            for _ in 0..<commandCount {
                guard let kind = load(command, UInt32.self), let size = load(command + 4, UInt32.self) else { return nil }
                if kind == 0x19, let address = load(command + 24, UInt64.self), let length = load(command + 48, UInt64.self),
                    let offset = load(command + 40, UInt64.self), let sectionCount = load(command + 64, UInt32.self)
                {
                    segments.append((Int(address), Int(length), Int(offset)))
                    for index in 0..<Int(sectionCount) {
                        let section = command + 72 + index * 80
                        guard section + 40 <= bytes.count else { return nil }
                        let name = String(decoding: bytes[section..<section + 16].prefix { $0 != 0 }, as: UTF8.self)
                        let segment = String(decoding: bytes[section + 16..<section + 32].prefix { $0 != 0 }, as: UTF8.self)
                        if segment == "__DATA_CONST", name == "__const" { table = load(section + 32, UInt64.self).map(Int.init) }
                    }
                }
                command += Int(size)
            }
            func fileOffset(_ address: Int) -> Int? {
                segments.first { address >= $0.address && address < $0.address + $0.size }.map { slice + $0.offset + address - $0.address }
            }
            func pointer(_ address: Int) -> Int? {
                fileOffset(address).flatMap { load($0, UInt64.self) }.map { Int($0 & 0xffff_ffff) }
            }
            guard var entry = table else { return nil }
            while let id = fileOffset(entry).flatMap({ load($0, UInt32.self) }), let descriptor = pointer(entry + 8), descriptor != 0 {
                entry += 16
                guard id == 100 else { continue }
                guard let base = fileOffset(descriptor), let width = load(base, UInt64.self).map(Double.init(bitPattern:)),
                    let frames = load(base + 0x20, UInt64.self), let imageCount = load(base + 0x30, UInt64.self), frames <= 1, imageCount < 16
                else { return nil }
                for index in 0..<Int(imageCount) {
                    guard let image = pointer(descriptor + 0x38 + index * 8).flatMap(fileOffset),
                        let pixelWidth = load(image, UInt64.self).map(Int.init), let pixelHeight = load(image + 8, UInt64.self).map(Int.init)
                    else { return nil }
                    guard pixelWidth == Int((width * scale).rounded()), pixelHeight > 0, pixelHeight < 4096,
                        image + 0x20 + pixelWidth * pixelHeight * 4 <= bytes.count
                    else { continue }
                    let pixels = Data(bytes[image + 0x20..<image + 0x20 + pixelWidth * pixelHeight * 4])
                    guard let provider = CGDataProvider(data: pixels as CFData) else { return nil }
                    return CGImage(
                        width: pixelWidth, height: pixelHeight, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: pixelWidth * 4,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!,
                        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue),
                        provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
                }
                return nil
            }
            return nil
        }
    }
}
