ObjC.import('AppKit')
ObjC.import('CoreGraphics')

const shell = Application.currentApplication()
shell.includeStandardAdditions = true

const cuaDriver = '/Applications/CuaDriver.app/Contents/MacOS/cua-driver'

const dockApps = [
	['com.apple.apps.launcher', '/System/Applications/Apps.app'],
	['com.apple.Safari', '/System/Volumes/Preboot/Cryptexes/App/System/Applications/Safari.app'],
	['com.apple.MobileSMS', '/System/Applications/Messages.app'],
	['com.apple.mail', '/System/Applications/Mail.app'],
	['com.apple.Maps', '/System/Applications/Maps.app'],
	['com.apple.Photos', '/System/Applications/Photos.app'],
	['com.apple.FaceTime', '/System/Applications/FaceTime.app'],
	['com.apple.mobilephone', '/System/Applications/Phone.app'],
	['com.apple.iCal', '/System/Applications/Calendar.app'],
	['com.apple.AddressBook', '/System/Applications/Contacts.app'],
	['com.apple.reminders', '/System/Applications/Reminders.app'],
	['com.apple.Notes', '/System/Applications/Notes.app'],
	['com.apple.TV', '/System/Applications/TV.app'],
	['com.apple.Music', '/System/Applications/Music.app'],
	['com.apple.games', '/System/Applications/Games.app'],
	['com.apple.AppStore', '/System/Applications/App Store.app'],
	['com.apple.ScreenContinuity', '/System/Applications/iPhone Mirroring.app'],
	['com.apple.systempreferences', '/System/Applications/System Settings.app'],
]

const dockPriority = [
	'com.apple.Safari', 'com.apple.MobileSMS', 'com.apple.mail', 'com.apple.Photos', 'com.apple.iCal',
	'com.apple.Notes', 'com.apple.Music', 'com.apple.Maps', 'com.apple.FaceTime', 'com.apple.AppStore',
	'com.apple.systempreferences', 'com.apple.apps.launcher', 'com.apple.reminders', 'com.apple.TV',
	'com.apple.AddressBook', 'com.apple.mobilephone', 'com.apple.games', 'com.apple.ScreenContinuity',
]

function sh(command) {
	return shell.doShellScript(command, {alteringLineEndings: false})
}

function quote(value) {
	return "'" + String(value).replaceAll("'", "'\\''") + "'"
}

function cua(tool, args) {
	return JSON.parse(sh(cuaDriver + ' call ' + tool + ' ' + quote(JSON.stringify(args))))
}

function sleep(seconds) {
	$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(seconds))
}

function waitFor(what, seconds, probe) {
	const deadline = Date.now() + seconds * 1000
	for (;;) {
		const result = probe()
		if (result) return result
		if (Date.now() > deadline) throw new Error('timed out waiting for ' + what)
		sleep(0.25)
	}
}

function preferences(domain) {
	const data = $(sh('defaults export ' + domain + ' -')).dataUsingEncoding($.NSUTF8StringEncoding)
	return ObjC.deepUnwrap($.NSPropertyListSerialization.propertyListWithDataOptionsFormatError(data, 0, null, null))
}

function canonical(value) {
	if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
	if (typeof value === 'object') return '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}'
	return JSON.stringify(value)
}

function plist(value) {
	if (Array.isArray(value)) return '<array>' + value.map(plist).join('') + '</array>'
	if (typeof value === 'object') return '<dict>' + Object.entries(value).map(([key, item]) => '<key>' + escape(key) + '</key>' + plist(item)).join('') + '</dict>'
	if (typeof value === 'boolean') return value ? '<true/>' : '<false/>'
	if (typeof value === 'number') return Number.isInteger(value) ? '<integer>' + value + '</integer>' : '<real>' + value + '</real>'
	return '<string>' + escape(value) + '</string>'
}

function escape(text) {
	return text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
}

