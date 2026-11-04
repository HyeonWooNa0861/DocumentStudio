import AppKit
import UniformTypeIdentifiers
import WebKit

final class VisualDocumentEditorWindowController: NSWindowController, StudioEditorSession {
    private(set) var activeURL: URL
    private let engine: EngineClient
    private let resourcesURL: URL
    private let onSaved: ((URL) -> Void)?
    private let onFallback: ((URL) -> Void)?

    private var snapshot: VisualDocumentSnapshot?
    private var webReady = false
    private var pageLoaded = false
    private var modelLoaded = false
    private var modelLoadInFlight = false
    private var isDirty = false
    private var isLoading = true
    private var isSaving = false
    private var isExportingPDF = false
    private var pdfExporter: StudioPDFExporter?
    private var pdfPreview: StudioPDFPreviewWindowController?
    private var closeCheckInFlight = false
    private var allowClose = false
    private var closeAfterSave = false
    private var lastSavedURL: URL?
    private var loadCompletion: ((Result<Void, Error>) -> Void)?
    private var injectPostLoadFailureForTesting = false
    private var injectDiagnosticsFailureForTesting = false

    private let webView: WKWebView
    private let statusLabel = NSTextField(labelWithString: "문서 시각 모델을 읽는 중…")
    private let filenameLabel = NSTextField(labelWithString: "")
    private let progress = NSProgressIndicator()
    private let saveButton = NSButton(title: "다른 이름으로 저장…", target: nil, action: nil)
    private let fallbackButton = NSButton(title: "목록식 편집", target: nil, action: nil)
    private let pdfButton = NSButton(title: "PDF 미리보기…", target: nil, action: nil)

    var hasUnsavedChanges: Bool { isDirty }
    var hasActiveOperation: Bool { isLoading || isSaving || isExportingPDF }

    init(
        inputURL: URL,
        engine: EngineClient,
        resourcesURL: URL? = nil,
        onSaved: ((URL) -> Void)? = nil,
        onFallback: ((URL) -> Void)? = nil
    ) {
        self.activeURL = inputURL
        self.engine = engine
        self.resourcesURL = resourcesURL ?? Bundle.main.resourceURL ?? Bundle.main.bundleURL
        self.onSaved = onSaved
        self.onFallback = onFallback

        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.userContentController.add(WeakScriptMessageHandler(), name: "studio")
        self.webView = WKWebView(frame: .zero, configuration: configuration)

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 1220, height: 820),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "Document Studio — \(inputURL.lastPathComponent)"
        window.minSize = NSSize(width: 960, height: 640)
        window.center()

