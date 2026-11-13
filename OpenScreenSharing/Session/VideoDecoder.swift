import CoreMedia
import CoreVideo
import Foundation
import QuartzCore
import VideoToolbox
import os

nonisolated final class VideoDecoder: @unchecked Sendable {
    private let queue = DispatchQueue(label: "io.nekohasekai.screensharing.video-decoder")
    private var session: VTDecompressionSession?
    private var formatDescription: CMVideoFormatDescription?
    private var codec: VideoCodec?
    private var parameterSets: [Data] = []
    private var waitingForSync = true
    private var canvasHeight = 0
    private var bands = 1
    private var isHDR = false
    private var suspended = false
    private var generation: UInt64 = 0
    private var nextTicket: UInt64 = 0
    private var inFlight: [UInt64: Submission] = [:]
    private var pending: [PendingFrame] = []
    private var base: [CVPixelBuffer?] = []
    private var lastSubmittedFrameID: Int64?
    private var lastSubmissionEnded = true
    private var rejections = 0
    private var reportedUnsupported = false
    private let completions = LockedState(initialState: [Completion]())
    private let output: @Sendable (DecodedVideoFrame) -> Void
    private let failure: @Sendable () -> Void
    private let unsupported: @Sendable (_ hdr: Bool, _ error: any Error) -> Void

    private static let maxInFlight = 8
    private static let maxPendingFrames = 4
    private static let rejectionLimit = 10

    private struct Submission {
        var frameID: Int64
        var band: Int
    }

    private struct PendingFrame {
        var id: Int64
        var arrival: CFTimeInterval
        var capture: CFTimeInterval
        var composed: CFTimeInterval?
        var submittedMask = 0
        var ended = false
        var remaining = 0
        var latestTickets: [UInt64?]
        var strips: [CVPixelBuffer?]
    }

    private struct Completion: @unchecked Sendable {
        var generation: UInt64
        var ticket: UInt64
        var status: OSStatus
        var pixelBuffer: CVPixelBuffer?
    }

    init(
        output: @escaping @Sendable (DecodedVideoFrame) -> Void,
        failure: @escaping @Sendable () -> Void,
        unsupported: @escaping @Sendable (_ hdr: Bool, _ error: any Error) -> Void
    ) {
        self.output = output
        self.failure = failure
        self.unsupported = unsupported
    }

    deinit {
        if let session {
            VTDecompressionSessionInvalidate(session)
        }
    }

    func reset() {
        queue.sync {
            invalidateSession()
            formatDescription = nil
            parameterSets = []
            codec = nil
        }
    }

    func setFormat(_ format: VideoFormat) {
        queue.sync {
            let changed =
                format.codec != codec || format.parameterSets != parameterSets || format.isHDR != isHDR
                || format.bands != bands || format.canvasHeight != canvasHeight
            guard changed || formatDescription == nil else { return }
            if changed {
                rejections = 0
                reportedUnsupported = false
            }
            invalidateSession()
            formatDescription = nil
            parameterSets = format.parameterSets
            codec = format.codec
            canvasHeight = format.canvasHeight
            bands = format.bands
            isHDR = format.isHDR
            do {
                guard bands == 1 || bands == 4, bands == 1 || canvasHeight > 0 else {
                    throw MessageError("the video stream has an invalid band layout")
                }
                formatDescription = try Self.makeFormatDescription(format)
            } catch {
                reportUnsupported(error)
            }
        }
    }

    func setSuspended(_ suspended: Bool) {
        queue.sync {
            guard suspended != self.suspended else { return }
            self.suspended = suspended
            if suspended {
                invalidateSession()
            }
        }
    }

    func decode(_ unit: VideoAccessUnit) {
        queue.sync { submit(unit) }
    }

    private func submit(_ unit: VideoAccessUnit) {
        guard !suspended else { return }
        if unit.discontinuity {
            invalidateSession()
        }
        if waitingForSync {
            guard unit.isSync else {
                if unit.discontinuity { failure() }
                return
            }
        }
        guard let formatDescription else { return }
        let fullMask = (1 << bands) - 1
        guard unit.bands == bands, unit.band >= 0, unit.band < bands,
            unit.frameID > 0, unit.changedBands > 0, unit.changedBands & ~fullMask == 0,
            unit.changedBands & (1 << unit.band) != 0
        else {
            recover()
            return
        }
        if session == nil {
            do {
                try createSession()
            } catch {
                reportUnsupported(error)
                return
            }
        }
        guard let session else { return }
        let epoch = generation
        if inFlight.count >= Self.maxInFlight || lastSubmittedFrameID != unit.frameID && pending.count >= Self.maxPendingFrames {
            let status = VTDecompressionSessionWaitForAsynchronousFrames(session)
            drainCompletions()
            guard generation == epoch else { return }
            guard status == noErr else {
                rejected(status)
                return
            }
            guard inFlight.count < Self.maxInFlight else {
                recover()
                return
            }
        }
        guard let sample = Self.makeSampleBuffer(unit.data, time: unit.presentationTime, format: formatDescription) else {
            recover()
            return
        }
        if lastSubmittedFrameID != unit.frameID {
            if let previous = lastSubmittedFrameID {
                guard lastSubmissionEnded, previous < Int64.max, unit.frameID == previous + 1 else {
                    recover()
                    return
                }
            }
            guard pending.count < Self.maxPendingFrames else {
                recover()
                return
            }
            pending.append(PendingFrame(
                id: unit.frameID, arrival: unit.arrival, capture: unit.presentationTime.seconds, composed: unit.composed,
                latestTickets: Array(repeating: nil, count: bands), strips: Array(repeating: nil, count: bands)))
            lastSubmittedFrameID = unit.frameID
            lastSubmissionEnded = false
        }
        guard !lastSubmissionEnded, let index = pending.indices.last, pending[index].id == unit.frameID else {
            recover()
            return
        }
        pending[index].submittedMask |= 1 << unit.band
        pending[index].arrival = max(pending[index].arrival, unit.arrival)
        guard unit.changedBands == pending[index].submittedMask else {
            recover()
            return
        }
        nextTicket &+= 1
        let ticket = nextTicket
        pending[index].remaining += 1
        pending[index].latestTickets[unit.band] = ticket
        pending[index].ended = unit.frameEnd
        lastSubmissionEnded = unit.frameEnd
        inFlight[ticket] = Submission(frameID: unit.frameID, band: unit.band)
        if waitingForSync {
            waitingForSync = false
            base = Array(repeating: nil, count: bands)
        }
        let status = VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample, flags: [._EnableAsynchronousDecompression], infoFlagsOut: nil) {
            [weak self, completions, queue] status, _, imageBuffer, _, _ in
            let completion = Completion(generation: epoch, ticket: ticket, status: status, pixelBuffer: imageBuffer)
            completions.withLock { $0.append(completion) }
            queue.async { [weak self] in self?.drainCompletions() }
        }
        if status != noErr {
            rejected(status)
        }
    }

    private func drainCompletions() {
        let ready = completions.withLock { values in
            let ready = values
            values.removeAll(keepingCapacity: true)
            return ready
        }
        for completion in ready {
            guard completion.generation == generation, let submission = inFlight.removeValue(forKey: completion.ticket) else { continue }
            guard completion.status == noErr else {
                rejected(completion.status)
                continue
            }
            guard let pixelBuffer = completion.pixelBuffer, let index = pending.firstIndex(where: { $0.id == submission.frameID }) else {
                recover()
                continue
            }
            rejections = 0
            pending[index].remaining -= 1
            if pending[index].latestTickets[submission.band] == completion.ticket {
                pending[index].strips[submission.band] = pixelBuffer
            }
            publishCompletedFrames()
        }
    }

    private func publishCompletedFrames() {
        while let first = pending.first, first.ended, first.remaining == 0 {
            for band in 0..<bands where first.submittedMask & (1 << band) != 0 {
                guard let strip = first.strips[band] else {
                    recover()
                    return
                }
                base[band] = strip
            }
            pending.removeFirst()
            let strips = base.compactMap { $0 }
            if strips.count == bands {
                let width = CVPixelBufferGetWidth(strips[0])
                let height = CVPixelBufferGetHeight(strips[0])
                guard width > 0, height > 0,
                    strips.allSatisfy({ CVPixelBufferGetWidth($0) == width && CVPixelBufferGetHeight($0) == height })
                else {
                    recover()
                    return
                }
                let frame = DecodedVideoFrame(
                    pixelBuffer: bands == 1 ? strips[0] : nil,
                    strips: bands == 1 ? [] : strips,
                    canvasHeight: canvasHeight,
                    timing: FrameTiming(arrival: first.arrival, capture: first.capture, composed: first.composed))
                output(frame)
            }
        }
    }

    private func recover() {
        invalidateSession()
        failure()
    }

    private func rejected(_ status: OSStatus) {
        LibsharingLog.logger.error("VideoToolbox rejected a frame: OSStatus \(status, privacy: .public)")
        rejections += 1
        recover()
        if rejections >= Self.rejectionLimit {
            reportUnsupported(MessageError("VTDecompressionSessionDecodeFrame", status: status))
        }
    }

    private func reportUnsupported(_ error: any Error) {
        guard !reportedUnsupported else { return }
        reportedUnsupported = true
        unsupported(isHDR, error)
    }

    private func invalidateSession() {
        generation &+= 1
        pending = []
        base = []
        lastSubmittedFrameID = nil
        lastSubmissionEnded = true
        inFlight = [:]
        completions.withLock { $0.removeAll(keepingCapacity: true) }
        waitingForSync = true
        if let session {
            VTDecompressionSessionInvalidate(session)
        }
        session = nil
    }

    private func createSession() throws {
        guard let formatDescription else { return }
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: isHDR ? kCVPixelFormatType_64RGBAHalf : kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [CFString: Any](),
            kCVPixelBufferMetalCompatibilityKey: true,
        ]
        var created: VTDecompressionSession?
        let status = VTDecompressionSessionCreate(
            allocator: nil, formatDescription: formatDescription, decoderSpecification: nil,
            imageBufferAttributes: attributes as CFDictionary, outputCallback: nil, decompressionSessionOut: &created
        )
        guard status == noErr, let created else { throw MessageError("VTDecompressionSessionCreate", status: status) }
        VTSessionSetProperty(created, key: kVTDecompressionPropertyKey_RealTime, value: kCFBooleanTrue)
        session = created
    }

    static func makeFormatDescription(_ format: VideoFormat) throws -> CMVideoFormatDescription {
        let buffers = format.parameterSets.map { [UInt8]($0) }
        guard !buffers.isEmpty else { throw MessageError("the \(format.codec.rawValue.uppercased()) stream came without parameter sets") }
        let copies = buffers.map { buffer -> UnsafeMutablePointer<UInt8> in
            let pointer = UnsafeMutablePointer<UInt8>.allocate(capacity: max(buffer.count, 1))
            pointer.initialize(from: buffer, count: buffer.count)
            return pointer
        }
        defer { copies.forEach { $0.deallocate() } }
        let pointers = copies.map { UnsafePointer($0) }
        let sizes = buffers.map(\.count)
        var description: CMVideoFormatDescription?
        let status: OSStatus
        let operation: String
        switch format.codec {
        case .h264:
            operation = "CMVideoFormatDescriptionCreateFromH264ParameterSets"
            status = CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: nil, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, formatDescriptionOut: &description
            )
        case .hevc:
            operation = "CMVideoFormatDescriptionCreateFromHEVCParameterSets"
            status = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: nil, parameterSetCount: pointers.count, parameterSetPointers: pointers,
                parameterSetSizes: sizes, nalUnitHeaderLength: 4, extensions: nil, formatDescriptionOut: &description
            )
        }
        guard status == noErr, let description else { throw MessageError(operation, status: status) }
        return description
    }

    static func makeSampleBuffer(_ payload: Data, time: CMTime, format: CMVideoFormatDescription) -> CMSampleBuffer? {
        guard !payload.isEmpty else { return nil }
        var blockBuffer: CMBlockBuffer?
        let count = payload.count
        guard
            CMBlockBufferCreateWithMemoryBlock(
                allocator: nil, memoryBlock: nil, blockLength: count, blockAllocator: nil, customBlockSource: nil,
                offsetToData: 0, dataLength: count, flags: 0, blockBufferOut: &blockBuffer
            ) == noErr, let blockBuffer
        else { return nil }
        let copied = payload.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: blockBuffer, offsetIntoDestination: 0, dataLength: count)
        }
        guard copied == noErr else { return nil }
        var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: time, decodeTimeStamp: .invalid)
        var sampleSize = count
        var sampleBuffer: CMSampleBuffer?
        guard
            CMSampleBufferCreateReady(
                allocator: nil, dataBuffer: blockBuffer, formatDescription: format, sampleCount: 1,
                sampleTimingEntryCount: 1, sampleTimingArray: &timing, sampleSizeEntryCount: 1,
                sampleSizeArray: &sampleSize, sampleBufferOut: &sampleBuffer
            ) == noErr
        else { return nil }
        return sampleBuffer
    }
}
