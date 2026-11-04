import AppKit
import UniformTypeIdentifiers

private enum ConversionFormat: String {
    case hwpx, hwp
    var title: String { rawValue.uppercased() }
}

private struct DocumentRow {
    let input: URL
    var output: URL?
    var status: String
    var error: String?
    var warnings: [String] = []
    var hwpOutput: URL?
    var document: URL? { output ?? (input.pathExtension.lowercased() == "hwpx" ? input : nil) }
    var latestOutput: URL? { hwpOutput ?? output }
    func conversionSource(for format: ConversionFormat) -> URL? {
        switch format {
        case .hwpx: return ["hwp", "docx"].contains(input.pathExtension.lowercased()) && output == nil ? input : nil
        case .hwp: return hwpOutput == nil ? document : nil
        }
    }
}

final class StudioAppDelegate: NSObject, NSApplicationDelegate, NSWindowDelegate, NSOpenSavePanelDelegate, NSTableViewDataSource, NSTableViewDelegate {
    private var window: NSWindow!
    private let engine = EngineClient()
    private let updater = UpdateController()
    private var rows: [DocumentRow] = []
    private var editors: [StudioEditorSession] = []
    private let table = NSTableView()
    private let status = NSTextField(wrappingLabelWithString: "HWP, DOCX 또는 HWPX 파일을 선택해 시작하세요.")
    private let folderLabel = NSTextField(labelWithString: "변환한 파일은 원본과 같은 폴더에 별도로 저장합니다.")
    private let convertButton = NSButton(title: "HWPX로 변환", target: nil, action: nil)
    private let outputFormat = NSPopUpButton(frame: .zero, pullsDown: false)
    private var conversionFormat: ConversionFormat { outputFormat.indexOfSelectedItem == 1 ? .hwp : .hwpx }
    private let editButton = NSButton(title: "문서 편집", target: nil, action: nil)
    private let blockEditButton = NSButton(title: "목록식 편집", target: nil, action: nil)
    private let warningsButton = NSButton(title: "변환 안내", target: nil, action: nil)
    private let revealButton = NSButton(title: "Finder에서 보기", target: nil, action: nil)
    private let removeButton = NSButton(title: "목록에서 제거", target: nil, action: nil)
    private let progress = NSProgressIndicator()
    private var outputFolder: URL?
    private var busy = false

    func applicationDidFinishLaunching(_ notification: Notification) {
        makeMenus()
        makeWindow()
        if let path = UserDefaults.standard.string(forKey: "outputFolder"),
           FileManager.default.fileExists(atPath: path) { outputFolder = URL(fileURLWithPath: path) }
        updateFolderLabel()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        updater.start()
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: nil, queue: .main) { [weak self] notification in
            guard let closing = notification.object as? NSWindow else { return }
            self?.editors.removeAll { $0.window === closing }
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if busy || editors.contains(where: { $0.hasActiveOperation }) {
            let alert = NSAlert()
            alert.messageText = "문서 작업이 진행 중입니다."
            alert.informativeText = "문서 읽기·변환·저장·PDF 준비가 끝난 뒤 앱을 종료해 주세요."
            alert.addButton(withTitle: "계속 기다리기")
            alert.runModal()
            return .terminateCancel
        }
        let openEditors = editors
        guard !openEditors.isEmpty else { return .terminateNow }
        DispatchQueue.main.async { self.confirmQuit(openEditors, index: 0, application: sender) }
        return .terminateLater
    }

