import AppKit
import UniformTypeIdentifiers

final class DocumentEditorWindowController: NSWindowController, StudioEditorSession {
    private let inputURL: URL
    private let engine: EngineClient
    private let onSaved: ((URL) -> Void)?

    private var snapshot: DocumentSnapshot?
    private var blocks: [TextBlock] = []
    private var filteredBlocks: [TextBlock] = []
    private var drafts: [String: String] = [:]
    private var savedDrafts: [String: String] = [:]
    private var selectedBlockID: String?
    private var lastSavedURL: URL?
    private var isLoading = false
    private var isSaving = false
    private var allowCloseAfterSave = false
    private var closeAfterCurrentSave = false

    private let searchField = NSSearchField()
    private let tableView = NSTableView()
    private let scrollView = NSScrollView()
    private let editorScrollView = NSScrollView()
    private let textView = NSTextView()
    private let locationLabel = NSTextField(labelWithString: "내용을 선택하세요")
    private let stateLabel = NSTextField(labelWithString: "")
    private let infoLabel = NSTextField(wrappingLabelWithString: "문단과 표 셀의 내용을 수정합니다. 인쇄 모양은 문서 앱에서 확인하세요.")
    private let warningLabel = NSTextField(wrappingLabelWithString: "")
    private let countLabel = NSTextField(labelWithString: "")
    private let progressIndicator = NSProgressIndicator()
    private let applyButton = NSButton(title: "변경 적용", target: nil, action: nil)
    private let revertButton = NSButton(title: "선택 내용 되돌리기", target: nil, action: nil)
    private let saveButton = NSButton(title: "다른 이름으로 저장…", target: nil, action: nil)
    private let openButton = NSButton(title: "문서 앱에서 열기", target: nil, action: nil)
    private let revealButton = NSButton(title: "Finder에서 보기", target: nil, action: nil)

    var hasUnsavedChanges: Bool {
        drafts != savedDrafts
    }

    var hasActiveOperation: Bool {
        isLoading || isSaving
    }

    init(inputURL: URL, engine: EngineClient, onSaved: ((URL) -> Void)? = nil) {
        self.inputURL = inputURL
        self.engine = engine
        self.onSaved = onSaved

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1120, height: 760),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Document Studio — \(inputURL.lastPathComponent)"
        window.minSize = NSSize(width: 1060, height: 720)
        window.center()

        super.init(window: window)
        window.delegate = self
        buildInterface()
        loadDocument()
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// 자동 검증에서 화면 조작 없이 편집 내용을 주입할 때 사용합니다.
    func applyEditsForTesting(_ edits: [String: String]) {
        for (id, text) in edits {
            guard let block = blocks.first(where: { $0.id == id }), block.editable else { continue }
            drafts[id] = text
        }
        refreshDirtyState()
        tableView.reloadData()
        if let id = selectedBlockID, let text = drafts[id] {
            textView.string = text
        }
    }

    /// 자동 검증용 저장 진입점입니다. 입력 파일은 그대로 두고 지정 경로에 새 파일을 만듭니다.
    func saveForTesting(to outputURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        commitActiveEditor()
        save(to: outputURL, presentsUI: false) { result in
            completion(result)
        }
    }

    /// 앱 종료 요청에서도 창 닫기와 같은 저장·버리기·취소 선택을 제공합니다.
    func confirmBeforeClosing(completion: @escaping (Bool) -> Void) {
        commitActiveEditor()
        guard !hasActiveOperation else {
            completion(false)
            return
        }
        guard hasUnsavedChanges else {
            completion(true)
            return
        }
        guard let window else {
            completion(false)
            return
        }

        let alert = makeUnsavedChangesAlert()
        alert.beginSheetModal(for: window) { [weak self] response in
            guard let self else {
                completion(false)
                return
            }
            switch response {
            case .alertFirstButtonReturn:
                self.presentSavePanel(closeAfterSave: true, completion: completion)
            case .alertSecondButtonReturn:
                completion(true)
            default:
                completion(false)
            }
        }
    }

