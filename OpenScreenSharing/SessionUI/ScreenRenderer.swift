import CoreGraphics
import CoreVideo
import Metal
import QuartzCore

final class ScreenRenderer {
    private static let commonShaderSource = """
        #include <metal_stdlib>
        using namespace metal;

        struct QuadVertex {
            float4 position [[position]];
            float2 textureCoordinate;
        };

        static float3 sRGBToPQ(float3 encoded) {
            float3 linear = select(pow((encoded + 0.055) / 1.055, 2.4), encoded / 12.92, encoded <= 0.04045);
            float3x3 sRGBToDisplayP3 = float3x3(
                float3(0.82246197, 0.03319420, 0.01708263), float3(0.17753803, 0.96680580, 0.07239744), float3(0, 0, 0.91051993));
            float3 scaled = pow(sRGBToDisplayP3 * linear * 0.01, 0.1593017578125);
            return pow((0.8359375 + 18.8515625 * scaled) / (1 + 18.6875 * scaled), 78.84375);
        }

        """

    private static let shaderSource =
        commonShaderSource + """

        vertex QuadVertex quadVertex(
            uint vertexID [[vertex_id]], constant float4 &rect [[buffer(0)]], constant float4 &textureRect [[buffer(1)]]
        ) {
            float2 corner = float2(vertexID & 1, vertexID >> 1);
            QuadVertex out;
            out.position = float4(mix(rect.xy, rect.zw, corner), 0, 1);
            out.textureCoordinate = mix(textureRect.xy, textureRect.zw, corner);
            return out;
        }

        fragment float4 screenFragment(QuadVertex in [[stage_in]], texture2d<float> texture [[texture(0)]], sampler textureSampler [[sampler(0)]]) {
            return float4(texture.sample(textureSampler, in.textureCoordinate).rgb, 1);
        }

        fragment float4 blackFragment(QuadVertex in [[stage_in]]) {
            return float4(0, 0, 0, 1);
        }

        fragment float4 cursorFragment(QuadVertex in [[stage_in]], texture2d<float> texture [[texture(0)]], sampler textureSampler [[sampler(0)]]) {
            return texture.sample(textureSampler, in.textureCoordinate);
        }

        fragment float4 maskedCursorFragment(
            QuadVertex in [[stage_in]], texture2d<float> texture [[texture(0)]], texture2d<float> inversion [[texture(1)]],
            sampler textureSampler [[sampler(0)]]
        ) {
            return texture.sample(textureSampler, in.textureCoordinate) * (1 - inversion.sample(textureSampler, in.textureCoordinate).r);
        }

        fragment float4 cursorInversionFragment(
            QuadVertex in [[stage_in]], texture2d<float> inversion [[texture(1)]], sampler textureSampler [[sampler(0)]]
        ) {
            return float4(inversion.sample(textureSampler, in.textureCoordinate).r);
        }

        fragment float4 cursorFragmentPQ(QuadVertex in [[stage_in]], texture2d<float> texture [[texture(0)]], sampler textureSampler [[sampler(0)]]) {
            float4 color = texture.sample(textureSampler, in.textureCoordinate);
            if (color.a <= 0) {
                return float4(0);
            }
            return float4(sRGBToPQ(color.rgb / color.a) * color.a, color.a);
        }

        fragment float4 pointerFallbackFragment(
            QuadVertex in [[stage_in]], texture2d<float> texture [[texture(0)]], sampler textureSampler [[sampler(0)]]
        ) {
            float2 coverage = texture.sample(textureSampler, in.textureCoordinate).rg;
            float alpha = max(coverage.r, coverage.g) * 0.6;
            return float4(float3(0.5 * alpha), alpha);
        }
        """

    private static let pointerShaderSource =
        commonShaderSource + """
        struct PointerMaterial {
            float4 body[4];
            float4 accessory[4];
        };

        static float3 pointerBackdrop(float3 backdrop, float coverage, constant float4 *matrix) {
            float4 tinted = saturate(matrix[0] * backdrop.r + matrix[1] * backdrop.g + matrix[2] * backdrop.b + matrix[3]);
            return mix(backdrop, tinted.rgb, tinted.a * coverage);
        }

        fragment float4 pointerFragment(
            QuadVertex in [[stage_in]], float4 destination [[color(0)]], texture2d<float> texture [[texture(0)]],
            sampler textureSampler [[sampler(0)]], constant PointerMaterial &material [[buffer(0)]]
        ) {
            float2 coverage = texture.sample(textureSampler, in.textureCoordinate).rg;
            float3 color = pointerBackdrop(destination.rgb, coverage.g, material.accessory);
            return float4(pointerBackdrop(color, coverage.r, material.body), 1);
        }

        fragment float4 pointerFragmentPQ(
            QuadVertex in [[stage_in]], float4 destination [[color(0)]], texture2d<float> texture [[texture(0)]],
            sampler textureSampler [[sampler(0)]], constant PointerMaterial &material [[buffer(0)]]
        ) {
            float2 coverage = texture.sample(textureSampler, in.textureCoordinate).rg;
            if (coverage.r <= 0 && coverage.g <= 0) {
                return destination;
            }
            float3 p = pow(max(destination.rgb, 0), 1 / 78.84375);
            float3 linear = pow(max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 1 / 0.1593017578125) * 100;
            float3x3 displayP3ToSRGB = float3x3(
                float3(1.22494017, -0.04205696, -0.01963755), float3(-0.22494017, 1.04205696, -0.07863604), float3(0, 0, 1.09827360));
            linear = saturate(displayP3ToSRGB * linear);
            float3 backdrop = select(1.055 * pow(linear, 1 / 2.4) - 0.055, linear * 12.92, linear <= 0.0031308);
            float3 color = pointerBackdrop(backdrop, coverage.g, material.accessory);
            return float4(sRGBToPQ(pointerBackdrop(color, coverage.r, material.body)), 1);
        }
        """

