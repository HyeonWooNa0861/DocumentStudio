import AppKit

@main
struct ControllerTests {
    static func main() {
        _ = NSApplication.shared
        let suite = "DocumentStudio.UpdateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var requests = 0
        var copied: String?
        var copySucceeds = true
        var pending: ((Result<ReleaseUpdate, Error>) -> Void)?
        let controller = UpdateController(defaults: defaults, currentVersion: "1.6.1", copyText: {
            copied = $0
            return copySucceeds
        }, fetch: { completion in
            requests += 1
            pending = completion
        })
        let menu = NSMenu()
        controller.install(in: menu)
        precondition(menu.items.map { $0.title } == ["업데이트 확인…", "새 버전 자동 확인"])
        menu.performActionForItem(at: 1)
        controller.start()
        precondition(requests == 0, "Disabled automatic checks must not fetch")
        menu.performActionForItem(at: 0)
        precondition(requests == 1, "Manual checks bypass opt-out")
        menu.performActionForItem(at: 0)
        precondition(requests == 1, "Requests must coalesce")
        // Enabling during an in-flight manual request does not duplicate it.
        menu.performActionForItem(at: 1)
        precondition(requests == 1)
        pending?(.success(ReleaseUpdate(version: "v1.6.0", notes: "", url: URL(string: "https://github.com/HyeonWooNa0861/DocumentStudio/releases/tag/v1.6.0")!)))
        precondition(menu.items[0].title == "업데이트 확인…")
        precondition(NSApp.windows.contains { $0.title == "업데이트 확인" }, "Manual results must be visible")
        func descendants(_ view: NSView) -> [NSView] { [view] + view.subviews.flatMap(descendants) }
        func buttons(_ panel: NSWindow) -> [NSButton] {
            descendants(panel.contentView!).compactMap { $0 as? NSButton }
        }
        precondition(!NSApp.windows.filter { $0.title == "업데이트 확인" }.flatMap(buttons).contains { $0.title == "설치 명령어 복사" }, "Current-version panels must not offer installation")
        NSApp.windows.filter { $0.title == "업데이트 확인" }.forEach { $0.close() }
        menu.performActionForItem(at: 1)
        menu.performActionForItem(at: 1)
        precondition(requests == 1, "Recent attempt must throttle automatic checks")
        menu.performActionForItem(at: 0)
        precondition(requests == 2, "Manual recheck bypasses daily throttle")
        pending?(.failure(URLError(.notConnectedToInternet)))
        precondition(menu.items[0].title == "업데이트 확인…")
        NSApp.windows.filter { $0.title == "업데이트 확인" }.forEach { $0.close() }
        menu.performActionForItem(at: 0)
        pending?(.success(ReleaseUpdate(version: "v1.6.2", notes: "Test release", url: URL(string: "https://github.com/HyeonWooNa0861/DocumentStudio/releases/tag/v1.6.2")!)))
        let panel = NSApp.windows.first { $0.title == "업데이트 확인" && $0.isVisible }!
        let copy = buttons(panel).first { $0.title == "설치 명령어 복사" }!
        copy.performClick(nil)
        precondition(copied == UpdateController.installCommand && copy.title == "복사 완료")
        copySucceeds = false
        copy.performClick(nil)
        precondition(copy.title == "다시 복사하기", "Clipboard failure must not claim success")
        copySucceeds = true
        copy.performClick(nil)
        precondition(copy.title == "복사 완료", "Retry must remain available")
        try! UpdateController.installCommand.write(toFile: CommandLine.arguments[1], atomically: true, encoding: .utf8)
        panel.close()
        print("Controller tests passed: menus, opt-out, manual checks, coalescing, throttle, panels, copy success/failure/retry (injected clipboard)")
    }
}