function setPreferences(domain, values) {
	const current = preferences(domain)
	const changed = Object.entries(values).filter(([key, value]) => current[key] === undefined || canonical(current[key]) !== canonical(value))
	changed.forEach(([key, value]) => sh('defaults write ' + domain + ' ' + quote(key) + ' ' + quote(plist(value))))
	return changed.length > 0
}

function regularApplications() {
	const list = $.NSWorkspace.sharedWorkspace.runningApplications
	const apps = []
	for (let i = 0; i < list.count; i++) {
		const app = list.objectAtIndex(i)
		if (app.activationPolicy === $.NSApplicationActivationPolicyRegular && app.bundleIdentifier.js !== 'com.apple.finder') apps.push(app)
	}
	return apps
}

function quitApplications(keep) {
	const apps = regularApplications().filter(app => !keep.includes(app.bundleIdentifier.js))
	const pids = apps.map(app => app.processIdentifier).join(' ')
	if (!pids) return
	const alive = () => sh('for pid in ' + pids + '; do kill -0 $pid 2>/dev/null && echo $pid; done; true').trim()
	apps.forEach(app => app.terminate)
	try {
		waitFor('applications to quit', 5, () => !alive())
	} catch {
		sh('kill -9 ' + alive().split('\n').join(' ') + ' 2>/dev/null; true')
		waitFor('applications to be killed', 5, () => !alive())
	}
}

function configureDock(width, tileSize) {
	const pitch = tileSize * 1.25
	const keep = new Set(dockPriority.slice(0, Math.max(6, Math.floor(width * 0.92 / pitch) - 3)))
	const apps = dockApps.filter(([bundle]) => keep.has(bundle)).map(([bundle, path]) => [bundle, 'file://' + encodeURI(path) + '/'])
	const current = (preferences('com.apple.dock')['persistent-apps'] || []).map(tile => [tile['tile-data']['bundle-identifier'], tile['tile-data']['file-data']._CFURLString])
	const changed = setPreferences('com.apple.dock', {
		autohide: false,
		magnification: false,
		orientation: 'bottom',
		tilesize: tileSize,
		'wvous-tl-corner': 1,
		'wvous-tr-corner': 1,
		'wvous-bl-corner': 1,
		'wvous-br-corner': 1,
		'show-recents': false,
		'show-process-indicators': true,
		'persistent-others': [],
		'recent-apps': [],
	})
	if (JSON.stringify(current) !== JSON.stringify(apps)) {
		setPreferences('com.apple.dock', {
			'persistent-apps': apps.map(([bundle, url]) => ({
				'tile-data': {
					'bundle-identifier': bundle,
					'file-data': {_CFURLString: url, _CFURLStringType: 15},
					'file-label': decodeURI(url).split('/').at(-2).replace(/\.app$/, ''),
				},
				'tile-type': 'file-tile',
			})),
		})
	} else if (!changed) {
		return
	}
	sh('killall Dock')
}

function windowsOf(name) {
	return ObjC.deepUnwrap(ObjC.castRefToObject($.CGWindowListCopyWindowInfo($.kCGWindowListOptionOnScreenOnly | $.kCGWindowListExcludeDesktopElements, 0)))
		.filter(window => window.kCGWindowOwnerName === name && window.kCGWindowLayer === 0 && window.kCGWindowBounds.Height > 200)
		.map(window => ({pid: window.kCGWindowOwnerPID, window_id: window.kCGWindowNumber}))
}

function closeFinderWindows() {
	for (let window = windowsOf('Finder')[0]; window; window = windowsOf('Finder')[0]) {
		cua('invoke_menu', {pid: window.pid, window_id: window.window_id, path: ['File', 'Close Window']})
		waitFor('Finder window to close', 5, () => !windowsOf('Finder').some(other => other.window_id === window.window_id))
	}
}

const minimumCalendar = {width: 680, height: 503}