    private struct Pipelines {
        var screen: MTLRenderPipelineState
        var black: MTLRenderPipelineState
        var cursor: MTLRenderPipelineState
        var maskedCursor: MTLRenderPipelineState?
        var cursorInversion: MTLRenderPipelineState?
        var pointer: MTLRenderPipelineState
        var pointerReadsBackdrop: Bool
    }

    private let device: MTLDevice
    private let commandQueue: MTLCommandQueue
    private let standardPipelines: Pipelines
    private let hdrPipelines: Pipelines
    private var isHDR = false
    private var colorSpaceName: CFString?
    private let linearSampler: MTLSamplerState
    private let nearestSampler: MTLSamplerState
    private let mipmappedSampler: MTLSamplerState
    private var textureCache: CVMetalTextureCache?
    private var cursorTextures: [(image: CGImage, texture: MTLTexture)] = []

    init(layer: CAMetalLayer) throws {
        guard let device = MTLCreateSystemDefaultDevice() else { throw MessageError("MTLCreateSystemDefaultDevice returned no device") }
        guard let commandQueue = device.makeCommandQueue() else { throw MessageError("MTLDevice returned no command queue") }
        let library = try device.makeLibrary(source: Self.shaderSource, options: nil)
        self.device = device
        self.commandQueue = commandQueue
        layer.device = device
        layer.pixelFormat = .bgra8Unorm
        layer.framebufferOnly = true
        layer.isOpaque = true

        let pointerLibrary = try? device.makeLibrary(source: Self.pointerShaderSource, options: nil)

        func makePipelines(pixelFormat: MTLPixelFormat, cursorFunction: String, pointerFunction: String, invertsCursor: Bool) throws -> Pipelines {
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.vertexFunction = library.makeFunction(name: "quadVertex")
            descriptor.fragmentFunction = library.makeFunction(name: "screenFragment")
            descriptor.colorAttachments[0].pixelFormat = pixelFormat
            let screen = try device.makeRenderPipelineState(descriptor: descriptor)
            descriptor.fragmentFunction = library.makeFunction(name: "blackFragment")
            let black = try device.makeRenderPipelineState(descriptor: descriptor)
            var backdropPointer: MTLRenderPipelineState?
            if let function = pointerLibrary?.makeFunction(name: pointerFunction) {
                descriptor.fragmentFunction = function
                backdropPointer = try? device.makeRenderPipelineState(descriptor: descriptor)
            }
            descriptor.fragmentFunction = library.makeFunction(name: cursorFunction)
            let attachment = descriptor.colorAttachments[0]!
            attachment.isBlendingEnabled = true
            attachment.sourceRGBBlendFactor = .one
            attachment.sourceAlphaBlendFactor = .one
            attachment.destinationRGBBlendFactor = .oneMinusSourceAlpha
            attachment.destinationAlphaBlendFactor = .oneMinusSourceAlpha
            let cursor = try device.makeRenderPipelineState(descriptor: descriptor)
            let pointer: MTLRenderPipelineState
            if let backdropPointer {
                pointer = backdropPointer
            } else {
                descriptor.fragmentFunction = library.makeFunction(name: "pointerFallbackFragment")
                pointer = try device.makeRenderPipelineState(descriptor: descriptor)
            }
            var pipelines = Pipelines(
                screen: screen, black: black, cursor: cursor, pointer: pointer, pointerReadsBackdrop: backdropPointer != nil)
            guard invertsCursor else { return pipelines }
            descriptor.fragmentFunction = library.makeFunction(name: "maskedCursorFragment")
            pipelines.maskedCursor = try device.makeRenderPipelineState(descriptor: descriptor)
            descriptor.fragmentFunction = library.makeFunction(name: "cursorInversionFragment")
            attachment.sourceRGBBlendFactor = .oneMinusDestinationColor
            attachment.destinationRGBBlendFactor = .oneMinusSourceColor
            attachment.sourceAlphaBlendFactor = .zero
            attachment.destinationAlphaBlendFactor = .one
            pipelines.cursorInversion = try device.makeRenderPipelineState(descriptor: descriptor)
            return pipelines
        }
        standardPipelines = try makePipelines(
            pixelFormat: .bgra8Unorm, cursorFunction: "cursorFragment", pointerFunction: "pointerFragment", invertsCursor: true)
        hdrPipelines = try makePipelines(
            pixelFormat: .rgba16Float, cursorFunction: "cursorFragmentPQ", pointerFunction: "pointerFragmentPQ", invertsCursor: false)

        let samplerDescriptor = MTLSamplerDescriptor()
        samplerDescriptor.minFilter = .linear
        samplerDescriptor.magFilter = .linear
        samplerDescriptor.sAddressMode = .clampToEdge
        samplerDescriptor.tAddressMode = .clampToEdge
        linearSampler = device.makeSamplerState(descriptor: samplerDescriptor)!
        samplerDescriptor.magFilter = .nearest
        nearestSampler = device.makeSamplerState(descriptor: samplerDescriptor)!
        samplerDescriptor.magFilter = .linear
        samplerDescriptor.mipFilter = .linear
        mipmappedSampler = device.makeSamplerState(descriptor: samplerDescriptor)!
        let status = CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
        guard status == kCVReturnSuccess else { throw MessageError("CVMetalTextureCacheCreate", status: status) }
    }

