#if os(iOS)
import UIKit
import UniformTypeIdentifiers

final class ScreenDrag: NSObject, UIDragInteractionDelegate {
    private unowned let view: RemoteScreenUIView
    private unowned let controller: SessionController
    private unowned let input: ScreenInput
    private let source = UIView()
    private var interaction: UIDragInteraction!
    private weak var attemptedTouch: UITouch?
    private(set) weak var takenTouch: UITouch?
    private weak var session: UIDragSession?
    private var liftPreviews: [ObjectIdentifier: [UIView]] = [:]
    private var offer: RemoteDragOffer?
    private var relative = false
    private var last: (location: CGPoint, time: TimeInterval)?
    private var isOverMac = true
    private var droppedOnMac = false

    init(view: RemoteScreenUIView, controller: SessionController, input: ScreenInput) {
        self.view = view
        self.controller = controller
        self.input = input
        super.init()
        source.isUserInteractionEnabled = false
        source.frame = view.bounds
        source.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.insertSubview(source, at: 0)
        interaction = UIDragInteraction(delegate: self)
        source.addInteraction(interaction)
    }

    func begin(with touch: UITouch, relative: Bool) {
        guard controller.remoteDragOffer != nil, session == nil, touch !== attemptedTouch else { return }
        attemptedTouch = touch
        self.relative = relative
        last = (touch.location(in: view), touch.timestamp)
        #if DEBUG || SIDELOAD
        // -[UIDragInteraction _immediatelyBeginDragWithTouch:completion:] skips the lift gesture: it asks the
        // delegate for items and a lift preview at once, then moves the touch, still down, into a system drag
        // session and cancels it in the view. UIKit tears sheets off into new windows this way
        // (UIKitCore 9127.1.12.1.101, iPadOS 27.2 24B5099f, UUID AC1DE7C2-BE96-3C32-8C62-77AD2B97AD03,
        // -[UIDragInteraction _immediatelyBeginDragWithTouch:completion:] 0x1853d2600..0x1853d275c,
        // ___63-[UIDragInteraction _immediatelyBeginDragWithTouch:completion:]_block_invoke 0x1853d27d8..0x1853d2850,
        // -[UIDragInteraction _beginLiftForItems:session:animated:] 0x1853d3de0,
        // -[UIView(UIDragging) _beginDragWithTouches:orInitialCentroid:accessibilityEndpoint:] 0x1853fdc10..0x1853fdc7c,
        // -[UISheetPresentationController sheetInteraction:didChangeOffset:] 0x184f93f40..0x184f940a8).
        // That lift is not animated, but UIKit still passes its animator to
        // dragInteraction:willAnimateLiftWithAnimator:session: and finishes it, running its completions, when the
        // session takes over the drag image (UIKitCore 9127.0.84, iPadOS 24A446,
        // UUID AAC84A00-13AB-3B06-B622-A4E676971EB8,
        // -[UIDragInteraction _beginLiftForItems:session:animated:] 0x1854dd8e0..0x1854dd928,
        // -[UIDragInteraction _sessionHandedOffDragImage:] 0x1854de53c..0x1854de554,
        // -[_UIDragSourceLiftEffect interaction:didChangeWithContext:] 0x1854e75f8..0x1854e760c,
        // 0x1854e76d8..0x1854e771c).
        let selector = NSSelectorFromString("_immediatelyBeginDragWithTouch:completion:")
        guard interaction.responds(to: selector) else { return }
        typealias Begin = @convention(c) (AnyObject, Selector, UITouch, @convention(block) (Bool) -> Void) -> Void
        unsafeBitCast(interaction.method(for: selector), to: Begin.self)(interaction, selector, touch) { _ in }
        #endif
    }

    var isDragging: Bool {
        session != nil
    }

    func owns(_ session: UIDragSession) -> Bool {
        session === self.session
    }

    func moved(to location: CGPoint) -> UIDropProposal {
        let now = CACurrentMediaTime()
        if relative {
            if let last {
                let delta = CGPoint(x: location.x - last.location.x, y: location.y - last.location.y)
                input.movePointer(by: delta, speed: hypot(delta.x, delta.y) / max(now - last.time, 0.001))
            }
            setOverMac(true)
        } else if view.imageRect.intersection(view.bounds).contains(location) {
            controller.movePointer(to: view.imagePoint(for: location))
            setOverMac(true)
        } else {
            setOverMac(false)
        }
        last = (location, now)
        return UIDropProposal(operation: isOverMac ? .move : .forbidden)
    }

    func exited() {
        last = nil
        setOverMac(false)
    }

    func drop(at location: CGPoint) {
        _ = moved(to: location)
        guard isOverMac else { return }
        droppedOnMac = true
        controller.release(.primary)
    }

    private func setOverMac(_ over: Bool) {
        guard over != isOverMac, let session else { return }
        isOverMac = over
        for item in session.items {
            let index = item.localObject as? Int
            item.previewProvider = { [weak self] in
                guard let self else { return nil }
                return UIDragPreview(view: self.previewView(index: index), parameters: self.previewParameters)
            }
        }
    }