        super.init(window: window)
        configuration.userContentController.removeScriptMessageHandler(forName: "studio")
        configuration.userContentController.add(WeakScriptMessageHandler(target: self), name: "studio")
        window.delegate = self
        buildInterface()
        loadWebResources()
        loadVisualSnapshot(from: inputURL)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "studio")
    }

    private func buildInterface() {
        guard let window else { return }
        let root = NSView(frame: NSRect(origin: .zero, size: window.contentLayoutRect.size))
        root.autoresizingMask = [.width, .height]
        root.wantsLayer = true
        root.layer?.backgroundColor = NSColor.windowBackgroundColor.cgColor
        window.contentView = root

        let title = NSTextField(labelWithString: "문서 편집")
        title.font = .systemFont(ofSize: 22, weight: .semibold)
        filenameLabel.stringValue = activeURL.lastPathComponent
        filenameLabel.font = .systemFont(ofSize: 13, weight: .medium)
        filenameLabel.textColor = .secondaryLabelColor
        filenameLabel.lineBreakMode = .byTruncatingMiddle
        filenameLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        let approximation = NSTextField(labelWithString: "근사 미리보기 · 원본 HWPX는 그대로 보존")
        approximation.font = .systemFont(ofSize: 12, weight: .medium)
        approximation.textColor = .secondaryLabelColor

        fallbackButton.target = self
        fallbackButton.action = #selector(openFallbackEditor)
        fallbackButton.bezelStyle = .rounded
        saveButton.target = self
        saveButton.action = #selector(saveAs)
        saveButton.bezelStyle = .rounded
        saveButton.keyEquivalent = "s"
        saveButton.keyEquivalentModifierMask = [.command, .shift]
        saveButton.isEnabled = false
        pdfButton.target = self
        pdfButton.action = #selector(previewPDF)
        pdfButton.bezelStyle = .rounded
        pdfButton.keyEquivalent = "p"
        pdfButton.keyEquivalentModifierMask = [.command]
        pdfButton.isEnabled = false
        progress.style = .spinning
        progress.controlSize = .small
        progress.isDisplayedWhenStopped = false
        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.lineBreakMode = .byTruncatingMiddle

        let heading = NSStackView(views: [title, filenameLabel, approximation])
        heading.orientation = .vertical
        heading.alignment = .leading
        heading.spacing = 2
        let actions = NSStackView(views: [progress, fallbackButton, pdfButton, saveButton])
        actions.orientation = .horizontal
        actions.alignment = .centerY
        actions.spacing = 8
        let header = NSStackView(views: [heading, NSView(), actions])
        header.orientation = .horizontal
        header.alignment = .centerY
        header.spacing = 12
        header.edgeInsets = NSEdgeInsets(top: 12, left: 18, bottom: 10, right: 18)

        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.setAccessibilityLabel("HWPX 문서 시각 편집 영역")

        let divider = NSBox()
        divider.boxType = .separator
        let footer = NSStackView(views: [statusLabel])
        footer.edgeInsets = NSEdgeInsets(top: 7, left: 18, bottom: 8, right: 18)

        for view in [header, divider, webView, footer] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(view)
        }
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: root.topAnchor),
            header.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            header.heightAnchor.constraint(greaterThanOrEqualToConstant: 76),
            divider.topAnchor.constraint(equalTo: header.bottomAnchor),
            divider.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            divider.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            webView.topAnchor.constraint(equalTo: divider.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: footer.topAnchor),
            footer.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            footer.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            footer.bottomAnchor.constraint(equalTo: root.bottomAnchor),
            footer.heightAnchor.constraint(greaterThanOrEqualToConstant: 32)
        ])
    }

    private var visualEditorDirectory: URL {
        resourcesURL.appendingPathComponent("VisualEditor", isDirectory: true)
    }

    private func loadWebResources() {
        let index = visualEditorDirectory.appendingPathComponent("index.html")
        guard FileManager.default.fileExists(atPath: index.path) else {
            failLoading(StudioError.message("번들에 VisualEditor/index.html이 없습니다."))
            return
        }
        webView.loadFileURL(index, allowingReadAccessTo: visualEditorDirectory)
    }

    private func loadVisualSnapshot(from url: URL) {
        isLoading = true
        updateBusyUI(message: "문서 시각 모델을 읽는 중…")
        engine.visualSnapshot(inputURL: url) { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let snapshot):
                self.snapshot = snapshot
                self.loadModelWhenReady()
            case .failure(let error):
                self.failLoading(error)
            }
        }
    }

    private func loadModelWhenReady(markSaved: Bool = false, completion: ((Result<Void, Error>) -> Void)? = nil) {
        guard pageLoaded, webReady, let snapshot else {
            if let completion { loadCompletion = completion }
            return
        }
        guard !modelLoadInFlight else {
            if let completion { loadCompletion = completion }
            return
        }
        modelLoadInFlight = true
        var model = snapshot.model
        model["availableFonts"] = NSFontManager.shared.availableFontFamilies.sorted()
        model["hosted"] = true
        let script = markSaved
            ? """
              const loaded = window.studio.loadDocument(model);
              if (!loaded) return { loaded: false, marked: false, dirty: true };
              const marked = window.studio.markSaved();
              if (failAfterLoad) throw new Error('injected post-load failure');
              const diagnostics = window.studio.getDiagnostics();
              return { loaded: true, marked: marked === true, dirty: diagnostics.dirty === true };
              """
            : "return window.studio.loadDocument(model)"
        let shouldInjectFailure = markSaved && injectPostLoadFailureForTesting
        if shouldInjectFailure { injectPostLoadFailureForTesting = false }
        callStudio(script, arguments: ["model": model, "failAfterLoad": shouldInjectFailure]) { [weak self] result in
            guard let self else { return }
            self.modelLoadInFlight = false
            switch result {
            case .success(let value):
                let loaded = markSaved
                    ? ((value as? [String: Any])?["loaded"] as? Bool) == true
                    : (value as? Bool) == true
                let cleanRebase = !markSaved || (
                    ((value as? [String: Any])?["marked"] as? Bool) == true
                    && ((value as? [String: Any])?["dirty"] as? Bool) == false
                )
                guard loaded, cleanRebase else {
                    let error = StudioError.message("웹 편집기가 문서 시각 모델을 거부했습니다.")
                    self.failLoading(error)
                    completion?(.failure(error))
                    self.loadCompletion?(.failure(error))
                    self.loadCompletion = nil
                    return
                }
                self.modelLoaded = true
                self.isLoading = false
                self.statusLabel.stringValue = "페이지에서 문단이나 표 셀을 눌러 편집하세요."
                self.updateBusyUI()
                if markSaved {
                    self.setDirty(false)
                }
                completion?(.success(()))
                self.loadCompletion?(.success(()))
                self.loadCompletion = nil
            case .failure(let error):
                self.failLoading(error)
                completion?(.failure(error))
                self.loadCompletion?(.failure(error))
                self.loadCompletion = nil
            }
        }
    }

    private func failLoading(_ error: Error) {
        isLoading = false
        modelLoaded = false
        updateBusyUI()
        statusLabel.stringValue = error.localizedDescription
        statusLabel.textColor = .systemRed
    }

    private func callStudio(
        _ body: String,
        arguments: [String: Any],
        completion: @escaping (Result<Any?, Error>) -> Void
    ) {
        webView.callAsyncJavaScript(body, arguments: arguments, in: nil, in: .page) { result in
            switch result {
            case .success(let value): completion(.success(value))
            case .failure(let error): completion(.failure(error))
            }
        }
    }

    private func collectChanges(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        callStudio("return window.studio.collectChanges()", arguments: [:]) { [weak self] result in
            switch result {
            case .success(let value):
                guard let request = value as? [String: Any], let snapshot = self?.snapshot else {
                    completion(.failure(StudioError.message("웹 편집기에서 저장 요청을 수집하지 못했습니다.")))
                    return
                }
                guard (request["schemaVersion"] as? NSNumber)?.intValue == snapshot.schemaVersion,
                      (request["sourceSha256"] as? String)?.lowercased() == snapshot.sourceSha256 else {
                    completion(.failure(StudioError.message("웹 편집기와 현재 문서의 버전 또는 원본 해시가 일치하지 않습니다. 저장하지 않았습니다.")))
                    return
                }
                completion(.success(request))
            case .failure(let error): completion(.failure(error))
            }
        }
    }

    private func queryLatestDirty(completion: @escaping (Result<Bool, Error>) -> Void) {
        guard pageLoaded, webReady, modelLoaded else {
            completion(.success(isDirty))
            return
        }
        let shouldInjectFailure = injectDiagnosticsFailureForTesting
        if shouldInjectFailure { injectDiagnosticsFailureForTesting = false }
        let script = shouldInjectFailure
            ? "throw new Error('injected diagnostics failure')"
            : "return window.studio.getDiagnostics()"
        callStudio(script, arguments: [:]) { [weak self] result in
            guard let self else {
                completion(.failure(StudioError.message("편집 창이 닫혀 변경 상태를 확인할 수 없습니다.")))
                return
            }
            switch result {
            case .success(let value):
                guard let diagnostics = value as? [String: Any] else {
                    completion(.failure(StudioError.message("웹 편집기의 변경 상태 응답이 올바르지 않습니다.")))
                    return
                }
                let dirty = (diagnostics["dirty"] as? Bool) == true
                    || (diagnostics["composing"] as? Bool) == true
                self.setDirty(dirty)
                completion(.success(dirty))
            case .failure(let error):
                completion(.failure(error))
            }
        }
    }

    private func setDirty(_ dirty: Bool) {
        isDirty = dirty
        window?.isDocumentEdited = dirty
        saveButton.title = dirty ? "수정본 저장…" : "다른 이름으로 저장…"
    }

    private func updateBusyUI(message: String? = nil) {
        let busy = hasActiveOperation
        if busy { progress.startAnimation(nil) } else { progress.stopAnimation(nil) }
        saveButton.isEnabled = modelLoaded && !busy
        pdfButton.isEnabled = modelLoaded && !busy
        fallbackButton.isEnabled = !busy
        if let message { statusLabel.stringValue = message }
        callStudio("return window.studio.setBusy(busy)", arguments: ["busy": busy]) { _ in }
    }

    @objc private func saveAs() {
        presentSavePanel(closeAfterSave: false)
    }

    @objc private func previewPDF() {
        preparePDF { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let pdf):
                do {
                    let preview = try StudioPDFPreviewWindowController(
                        result: pdf,
                        suggestedFilename: self.activeURL.deletingPathExtension().lastPathComponent + ".pdf",
                        initialDirectoryURL: self.activeURL.deletingLastPathComponent()
                    )
                    self.pdfPreview?.close()
                    self.pdfPreview = preview
                    preview.showWindow(nil)
                    preview.window?.makeKeyAndOrderFront(nil)
                } catch { self.showError(error) }
            case .failure(let error): self.showError(error)
            }
        }
    }

    private func preparePDF(completion: @escaping (Result<StudioPDFResult, Error>) -> Void) {
        guard modelLoaded, !hasActiveOperation else {
            completion(.failure(StudioError.message("문서 읽기나 저장이 끝난 뒤 PDF를 만들어 주세요.")))
            return
        }
        // The renderer commits the current input before taking an immutable snapshot.
        // Do not set its busy flag until that commit has completed.
        isExportingPDF = true
        saveButton.isEnabled = false
        pdfButton.isEnabled = false
        fallbackButton.isEnabled = false
        progress.startAnimation(nil)
        statusLabel.stringValue = "출력할 페이지와 잘림 여부를 확인하는 중…"
        let snapshotScript = """
        try { return await window.studio.preparePDFSnapshot(); }
        catch (error) { return { preparationError: String(error.message || error) }; }
        """
        callStudio(snapshotScript, arguments: [:]) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error): self.finishPDF(.failure(error), completion: completion)
            case .success(let value):
                guard let snapshot = value as? [String: Any] else {
                    self.finishPDF(.failure(StudioError.message("PDF 출력용 문서를 준비하지 못했습니다.")), completion: completion)
                    return
                }
                if let message = snapshot["preparationError"] as? String {
                    self.finishPDF(.failure(StudioError.message(message)), completion: completion)
                    return
                }
                self.updateBusyUI(message: "PDF 미리보기를 만드는 중…")
                let exporter = StudioPDFExporter(resourcesURL: self.resourcesURL)
                self.pdfExporter = exporter
                exporter.generate(snapshot: snapshot) { [weak self] outcome in
                    self?.finishPDF(outcome, completion: completion)
                }
            }
        }
    }

    private func finishPDF(_ result: Result<StudioPDFResult, Error>, completion: @escaping (Result<StudioPDFResult, Error>) -> Void) {
        pdfExporter = nil
        isExportingPDF = false
        updateBusyUI()
        switch result {
        case .success(let pdf):
            statusLabel.stringValue = "\(pdf.pageCount)쪽 PDF 준비 완료 · HWPX 변경 내용은 별도로 저장하세요."
            statusLabel.textColor = .secondaryLabelColor
        case .failure:
            statusLabel.stringValue = "PDF를 만들지 못했습니다. 편집 내용은 유지됩니다."
            statusLabel.textColor = .systemRed
        }
        completion(result)
    }

    private func presentSavePanel(closeAfterSave: Bool, completion: ((Bool) -> Void)? = nil) {
        guard !hasActiveOperation, let window else { completion?(false); return }
        let panel = NSSavePanel()
        panel.title = "수정한 HWPX 문서 저장"
        panel.prompt = "저장"
        panel.message = "같은 이름을 선택하고 대치를 승인하면 기존 파일을 바꿉니다."
        panel.directoryURL = activeURL.deletingLastPathComponent()
        panel.nameFieldStringValue = "\(activeURL.deletingPathExtension().lastPathComponent)-수정본.hwpx"
        if let type = UTType(filenameExtension: "hwpx") { panel.allowedContentTypes = [type] }
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self else { completion?(false); return }
            guard response == .OK, let outputURL = panel.url else {
                self.closeAfterSave = false
                completion?(false)
                return
            }
            self.closeAfterSave = closeAfterSave
            self.save(to: outputURL, replacing: FileManager.default.fileExists(atPath: outputURL.path), presentsUI: true) { result in completion?(result.isSuccess) }
        }
    }

    private func save(
        to outputURL: URL,
        replacing: Bool = false,
        presentsUI: Bool,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        guard !hasActiveOperation else {
            completion(.failure(StudioError.message("다른 문서 작업이 진행 중입니다.")))
            return
        }

        collectChanges { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                if presentsUI { self.showError(error) }
                completion(.failure(error))
            case .success(let request):
                self.isSaving = true
                self.updateBusyUI(message: "수정본을 저장하는 중…")
                self.engine.saveVisual(inputURL: self.activeURL, outputURL: outputURL, request: request, replacing: replacing) { [weak self] saveResult in
                    guard let self else { return }
                    switch saveResult {
                    case .failure(let error):
                        self.isSaving = false
                        self.updateBusyUI()
                        self.statusLabel.stringValue = "저장 실패 — 편집 내용은 유지됩니다."
                        if presentsUI { self.showError(error) }
                        completion(.failure(error))
                        self.closeAfterSave = false
                    case .success(let savedURL):
                        self.reloadAfterSave(savedURL, presentsUI: presentsUI, completion: completion)
                    }
                }
            }
        }
    }

    private func reloadAfterSave(
        _ savedURL: URL,
        presentsUI: Bool,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        engine.visualSnapshot(inputURL: savedURL) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.isSaving = false
                self.updateBusyUI()
                let wrapped = StudioError.message("파일은 저장했지만 다시 읽지 못했습니다: \(savedURL.path)\n\(error.localizedDescription)\n현재 편집 내용은 유지됩니다.")
                self.statusLabel.stringValue = "저장 파일 재확인 실패 — 편집 내용 유지"
                if presentsUI { self.showError(wrapped) }
                completion(.failure(wrapped))
                self.closeAfterSave = false
            case .success(let newSnapshot):
                self.captureWebState { captureResult in
                    guard case .success(let rollbackToken) = captureResult else {
                        let captureError: Error
                        if case .failure(let error) = captureResult {
                            captureError = error
                        } else {
                            captureError = StudioError.message("현재 편집 초안을 백업하지 못했습니다.")
                        }
                        self.isSaving = false
                        self.updateBusyUI()
                        let wrapped = StudioError.message("파일은 저장했지만 화면 재로딩 준비에 실패했습니다: \(savedURL.path)\n\(captureError.localizedDescription)\n현재 편집 내용은 유지됩니다.")
                        if presentsUI { self.showError(wrapped) }
                        completion(.failure(wrapped))
                        self.closeAfterSave = false
                        return
                    }
                    let previousSnapshot = self.snapshot
                    self.snapshot = newSnapshot
                    self.loadModelWhenReady(markSaved: true) { loadResult in
                    switch loadResult {
                    case .failure(let error):
                        self.restoreWebState(rollbackToken) { restored in
                            if restored {
                                self.snapshot = previousSnapshot
                                self.modelLoaded = true
                                self.isLoading = false
                                self.setDirty((rollbackToken["dirty"] as? Bool) == true)
                            }
                            self.isSaving = false
                            self.updateBusyUI()
                            let suffix = restored
                                ? "현재 편집 내용은 복구되어 유지됩니다."
                                : "화면 초안을 자동 복구하지 못했습니다. 저장된 파일을 다시 여세요."
                            let wrapped = StudioError.message("파일은 저장했지만 화면을 새 기준으로 바꾸지 못했습니다: \(savedURL.path)\n\(error.localizedDescription)\n\(suffix)")
                            self.statusLabel.stringValue = restored
                                ? "저장 파일 화면 재로딩 실패 — 편집 내용 복구됨"
                                : "저장 완료 · 화면 초안 복구 실패"
                            if presentsUI { self.showError(wrapped) }
                            completion(.failure(wrapped))
                            self.closeAfterSave = false
                        }
                    case .success:
                        self.activeURL = savedURL
                        self.lastSavedURL = savedURL
                        self.isSaving = false
                        self.window?.representedURL = savedURL
                        self.window?.title = "Document Studio — \(savedURL.lastPathComponent)"
                        self.filenameLabel.stringValue = savedURL.lastPathComponent
                        self.updateBusyUI()
                        self.statusLabel.stringValue = "저장 완료: \(savedURL.lastPathComponent)"
                        self.statusLabel.textColor = .systemGreen
                        self.onSaved?(savedURL)
                        completion(.success(savedURL))
                        if self.closeAfterSave {
                            self.allowClose = true
                            self.window?.performClose(nil)
                        } else if presentsUI {
                            self.showSaveSuccess(savedURL)
                        }
                        self.closeAfterSave = false
                    }
                }
                }
            }
        }
    }

    private func captureWebState(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        callStudio("return window.studio.captureState()", arguments: [:]) { result in
            switch result {
            case .success(let value):
                guard let token = value as? [String: Any] else {
                    completion(.failure(StudioError.message("웹 편집 초안 백업이 객체가 아닙니다.")))
                    return
                }
                completion(.success(token))
            case .failure(let error): completion(.failure(error))
            }
        }
    }

    private func restoreWebState(_ token: [String: Any], completion: @escaping (Bool) -> Void) {
        callStudio("return window.studio.restoreState(token)", arguments: ["token": token]) { result in
            guard case .success(let value) = result else { completion(false); return }
            completion((value as? Bool) == true)
        }
    }

    private func showSaveSuccess(_ url: URL) {
        guard let window else { return }
        let alert = NSAlert()
        alert.alertStyle = .informational
        alert.messageText = "수정본을 저장했습니다"
        alert.informativeText = url.path
        alert.addButton(withTitle: "Finder에서 보기")
        alert.addButton(withTitle: "완료")
        alert.beginSheetModal(for: window) { response in
            if response == .alertFirstButtonReturn {
                NSWorkspace.shared.activateFileViewerSelecting([url])
            }
        }
    }

    private func showError(_ error: Error) {
        guard let window else { return }
        let alert = NSAlert()
        alert.alertStyle = .warning
        alert.messageText = "문서 작업을 완료하지 못했습니다"
        alert.informativeText = error.localizedDescription
        alert.addButton(withTitle: "확인")
        alert.beginSheetModal(for: window)
    }

    @objc private func openFallbackEditor() {
        guard !hasActiveOperation else { return }
        queryLatestDirty { [weak self] result in
            guard let self else { return }
            guard case .success(let dirty) = result else {
                self.statusLabel.stringValue = "변경 상태를 확인하지 못해 목록식 편집기를 열지 않았습니다."
                self.statusLabel.textColor = .systemRed
                NSSound.beep()
                return
            }
            guard !dirty else {
                self.statusLabel.stringValue = "목록식 편집기를 열기 전에 현재 변경 내용을 저장하거나 버려 주세요."
                NSSound.beep()
                return
            }
            self.onFallback?(self.activeURL)
        }
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

    private func resolveCloseRequest(completion: @escaping (Bool) -> Void) {
        guard !hasActiveOperation else { completion(false); return }
        queryLatestDirty { [weak self] result in
            guard let self else { completion(false); return }
            guard case .success(let dirty) = result else {
                self.statusLabel.stringValue = "변경 상태를 확인하지 못해 닫기를 취소했습니다."
                self.statusLabel.textColor = .systemRed
                NSSound.beep()
                completion(false)
                return
            }
            guard dirty, let window = self.window else { completion(true); return }
            self.makeUnsavedChangesAlert().beginSheetModal(for: window) { response in
                switch response {
                case .alertFirstButtonReturn:
                    self.presentSavePanel(closeAfterSave: false, completion: completion)
                case .alertSecondButtonReturn:
                    completion(true)
                default:
                    completion(false)
                }
            }
        }
    }

    func confirmBeforeClosing(completion: @escaping (Bool) -> Void) {
        resolveCloseRequest(completion: completion)
    }

    // MARK: - Repository-owned integration test surface

    func waitUntilLoadedForTesting(completion: @escaping (Result<Void, Error>) -> Void) {
        if modelLoaded { completion(.success(())) }
        else if !isLoading { completion(.failure(StudioError.message(statusLabel.stringValue))) }
        else { loadCompletion = completion }
    }

    func callStudioForTesting(
        _ body: String,
        arguments: [String: Any] = [:],
        completion: @escaping (Result<Any?, Error>) -> Void
    ) {
        callStudio(body, arguments: arguments, completion: completion)
    }

    func diagnosticsForTesting(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        callStudio("return window.studio.getDiagnostics()", arguments: [:]) { result in
            switch result {
            case .success(let value):
                guard let dictionary = value as? [String: Any] else {
                    completion(.failure(StudioError.message("진단 결과가 객체가 아닙니다.")))
                    return
                }
                completion(.success(dictionary))
            case .failure(let error): completion(.failure(error))
            }
        }
    }

    func collectChangesForTesting(completion: @escaping (Result<[String: Any], Error>) -> Void) {
        collectChanges(completion: completion)
    }

    func saveForTesting(to outputURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        save(to: outputURL, presentsUI: false, completion: completion)
    }

    func preparePDFForTesting(completion: @escaping (Result<StudioPDFResult, Error>) -> Void) {
        preparePDF(completion: completion)
    }

    func injectPostLoadFailureOnceForTesting() {
        injectPostLoadFailureForTesting = true
    }

    func injectDiagnosticsFailureOnceForTesting() {
        injectDiagnosticsFailureForTesting = true
    }

    func snapshotForTesting(configuration: WKSnapshotConfiguration, completion: @escaping (Result<NSImage, Error>) -> Void) {
        webView.takeSnapshot(with: configuration) { image, error in
            if let image {
                completion(.success(image))
            } else {
                completion(.failure(error ?? StudioError.message("WebKit 화면 이미지를 만들지 못했습니다.")))
            }
        }
    }

    func externalNavigationBlockedForTesting(
        _ url: URL,
        completion: @escaping (Bool) -> Void
    ) {
        let originalURL = webView.url?.standardizedFileURL
        webView.load(URLRequest(url: url))
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
            guard let self else { completion(false); return }
            completion(self.webView.url?.standardizedFileURL == originalURL)
        }
    }
}