    struct Frame {
        var pixelBuffer: CVPixelBuffer?
        var framebuffer: Framebuffer?
        var strips: [CVPixelBuffer?]
        var stripHeight: Int
        var canvasHeight: Int
        var imageRect: CGRect
        var cursor: CursorShape?
        var cursorRect: CGRect?
        var cursorShownBySystem: Bool
        var cursorOverDarkBackdrop: Bool?
        var viewSize: CGSize
        var pixelsPerImagePixel: CGFloat
        var pixelsPerCursorPixel: CGFloat
        var loupe: LoupeSpot?
    }

    func render(
        _ frame: Frame, in layer: CAMetalLayer,
        presented: @escaping @Sendable (_ time: CFTimeInterval, _ composited: Bool) -> Void,
        failed: @escaping @Sendable () -> Void
    ) -> Bool {
        var retained: [CVMetalTexture] = []
        var images: [(texture: MTLTexture, rect: CGRect, textureRect: SIMD4<Float>)] = []
        if let pixelBuffer = frame.pixelBuffer {
            guard let texture = makeTexture(pixelBuffer, retained: &retained) else { return false }
            images.append((texture, frame.imageRect, SIMD4<Float>(0, 0, 1, 1)))
        } else if !frame.strips.isEmpty {
            guard frame.stripHeight > 0, frame.canvasHeight > 0,
                frame.canvasHeight <= frame.stripHeight * frame.strips.count
            else { return false }
            let canvasHeight = CGFloat(frame.canvasHeight)
            for (band, strip) in frame.strips.enumerated() {
                guard let strip, let texture = makeTexture(strip, retained: &retained) else { return false }
                let top = CGFloat(band * frame.stripHeight)
                let visible = min(CGFloat(frame.stripHeight), canvasHeight - top)
                guard visible > 0 else { continue }
                let rect = CGRect(
                    x: frame.imageRect.minX,
                    y: frame.imageRect.minY + frame.imageRect.height * top / canvasHeight,
                    width: frame.imageRect.width,
                    height: frame.imageRect.height * visible / canvasHeight
                )
                images.append((texture, rect, SIMD4<Float>(0, 0, 1, Float(visible / CGFloat(frame.stripHeight)))))
            }
        }
        var drawnCursor: (texture: MTLTexture, inversion: MTLTexture?, rect: CGRect, sampler: MTLSamplerState, material: [SIMD4<Float>]?)?
        if let cursor = frame.cursor, let cursorRect = frame.cursorRect, !frame.cursorShownBySystem || frame.loupe != nil {
            let mipmapped = cursor.scale > 1
            guard let texture = texture(for: cursor.image, mipmapped: mipmapped) else { return false }
            var inversion: MTLTexture?
            if let image = cursor.inversion {
                guard let texture = self.texture(for: image, mipmapped: false) else { return false }
                inversion = texture
            }
            let material = cursor.material.map {
                $0.matrix(lightPointer: frame.cursorOverDarkBackdrop ?? $0.isDarkInterface) + $0.matrix(lightPointer: $0.isDarkInterface)
            }
            drawnCursor = (
                texture, inversion, cursorRect, mipmapped ? mipmappedSampler : sampler(pixelsPerTexel: frame.pixelsPerCursorPixel), material
            )
        }
        configure(layer, for: frame.pixelBuffer ?? frame.strips.compactMap { $0 }.first)
        let pipelines = isHDR ? hdrPipelines : standardPipelines
        guard let drawable = layer.nextDrawable(), let commandBuffer = commandQueue.makeCommandBuffer() else { return false }
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = drawable.texture
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].storeAction = .store
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else { return false }
        let sampler = self.sampler(pixelsPerTexel: frame.pixelsPerImagePixel)
        func setQuad(_ rect: CGRect) {
            var position = ndc(rect, in: frame.viewSize)
            var textureRect = SIMD4<Float>(0, 0, 1, 1)
            encoder.setVertexBytes(&position, length: MemoryLayout<SIMD4<Float>>.size, index: 0)
            encoder.setVertexBytes(&textureRect, length: MemoryLayout<SIMD4<Float>>.size, index: 1)
        }
        func drawContent(dx: CGFloat, dy: CGFloat, drawsCursor: Bool) {
            for image in images {
                draw(
                    image.texture, in: image.rect.offsetBy(dx: dx, dy: dy), textureRect: image.textureRect, viewSize: frame.viewSize,
                    sampler: sampler, pipeline: pipelines.screen, encoder: encoder)
            }
            guard drawsCursor, let drawnCursor else { return }
            setQuad(drawnCursor.rect.offsetBy(dx: dx, dy: dy))
            encoder.setFragmentTexture(drawnCursor.texture, index: 0)
            encoder.setFragmentSamplerState(drawnCursor.sampler, index: 0)
            if let material = drawnCursor.material {
                encoder.setRenderPipelineState(pipelines.pointer)
                if pipelines.pointerReadsBackdrop {
                    material.withUnsafeBytes { encoder.setFragmentBytes($0.baseAddress!, length: $0.count, index: 0) }
                }
            } else if let inversion = drawnCursor.inversion, let maskedCursor = pipelines.maskedCursor,
                let cursorInversion = pipelines.cursorInversion
            {
                encoder.setFragmentTexture(inversion, index: 1)
                encoder.setRenderPipelineState(maskedCursor)
                encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
                encoder.setRenderPipelineState(cursorInversion)
            } else {
                encoder.setRenderPipelineState(pipelines.cursor)
            }
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
        }
        drawContent(dx: 0, dy: 0, drawsCursor: !frame.cursorShownBySystem)
        if let loupe = frame.loupe {
            let pixelsPerPoint = CGFloat(drawable.texture.width) / frame.viewSize.width
            encoder.setScissorRect(
                MTLScissorRect(
                    x: Int((loupe.frame.minX * pixelsPerPoint).rounded()), y: Int((loupe.frame.minY * pixelsPerPoint).rounded()),
                    width: Int((loupe.frame.width * pixelsPerPoint).rounded()), height: Int((loupe.frame.height * pixelsPerPoint).rounded())))
            setQuad(loupe.frame)
            encoder.setRenderPipelineState(pipelines.black)
            encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
            drawContent(dx: loupe.frame.minX - loupe.source.minX, dy: loupe.frame.minY - loupe.source.minY, drawsCursor: true)
        }
        encoder.endEncoding()
        let containsImage = !images.isEmpty
        let box = TextureRetainer(textures: retained, framebuffer: frame.framebuffer)
        commandBuffer.addCompletedHandler { buffer in
            withExtendedLifetime(box) {}
            if buffer.status == .error {
                failed()
                return
            }
            #if targetEnvironment(simulator)
            if containsImage {
                presented(CACurrentMediaTime(), false)
            }
            #endif
        }
        #if !targetEnvironment(simulator)
        drawable.addPresentedHandler { drawable in
            let time = drawable.presentedTime
            #if os(macOS)
            // On a virtual display presentedTime stays 0, and the handler runs about 2 ms after the vsync at
            // which WindowServer composites the drawable; the composite is displayed at the following vsync
            // (observed on Mac14,3, macOS 27.0.1 26A434, against ScreenCaptureKit display times).
            if time == 0 {
                if containsImage {
                    presented(CACurrentMediaTime(), true)
                }
                return
            }
            #endif
            guard time.isFinite, time > 0 else {
                failed()
                return
            }
            if containsImage {
                presented(time, false)
            }
        }
        #endif
        commandBuffer.present(drawable)
        commandBuffer.commit()
        return true
    }

    private func configure(_ layer: CAMetalLayer, for pixelBuffer: CVPixelBuffer?) {
        let hdr = pixelBuffer.map { CVPixelBufferGetPixelFormatType($0) == kCVPixelFormatType_64RGBAHalf } ?? false
        if hdr != isHDR {
            isHDR = hdr
            layer.pixelFormat = hdr ? .rgba16Float : .bgra8Unorm
            if #available(iOS 16, *) {
                layer.wantsExtendedDynamicRangeContent = hdr
                layer.edrMetadata = hdr ? CAEDRMetadata.hdr10(minLuminance: 0.0005, maxLuminance: 1000, opticalOutputScale: 100) : nil
            }
        }
        let colorSpace: CGColorSpace?
        if hdr {
            colorSpace = CGColorSpace(name: CGColorSpace.displayP3_PQ)
        } else if let pixelBuffer, let attached = CVImageBufferGetColorSpace(pixelBuffer)?.takeUnretainedValue() {
            colorSpace = attached
        } else if let pixelBuffer, let attachments = CVBufferCopyAttachments(pixelBuffer, .shouldPropagate),
            let derived = CVImageBufferCreateColorSpaceFromAttachments(attachments)?.takeRetainedValue()
        {
            colorSpace = derived
        } else {
            colorSpace = CGColorSpace(name: CGColorSpace.sRGB)
        }
        let name = colorSpace?.name
        guard name != colorSpaceName || name == nil else { return }
        colorSpaceName = name
        layer.colorspace = colorSpace
    }

    private func draw(
        _ texture: MTLTexture, in rect: CGRect, textureRect: SIMD4<Float>, viewSize: CGSize, sampler: MTLSamplerState,
        pipeline: MTLRenderPipelineState, encoder: MTLRenderCommandEncoder
    ) {
        encoder.setRenderPipelineState(pipeline)
        var position = ndc(rect, in: viewSize)
        var coordinates = textureRect
        encoder.setVertexBytes(&position, length: MemoryLayout<SIMD4<Float>>.size, index: 0)
        encoder.setVertexBytes(&coordinates, length: MemoryLayout<SIMD4<Float>>.size, index: 1)
        encoder.setFragmentTexture(texture, index: 0)
        encoder.setFragmentSamplerState(sampler, index: 0)
        encoder.drawPrimitives(type: .triangleStrip, vertexStart: 0, vertexCount: 4)
    }

    private func sampler(pixelsPerTexel: CGFloat) -> MTLSamplerState {
        pixelsPerTexel >= 2 || abs(pixelsPerTexel - 1) < 0.001 ? nearestSampler : linearSampler
    }

    private func ndc(_ rect: CGRect, in size: CGSize) -> SIMD4<Float> {
        SIMD4<Float>(
            Float(rect.minX / size.width * 2 - 1),
            Float(1 - rect.minY / size.height * 2),
            Float(rect.maxX / size.width * 2 - 1),
            Float(1 - rect.maxY / size.height * 2)
        )
    }

    private func makeTexture(_ pixelBuffer: CVPixelBuffer, retained: inout [CVMetalTexture]) -> MTLTexture? {
        guard let textureCache else { return nil }
        let pixelFormat: MTLPixelFormat
        switch CVPixelBufferGetPixelFormatType(pixelBuffer) {
        case kCVPixelFormatType_32BGRA: pixelFormat = .bgra8Unorm
        case kCVPixelFormatType_64RGBAHalf: pixelFormat = .rgba16Float
        default: return nil
        }
        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            nil, textureCache, pixelBuffer, nil, pixelFormat,
            CVPixelBufferGetWidth(pixelBuffer), CVPixelBufferGetHeight(pixelBuffer), 0, &cvTexture
        )
        guard status == kCVReturnSuccess, let cvTexture, let texture = CVMetalTextureGetTexture(cvTexture) else { return nil }
        retained.append(cvTexture)
        return texture
    }

    private func texture(for image: CGImage, mipmapped: Bool) -> MTLTexture? {
        if let cached = cursorTextures.first(where: { $0.image === image && (!mipmapped || $0.texture.mipmapLevelCount > 1) }) {
            return cached.texture
        }
        let width = image.width
        let height = image.height
        let bytesPerRow = width * 4
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * height)
        let drawn = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard
                let context = CGContext(
                    data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: bytesPerRow,
                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                    bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
                )
            else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: mipmapped)
        descriptor.usage = .shaderRead
        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }
        texture.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: pixels, bytesPerRow: bytesPerRow)
        if mipmapped {
            guard let commandBuffer = commandQueue.makeCommandBuffer(), let blit = commandBuffer.makeBlitCommandEncoder() else { return nil }
            blit.generateMipmaps(for: texture)
            blit.endEncoding()
            commandBuffer.commit()
        }
        cursorTextures = cursorTextures.suffix(1) + [(image, texture)]
        return texture
    }
}

