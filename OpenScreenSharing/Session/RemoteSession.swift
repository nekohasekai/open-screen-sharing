import CoreGraphics
import CoreMedia
import CoreVideo
import Foundation

nonisolated enum SessionPhase: Equatable, Sendable {
    case idle
    case connecting
    case authenticating
    case waitingForPermission
    case choosingSession(SessionChoicePrompt)
    case preparingCursors
    case connected
    case reconnecting(attempt: Int)
    case disconnected
    case failed(SessionFailure)

    var isActive: Bool {
        switch self {
        case .connected, .reconnecting: true
        default: false
        }
    }
}

nonisolated struct SessionFailure: Error, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case authenticationFailed
        case permissionDenied
        case hostNotFound
        case ownScreen
        case unreachable
        case connectionLost
        case closedByServer
        case unsupported
        case certificateChanged(fingerprint: String)
        case unverifiedServer
        case highPerformance(MediaFailure.Kind)
        case drawingFailed
        case ended
        case other
    }

    var kind: Kind
    var message: String

    init(_ kind: Kind, message: String = "") {
        self.kind = kind
        self.message = message
    }
}

nonisolated struct SessionChoicePrompt: Equatable, Sendable {
    var id: Int64
    var consoleUser: String
    var allowsShareConsole: Bool
    var allowsOwnSession: Bool
}

nonisolated enum SessionChoice: Sendable {
    case shareConsole
    case ownSession
    case cancel
}

nonisolated enum SessionMode: Equatable, Sendable {
    case control
    case observe

    var controls: Bool { self == .control }
}

nonisolated struct RemoteDisplay: Identifiable, Hashable, Sendable {
    var id: Int64
    var name: String
    var width: Int
    var height: Int
    var isMain: Bool
    var isVirtual: Bool = false
}

nonisolated enum DisplaySelection: Hashable, Sendable {
    case display(Int64)
    case all
}

nonisolated struct MediaFailure: Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case busy
        case network
        case refused
        case silent
        case unsupported
        case undecodable
        case other
    }

    var kind: Kind
    var message: String

    init(_ kind: Kind, message: String = "") {
        self.kind = kind
        self.message = message
    }
}

nonisolated enum SessionEvent: Sendable {
    case bell
    case pointerVisible(Bool)
    case clipboardUnreadable(String)
    case clipboardNotRestored(String)
}

typealias ClipboardContent = [[String: Data]]

nonisolated struct FileTransferError: Error, Equatable, Sendable {
    enum Kind: Equatable, Sendable {
        case cancelled
        case notAllowed
        case notFound
        case notWritable
        case diskFull
        case permission
        case local
        case connection
        case observeOnly
        case unsupported
        case failed
    }

    var kind: Kind
    var message: String

    init(_ kind: Kind, message: String = "") {
        self.kind = kind
        self.message = message
    }
}

nonisolated struct RemoteDragItem: Sendable {
    let path: String
    let isFolder: Bool
}

nonisolated struct PointerButtons: OptionSet, Hashable, Sendable {
    let rawValue: UInt8

    static let primary = PointerButtons(rawValue: 1 << 0)
    static let middle = PointerButtons(rawValue: 1 << 1)
    static let secondary = PointerButtons(rawValue: 1 << 2)
}

nonisolated struct CursorShape: @unchecked Sendable {
    var image: CGImage
    var hotspot: CGPoint
    var kind: StandardCursor?
    var inversion: CGImage?
    var scale: CGFloat = 1
    var material: CursorMaterial?
}

