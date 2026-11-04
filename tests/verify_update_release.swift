import AppKit

@main
struct ReleaseIntegrationTests {
    static func main() throws {
        _ = NSApplication.shared
        let old = Bundle(path: CommandLine.arguments[1])!
        let candidate = Bundle(path: CommandLine.arguments[2])!
        let oldVersion = old.object(forInfoDictionaryKey: "CFBundleShortVersionString") as! String
        let version = candidate.object(forInfoDictionaryKey: "CFBundleShortVersionString") as! String
        precondition(oldVersion == "1.6.0" && version == "1.6.1")
        precondition(ReleaseUpdate.isNewer(version, than: oldVersion))
        print("Downloaded old bundle and candidate versions verified: \(oldVersion) -> \(version)")
        let suite = "DocumentStudio.ReleaseIntegration.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var liveDone = false
        var liveResult: Result<ReleaseUpdate, Error>?
        ReleaseUpdate.fetch { result in liveResult = result; liveDone = true }
        let deadline = Date().addingTimeInterval(20)
        while !liveDone && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.05)) }
        precondition(liveDone, "Live GitHub request timed out")
        let live = try liveResult!.get()
        precondition(!ReleaseUpdate.isNewer(live.version, than: version), "Unexpected newer public release")
        print("Actual URLSession GitHub query passed: \(live.version)")
        var requests = 0
        var opened: URL?
        let futureURL = URL(string: "https://github.com/HyeonWooNa0861/DocumentStudio/releases/tag/v1.6.2")!
        let controller = UpdateController(defaults: defaults, currentVersion: version, openURL: { opened = $0 }) { callback in
            requests += 1
            callback(.success(ReleaseUpdate(version: "v1.6.2", notes: "Synthetic future release: notification verification", url: futureURL)))
        }
        let menu = NSMenu(); controller.install(in: menu); controller.start()
        precondition(requests == 1, "Automatic first check did not run")
        let panel = NSApp.windows.first { $0.title == "업데이트 확인" }!
        precondition(NSApp.modalWindow == nil && panel.styleMask.contains(.nonactivatingPanel))
        func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap(descendants) }
        panel.contentView!.layoutSubtreeIfNeeded()
        let views = descendants(panel.contentView!)
        let text = views.compactMap { $0 as? NSTextView }.first!
        precondition(text.string.contains("Synthetic future release") && text.frame.width > 100 && text.frame.height > 0)
        let button = views.compactMap { $0 as? NSButton }.first { $0.title == "변경 내용 및 다운로드" }!
        button.performClick(nil)
        precondition(opened == futureURL, "Download action routed to wrong URL")
        panel.close()
        let next = UpdateController(defaults: defaults, currentVersion: version, fetch: { _ in requests += 1 })
        next.install(in: NSMenu()); next.start()
        precondition(requests == 1, "Recreated controller ignored persisted check time")
        defaults.set(Date().addingTimeInterval(-86401), forKey: "updateCheckLastAttempt")
        let retry = UpdateController(defaults: defaults, currentVersion: version, fetch: { callback in
            requests += 1; callback(.failure(URLError(.notConnectedToInternet)))
        })
        retry.install(in: NSMenu()); retry.start()
        precondition(requests == 2)
        precondition(!NSApp.windows.contains { $0.title == "업데이트 확인" && $0.isVisible }, "Automatic failure interrupted user")
        print("Synthetic future release: automatic nonmodal alert, readable notes, exact download URL, persistent throttle and silent offline failure PASS")
    }
}