private nonisolated final class TextureRetainer: @unchecked Sendable {
    let textures: [CVMetalTexture]
    let framebuffer: Framebuffer?

    init(textures: [CVMetalTexture], framebuffer: Framebuffer?) {
        self.textures = textures
        self.framebuffer = framebuffer
    }
}

protocol RemoteScreen: AnyObject {
    var bounds: CGRect { get }
    var imageRect: CGRect { get }
    var displayScale: CGFloat { get }
    var isOccluded: Bool { get }
    func drawnCursor(_ shape: CursorShape?, visible: Bool, scale: CGFloat) -> (shape: CursorShape, shownBySystem: Bool)?
    #if os(iOS)
    func cursorDidRender(in rect: CGRect?)
    func loupe(for icon: CGRect?) -> LoupeSpot?
    #endif
}

final class ScreenPresenter {
    private static let backdropSampleSize: CGFloat = 19

    private unowned let screen: any RemoteScreen
    private unowned let controller: SessionController
    private let pipeline: DisplayPipeline
    private let layer: CAMetalLayer
    private var renderer: ScreenRenderer?
    private var displayLink: CADisplayLink?
    private var presentationTimer: DispatchSourceTimer?
    private var presentationTick: CFTimeInterval = 0
    private var needsRender = true
    private var renderedThisFrame = false
    private var commits = 0
    private var drainedThrough = 0
    private var presentationDepth = 1
    private var excessDepth = 0
    private var drained = false
    private var drainsNext = false
    private var idleFrames = 0
    private var lastSnapshot: DisplayPipeline.Snapshot?
    private var refreshRate = 0
    private var cursorOverDarkBackdrop: Bool?