nonisolated struct CursorMaterial: Equatable, Sendable {
    // Colour matrices of the pointer material for a light or dark pointer, with or without
    // _AXSPointerIncreasedContrastEnabled (PointerUIServices 172, iPadOS 27.2 24B5110a,
    // UUID D63E9940-478E-322E-A6A3-66E96D5B679C, _PSPointerVibrantColorMatrixForLuminanceWithPlasmaEnabled
    // 0x227816d70..0x227816dcc, matrices 0x2278190c8..0x227819208).
    private static let lightPointerMatrix: [SIMD4<Float>] = [
        SIMD4(0.03331, -0.07167, -0.07173, 0.20796), SIMD4(-0.14078, -0.03573, -0.14068, 0.23153),
        SIMD4(-0.02729, -0.02735, 0.07767, 0.04501), SIMD4(0.95, 0.95, 0.95, 1 - 0.40335),
    ]
    private static let lightPointerContrastMatrix: [SIMD4<Float>] = [
        lightPointerMatrix[0], lightPointerMatrix[1], lightPointerMatrix[2], SIMD4(1.05, 1.05, 1.05, 1 - 0.40335),
    ]
    private static let darkPointerMatrix: [SIMD4<Float>] = [
        SIMD4(0.15, 0.10, 0.10, -0.025), SIMD4(0.10, 0.15, 0.10, -0.025), SIMD4(0.10, 0.10, 0.15, -0.025), SIMD4(0.05, 0.05, 0.05, 1),
    ]
    private static let darkPointerContrastMatrix: [SIMD4<Float>] = [
        SIMD4(0.15, 0.05, 0.05, 0.12), SIMD4(0.05, 0.15, 0.07, 0.12), SIMD4(0.05, 0.05, 0.15, 0.12), SIMD4(0, 0, 0, 1),
    ]

    var isDarkInterface: Bool
    var increasesContrast: Bool

    func matrix(lightPointer: Bool) -> [SIMD4<Float>] {
        switch (lightPointer, increasesContrast) {
        case (true, false): Self.lightPointerMatrix
        case (true, true): Self.lightPointerContrastMatrix
        case (false, false): Self.darkPointerMatrix
        case (false, true): Self.darkPointerContrastMatrix
        }
    }
}

nonisolated struct DeviceInfo: Equatable, Sendable {
    var model: String
    var enclosureColor: String
}

nonisolated enum SessionCredentials: Sendable, Hashable {
    case account(username: String, password: String)
    case vncPassword(String)
    case askForPermission(username: String)
    case noAuthentication

    var username: String {
        switch self {
        case .account(let username, _), .askForPermission(let username): username
        case .vncPassword, .noAuthentication: ""
        }
    }

    var account: (username: String, password: String)? {
        if case .account(let username, let password) = self { return (username, password) }
        return nil
    }
}

nonisolated struct SessionConfiguration: Sendable {
    var address: ComputerAddress
    var remoteProtocol: RemoteProtocol
    var platform: RemotePlatform?
    var trustedFingerprint: String
    var allowsUnverifiedLogin = false
    var keyboardLayout: Int
    var clientName: String
    var credentials: SessionCredentials
    var quality: SessionQuality
    var startsInObserveMode: Bool
    var sharesClipboard: Bool
    var receivesAudio: Bool
    var sendsAudio: Bool
    var updateRate: Int
    var awaitsVirtualDisplay: Bool
    var networkConfiguration = NetworkConfiguration()
    var keepsAlive = false
    var disablesUDP = false
}

nonisolated struct SessionInfo: Equatable, Sendable {
    var videoCodec: String?
    var framebufferWidth: Int = 0
    var framebufferHeight: Int = 0
    var framebufferPixelsPerCursorPixel: Double = 1
    var cursorPixelsPerPoint: Double = 1
    var dialect: ServerDialect
    var platform: RemotePlatform
    var fingerprint: String = ""

    init(remoteProtocol: RemoteProtocol, platform: RemotePlatform?) {
        dialect = remoteProtocol == .rdp ? .rdp : .apple
        self.platform = platform ?? (remoteProtocol == .rdp ? .pc : .mac)
    }
}

nonisolated enum ServerDialect: Sendable {
    case apple
    case standard
    case rdp
}

nonisolated enum RemotePlatform: Sendable {
    case mac
    case pc
}

