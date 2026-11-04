import AppKit

@main
struct ControllerTests {
    static func main() {
        _ = NSApplication.shared
        let suite = "DocumentStudio.UpdateTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var requests = 0
        var pending: ((Result<ReleaseUpdate, Error>) -> Void)?
        let controller = UpdateController(defaults: defaults, currentVersion: "1.6.1", fetch: { completion in
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
        NSApp.windows.filter { $0.title == "업데이트 확인" }.forEach { $0.close() }
        menu.performActionForItem(at: 1)
        menu.performActionForItem(at: 1)
        precondition(requests == 1, "Recent attempt must throttle automatic checks")
        menu.performActionForItem(at: 0)
        precondition(requests == 2, "Manual recheck bypasses daily throttle")
        pending?(.failure(URLError(.notConnectedToInternet)))
        precondition(menu.items[0].title == "업데이트 확인…")
        NSApp.windows.filter { $0.title == "업데이트 확인" }.forEach { $0.close() }
        print("Controller tests passed: menus, opt-out, manual checks, coalescing, throttle, result and error panels")
    }
}