    init(screen: any RemoteScreen, controller: SessionController, layer: CAMetalLayer) {
        self.screen = screen
        self.controller = controller
        pipeline = controller.pipeline
        self.layer = layer
        do {
            renderer = try ScreenRenderer(layer: layer)
        } catch {
            DispatchQueue.main.async { [controller] in controller.drawingFailed(error) }
        }
        pipeline.setWakeHandler { [weak self] in
            DispatchQueue.main.async { self?.contentChanged() }
        }
    }

    deinit {
        MainActor.assumeIsolated {
            displayLink?.invalidate()
            presentationTimer?.cancel()
            pipeline.setWakeHandler(nil)
        }
    }

    func start(_ makeLink: (_ target: Any, _ selector: Selector) -> CADisplayLink) {
        let link = makeLink(DisplayLinkTarget(self), #selector(DisplayLinkTarget.tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
        refreshRate = 0
    }

    func stop() {
        displayLink?.invalidate()
        displayLink = nil
        presentationTimer?.cancel()
        presentationTimer = nil
    }

    func setRefreshRate(_ rate: Int) {
        guard rate > 0, rate != refreshRate else { return }
        refreshRate = rate
        let maximum = Float(rate)
        displayLink?.preferredFrameRateRange = CAFrameRateRange(minimum: min(30, maximum), maximum: maximum, preferred: maximum)
        controller.screenRefreshRateDidChange(rate)
    }

    func pause() {
        displayLink?.isPaused = true
    }

    func setNeedsRender() {
        needsRender = true
        wake()
    }

    fileprivate func tick() {
        renderedThisFrame = false
        guard !screen.isOccluded else {
            displayLink?.isPaused = true
            return
        }
        if let displayLink, pipeline.isSchedulingPresentation {
            if presentationTimer == nil {
                let timer = DispatchSource.makeTimerSource(queue: .main)
                timer.setEventHandler { [weak self] in
                    MainActor.assumeIsolated { self?.presentScheduledFrames() }
                }
                timer.activate()
                presentationTimer = timer
            }
            presentationTick = displayLink.targetTimestamp
            let midpoint = (displayLink.timestamp + displayLink.targetTimestamp) / 2
            presentationTimer?.schedule(deadline: .now() + max(midpoint - CACurrentMediaTime(), 0), leeway: .microseconds(100))
            return
        }
        let snapshot = pipeline.consume(through: displayLink?.timestamp ?? CACurrentMediaTime())
        if let snapshot {
            lastSnapshot = snapshot
        }
        guard snapshot != nil || needsRender else {
            idleFrames += 1
            if idleFrames > 30 { displayLink?.isPaused = true }
            return
        }
        idleFrames = 0
        renderedThisFrame = true
        needsRender = !render()
    }

    private func presentScheduledFrames() {
        guard !screen.isOccluded else { return }
        let snapshot = pipeline.consume(through: presentationTick)
        if let snapshot {
            lastSnapshot = snapshot
        }
        guard snapshot != nil || needsRender else {
            idleFrames += 1
            if idleFrames > 30 { displayLink?.isPaused = true }
            return
        }
        idleFrames = 0
        renderedThisFrame = true
        needsRender = !render()
    }

    private func contentChanged() {
        wake()
        guard !pipeline.isSchedulingPresentation, !renderedThisFrame, !screen.isOccluded,
            let snapshot = pipeline.consume(through: CACurrentMediaTime())
        else { return }
        lastSnapshot = snapshot
        renderedThisFrame = true
        needsRender = !render()
    }

    private func wake() {
        guard !screen.isOccluded else { return }
        idleFrames = 0
        displayLink?.isPaused = false
    }

    // Core Animation shows the drawables presented to a CAMetalLayer one per refresh, oldest first, so a second
    // drawable within one refresh delays every later one by a refresh for as long as one is presented each refresh
    // (observed on Mac14,3, macOS 27.0.1 26A434).
    private func drawablePresented(_ commit: Int, committed: CFTimeInterval, at time: CFTimeInterval) {
        guard commit > drainedThrough, let link = displayLink, link.duration > 0 else { return }
        let latched = link.timestamp + ((committed - link.timestamp) / link.duration).rounded(.up) * link.duration
        guard latched - committed > link.duration / 4 else { return }
        let depth = Int(((time - latched) / link.duration).rounded())
        if depth <= presentationDepth {
            presentationDepth = depth
            excessDepth = 0
            drained = false
            return
        }
        excessDepth += 1
        guard excessDepth >= 3 else { return }
        excessDepth = 0
        if drained {
            presentationDepth = depth
            drained = false
            return
        }
        drained = true
        drainsNext = true
        drainedThrough = commits
    }

    private func render() -> Bool {
        if drainsNext {
            drainsNext = false
            return false
        }
        let viewSize = screen.bounds.size
        guard let renderer, viewSize.width > 0, viewSize.height > 0 else { return false }
        let snapshot = lastSnapshot ?? pipeline.snapshot()
        lastSnapshot = snapshot
        let epoch = snapshot.presentationEpoch
        let generation = snapshot.pictureGeneration
        let run = controller.debugInfo.presentationRun
        guard CGSize(width: snapshot.width, height: snapshot.height) == controller.imageSize, pipeline.isCurrentPresentation(epoch: epoch)
        else { return false }
        let imageRect = screen.imageRect
        let scale = screen.displayScale
        let resolutionScale = controller.pictureMatchesWindow ? controller.resolutionScale : 1
        let cursorScale = max(
            scale * controller.info.framebufferPixelsPerCursorPixel, 1 / (controller.info.cursorPixelsPerPoint * resolutionScale))
        let cursor = screen.drawnCursor(snapshot.cursor, visible: snapshot.cursorVisible, scale: cursorScale)
        var cursorRect: CGRect?
        if let cursor = cursor?.shape, snapshot.cursorVisible, controller.phase == .connected {
            let origin = CGPoint(
                x: imageRect.minX + snapshot.cursorPosition.x * scale - cursor.hotspot.x * cursorScale,
                y: imageRect.minY + snapshot.cursorPosition.y * scale - cursor.hotspot.y * cursorScale
            )
            cursorRect = CGRect(
                origin: origin,
                size: CGSize(
                    width: CGFloat(cursor.image.width) / cursor.scale * cursorScale,
                    height: CGFloat(cursor.image.height) / cursor.scale * cursorScale))
        }
        #if os(iOS)
        screen.cursorDidRender(in: cursor?.shownBySystem == false ? cursorRect : nil)
        let loupe = screen.loupe(for: cursorRect)
        // pointeruid tracks the luma behind the pointer with a 19 x 19 pt _UILumaTrackingBackdropView, which turns the
        // pointer light at luma <= 0.45 and dark at >= 0.55, and splits a first sample in between at 0.5; Core
        // Animation takes the luma as 0.2126 R + 0.7152 G + 0.0722 B of the mean encoded backdrop colour (pointeruid
        // 172, iPadOS 27.2 24B5110a, UUID DC5440B7-2A3E-3CB1-BFC8-F86EEB565E5C,
        // -[PUIDSystemPointerRootViewController viewDidLoad] 0x10001e6ec..0x10001e714, sub_100007328
        // 0x100007360..0x100007374; UIKitCore 9127.1.13.1.106, UUID 56EB6C8F-D489-378F-AABA-81975A6D213E,
        // -[_UILumaTrackingBackdropView backdropLayer:didChangeLuma:] 0x184b9654c..0x184b966c8; QuartzCore 1223.10.19,
        // UUID 2F6224C2-91DC-30A3-A132-806F887DD1D8, CA::OGL::MetalContext::calculate_average_luma block
        // 0x18460876c..0x1846087b8).
        if let cursor, cursor.shape.material != nil, !cursor.shownBySystem || loupe != nil,
            let brightness = Self.brightness(of: snapshot, around: snapshot.cursorPosition, radius: Self.backdropSampleSize / 2 / scale)
        {
            if brightness >= 0.55 {
                cursorOverDarkBackdrop = false
            } else if brightness <= 0.45 {
                cursorOverDarkBackdrop = true
            } else if cursorOverDarkBackdrop == nil {
                cursorOverDarkBackdrop = brightness < 0.5
            }
        }
        #else
        let loupe: LoupeSpot? = nil
        #endif
        let committed = CACurrentMediaTime()
        let commit = commits &+ 1
        let rendered = renderer.render(
            ScreenRenderer.Frame(
                pixelBuffer: snapshot.pixelBuffer,
                framebuffer: snapshot.framebuffer,
                strips: snapshot.strips,
                stripHeight: snapshot.stripHeight,
                canvasHeight: snapshot.canvasHeight,
                imageRect: imageRect,
                cursor: cursor?.shape,
                cursorRect: cursorRect,
                cursorShownBySystem: cursor?.shownBySystem ?? false,
                cursorOverDarkBackdrop: cursorOverDarkBackdrop,
                viewSize: viewSize,
                pixelsPerImagePixel: scale * layer.contentsScale,
                pixelsPerCursorPixel: cursorScale * layer.contentsScale,
                loupe: loupe
            ),
            in: layer,
            presented: { [weak self, pipeline, debugInfo = controller.debugInfo] time, composited in
                DispatchQueue.main.async {
                    var time = time
                    if composited {
                        guard let link = self?.displayLink, link.duration > 0 else { return }
                        time = link.timestamp + ((time - link.timestamp) / link.duration).rounded(.up) * link.duration
                    }
                    self?.drawablePresented(commit, committed: committed, at: time)
                    guard let generation, let run, pipeline.isCurrentPresentation(epoch: epoch) else { return }
                    debugInfo.picturePresented(generation, at: time, run: run)
                }
            },
            failed: { [weak self, pipeline] in
                DispatchQueue.main.async {
                    guard let self, pipeline.isCurrentPresentation(epoch: epoch) else { return }
                    self.setNeedsRender()
                }
            }
        )
        if rendered {
            commits = commit
        }
        return rendered
    }
}

#if os(iOS)
extension ScreenPresenter {
    fileprivate static func brightness(of snapshot: DisplayPipeline.Snapshot, around center: CGPoint, radius: CGFloat) -> CGFloat? {
        var locked: [CVPixelBuffer] = []
        defer {
            for buffer in locked { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        }
        var total: CGFloat = 0
        var count = 0
        for row in 0..<7 {
            for column in 0..<7 {
                let point = CGPoint(x: center.x + radius * (CGFloat(column) / 3 - 1), y: center.y + radius * (CGFloat(row) / 3 - 1))
                let x = Int(point.x.rounded(.down))
                var y = Int(point.y.rounded(.down))
                guard x >= 0, y >= 0, x < snapshot.width, y < snapshot.height else { continue }
                var buffer = snapshot.pixelBuffer
                if buffer == nil, snapshot.stripHeight > 0, y / snapshot.stripHeight < snapshot.strips.count {
                    buffer = snapshot.strips[y / snapshot.stripHeight]
                    y %= snapshot.stripHeight
                }
                guard let buffer, x < CVPixelBufferGetWidth(buffer), y < CVPixelBufferGetHeight(buffer) else { continue }
                if !locked.contains(where: { $0 === buffer }) {
                    guard CVPixelBufferLockBaseAddress(buffer, .readOnly) == kCVReturnSuccess else { continue }
                    locked.append(buffer)
                }
                guard let base = CVPixelBufferGetBaseAddress(buffer) else { continue }
                let line = base + y * CVPixelBufferGetBytesPerRow(buffer)
                switch CVPixelBufferGetPixelFormatType(buffer) {
                case kCVPixelFormatType_32BGRA:
                    let pixel = line.advanced(by: x * 4).assumingMemoryBound(to: UInt8.self)
                    total += (0.0722 * CGFloat(pixel[0]) + 0.7152 * CGFloat(pixel[1]) + 0.2126 * CGFloat(pixel[2])) / 255
                case kCVPixelFormatType_64RGBAHalf:
                    let pixel = line.advanced(by: x * 8).assumingMemoryBound(to: Float16.self)
                    let linear = (0..<3).map { channel in
                        let p = pow(max(CGFloat(pixel[channel]), 0), 1 / 78.84375)
                        return pow(max(p - 0.8359375, 0) / (18.8515625 - 18.6875 * p), 1 / 0.1593017578125) * 100
                    }
                    let encoded = [
                        1.22494017 * linear[0] - 0.22494017 * linear[1],
                        -0.04205696 * linear[0] + 1.04205696 * linear[1],
                        -0.01963755 * linear[0] - 0.07863604 * linear[1] + 1.09827360 * linear[2],
                    ].map { component in
                        let component = min(max(component, 0), 1)
                        return component <= 0.0031308 ? component * 12.92 : 1.055 * pow(component, 1 / 2.4) - 0.055
                    }
                    total += 0.2126 * encoded[0] + 0.7152 * encoded[1] + 0.0722 * encoded[2]
                default:
                    continue
                }
                count += 1
            }
        }
        return count > 0 ? total / CGFloat(count) : nil
    }
}
#endif

private final class DisplayLinkTarget {
    weak var presenter: ScreenPresenter?

    init(_ presenter: ScreenPresenter) {
        self.presenter = presenter
    }

    @objc func tick() {
        presenter?.tick()
    }
}