function layout(screen, menuBar, dock) {
	const free = screen.height - menuBar - dock
	const width = Math.max(minimumCalendar.width, screen.width * 0.64)
	const height = Math.max(minimumCalendar.height, free * 0.8)
	return {
		x: Math.round((screen.width - width) / 2),
		y: Math.round(menuBar + Math.max(0, (free - height) / 2)),
		width: Math.round(width),
		height: Math.round(height),
	}
}

function run(argv) {
	if ((argv[0] !== 'light' && argv[0] !== 'dark') || !argv[1]) throw new Error('usage: desktop.js light|dark <picture>')
	const serving = () => sh(cuaDriver + ' status >/dev/null 2>&1 && echo yes; true').trim()
	if (!serving()) {
		sh('open -n -g -a CuaDriver --args serve')
		waitFor('cua-driver', 20, serving)
	}
	const mode = $.CGDisplayCopyDisplayMode($.CGMainDisplayID())
	const screen = {width: Number($.CGDisplayModeGetWidth(mode)), height: Number($.CGDisplayModeGetHeight(mode)), scale_factor: Number($.CGDisplayModeGetPixelWidth(mode)) / Number($.CGDisplayModeGetWidth(mode))}
	ObjC.bindFunction('SLSSetAppearanceThemeLegacy', ['void', ['bool']])
	$.SLSSetAppearanceThemeLegacy(argv[0] === 'dark')
	const wallpaper = $.NSURL.fileURLWithPath('/System/Library/Desktop Pictures/Mac Purple.heic')
	if (!$.NSWorkspace.sharedWorkspace.desktopImageURLForScreen($.NSScreen.mainScreen).isEqual(wallpaper)) $.NSWorkspace.sharedWorkspace.setDesktopImageURLForScreenOptionsError(wallpaper, $.NSScreen.mainScreen, $({}), null)
	const main = $.NSScreen.mainScreen
	const menuBar = main.frame.size.height - main.visibleFrame.origin.y - main.visibleFrame.size.height
	const tileSize = screen.height < 700 ? 40 : 48
	const calendar = layout(screen, menuBar, tileSize + 26)
	const calendarView = calendar.width >= calendar.height * 1.25 ? 'Yearly' : 'Monthly'
	const system = setPreferences('NSGlobalDomain', {AppleLocale: 'en_US', AppleLanguages: ['en-US'], AppleShowScrollBars: 'WhenScrolling', AppleKeyboardUIMode: 0})
	const clock = setPreferences('com.apple.menuextra.clock', {IsAnalog: true, ShowDate: 2, ShowDayOfWeek: false, ShowSeconds: false})
	quitApplications(!system && preferences('com.apple.iCal')['last calendar view description'] === calendarView ? ['com.apple.iCal'] : [])
	if (system || clock) sh('killall ControlCenter')
	configureDock(screen.width, tileSize)
	if (setPreferences('com.apple.finder', {CreateDesktop: false})) sh('killall Finder')
	const pointer = $.NSEvent.mouseLocation
	if (pointer.x < screen.width - 1 || pointer.y > 1) cua('move_cursor', {scope: 'desktop', x: screen.width * screen.scale_factor - 1, y: screen.height * screen.scale_factor - 1})
	waitFor('Finder', 10, () => sh('pgrep -x Finder || true').trim())
	closeFinderWindows()
	setPreferences('com.apple.iCal', {CalendarSidebarShown: false, 'last calendar view description': calendarView})
	sh('open -a Calendar')
	cua('set_window_frame', Object.assign(waitFor('Calendar window', 20, () => windowsOf('Calendar')[0]), calendar))
	let last
	waitFor('a still desktop', 90, () => {
		// macOS 27.0.1 (26A434) shows the screen capture indicator in the menu bar for about 3 s after a capture.
		sleep(4)
		cua('get_desktop_state', {screenshot_out_file: argv[1]})
		const picture = sh('shasum ' + quote(argv[1]))
		const still = picture === last
		last = picture
		return still
	})
}