    private func buildInterface() {
        guard let window else { return }

        let root = NSView(frame: NSRect(origin: .zero, size: window.contentLayoutRect.size))
        root.autoresizingMask = [.width, .height]
        root.wantsLayer = true
        root.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        window.contentView = root

        let header = makeHeader()
        let divider = NSBox()
        divider.boxType = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false
        let body = makeBody()
        let footer = makeFooter()

        root.addSubview(header)
        root.addSubview(divider)
        root.addSubview(body)
        root.addSubview(footer)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: root.topAnchor),
            header.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            header.heightAnchor.constraint(equalToConstant: 94),

            divider.topAnchor.constraint(equalTo: header.bottomAnchor),
            divider.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: root.trailingAnchor),

            body.topAnchor.constraint(equalTo: divider.bottomAnchor),
            body.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            body.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            body.bottomAnchor.constraint(equalTo: footer.topAnchor),

            footer.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            footer.heightAnchor.constraint(equalToConstant: 64)
        ])
    }

    private func makeHeader() -> NSView {
        let container = NSView()
        container.translatesAutoresizingMaskIntoConstraints = false

        let title = NSTextField(labelWithString: "문서 내용 편집")
        title.font = .systemFont(ofSize: 22, weight: .semibold)
        title.textColor = .labelColor

        let filename = NSTextField(labelWithString: inputURL.lastPathComponent)
        filename.font = .systemFont(ofSize: 12, weight: .medium)
        filename.textColor = .secondaryLabelColor
        filename.lineBreakMode = .byTruncatingMiddle
        filename.toolTip = inputURL.path

        infoLabel.font = .systemFont(ofSize: 12)
        infoLabel.textColor = .secondaryLabelColor
        infoLabel.maximumNumberOfLines = 1

        let titleStack = NSStackView(views: [title, filename, infoLabel])
        titleStack.orientation = .vertical
        titleStack.alignment = .leading
        titleStack.spacing = 3
        titleStack.translatesAutoresizingMaskIntoConstraints = false

        progressIndicator.style = .spinning
        progressIndicator.controlSize = .small
        progressIndicator.isDisplayedWhenStopped = false
        progressIndicator.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(titleStack)
        container.addSubview(progressIndicator)
        NSLayoutConstraint.activate([
            titleStack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            titleStack.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            titleStack.trailingAnchor.constraint(lessThanOrEqualTo: progressIndicator.leadingAnchor, constant: -16),
            progressIndicator.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24),
            progressIndicator.centerYAnchor.constraint(equalTo: container.centerYAnchor)
        ])
        return container
    }

    private func makeBody() -> NSView {
        let splitView = NSSplitView()
        splitView.isVertical = true
        splitView.dividerStyle = .thin
        splitView.translatesAutoresizingMaskIntoConstraints = false

        let sidebar = makeSidebar()
        let editor = makeEditor()
        splitView.addArrangedSubview(sidebar)
        splitView.addArrangedSubview(editor)

        sidebar.widthAnchor.constraint(equalToConstant: 318).isActive = true
        editor.widthAnchor.constraint(greaterThanOrEqualToConstant: 600).isActive = true
        sidebar.setContentHuggingPriority(.defaultHigh, for: .horizontal)
        splitView.setHoldingPriority(.defaultHigh, forSubviewAt: 0)
        splitView.setHoldingPriority(.defaultLow, forSubviewAt: 1)
        return splitView
    }

    private func makeSidebar() -> NSView {
        let container = NSView()
        container.translatesAutoresizingMaskIntoConstraints = false
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.controlBackgroundColor.cgColor

        searchField.placeholderString = "문단 또는 표 내용 검색"
        searchField.delegate = self
        searchField.translatesAutoresizingMaskIntoConstraints = false
        searchField.setAccessibilityLabel("문서 내용 검색")

        countLabel.font = .systemFont(ofSize: 11, weight: .medium)
        countLabel.textColor = .secondaryLabelColor
        countLabel.translatesAutoresizingMaskIntoConstraints = false

        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("BlockColumn"))
        column.title = "문서 내용"
        tableView.addTableColumn(column)
        tableView.headerView = nil
        tableView.rowHeight = 58
        tableView.intercellSpacing = NSSize(width: 0, height: 1)
        tableView.backgroundColor = .clear
        tableView.selectionHighlightStyle = .regular
        tableView.delegate = self
        tableView.dataSource = self
        tableView.setAccessibilityLabel("문단과 표 셀 목록")

        scrollView.documentView = tableView
        scrollView.hasVerticalScroller = true
        scrollView.autohidesScrollers = true
        scrollView.drawsBackground = false
        scrollView.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(searchField)
        container.addSubview(countLabel)
        container.addSubview(scrollView)

        NSLayoutConstraint.activate([
            container.widthAnchor.constraint(greaterThanOrEqualToConstant: 280),
            searchField.topAnchor.constraint(equalTo: container.topAnchor, constant: 16),
            searchField.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            searchField.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),

            countLabel.topAnchor.constraint(equalTo: searchField.bottomAnchor, constant: 10),
            countLabel.leadingAnchor.constraint(equalTo: searchField.leadingAnchor, constant: 2),
            countLabel.trailingAnchor.constraint(equalTo: searchField.trailingAnchor),

            scrollView.topAnchor.constraint(equalTo: countLabel.bottomAnchor, constant: 8),
            scrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: container.bottomAnchor)
        ])
        return container
    }

    private func makeEditor() -> NSView {
        let container = NSView()
        container.translatesAutoresizingMaskIntoConstraints = false

        locationLabel.font = .systemFont(ofSize: 17, weight: .semibold)
        locationLabel.textColor = .labelColor
        locationLabel.lineBreakMode = .byTruncatingTail
        locationLabel.translatesAutoresizingMaskIntoConstraints = false

        stateLabel.font = .systemFont(ofSize: 11, weight: .medium)
        stateLabel.textColor = .secondaryLabelColor
        stateLabel.alignment = .right
        stateLabel.translatesAutoresizingMaskIntoConstraints = false

        warningLabel.font = .systemFont(ofSize: 12)
        warningLabel.textColor = .secondaryLabelColor
        warningLabel.maximumNumberOfLines = 2
        warningLabel.translatesAutoresizingMaskIntoConstraints = false

        configureTextView()
        editorScrollView.documentView = textView
        editorScrollView.hasVerticalScroller = true
        editorScrollView.autohidesScrollers = true
        editorScrollView.borderType = .noBorder
        editorScrollView.drawsBackground = true
        editorScrollView.backgroundColor = .textBackgroundColor
        editorScrollView.translatesAutoresizingMaskIntoConstraints = false
        editorScrollView.wantsLayer = true
        editorScrollView.layer?.cornerRadius = 9
        editorScrollView.layer?.borderWidth = 1
        editorScrollView.layer?.borderColor = NSColor.separatorColor.cgColor

        applyButton.bezelStyle = .rounded
        applyButton.target = self
        applyButton.action = #selector(applyCurrentEdit)
        applyButton.keyEquivalent = "\r"
        applyButton.keyEquivalentModifierMask = [.command]
        applyButton.isEnabled = false

        revertButton.bezelStyle = .rounded
        revertButton.target = self
        revertButton.action = #selector(revertCurrentEdit)
        revertButton.isEnabled = false

        let editActions = NSStackView(views: [revertButton, applyButton])
        editActions.orientation = .horizontal
        editActions.alignment = .centerY
        editActions.spacing = 8
        editActions.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(locationLabel)
        container.addSubview(stateLabel)
        container.addSubview(warningLabel)
        container.addSubview(editorScrollView)
        container.addSubview(editActions)

        NSLayoutConstraint.activate([
            locationLabel.topAnchor.constraint(equalTo: container.topAnchor, constant: 22),
            locationLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            locationLabel.trailingAnchor.constraint(lessThanOrEqualTo: stateLabel.leadingAnchor, constant: -12),

            stateLabel.centerYAnchor.constraint(equalTo: locationLabel.centerYAnchor),
            stateLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24),

            warningLabel.topAnchor.constraint(equalTo: locationLabel.bottomAnchor, constant: 6),
            warningLabel.leadingAnchor.constraint(equalTo: locationLabel.leadingAnchor),
            warningLabel.trailingAnchor.constraint(equalTo: stateLabel.trailingAnchor),

            editorScrollView.topAnchor.constraint(equalTo: warningLabel.bottomAnchor, constant: 14),
            editorScrollView.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            editorScrollView.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24),
            editorScrollView.bottomAnchor.constraint(equalTo: editActions.topAnchor, constant: -14),

            editActions.trailingAnchor.constraint(equalTo: editorScrollView.trailingAnchor),
            editActions.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -18)
        ])
        return container
    }

    private func configureTextView() {
        textView.delegate = self
        textView.isRichText = false
        textView.isEditable = false
        textView.isSelectable = true
        textView.allowsUndo = true
        textView.font = .systemFont(ofSize: 15)
        textView.textColor = .labelColor
        textView.backgroundColor = .textBackgroundColor
        textView.textContainerInset = NSSize(width: 18, height: 18)
        textView.isVerticallyResizable = true
        textView.isHorizontallyResizable = false
        textView.autoresizingMask = [.width]
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.containerSize = NSSize(width: 0, height: CGFloat.greatestFiniteMagnitude)
        textView.setAccessibilityLabel("선택한 문서 내용 편집")
    }

    private func makeFooter() -> NSView {
        let container = NSView()
        container.translatesAutoresizingMaskIntoConstraints = false

        let divider = NSBox()
        divider.boxType = .separator
        divider.translatesAutoresizingMaskIntoConstraints = false

        saveButton.bezelStyle = .rounded
        saveButton.target = self
        saveButton.action = #selector(saveAs)
        saveButton.keyEquivalent = "s"
        saveButton.keyEquivalentModifierMask = [.command, .shift]
        saveButton.isEnabled = false
        saveButton.translatesAutoresizingMaskIntoConstraints = false

        openButton.bezelStyle = .rounded
        openButton.target = self
        openButton.action = #selector(openSavedDocument)
        openButton.isEnabled = false

        revealButton.bezelStyle = .rounded
        revealButton.target = self
        revealButton.action = #selector(revealSavedDocument)
        revealButton.isEnabled = false

        let leftActions = NSStackView(views: [openButton, revealButton])
        leftActions.orientation = .horizontal
        leftActions.spacing = 8
        leftActions.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(divider)
        container.addSubview(leftActions)
        container.addSubview(saveButton)
        NSLayoutConstraint.activate([
            divider.topAnchor.constraint(equalTo: container.topAnchor),
            divider.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: container.trailingAnchor),

            leftActions.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 24),
            leftActions.centerYAnchor.constraint(equalTo: container.centerYAnchor),

            saveButton.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -24),
            saveButton.centerYAnchor.constraint(equalTo: container.centerYAnchor)
        ])
        return container
    }

    private func loadDocument() {
        isLoading = true
        setBusy(true, message: "문서 내용을 읽는 중…")
        engine.inspect(inputURL: inputURL) { [weak self] result in
            guard let self else { return }
            self.isLoading = false
            self.setBusy(false, message: nil)
            switch result {
            case .success(let snapshot):
                self.snapshot = snapshot
                self.blocks = snapshot.blocks
                self.filteredBlocks = snapshot.blocks
                self.updateWarnings(snapshot.warnings)
                self.updateCount()
                self.tableView.reloadData()
                self.saveButton.isEnabled = !snapshot.blocks.isEmpty
                if !self.filteredBlocks.isEmpty {
                    self.tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
                    self.tableView.scrollRowToVisible(0)
                } else {
                    self.showEmptyDocument()
                }
            case .failure(let error):
                self.showLoadFailure(error)
            }
        }
    }

    private func updateWarnings(_ warnings: [String]) {
        guard !warnings.isEmpty else { return }
        infoLabel.stringValue = "\(infoLabel.stringValue)  ·  일부 내용은 안전을 위해 읽기 전용입니다."
        infoLabel.toolTip = warnings.joined(separator: "\n")
    }

    private func showEmptyDocument() {
        locationLabel.stringValue = "편집할 내용이 없습니다"
        warningLabel.stringValue = "이 문서에서 안전하게 수정할 수 있는 문단이나 표 셀을 찾지 못했습니다."
        stateLabel.stringValue = ""
        textView.string = ""
        textView.isEditable = false
        applyButton.isEnabled = false
        revertButton.isEnabled = false
    }

    private func showLoadFailure(_ error: Error) {
        locationLabel.stringValue = "문서를 열 수 없습니다"
        warningLabel.stringValue = error.localizedDescription
        stateLabel.stringValue = ""
        textView.string = ""
        textView.isEditable = false
        saveButton.isEnabled = false

        guard let window else { return }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "문서 내용을 읽지 못했습니다"
        alert.informativeText = error.localizedDescription
        alert.addButton(withTitle: "확인")
        alert.beginSheetModal(for: window)
    }

    private func select(block: TextBlock) {
        commitActiveEditor()
        selectedBlockID = block.id
        locationLabel.stringValue = block.label
        textView.string = drafts[block.id] ?? block.text
        textView.isEditable = block.editable
        textView.isSelectable = true
        textView.textColor = block.editable ? .labelColor : .secondaryLabelColor
        stateLabel.stringValue = block.editable ? "수정 가능" : "읽기 전용"
        stateLabel.textColor = block.editable ? .systemGreen : .secondaryLabelColor
        warningLabel.stringValue = block.editable
            ? "내용만 변경됩니다. 글꼴·문단 모양·표 크기는 원본 설정을 유지합니다."
            : (block.reason ?? "이 내용은 문서 손상을 막기 위해 수정할 수 없습니다.")
        applyButton.isEnabled = block.editable
        revertButton.isEnabled = block.editable && drafts[block.id] != nil
    }

    private func commitActiveEditor() {
        guard let id = selectedBlockID,
              let block = blocks.first(where: { $0.id == id }),
              block.editable else { return }
        if textView.string == block.text {
            drafts.removeValue(forKey: id)
        } else {
            drafts[id] = textView.string
        }
        refreshDirtyState()
    }

    private func refreshDirtyState() {
        let unsavedIDs = Set(drafts.keys).union(savedDrafts.keys).filter { drafts[$0] != savedDrafts[$0] }
        window?.isDocumentEdited = !unsavedIDs.isEmpty
        let dirtyCount = unsavedIDs.count
        saveButton.title = dirtyCount == 0 ? "다른 이름으로 저장…" : "수정본 저장… (\(dirtyCount))"
        revertButton.isEnabled = selectedBlockID.flatMap { drafts[$0] } != nil
    }

    private func updateCount() {
        if filteredBlocks.count == blocks.count {
            countLabel.stringValue = "전체 \(blocks.count)개"
        } else {
            countLabel.stringValue = "검색 결과 \(filteredBlocks.count)개 · 전체 \(blocks.count)개"
        }
    }

    private func filterBlocks() {
        commitActiveEditor()
        let query = searchField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if query.isEmpty {
            filteredBlocks = blocks
        } else {
            filteredBlocks = blocks.filter { block in
                block.label.localizedCaseInsensitiveContains(query)
                    || (drafts[block.id] ?? block.text).localizedCaseInsensitiveContains(query)
            }
        }
        updateCount()
        tableView.reloadData()

        if let selectedBlockID,
           let row = filteredBlocks.firstIndex(where: { $0.id == selectedBlockID }) {
            tableView.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
        } else if !filteredBlocks.isEmpty {
            tableView.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
        } else {
            selectedBlockID = nil
            locationLabel.stringValue = "검색 결과가 없습니다"
            warningLabel.stringValue = "다른 검색어를 입력해 보세요."
            stateLabel.stringValue = ""
            textView.string = ""
            textView.isEditable = false
            applyButton.isEnabled = false
            revertButton.isEnabled = false
        }
    }

    @objc private func applyCurrentEdit() {
        commitActiveEditor()
        tableView.reloadData()
        stateLabel.stringValue = drafts[selectedBlockID ?? ""] == nil ? "원본과 같음" : "변경 적용됨"
        stateLabel.textColor = .secondaryLabelColor
    }

    @objc private func revertCurrentEdit() {
        guard let id = selectedBlockID,
              let block = blocks.first(where: { $0.id == id }) else { return }
        drafts.removeValue(forKey: id)
        textView.string = block.text
        textView.undoManager?.removeAllActions()
        refreshDirtyState()
        tableView.reloadData()
        stateLabel.stringValue = "원본으로 되돌림"
        stateLabel.textColor = .secondaryLabelColor
    }

    @objc private func saveAs() {
        presentSavePanel(closeAfterSave: false)
    }

    private func presentSavePanel(closeAfterSave: Bool, completion: ((Bool) -> Void)? = nil) {
        guard !hasActiveOperation, let window else {
            completion?(false)
            return
        }
        commitActiveEditor()

        let panel = NSSavePanel()
        panel.title = "수정한 HWPX 문서 저장"
        panel.prompt = "저장"
        panel.message = "같은 이름을 선택하고 대치를 승인하면 기존 파일을 바꿉니다."
        panel.directoryURL = inputURL.deletingLastPathComponent()
        let baseName = inputURL.deletingPathExtension().lastPathComponent
        panel.nameFieldStringValue = "\(baseName)-수정본.hwpx"
        if let hwpxType = UTType(filenameExtension: "hwpx") {
            panel.allowedContentTypes = [hwpxType]
        }
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false

        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self else {
                completion?(false)
                return
            }
            guard response == .OK, let outputURL = panel.url else {
                self.closeAfterCurrentSave = false
                completion?(false)
                return
            }
            self.closeAfterCurrentSave = closeAfterSave
            self.save(to: outputURL, replacing: FileManager.default.fileExists(atPath: outputURL.path)) { result in
                completion?(result.isSuccess)
            }
        }
    }

    private func save(
        to outputURL: URL,
        replacing: Bool = false,
        presentsUI: Bool = true,
        completion: ((Result<URL, Error>) -> Void)?
    ) {
        guard !hasActiveOperation else {
            completion?(.failure(StudioError.message("다른 문서 작업이 진행 중입니다. 작업이 끝난 뒤 다시 시도해 주세요.")))
            return
        }

        let edits = blocks.compactMap { block -> TextEdit? in
            guard let text = drafts[block.id], text != block.text else { return nil }
            return TextEdit(id: block.id, text: text)
        }

        isSaving = true
        setBusy(true, message: "수정본을 저장하는 중…")
        engine.save(inputURL: inputURL, outputURL: outputURL, edits: edits, replacing: replacing) { [weak self] result in
            guard let self else {
                completion?(.failure(StudioError.message("편집 창이 닫혀 저장 결과를 확인할 수 없습니다.")))
                return
            }
            self.isSaving = false
            self.setBusy(false, message: nil)
            switch result {
            case .success(let savedURL):
                self.savedDrafts = self.drafts
                self.refreshDirtyState()
                self.lastSavedURL = savedURL
                self.openButton.isEnabled = true
                self.revealButton.isEnabled = true
                self.window?.representedURL = savedURL
                self.onSaved?(savedURL)
                if presentsUI { self.showSaveSuccess(savedURL) }
                completion?(.success(savedURL))
                if self.closeAfterCurrentSave {
                    self.allowCloseAfterSave = true
                    self.window?.performClose(nil)
                }
                self.closeAfterCurrentSave = false
            case .failure(let error):
                if presentsUI { self.showSaveFailure(error) }
                completion?(.failure(error))
                self.closeAfterCurrentSave = false
            }
        }
    }

    private func showSaveSuccess(_ url: URL) {
        stateLabel.stringValue = "저장 완료"
        stateLabel.textColor = .systemGreen
        guard let window, !closeAfterCurrentSave else { return }

        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = "수정본을 저장했습니다"
        alert.informativeText = url.path
        alert.addButton(withTitle: "문서 앱에서 열기")
        alert.addButton(withTitle: "Finder에서 보기")
        alert.addButton(withTitle: "완료")
        alert.beginSheetModal(for: window) { [weak self] response in
            switch response {
            case .alertFirstButtonReturn:
                self?.openSavedDocument()
            case .alertSecondButtonReturn:
                self?.revealSavedDocument()
            default:
                break
            }
        }
    }

    private func showSaveFailure(_ error: Error) {
        stateLabel.stringValue = "저장 실패"
        stateLabel.textColor = .systemRed
        guard let window else { return }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "수정본을 저장하지 못했습니다"
        alert.informativeText = error.localizedDescription
        alert.addButton(withTitle: "확인")
        alert.beginSheetModal(for: window)
    }

    private func showExistingOutputWarning(retry: @escaping () -> Void, cancel: @escaping () -> Void) {
        guard let window else {
            cancel()
            return
        }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "새 파일 이름을 선택해 주세요"
        alert.informativeText = "같은 이름의 파일이 이미 있습니다. 원본과 기존 파일을 보호하기 위해 Document Studio는 파일을 덮어쓰지 않습니다."
        alert.addButton(withTitle: "다시 선택")
        alert.addButton(withTitle: "취소")
        alert.beginSheetModal(for: window) { response in
            if response == .alertFirstButtonReturn {
                retry()
            } else {
                cancel()
            }
        }
    }

    @objc private func openSavedDocument() {
        guard let lastSavedURL else { return }
        NSWorkspace.shared.open(lastSavedURL)
    }

    @objc private func revealSavedDocument() {
        guard let lastSavedURL else { return }
        NSWorkspace.shared.activateFileViewerSelecting([lastSavedURL])
    }

    private func setBusy(_ busy: Bool, message: String?) {
        if busy {
            progressIndicator.startAnimation(nil)
        } else {
            progressIndicator.stopAnimation(nil)
        }
        searchField.isEnabled = !busy
        tableView.isEnabled = !busy
        textView.isEditable = !busy && selectedBlockID.flatMap { id in
            blocks.first(where: { $0.id == id })?.editable
        } == true
        saveButton.isEnabled = !busy && snapshot != nil
        applyButton.isEnabled = !busy && selectedBlockID.flatMap { id in
            blocks.first(where: { $0.id == id })?.editable
        } == true
        if let message {
            stateLabel.stringValue = message
            stateLabel.textColor = .secondaryLabelColor
        }
    }

    private func preview(for block: TextBlock) -> String {
        let text = (drafts[block.id] ?? block.text)
            .replacingOccurrences(of: "\n", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return "(빈 내용)" }
        return text
    }
}