    private func confirmQuit(_ controllers: [StudioEditorSession], index: Int, application: NSApplication) {
        guard controllers.indices.contains(index) else { application.reply(toApplicationShouldTerminate: true); return }
        controllers[index].confirmBeforeClosing { [weak self] allowed in
            guard allowed else { application.reply(toApplicationShouldTerminate: false); return }
            self?.confirmQuit(controllers, index: index + 1, application: application)
        }
    }

    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        if window == nil { makeMenus(); makeWindow(); window.makeKeyAndOrderFront(nil) }
        addFiles(filenames.map { URL(fileURLWithPath: $0) })
        sender.reply(toOpenOrPrint: .success)
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        makeWindow()
        window.makeKeyAndOrderFront(nil)
        return true
    }

    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard busy else { return true }
        status.stringValue = "문서 변환이 끝난 뒤 창을 닫을 수 있습니다."
        return false
    }

    private func makeMenus() {
        let bar = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Document Studio에 관하여", action: #selector(showAbout), keyEquivalent: "")
        updater.install(in: appMenu)
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Document Studio 종료", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        bar.addItem(appItem)
        let fileItem = NSMenuItem(title: "파일", action: nil, keyEquivalent: "")
        let fileMenu = NSMenu(title: "파일")
        let choose = NSMenuItem(title: "문서 추가…", action: #selector(chooseFiles), keyEquivalent: "o")
        choose.identifier = NSUserInterfaceItemIdentifier("add-documents-menu")
        choose.target = self
        fileMenu.addItem(choose)
        fileMenu.addItem(withTitle: "윈도우 닫기", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        fileItem.submenu = fileMenu
        bar.addItem(fileItem)
        let editItem = NSMenuItem(title: "편집", action: nil, keyEquivalent: "")
        let editMenu = NSMenu(title: "편집")
        for (title, action, key) in [("실행 취소", Selector(("undo:")), "z"), ("잘라내기", #selector(NSText.cut(_:)), "x"),
                                     ("복사", #selector(NSText.copy(_:)), "c"), ("붙여넣기", #selector(NSText.paste(_:)), "v"),
                                     ("전체 선택", #selector(NSText.selectAll(_:)), "a")] {
            editMenu.addItem(withTitle: title, action: action, keyEquivalent: key)
        }
        editItem.submenu = editMenu
        bar.addItem(editItem)
        NSApp.mainMenu = bar
    }

    private func makeWindow() {
        guard window == nil else { return }
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1020, height: 700),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Document Studio"
        window.delegate = self
        window.minSize = NSSize(width: 840, height: 570)
        window.setFrameAutosaveName("HWPXStudioMain")
        window.center()
        let root = NSView()
        window.contentView = root
        let title = NSTextField(labelWithString: "문서, 손쉽게 변환하고 편집하세요.")
        title.font = .systemFont(ofSize: 27, weight: .bold)
        let subtitle = NSTextField(labelWithString: "HWP·HWPX 형식 변환 · DOCX 가져오기 · 문서 편집 · PDF 저장")
        subtitle.font = .systemFont(ofSize: 13)
        subtitle.textColor = .secondaryLabelColor
        let chooseButton = NSButton(title: "HWP·DOCX·HWPX 추가…", target: self, action: #selector(chooseFiles))
        chooseButton.setAccessibilityIdentifier("add-documents")
        chooseButton.setAccessibilityLabel(chooseButton.title)
        chooseButton.setAccessibilityHelp("HWP, DOCX 또는 HWPX 문서를 목록에 추가합니다. 단축키는 Command-O입니다.")
        chooseButton.toolTip = "HWP, DOCX 또는 HWPX 문서 추가 (⌘O)"
        chooseButton.bezelStyle = .rounded
        chooseButton.image = NSImage(systemSymbolName: "doc.badge.plus", accessibilityDescription: nil)
        chooseButton.imagePosition = .imageLeading
        // AppKit exposes the button cell in the accessibility hierarchy.
        chooseButton.cell?.setAccessibilityIdentifier("add-documents")
        chooseButton.cell?.setAccessibilityLabel(chooseButton.title)
        chooseButton.cell?.setAccessibilityHelp(chooseButton.accessibilityHelp())
        let folderButton = NSButton(title: "저장 폴더 선택…", target: self, action: #selector(chooseFolder))
        folderButton.bezelStyle = .rounded
        let resetFolder = NSButton(title: "원본 폴더에 저장", target: self, action: #selector(resetFolder))
        resetFolder.bezelStyle = .rounded
        let controls = NSStackView(views: [chooseButton, folderButton, resetFolder])
        controls.spacing = 10
        controls.alignment = .centerY
        folderLabel.font = .systemFont(ofSize: 12)
        folderLabel.textColor = .secondaryLabelColor
        folderLabel.lineBreakMode = .byTruncatingMiddle

        let scroll = NSScrollView()
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        scroll.documentView = table
        table.dataSource = self
        table.delegate = self
        table.allowsMultipleSelection = true
        table.rowHeight = 58
        table.usesAlternatingRowBackgroundColors = true
        table.columnAutoresizingStyle = .lastColumnOnlyAutoresizingStyle
        let fileColumn = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("file"))
        fileColumn.title = "문서"
        fileColumn.width = 610
        fileColumn.minWidth = 420
        table.addTableColumn(fileColumn)
        let stateColumn = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("state"))
        stateColumn.title = "상태"
        stateColumn.width = 200
        table.addTableColumn(stateColumn)
        table.target = self
        table.doubleAction = #selector(editSelected)
        table.setAccessibilityLabel("선택한 문서 목록")

        convertButton.target = self; convertButton.action = #selector(convertFiles)
        editButton.target = self; editButton.action = #selector(editSelected)
        blockEditButton.target = self; blockEditButton.action = #selector(editSelectedAsList)
        warningsButton.target = self; warningsButton.action = #selector(showConversionWarnings)
        revealButton.target = self; revealButton.action = #selector(revealSelected)
        removeButton.target = self; removeButton.action = #selector(removeSelected)
        convertButton.image = NSImage(systemSymbolName: "arrow.right.doc.on.clipboard", accessibilityDescription: nil)
        convertButton.imagePosition = .imageLeading
        editButton.image = NSImage(systemSymbolName: "square.and.pencil", accessibilityDescription: nil)
        editButton.imagePosition = .imageLeading
        blockEditButton.image = NSImage(systemSymbolName: "list.bullet.rectangle", accessibilityDescription: nil)
        blockEditButton.imagePosition = .imageLeading
        warningsButton.image = NSImage(systemSymbolName: "exclamationmark.triangle", accessibilityDescription: nil)
        warningsButton.imagePosition = .imageLeading
        for (button, identifier) in [(convertButton, "convert-documents"), (editButton, "edit-document"),
                                     (blockEditButton, "edit-document-as-list"), (warningsButton, "conversion-warnings"),
                                     (revealButton, "reveal-document"), (removeButton, "remove-document")] {
            button.bezelStyle = .rounded
            button.setAccessibilityIdentifier(identifier)
            button.setAccessibilityLabel(button.title)
            button.cell?.setAccessibilityIdentifier(identifier)
            button.cell?.setAccessibilityLabel(button.title)
        }
        let actions = NSStackView(views: [convertButton, editButton, blockEditButton, warningsButton, revealButton, removeButton])
        actions.spacing = 10
        actions.alignment = .centerY
        outputFormat.addItems(withTitles: ["HWPX (.hwpx)", "HWP (.hwp)"])
        outputFormat.target = self
        outputFormat.action = #selector(changeOutputFormat)
        outputFormat.setAccessibilityLabel("출력 형식")
        outputFormat.setAccessibilityIdentifier("conversion-output-format")
        outputFormat.cell?.setAccessibilityLabel("출력 형식")
        outputFormat.cell?.setAccessibilityIdentifier("conversion-output-format")
        let formatLabel = NSTextField(labelWithString: "출력 형식")
        let formatHint = NSTextField(labelWithString: "HWP 출력은 HWPX 문서에서 변환합니다.")
        formatHint.font = .systemFont(ofSize: 12)
        formatHint.textColor = .secondaryLabelColor
        let formatControls = NSStackView(views: [formatLabel, outputFormat, formatHint])
        formatControls.alignment = .centerY
        formatControls.spacing = 10
        status.font = .systemFont(ofSize: 13)
        status.textColor = .secondaryLabelColor
        status.maximumNumberOfLines = 3
        progress.style = .spinning
        progress.controlSize = .small
        progress.isDisplayedWhenStopped = false

        for view in [title, subtitle, controls, folderLabel, scroll, formatControls, actions, status, progress] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(view)
        }
        NSLayoutConstraint.activate([
            title.topAnchor.constraint(equalTo: root.topAnchor, constant: 30), title.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 28),
            title.trailingAnchor.constraint(lessThanOrEqualTo: root.trailingAnchor, constant: -28),
            subtitle.topAnchor.constraint(equalTo: title.bottomAnchor, constant: 8), subtitle.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            controls.topAnchor.constraint(equalTo: subtitle.bottomAnchor, constant: 24), controls.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            folderLabel.topAnchor.constraint(equalTo: controls.bottomAnchor, constant: 10), folderLabel.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            folderLabel.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -28),
            scroll.topAnchor.constraint(equalTo: folderLabel.bottomAnchor, constant: 18), scroll.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            scroll.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -28),
            formatControls.topAnchor.constraint(equalTo: scroll.bottomAnchor, constant: 14), formatControls.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            formatControls.trailingAnchor.constraint(lessThanOrEqualTo: root.trailingAnchor, constant: -28),
            actions.topAnchor.constraint(equalTo: formatControls.bottomAnchor, constant: 10), actions.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            actions.trailingAnchor.constraint(lessThanOrEqualTo: root.trailingAnchor, constant: -28),
            status.topAnchor.constraint(equalTo: actions.bottomAnchor, constant: 12), status.leadingAnchor.constraint(equalTo: title.leadingAnchor),
            status.trailingAnchor.constraint(equalTo: progress.leadingAnchor, constant: -12), status.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -24),
            status.heightAnchor.constraint(greaterThanOrEqualToConstant: 40), progress.centerYAnchor.constraint(equalTo: status.centerYAnchor),
            progress.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -30), progress.widthAnchor.constraint(equalToConstant: 18)
        ])
        updateButtons()
    }

    @objc private func showAbout() {
        NSApp.orderFrontStandardAboutPanel(options: [.applicationName: "Document Studio", .applicationVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "",
            .credits: NSAttributedString(string: "HWP와 HWPX를 변환하고 DOCX를 가져와 문서 내용을 편집합니다.\n모든 처리는 이 Mac에서 이루어집니다.\n복잡한 서식과 쪽 배치는 원본과 다를 수 있습니다. DOC 형식은 지원하지 않습니다.\nhwp2hwpx · hwplib · hwpxlib (Apache-2.0)\n본 제품은 한글과컴퓨터의 HWP 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.")])
    }

    @objc private func chooseFiles() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        if let sheet = window.attachedSheet {
            sheet.makeKeyAndOrderFront(nil)
            return
        }
        let panel = NSOpenPanel()
        panel.identifier = NSUserInterfaceItemIdentifier("add-documents-panel")
        panel.title = "변환하거나 수정할 문서 선택"
        panel.message = "HWP, DOCX 또는 HWPX 파일을 선택한 뒤 ‘추가’를 누르세요."
        panel.prompt = "추가"
        panel.canChooseFiles = true
        panel.canChooseDirectories = false
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = []
        panel.treatsFilePackagesAsDirectories = false
        // Validate extensions after selection, independent of other apps' type registrations.
        panel.delegate = self
        panel.beginSheetModal(for: window) { [weak self] response in
            if response == .OK { self?.addFiles(panel.urls) }
        }
    }

    func panel(_ sender: Any, validate url: URL) throws {
        guard ["hwp", "hwpx", "docx"].contains(url.pathExtension.lowercased()) else {
            throw StudioError.message("HWP, DOCX 또는 HWPX 문서를 선택해 주세요. DOC 형식은 아직 지원하지 않습니다.")
        }
    }

    private func addFiles(_ urls: [URL]) {
        var unsupportedCount = 0
        for url in urls {
            guard ["hwp", "hwpx", "docx"].contains(url.pathExtension.lowercased()) else { unsupportedCount += 1; continue }
            if !rows.contains(where: { $0.input.standardizedFileURL == url.standardizedFileURL }) {
                rows.append(DocumentRow(input: url, output: nil, status: url.pathExtension.lowercased() == "hwpx" ? "편집 가능" : "변환 대기", error: nil))
            }
        }
        table.reloadData()
        if !rows.isEmpty { table.selectRowIndexes(IndexSet(integer: rows.count - 1), byExtendingSelection: false) }
        status.stringValue = "\(rows.count)개 문서를 추가했습니다. HWP·DOCX는 변환 후 내용을 수정할 수 있습니다."
        if unsupportedCount > 0 {
            status.stringValue += " 지원하지 않는 파일 \(unsupportedCount)개는 제외했습니다. DOC 형식은 아직 지원하지 않습니다."
        }
        updateButtons()
    }

    @objc private func chooseFolder() {
        guard !busy else { return }
        let panel = NSOpenPanel()
        panel.title = "변환한 문서를 저장할 폴더"
        panel.canChooseDirectories = true; panel.canChooseFiles = false; panel.canCreateDirectories = true
        panel.prompt = "선택"
        panel.directoryURL = outputFolder
        panel.beginSheetModal(for: window) { [weak self] response in
            if response == .OK, let url = panel.url {
                self?.outputFolder = url
                UserDefaults.standard.set(url.path, forKey: "outputFolder")
                self?.updateFolderLabel()
            }
        }
    }

    @objc private func resetFolder() {
        guard !busy else { return }
        outputFolder = nil
        UserDefaults.standard.removeObject(forKey: "outputFolder")
        updateFolderLabel()
    }

    private func updateFolderLabel() {
        folderLabel.stringValue = outputFolder.map { "저장 폴더: \($0.path)" } ?? "변환한 파일은 원본과 같은 폴더에 별도로 저장합니다."
        folderLabel.toolTip = outputFolder?.path
    }

    func numberOfRows(in tableView: NSTableView) -> Int { rows.count }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard rows.indices.contains(row) else { return nil }
        let item = rows[row]
        if tableColumn?.identifier.rawValue == "state" {
            let label = NSTextField(labelWithString: item.status)
            label.font = .systemFont(ofSize: 13, weight: .medium)
            label.textColor = item.error != nil ? .systemRed : (item.warnings.isEmpty ? .secondaryLabelColor : .systemOrange)
            label.toolTip = item.error ?? (item.warnings.isEmpty ? nil : item.warnings.joined(separator: "\n"))
            return label
        }
        let name = NSTextField(labelWithString: item.input.lastPathComponent)
        name.font = .systemFont(ofSize: 14, weight: .medium)
        name.lineBreakMode = .byTruncatingMiddle
        let location = NSTextField(labelWithString: item.latestOutput?.path ?? item.document?.path ?? item.input.deletingLastPathComponent().path)
        location.font = .systemFont(ofSize: 11)
        location.textColor = .secondaryLabelColor
        location.lineBreakMode = .byTruncatingMiddle
        let stack = NSStackView(views: [name, location])
        stack.orientation = .vertical; stack.alignment = .leading; stack.spacing = 3
        stack.toolTip = item.input.path
        return stack
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        updateButtons()
        if rows.indices.contains(table.selectedRow) {
            let row = rows[table.selectedRow]
            if let message = row.error { status.stringValue = message }
            else if !row.warnings.isEmpty {
                status.stringValue = "변환 안내 \(row.warnings.count)건: \(row.warnings[0]) ‘변환 안내’에서 전체 내용을 확인하세요."
            }
            else { status.stringValue = "\(row.input.lastPathComponent): \(row.status)" }
        }
    }

    private func updateButtons() {
        let selection = table.selectedRow
        let selected = rows.indices.contains(selection) ? rows[selection] : nil
        let format = conversionFormat
        convertButton.title = "\(format.title)로 변환"
        convertButton.setAccessibilityLabel(convertButton.title)
        convertButton.cell?.setAccessibilityLabel(convertButton.title)
        convertButton.isEnabled = !busy && rows.contains { $0.conversionSource(for: format) != nil }
        outputFormat.isEnabled = !busy
        editButton.isEnabled = !busy && selected?.document != nil
        blockEditButton.isEnabled = !busy && selected?.document != nil
        warningsButton.isEnabled = !busy && !(selected?.warnings.isEmpty ?? true)
        revealButton.isEnabled = selected != nil
        removeButton.isEnabled = !busy && selected != nil
    }

    @objc private func changeOutputFormat() {
        guard !busy else { return }
        updateButtons()
        status.stringValue = conversionFormat == .hwp
            ? "HWPX 문서를 HWP로 저장합니다. DOCX·HWP는 먼저 HWPX로 변환해 주세요."
            : "HWP·DOCX 문서를 HWPX로 저장합니다."
    }

    private func uniqueOutput(for source: URL, format: ConversionFormat) -> URL {
        let folder = outputFolder ?? source.deletingLastPathComponent()
        let name = source.deletingPathExtension().lastPathComponent
        var destination = folder.appendingPathComponent(name + "." + format.rawValue)
        var index = 2
        while FileManager.default.fileExists(atPath: destination.path) {
            destination = folder.appendingPathComponent(name + "_\(index)." + format.rawValue)
            index += 1
        }
        return destination
    }

    @objc private func convertFiles() {
        guard !busy else { return }
        let format = conversionFormat
        let targets = rows.indices.filter { rows[$0].conversionSource(for: format) != nil }
        guard !targets.isEmpty else { return }
        busy = true; progress.startAnimation(nil); updateButtons()
        convertNext(targets, format: format, completed: 0, failed: 0)
    }

    private func convertNext(_ pending: [Int], format: ConversionFormat, completed: Int, failed: Int) {
        guard let index = pending.first else {
            busy = false; progress.stopAnimation(nil); updateButtons()
            let warningsCount = rows.filter { !$0.warnings.isEmpty }.count
            let summary = failed == 0 ? "\(completed)개 문서를 \(format.title)로 변환했습니다." : "\(completed)개 변환 완료, \(failed)개 실패. 오류가 표시된 문서를 선택해 원인을 확인하세요."
            status.stringValue = summary + (warningsCount == 0 ? "" : " 안내가 있는 문서 \(warningsCount)개: 선택 후 ‘변환 안내’를 확인하세요.")
            return
        }
        guard let input = rows[index].conversionSource(for: format) else {
            convertNext(Array(pending.dropFirst()), format: format, completed: completed, failed: failed)
            return
        }
        rows[index].status = "변환 중…"; rows[index].error = nil; rows[index].warnings = []
        reloadKeepingSelection()
        status.stringValue = "\(input.lastPathComponent)을 변환하고 있습니다."
        engine.convert(inputURL: input, outputURL: uniqueOutput(for: rows[index].input, format: format)) { [weak self] result in
            guard let self else { return }
            var nextCompleted = completed, nextFailed = failed
            switch result {
            case .success(let converted):
                if format == .hwp { self.rows[index].hwpOutput = converted.outputURL }
                else { self.rows[index].output = converted.outputURL }
                self.rows[index].warnings = converted.warnings
                self.rows[index].status = converted.warnings.isEmpty ? "\(format.title) 저장 완료" : "\(format.title) 완료 · 안내 \(converted.warnings.count)건"
                nextCompleted += 1
            case .failure(let error): self.rows[index].status = "변환 실패"; self.rows[index].error = error.localizedDescription; nextFailed += 1
            }
            self.reloadKeepingSelection()
            self.convertNext(Array(pending.dropFirst()), format: format, completed: nextCompleted, failed: nextFailed)
        }
    }

    private func reloadKeepingSelection() {
        let selected = table.selectedRowIndexes
        table.reloadData()
        table.selectRowIndexes(selected, byExtendingSelection: false)
    }

    @objc private func showConversionWarnings() {
        guard !busy, rows.indices.contains(table.selectedRow) else { return }
        let row = rows[table.selectedRow]
        guard !row.warnings.isEmpty else { return }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "\(row.input.lastPathComponent) 변환 안내"
        alert.informativeText = "변환 파일을 저장했습니다. 아래 내용을 확인하고 원본과 비교해 주세요."
        alert.addButton(withTitle: "확인")
        let scroll = NSScrollView(frame: NSRect(x: 0, y: 0, width: 500, height: 220))
        scroll.hasVerticalScroller = true
        scroll.borderType = .bezelBorder
        let text = NSTextView(frame: scroll.bounds)
        text.isEditable = false
        text.isSelectable = true
        text.isVerticallyResizable = true
        text.isHorizontallyResizable = false
        text.autoresizingMask = [.width]
        text.font = .systemFont(ofSize: 13)
        text.textContainerInset = NSSize(width: 8, height: 8)
        text.textContainer?.widthTracksTextView = true
        text.string = row.warnings.enumerated().map { "\($0.offset + 1). \($0.element)" }.joined(separator: "\n\n")
        scroll.documentView = text
        alert.accessoryView = scroll
        alert.beginSheetModal(for: window)
    }

    @objc private func editSelected() {
        guard !busy, rows.indices.contains(table.selectedRow), let url = rows[table.selectedRow].document else { return }
        openVisualEditor(url)
    }

    private func openVisualEditor(_ url: URL) {
        let editor = VisualDocumentEditorWindowController(inputURL: url, engine: engine, onSaved: { [weak self] saved in
            self?.status.stringValue = "수정본 저장 완료: \(saved.lastPathComponent)"
        }, onFallback: { [weak self] currentURL in
            self?.openBlockEditor(currentURL)
        })
        editors.append(editor)
        editor.showWindow(nil)
        editor.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func editSelectedAsList() {
        guard !busy, rows.indices.contains(table.selectedRow), let url = rows[table.selectedRow].document else { return }
        openBlockEditor(url)
    }

    private func openBlockEditor(_ url: URL) {
        let editor = DocumentEditorWindowController(inputURL: url, engine: engine) { [weak self] saved in
            self?.status.stringValue = "수정본 저장 완료: \(saved.lastPathComponent)"
        }
        editors.append(editor)
        editor.showWindow(nil)
        editor.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func revealSelected() {
        guard rows.indices.contains(table.selectedRow) else { return }
        let row = rows[table.selectedRow]
        NSWorkspace.shared.activateFileViewerSelecting([row.latestOutput ?? row.document ?? row.input])
    }

    @objc private func removeSelected() {
        guard !busy else { return }
        for index in table.selectedRowIndexes.sorted(by: >) { rows.remove(at: index) }
        table.reloadData(); updateButtons()
        status.stringValue = "목록에서 제거했습니다. 실제 파일은 그대로 보관됩니다."
    }
}
