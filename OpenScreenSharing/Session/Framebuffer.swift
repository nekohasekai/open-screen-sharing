import CoreGraphics
import CoreImage
import CoreVideo
import Foundation
import ImageIO

nonisolated final class Framebuffer: @unchecked Sendable {
    let pixelBuffer: CVPixelBuffer
    let width: Int
    let height: Int
    let bytesPerRow: Int
    let baseAddress: UnsafeMutableRawPointer

    init(width: Int, height: Int) throws {
        guard width > 0, height > 0 else { throw MessageError("framebuffer size \(width)x\(height)") }
        let attributes: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [CFString: Any](),
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
        ]
        var buffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes as CFDictionary, &buffer)
        guard status == kCVReturnSuccess, let buffer else { throw MessageError("CVPixelBufferCreate", status: status) }
        CVPixelBufferLockBaseAddress(buffer, [])
        guard let address = CVPixelBufferGetBaseAddress(buffer) else {
            CVPixelBufferUnlockBaseAddress(buffer, [])
            throw MessageError("CVPixelBufferGetBaseAddress returned no address")
        }
        pixelBuffer = buffer
        self.width = width
        self.height = height
        bytesPerRow = CVPixelBufferGetBytesPerRow(buffer)
        baseAddress = address
        memset(address, 0, bytesPerRow * height)
    }

    var bounds: CGRect {
        CGRect(x: 0, y: 0, width: width, height: height)
    }

    func copy(_ rect: CGRect, from source: Framebuffer) {
        let rect = rect.intersection(bounds).intersection(source.bounds)
        guard !rect.isEmpty else { return }
        let offset = Int(rect.minX) * 4
        let length = Int(rect.width) * 4
        for row in Int(rect.minY)..<Int(rect.maxY) {
            memcpy(baseAddress + row * bytesPerRow + offset, source.baseAddress + row * source.bytesPerRow + offset, length)
        }
    }

    deinit {
        CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
    }
}

nonisolated enum PixelBufferImage {
    private static let context = CIContext(options: [.cacheIntermediates: false])
    private static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)
    private static let thumbnailWidth: CGFloat = 1280

    static func makeThumbnail(_ pixelBuffer: CVPixelBuffer) -> Data? {
        thumbnail(of: CIImage(cvPixelBuffer: pixelBuffer))
    }

    static func makeThumbnail(strips: [CVPixelBuffer?], stripHeight: Int, canvasHeight: Int) -> Data? {
        guard let image = composed(strips: strips, stripHeight: stripHeight, canvasHeight: canvasHeight) else { return nil }
        return thumbnail(of: image)
    }

    private static func composed(strips: [CVPixelBuffer?], stripHeight: Int, canvasHeight: Int) -> CIImage? {
        guard let width = strips.compactMap({ $0 }).first.map(CVPixelBufferGetWidth), stripHeight > 0, canvasHeight > 0 else { return nil }
        var image = CIImage(color: .black).cropped(to: CGRect(x: 0, y: 0, width: width, height: canvasHeight))
        for (band, strip) in strips.enumerated() {
            guard let strip else { continue }
            let top = band * stripHeight
            let placed = CIImage(cvPixelBuffer: strip).transformed(by: CGAffineTransform(translationX: 0, y: CGFloat(canvasHeight - top - stripHeight)))
            image = placed.composited(over: image)
        }
        return image.cropped(to: CGRect(x: 0, y: 0, width: width, height: canvasHeight))
    }

    private static func thumbnail(of image: CIImage) -> Data? {
        guard image.extent.width > 0, image.extent.height > 0 else { return nil }
        let scale = min(1, thumbnailWidth / image.extent.width)
        let scaled = image.applyingFilter("CILanczosScaleTransform", parameters: [kCIInputScaleKey: scale, kCIInputAspectRatioKey: 1])
        guard let colorSpace else { return nil }
        return context.jpegRepresentation(
            of: scaled, colorSpace: colorSpace, options: [CIImageRepresentationOption(rawValue: kCGImageDestinationLossyCompressionQuality as String): 0.8])
    }
}