extension DocumentEditorWindowController: NSTableViewDataSource, NSTableViewDelegate {
    func numberOfRows(in tableView: NSTableView) -> Int {
        filteredBlocks.count
    }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        guard filteredBlocks.indices.contains(row) else { return nil }
        let block = filteredBlocks[row]
        let identifier = NSUserInterfaceItemIdentifier("DocumentBlockCell")
        let cell: DocumentBlockCell
        if let reused = tableView.makeView(withIdentifier: identifier, owner: self) as? DocumentBlockCell {
            cell = reused
        } else {
            cell = DocumentBlockCell()
            cell.identifier = identifier
        }
        cell.configure(
            title: block.label,
            preview: preview(for: block),
            isEditable: block.editable,
            isChanged: drafts[block.id] != nil
        )
        return cell
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        let row = tableView.selectedRow
        guard filteredBlocks.indices.contains(row) else { return }
        select(block: filteredBlocks[row])
    }
}

extension DocumentEditorWindowController: NSSearchFieldDelegate {
    func controlTextDidChange(_ obj: Notification) {
        guard obj.object as AnyObject? === searchField else { return }
        filterBlocks()
    }
}

extension DocumentEditorWindowController: NSTextViewDelegate {
    func textDidChange(_ notification: Notification) {
        guard notification.object as AnyObject? === textView else { return }
        commitActiveEditor()
        if let id = selectedBlockID,
           let row = filteredBlocks.firstIndex(where: { $0.id == id }) {
            tableView.reloadData(forRowIndexes: IndexSet(integer: row), columnIndexes: IndexSet(integer: 0))
        }
    }
}

