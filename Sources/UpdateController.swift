import AppKit

final class UpdateController: NSObject {
    private let defaults: UserDefaults
    private let currentVersion: String?
    private let fetch: (@escaping (Result<ReleaseUpdate, Error>) -> Void) -> Void
    private let openURL: (URL) -> Void
    private let copyText: (String) -> Bool
    static let installCommand = """
    (
      set -e
      if [ -x "$HOME/.local/bin/document-studio" ]; then
        "$HOME/.local/bin/document-studio" update
      else
        install_dir="$(mktemp -d)"
        trap 'rm -f "$install_dir/install-macos.sh"; rmdir "$install_dir"' EXIT
        curl --fail --location --proto '=https' --retry 2 'https://github.com/HyeonWooNa0861/DocumentStudio/releases/latest/download/install-macos.sh' -o "$install_dir/install-macos.sh"
        bash "$install_dir/install-macos.sh" --repo HyeonWooNa0861/DocumentStudio
      fi
    )
    """
    private var checking = false
    private var timer: Timer?
    private var panel: NSPanel?
    private var releaseURL: URL?
    private var manualItem: NSMenuItem?
    private var automaticItem: NSMenuItem?
    private let lastKey = "updateCheckLastAttempt"
    private let enabledKey = "automaticallyCheckForUpdates"

    init(defaults: UserDefaults = .standard,
         currentVersion: String? = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
         copyText: @escaping (String) -> Bool = {
             NSPasteboard.general.clearContents()
             return NSPasteboard.general.setString($0, forType: .string)
         },
         openURL: @escaping (URL) -> Void = { NSWorkspace.shared.open($0) },
         fetch: @escaping (@escaping (Result<ReleaseUpdate, Error>) -> Void) -> Void = ReleaseUpdate.fetch) {
        self.defaults = defaults
        self.currentVersion = currentVersion
        self.fetch = fetch
        self.openURL = openURL
        self.copyText = copyText
        super.init()
    }

    deinit { timer?.invalidate() }

    func install(in menu: NSMenu) {
        defaults.register(defaults: [enabledKey: true])
        let manual = NSMenuItem(title: "업데이트 확인…", action: #selector(checkManually), keyEquivalent: "")
        manual.target = self
        menu.addItem(manual)
        manualItem = manual
        let automatic = NSMenuItem(title: "새 버전 자동 확인", action: #selector(toggleAutomatic), keyEquivalent: "")
        automatic.target = self
        automatic.state = defaults.bool(forKey: enabledKey) ? .on : .off
        menu.addItem(automatic)
        automaticItem = automatic
    }

    func start() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in self?.check(manual: false) }
        check(manual: false)
    }

    @objc private func toggleAutomatic() {
        let enabled = !defaults.bool(forKey: enabledKey)
        defaults.set(enabled, forKey: enabledKey)
        automaticItem?.state = enabled ? .on : .off
        if enabled { check(manual: false) }
    }

    @objc private func checkManually() { check(manual: true) }

    private func check(manual: Bool) {
        guard !checking else { return }
        guard manual || (defaults.bool(forKey: enabledKey) && ReleaseUpdate.shouldCheck(last: defaults.object(forKey: lastKey) as? Date)) else { return }
        guard let current = currentVersion,
              ReleaseUpdate.components(current) != nil else { return }
        checking = true
        manualItem?.title = "업데이트 확인 중…"
        defaults.set(Date(), forKey: lastKey)
        fetch { [weak self] result in
            guard let self = self else { return }
            self.checking = false
            self.manualItem?.title = "업데이트 확인…"
            if !manual && !self.defaults.bool(forKey: self.enabledKey) { return }
            switch result {
            case .success(let update):
                if ReleaseUpdate.isNewer(update.version, than: current) {
                    self.show(title: "Document Studio \(update.version) 업데이트", message: "현재 버전: \(current)\n\n\(update.notes)", url: update.url)
                } else if manual {
                    self.show(title: "최신 버전을 사용하고 있습니다.", message: "현재 버전: \(current)", url: nil)
                }
            case .failure:
                if manual { self.show(title: "업데이트를 확인하지 못했습니다.", message: "인터넷 연결을 확인하고 잠시 후 다시 시도하세요. 문서 작업은 계속할 수 있습니다.", url: nil) }
            }
        }
    }

    private func show(title: String, message: String, url: URL?) {
        panel?.close()
        releaseURL = url
        let view = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 600, height: 420),
                           styleMask: [.titled, .closable, .nonactivatingPanel], backing: .buffered, defer: false)
        view.title = "업데이트 확인"
        view.isReleasedWhenClosed = false
        let heading = NSTextField(wrappingLabelWithString: title)
        heading.font = .boldSystemFont(ofSize: 17)
        let text = NSTextView(frame: NSRect(x: 0, y: 0, width: 480, height: 200))
        text.isVerticallyResizable = true
        text.maxSize = NSSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)
        text.isEditable = false
        text.isSelectable = true
        text.font = .systemFont(ofSize: 13)
        text.string = message
        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.documentView = text
        text.autoresizingMask = [.width]
        text.textContainer?.widthTracksTextView = true
        let close = NSButton(title: "닫기", target: self, action: #selector(closePanel))
        let buttons = NSStackView(views: [close])
        if url != nil {
            buttons.addArrangedSubview(NSButton(title: "설치 명령어 복사", target: self, action: #selector(copyInstallCommand(_:))))
            buttons.addArrangedSubview(NSButton(title: "변경 내용 및 다운로드", target: self, action: #selector(openRelease)))
        }
        let hint = NSTextField(wrappingLabelWithString: url == nil ? "" : "문서를 저장하고 앱을 종료한 뒤, 복사한 명령어를 터미널에 붙여넣어 실행하세요.")
        hint.font = .systemFont(ofSize: 12)
        hint.textColor = .secondaryLabelColor
        let stack = NSStackView(views: [heading, scroll, hint, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.contentView!.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.contentView!.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.contentView!.trailingAnchor, constant: -20),
            stack.topAnchor.constraint(equalTo: view.contentView!.topAnchor, constant: 20),
            stack.bottomAnchor.constraint(equalTo: view.contentView!.bottomAnchor, constant: -20),
            heading.widthAnchor.constraint(equalTo: stack.widthAnchor),
            hint.widthAnchor.constraint(equalTo: stack.widthAnchor),
            scroll.widthAnchor.constraint(equalTo: stack.widthAnchor),
            scroll.heightAnchor.constraint(greaterThanOrEqualToConstant: 180)
        ])
        panel = view
        view.center()
        view.orderFront(nil)
    }

    @objc private func openRelease() { if let url = releaseURL { openURL(url) } }
    @objc private func copyInstallCommand(_ sender: NSButton) {
        sender.title = copyText(Self.installCommand) ? "복사 완료" : "다시 복사하기"
    }
    @objc private func closePanel() { panel?.close() }
}