nonisolated struct SessionCapabilities: OptionSet, Hashable, Sendable {
    let rawValue: UInt32

    static let control = SessionCapabilities(rawValue: 1 << 0)
    static let highPerformance = SessionCapabilities(rawValue: 1 << 2)
    static let clipboard = SessionCapabilities(rawValue: 1 << 3)
    static let displaySelection = SessionCapabilities(rawValue: 1 << 4)
    static let virtualDisplay = SessionCapabilities(rawValue: 1 << 5)
    static let fileTransfer = SessionCapabilities(rawValue: 1 << 6)
    static let keyboardSource = SessionCapabilities(rawValue: 1 << 7)
    static let gestures = SessionCapabilities(rawValue: 1 << 8)
    static let assistCursor = SessionCapabilities(rawValue: 1 << 9)
    static let missionControl = SessionCapabilities(rawValue: 1 << 10)
    static let appWindows = SessionCapabilities(rawValue: 1 << 11)
    static let showDesktop = SessionCapabilities(rawValue: 1 << 12)
    static let launchpad = SessionCapabilities(rawValue: 1 << 13)
    static let keyCodes = SessionCapabilities(rawValue: 1 << 14)
    static let dragToRemote = SessionCapabilities(rawValue: 1 << 15)
    static let dragFromRemote = SessionCapabilities(rawValue: 1 << 16)
    static let quality = SessionCapabilities(rawValue: 1 << 17)
    static let observe = SessionCapabilities(rawValue: 1 << 18)
    static let audio = SessionCapabilities(rawValue: 1 << 19)
    static let clipboardRequest = SessionCapabilities(rawValue: 1 << 20)
    static let unicodeText = SessionCapabilities(rawValue: 1 << 21)
    static let touch = SessionCapabilities(rawValue: 1 << 22)
}

nonisolated enum GesturePhase: Sendable {
    case mayBegin
    case began
    case changed
    case ended
    case cancelled
}

nonisolated enum TouchPhase: Sendable {
    case began
    case moved
    case ended
    case cancelled
}

nonisolated struct RemoteTouch: Sendable {
    var id: Int64
    var phase: TouchPhase
    var location: CGPoint
}

nonisolated enum SystemCommand: CaseIterable, Identifiable, Sendable {
    case missionControl
    case appWindows
    case showDesktop
    case launchpad

    var id: Self { self }

    var capability: SessionCapabilities {
        switch self {
        case .missionControl: .missionControl
        case .appWindows: .appWindows
        case .showDesktop: .showDesktop
        case .launchpad: .launchpad
        }
    }
}

nonisolated enum VideoCodec: String, Codable, Sendable {
    case h264
    case hevc
}

nonisolated struct VideoFormat: @unchecked Sendable {
    var codec: VideoCodec
    var parameterSets: [Data]
    var bands: Int
    var canvasHeight: Int = 0
    var isHDR = false
}

nonisolated struct VideoAccessUnit: @unchecked Sendable {
    var data: Data
    var isSync: Bool
    var presentationTime: CMTime
    var band: Int
    var bands: Int
    var frameID: Int64
    var frameEnd: Bool
    var changedBands: Int
    var discontinuity: Bool
    var arrival: CFTimeInterval
    var composed: CFTimeInterval?
}

nonisolated struct FrameTiming: Sendable {
    var arrival: CFTimeInterval
    var capture: CFTimeInterval
    var composed: CFTimeInterval?
}

nonisolated struct DecodedVideoFrame: @unchecked Sendable {
    var pixelBuffer: CVPixelBuffer?
    var strips: [CVPixelBuffer]
    var canvasHeight: Int
    var timing: FrameTiming
}

nonisolated struct AudioFormat: Equatable, Sendable {
    var formatID: AudioFormatID
    var sampleRate: Double
    var channels: UInt32
    var framesPerPacket: UInt32
    var magicCookie: Data
}

nonisolated struct AudioAccessUnit: Sendable {
    var data: Data
    var timestamp: Int64
    var lost: Int
}

nonisolated struct SessionStatistics: Sendable {
    var roundTripMilliseconds: Double?
    var receivedBytes: Int64
    var encoding: String?
    var packets: PacketStatistics?
    var video: VideoStreamStatistics?
}

nonisolated struct PacketStatistics: Sendable {
    var received: Int64
    var expected: Int64
}

nonisolated struct VideoStreamStatistics: Sendable {
    var losses: Int64
}