extension DocumentEditorWindowController: NSWindowDelegate {
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        guard !hasActiveOperation else {
            stateLabel.stringValue = isSaving ? "저장이 끝난 뒤 창을 닫아 주세요" : "문서를 불러온 뒤 창을 닫아 주세요"
            stateLabel.textColor = .secondaryLabelColor
            NSSound.beep()
            return false
        }
        commitActiveEditor()
        if allowCloseAfterSave || !hasUnsavedChanges { return true }

        let alert = makeUnsavedChangesAlert()
        alert.beginSheetModal(for: sender) { [weak self] response in
            guard let self else { return }
            switch response {
            case .alertFirstButtonReturn:
                self.presentSavePanel(closeAfterSave: true)
            case .alertSecondButtonReturn:
                self.allowCloseAfterSave = true
                sender.performClose(nil)
            default:
                break
            }
        }
        return false
    }

    private func makeUnsavedChangesAlert() -> NSAlert {
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "저장하지 않은 변경 내용이 있습니다"
        alert.informativeText = "수정본을 저장한 뒤 닫거나, 변경 내용을 버릴 수 있습니다. 원본 문서는 변경되지 않습니다."
        alert.addButton(withTitle: "저장 후 닫기")
        alert.addButton(withTitle: "변경 내용 버리기")
        alert.addButton(withTitle: "취소")
        return alert
    }
}