extension VisualDocumentEditorWindowController: WKScriptMessageHandler {
    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "studio", let payload = message.body as? [String: Any], let kind = payload["kind"] as? String else { return }
        switch kind {
        case "ready":
            webReady = true
            loadModelWhenReady()
        case "dirty":
            if let dirty = payload["dirty"] as? Bool { setDirty(dirty) }
        case "save":
            saveAs()
        case "fallback":
            openFallbackEditor()
        case "status":
            if let text = payload["message"] as? String ?? payload["text"] as? String {
                statusLabel.stringValue = text
                statusLabel.textColor = .secondaryLabelColor
            }
        case "error":
            if let text = payload["message"] as? String ?? payload["text"] as? String {
                statusLabel.stringValue = text
                statusLabel.textColor = .systemRed
            }
        default:
            break
        }
    }
}

extension VisualDocumentEditorWindowController: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        pageLoaded = true
        loadModelWhenReady()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        failLoading(error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        failLoading(error)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard navigationAction.targetFrame?.isMainFrame == true,
              let url = navigationAction.request.url,
              url.isFileURL,
              url.standardizedFileURL == visualEditorDirectory.appendingPathComponent("index.html").standardizedFileURL else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(.allow)
    }

    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        nil
    }
}

extension VisualDocumentEditorWindowController: NSWindowDelegate {
    func windowShouldClose(_ sender: NSWindow) -> Bool {
        if allowClose { return true }
        guard !hasActiveOperation else {
            statusLabel.stringValue = isExportingPDF ? "PDF 준비가 끝난 뒤 창을 닫아 주세요." : (isSaving ? "저장이 끝난 뒤 창을 닫아 주세요." : "문서를 불러온 뒤 창을 닫아 주세요.")
            NSSound.beep()
            return false
        }
        guard !closeCheckInFlight else { return false }
        closeCheckInFlight = true
        resolveCloseRequest { [weak self] allowed in
            guard let self else { return }
            self.closeCheckInFlight = false
            if allowed {
                self.allowClose = true
                sender.performClose(nil)
            }
        }
        return false
    }
}

private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?

    init(target: WKScriptMessageHandler? = nil) {
        self.target = target
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

private extension Result {
    var isSuccess: Bool {
        if case .success = self { return true }
        return false
    }
}
