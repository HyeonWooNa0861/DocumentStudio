import AppKit

let app = NSApplication.shared
let delegate = StudioAppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