private extension Result {
    var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }
}

private final class DocumentBlockCell: NSTableCellView {
    private let titleLabel = NSTextField(labelWithString: "")
    private let previewLabel = NSTextField(labelWithString: "")
    private let badgeLabel = NSTextField(labelWithString: "")

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        wantsLayer = true

        titleLabel.font = .systemFont(ofSize: 12, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        titleLabel.translatesAutoresizingMaskIntoConstraints = false

        previewLabel.font = .systemFont(ofSize: 11)
        previewLabel.textColor = .secondaryLabelColor
        previewLabel.lineBreakMode = .byTruncatingTail
        previewLabel.translatesAutoresizingMaskIntoConstraints = false

        badgeLabel.font = .systemFont(ofSize: 10, weight: .medium)
        badgeLabel.alignment = .right
        badgeLabel.translatesAutoresizingMaskIntoConstraints = false
        badgeLabel.setContentHuggingPriority(.required, for: .horizontal)

        addSubview(titleLabel)
        addSubview(previewLabel)
        addSubview(badgeLabel)
        NSLayoutConstraint.activate([
            titleLabel.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 14),
            titleLabel.topAnchor.constraint(equalTo: topAnchor, constant: 9),
            titleLabel.trailingAnchor.constraint(lessThanOrEqualTo: badgeLabel.leadingAnchor, constant: -8),

            badgeLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            badgeLabel.centerYAnchor.constraint(equalTo: titleLabel.centerYAnchor),

            previewLabel.leadingAnchor.constraint(equalTo: titleLabel.leadingAnchor),
            previewLabel.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            previewLabel.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 5)
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(title: String, preview: String, isEditable: Bool, isChanged: Bool) {
        titleLabel.stringValue = title
        previewLabel.stringValue = preview
        if isChanged {
            badgeLabel.stringValue = "변경됨"
            badgeLabel.textColor = .systemBlue
        } else if !isEditable {
            badgeLabel.stringValue = "읽기 전용"
            badgeLabel.textColor = .tertiaryLabelColor
        } else {
            badgeLabel.stringValue = ""
        }
        toolTip = preview
    }
}
