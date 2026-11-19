import Perception
import SwiftUI
import UniformTypeIdentifiers

struct CursorStyleView: View {
    #if os(iOS)
    static let systemStyleName: LocalizedStringKey = "iPad Style"
    #else
    static let systemStyleName: LocalizedStringKey = "System Style"
    #endif

    private static let previewSize: CGFloat = 28
    private static let previewHeight: CGFloat = 64
    #if os(iOS)
    private static let systemPreviews: [StandardCursor] = [
        .arrow, .iBeam, .crosshair, .operationNotAllowed, .dragCopy, .resize([.left, .right]), .resize([.up, .down]),
        .resize([.upLeft, .downRight]),
    ]
    private static var systemPreviewCache: (isDark: Bool, increasesContrast: Bool, scale: CGFloat, images: [(image: CGImage, scale: CGFloat)])?
    #else
    private static let systemPreviews: [StandardCursor] = [
        .arrow, .pointingHand, .iBeam, .openHand, .crosshair, .operationNotAllowed, .dragCopy, .resize([.left, .right]),
    ]
    #endif

    private let settings = AppSettings.shared
    private let store = CursorThemeStore.shared
    @Environment(\.displayScale) private var displayScale
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.colorSchemeContrast) private var colorSchemeContrast
    @State private var isPickingFile = false
    @State private var alert: AlertState?

    var body: some View {
        WithPerceptionTracking {
            Form {
                if let images = previewImages {
                    Section {
                        HStack(spacing: 0) {
                            ForEach(Array(images.enumerated()), id: \.offset) { _, preview in
                                let size = CGSize(
                                    width: CGFloat(preview.image.width) / preview.scale, height: CGFloat(preview.image.height) / preview.scale)
                                let factor = min(1, Self.previewSize / max(size.width, size.height))
                                Image(decorative: preview.image, scale: 1)
                                    .resizable()
                                    .interpolation(.high)
                                    .frame(width: size.width * factor, height: size.height * factor)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                        .frame(height: Self.previewHeight)
                    }
                }
                Section {
                    row(.system) {
                        Text(Self.systemStyleName)
                    }
                    row(.remote) {
                        Text("Remote Style")
                    }
                }
                Section("Custom") {
                    Button {
                        isPickingFile = true
                    } label: {
                        HStack {
                            Label("Import", systemImage: "doc.badge.plus")
                            if store.isImporting {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .formRowButtonStyleCompat()
                    .disabled(store.isImporting)
                    ForEach(store.themes) { theme in
                        WithPerceptionTracking {
                            row(.theme(theme.id)) {
                                Text(verbatim: theme.name)
                            }
                            .contextMenu {
                                Button(role: .destructive) {
                                    delete(theme)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                            }
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { store.themes[$0] }.forEach(delete)
                    }
                }
            }
            #if os(macOS)
            .formStyle(.grouped)
            .unboundedFormWidth()
            #endif
            .navigationTitle("Cursor Style")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .alert($alert)
            .fileImporter(isPresented: $isPickingFile, allowedContentTypes: [.folder, .data], allowsMultipleSelection: true) { result in
                guard case .success(let urls) = result else { return }
                Task {
                    var failure: (name: String, error: any Error)?
                    for url in urls {
                        do {
                            try await store.importFile(url)
                        } catch {
                            failure = failure ?? (url.lastPathComponent, error)
                        }
                    }
                    if let failure {
                        alert = AlertState(String(localized: "Import Failed"), item: failure.name, error: failure.error)
                    }
                }
            }
        }
    }

    private var previewImages: [(image: CGImage, scale: CGFloat)]? {
        switch settings.cursorStyle {
        case .system: systemPreviewImages()
        case .remote: nil
        case .theme(let id): store.theme(id: id)?.previewImages(scale: displayScale)
        }
    }

    private func systemPreviewImages() -> [(image: CGImage, scale: CGFloat)] {
        #if os(iOS)
        let isDark = colorScheme == .dark
        let increasesContrast = colorSchemeContrast == .increased
        if let cache = Self.systemPreviewCache, cache.isDark == isDark, cache.increasesContrast == increasesContrast, cache.scale == displayScale {
            return cache.images
        }
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        UIColor.secondarySystemGroupedBackground.resolvedColor(with: UITraitCollection(userInterfaceStyle: isDark ? .dark : .light))
            .getRed(&red, green: &green, blue: &blue, alpha: nil)
        let material = CursorMaterial(isDarkInterface: isDark, increasesContrast: increasesContrast)
        let images = Self.systemPreviews.compactMap { cursor in
            PointerShape.preview(for: cursor, backdrop: SIMD3(Float(red), Float(green), Float(blue)), material: material, scale: displayScale)
                .map { ($0, displayScale) }
        }
        Self.systemPreviewCache = (isDark, increasesContrast, displayScale, images)
        return images
        #else
        return Self.systemPreviews.compactMap { cursor in
            guard let image = cursor.systemCursor?.image else { return nil }
            var rect = CGRect(x: 0, y: 0, width: image.size.width * displayScale, height: image.size.height * displayScale)
            return image.cgImage(forProposedRect: &rect, context: nil, hints: nil).map { ($0, CGFloat($0.width) / image.size.width) }
        }
        #endif
    }

    private func row(_ style: CursorStyle, @ViewBuilder label: () -> some View) -> some View {
        Button {
            settings.cursorStyle = style
        } label: {
            HStack {
                label()
                    .foregroundStyle(.primary)
                Spacer()
                if settings.cursorStyle == style {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func delete(_ theme: CursorTheme) {
        do {
            try store.delete(theme)
        } catch {
            alert = AlertState(String(localized: "Delete Failed"), error: error)
        }
    }
}
