#if os(macOS)
import AppKit
import ObjectiveC

enum FullScreenCameraHousing {
    // In a full screen space on a display with a camera housing, WindowServer keeps its opaque Menubar window across the
    // housing row at NSMainMenuWindowLevel even while the menu bar is hidden, with or without
    // NSApplicationPresentationHideMenuBar, and MenuBarAgent's window sits one level above it. With the row no longer
    // reserved, AppKit leaves the hidden NSToolbarFullScreenWindow at zero alpha right below the row, where it still
    // takes clicks (macOS 27.3 26C5047e on Mac16,8, observed through CGWindowListCopyWindowInfo and screen captures).
    static let windowLevel = NSWindow.Level(rawValue: NSWindow.Level.mainMenu.rawValue + 2)

    // AppKit keeps a full screen window out of the menu bar row whenever the window's content controller answers YES
    // here: -[NSWindow _visibleTileFrameForFullScreen] then cuts +[NSMenu _revealedMenuBarHeight] off the top of the tile,
    // and the menu bar companion window, the tile content rectangle and the enter transition snapshot ask the same
    // method. It answers YES while the user keeps the menu bar visible in full screen (_hasAlwaysVisibleMenuBar with
    // NSApplicationPresentationAutoHideMenuBar), and otherwise whenever the screen has a camera housing. Live changes go
    // through -[_NSFullScreenSpace updateTileShapeAndReshapeContents] and the menu bar companion controller
    // (AppKit 2775.20.133, macOS 27.2.1 26B131, UUID 84D3429A-075D-366D-95D1-36B2D44E8B5D,
    // -[_NSFullScreenContentController reservesSpaceForMenuBarInFullScreen] 0x18af7fb94..0x18af7fbb4,
    // -[_NSFullScreenSpace alwaysReservesSpaceForMenuBarInFullScreen] 0x18b06dd08..0x18b06dd70,
    // -[NSWindow(NSFullScreen) _visibleTileFrameForFullScreen] 0x18b1cad48..0x18b1cade8,
    // +[_NSFullScreenSpace _fullscreenMenuBarVisibilityChanged] 0x18b06be28..0x18b06bf3c,
    // -[_NSFullScreenContentController displayChanged] 0x18af8072c..0x18af807b8,
    // -[_NSFullScreenMenuBarCompanionController updateContentViewForReveal] 0x18aadeb5c..0x18aaded9c).
    private static var isInstalled = false

    static var isAvailable: Bool {
        isInstalled
            && NSScreen.screens.contains {
                $0.safeAreaInsets.top > 0 && $0.auxiliaryTopLeftArea != nil && $0.auxiliaryTopRightArea != nil
            }
    }

    static func install() {
        guard !isInstalled else { return }
        let selector = NSSelectorFromString("reservesSpaceForMenuBarInFullScreen")
        guard let type = NSClassFromString("_NSFullScreenContentController"),
            let method = class_getInstanceMethod(type, selector),
            let keepsMenuBar = class_getInstanceVariable(type, "_hasAlwaysVisibleMenuBar"),
            class_getInstanceMethod(type, NSSelectorFromString("window")) != nil
        else { return }
        typealias Getter = @convention(c) (NSObject, Selector) -> Bool
        let original = unsafeBitCast(method_getImplementation(method), to: Getter.self)
        let offset = ivar_getOffset(keepsMenuBar)
        let getter: @convention(block) (NSObject) -> Bool = { controller in
            guard original(controller, selector) else { return false }
            if Unmanaged.passUnretained(controller).toOpaque().load(fromByteOffset: offset, as: Bool.self) { return true }
            return !RemoteScreenNSView.fillsCameraHousing(controller.value(forKey: "window") as? NSWindow)
        }
        method_setImplementation(method, imp_implementationWithBlock(getter))
        isInstalled = true
    }

    static func update(_ window: NSWindow) {
        let contentControllerSelector = NSSelectorFromString("_fullScreenContentController")
        let spaceSelector = NSSelectorFromString("space")
        guard isInstalled, window.styleMask.contains(.fullScreen), window.responds(to: contentControllerSelector),
            let controller = window.perform(contentControllerSelector)?.takeUnretainedValue() as? NSObject,
            controller.responds(to: spaceSelector),
            let space = controller.perform(spaceSelector)?.takeUnretainedValue() as? NSObject
        else { return }
        let updateTile = NSSelectorFromString("updateTileShapeAndReshapeContents")
        if space.responds(to: updateTile) {
            _ = space.perform(updateTile)
        }
        let companionSelector = NSSelectorFromString("menuBarCompanionController")
        if controller.responds(to: companionSelector),
            let companion = controller.perform(companionSelector)?.takeUnretainedValue() as? NSObject
        {
            for name in ["_updateMenubarCompanionWindowAutohideHeightAndPosition", "updateContentViewForReveal"] {
                let selector = NSSelectorFromString(name)
                if companion.responds(to: selector) {
                    _ = companion.perform(selector)
                }
            }
        }
    }

    // When the menu bar auto-hides in full screen, MenuBarAgent reveals it in a higher-level space whenever the pointer
    // enters the menu bar row on a notched display or pushes against the top edge, unless NSMenu locks its position
    // through MenuBarAgent (AppKit 2775.20.133, -[NSToolbarFullScreenWindow _disableFullScreenMenubarAutohiding]
    // 0x1a7c5ad28..0x1a7c5ad78, -[NSMenu _lockMenuPosition] 0x18aa02ba0..0x18aa02bf8).
    private static let lockMenuPosition = NSSelectorFromString("_lockMenuPosition")
    private static let unlockMenuPosition = NSSelectorFromString("_unlockMenuPosition")
    private static var isMenuBarLocked = false

    static func setMenuBarLocked(_ locked: Bool) {
        guard locked != isMenuBarLocked else { return }
        let selector = locked ? lockMenuPosition : unlockMenuPosition
        guard let menu = NSApp.mainMenu, menu.responds(to: selector) else { return }
        isMenuBarLocked = locked
        menu.perform(selector)
    }

    static func housing(coveredBy window: NSWindow) -> CGRect? {
        guard isInstalled, RemoteScreenNSView.fillsCameraHousing(window),
            !UserDefaults.standard.bool(forKey: "AppleMenuBarVisibleInFullscreen"), let screen = window.screen,
            let left = screen.auxiliaryTopLeftArea, let right = screen.auxiliaryTopRightArea,
            right.minX > left.maxX
        else { return nil }
        let height = screen.safeAreaInsets.top
        guard height > 0 else { return nil }
        let housing = CGRect(x: left.maxX, y: screen.frame.maxY - height, width: right.minX - left.maxX, height: height)
        return window.frame.insetBy(dx: -1, dy: -1).contains(housing) ? housing : nil
    }
}
#endif