    private func previewView(index: Int?) -> UIView {
        let preview = UIImageView()
        guard !isOverMac, index == 0 else {
            preview.bounds.size = CGSize(width: 1, height: 1)
            return preview
        }
        if let image = offer?.image {
            let scale = view.displayScale
            preview.image = UIImage(cgImage: image)
            preview.bounds.size = CGSize(width: CGFloat(image.width) * scale, height: CGFloat(image.height) * scale)
        } else {
            preview.image = UIImage(systemName: offer?.items.first?.isFolder == true ? "folder.fill" : "doc.fill")
            preview.contentMode = .scaleAspectFit
            preview.bounds.size = CGSize(width: 48, height: 48)
        }
        return preview
    }

    private var previewParameters: UIDragPreviewParameters {
        let parameters = UIDragPreviewParameters()
        parameters.backgroundColor = .clear
        return parameters
    }

    func dragInteraction(_ interaction: UIDragInteraction, itemsForBeginning session: UIDragSession) -> [UIDragItem] {
        guard let offer = controller.takeRemoteDrag() else { return [] }
        self.offer = offer
        self.session = session
        takenTouch = attemptedTouch
        isOverMac = true
        droppedOnMac = false
        let folder = AppDirectory.caches.appendingPathComponent("Drags", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        session.localContext = folder
        return offer.items.enumerated().map { index, item in
            let dragItem = UIDragItem(itemProvider: itemProvider(for: item, in: folder))
            dragItem.localObject = index
            return dragItem
        }
    }

    // Files takes a drag only when every item conforms to public.content, public.directory or one
    // of a few data types such as archives (DOCAcceptableDragPasteboardTypes) (DocumentManagerExecutables 403.1.8,
    // iPadOS 27.2 24B5099f, UUID 1DAC9909-A490-3241-88D4-91DF8E8C2F6D,
    // one-time initialization function for DOCAcceptableDragPasteboardTypes 0x2639c2ae4..0x2639c2d44,
    // function signature specialization <Arg[0] = Dead, Arg[2] = Dead> of
    // DocumentManagerExecutables.DOCItemCollectionViewController.collectionView(_: __C.UICollectionView, canHandle:
    // __C.UIDropSession) -> Swift.Bool 0x2637d4010..0x2637d42b0).
    private func itemProvider(for item: RemoteDragItem, in folder: URL) -> NSItemProvider {
        let name = (item.path as NSString).lastPathComponent
        let provider = NSItemProvider()
        provider.suggestedName = name
        let extensionType = UTType(filenameExtension: (name as NSString).pathExtension).flatMap { $0.isDeclared ? $0 : nil }
        let type = item.isFolder ? .folder : extensionType ?? .data
        let types = type.conforms(to: .content) || type.conforms(to: .directory) ? [type] : [type, .content]
        for type in types {
            provider.registerFileRepresentation(forTypeIdentifier: type.identifier, fileOptions: [], visibility: .all) { [weak self] completion in
                let progress = Progress(totalUnitCount: 0)
                let destination = folder.appendingPathComponent(UUID().uuidString, isDirectory: true)
                Task { @MainActor in
                    guard let self else { return completion(nil, false, CocoaError(.userCancelled)) }
                    do {
                        try FileManager.default.createDirectory(at: destination, withIntermediateDirectories: true)
                    } catch {
                        return completion(nil, false, error)
                    }
                    self.controller.downloadFile(item.path, into: destination, progress: progress) { result in
                        switch result {
                        case .success(let url): completion(url, false, nil)
                        case .failure(let error): completion(nil, false, error)
                        }
                    }
                }
                return progress
            }
        }
        return provider
    }

    func dragInteraction(_ interaction: UIDragInteraction, previewForLifting item: UIDragItem, session: UIDragSession) -> UITargetedDragPreview? {
        let preview = previewView(index: item.localObject as? Int)
        preview.center = last?.location ?? .zero
        view.addSubview(preview)
        liftPreviews[ObjectIdentifier(session), default: []].append(preview)
        return UITargetedDragPreview(view: preview, parameters: previewParameters)
    }

    func dragInteraction(_ interaction: UIDragInteraction, willAnimateLiftWith animator: UIDragAnimating, session: UIDragSession) {
        let identifier = ObjectIdentifier(session)
        guard let previews = liftPreviews[identifier] else { return }
        animator.addCompletion { [weak self] _ in
            previews.forEach { $0.removeFromSuperview() }
            guard let self else { return }
            self.liftPreviews[identifier]?.removeAll { preview in previews.contains { $0 === preview } }
            if self.liftPreviews[identifier]?.isEmpty == true {
                self.liftPreviews[identifier] = nil
            }
        }
    }

    func dragInteraction(_ interaction: UIDragInteraction, session: UIDragSession, didEndWith operation: UIDropOperation) {
        liftPreviews.removeValue(forKey: ObjectIdentifier(session))?.forEach { $0.removeFromSuperview() }
        if !droppedOnMac { controller.cancelRemoteDrag() }
        if droppedOnMac || operation == .cancel || operation == .forbidden, let folder = session.localContext as? URL {
            try? FileManager.default.removeItem(at: folder)
        }
        self.session = nil
        offer = nil
        takenTouch = nil
        last = nil
    }

    func dragInteraction(_ interaction: UIDragInteraction, sessionDidTransferItems session: UIDragSession) {
        guard let folder = session.localContext as? URL else { return }
        try? FileManager.default.removeItem(at: folder)
    }
}
#endif
