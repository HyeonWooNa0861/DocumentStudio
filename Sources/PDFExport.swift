import AppKit
import PDFKit
import UniformTypeIdentifiers
import WebKit

struct StudioPDFPageSize: Equatable {
    let widthPt: CGFloat
    let heightPt: CGFloat
}

struct StudioPDFResult {
    let data: Data
    let warnings: [String]
    let pageCount: Int
}

private struct StudioPDFSnapshot {
    static let maximumPageCount = 2_000
    static let maximumPageDimension: CGFloat = 14_400

    let bodyHTML: String
    let pageSizes: [StudioPDFPageSize]
    let warnings: [String]
    let exportCSS: String

    init(dictionary: [String: Any]) throws {
        guard let bodyHTML = dictionary["bodyHTML"] as? String, !bodyHTML.isEmpty else {
            throw StudioError.message("PDF로 변환할 문서 내용을 받지 못했습니다.")
        }
        guard let rawPages = dictionary["pages"] as? [[String: Any]],
              !rawPages.isEmpty,
              rawPages.count <= Self.maximumPageCount else {
            throw StudioError.message("PDF 페이지 수가 올바르지 않습니다.")
        }
        let blockingIssues = dictionary["blockingIssues"] as? [String] ?? []
        guard blockingIssues.isEmpty else {
            throw StudioError.message("PDF 출력을 진행할 수 없습니다.\n" + blockingIssues.joined(separator: "\n"))
        }

        self.pageSizes = try rawPages.enumerated().map { index, page in
            guard let width = Self.number(page["widthPt"]),
                  let height = Self.number(page["heightPt"]),
                  width.isFinite,
                  height.isFinite,
                  width > 0,
                  height > 0,
                  width <= Self.maximumPageDimension,
                  height <= Self.maximumPageDimension else {
                throw StudioError.message("PDF \(index + 1)쪽의 용지 크기가 올바르지 않습니다.")
            }
            return StudioPDFPageSize(widthPt: width, heightPt: height)
        }
        self.bodyHTML = bodyHTML
        self.warnings = dictionary["warnings"] as? [String] ?? []
        self.exportCSS = dictionary["exportCSS"] as? String ?? ""
    }

    private static func number(_ value: Any?) -> CGFloat? {
        if let number = value as? NSNumber { return CGFloat(number.doubleValue) }
        if let number = value as? Double { return CGFloat(number) }
        if let number = value as? Int { return CGFloat(number) }
        return nil
    }
}

private struct StudioPDFLayoutPage {
    let rect: CGRect
    let clipped: Bool
}

private struct StudioPDFLayout {
    let pages: [StudioPDFLayoutPage]
    let incompleteAssets: [String]
    let textCharacterCount: Int
}

final class StudioPDFExporter: NSObject {
    private enum State {
        case idle
        case loading
        case rendering
        case finished
    }

    private let visualEditorDirectory: URL
    private var state: State = .idle
    private var snapshot: StudioPDFSnapshot?
    private var completion: ((Result<StudioPDFResult, Error>) -> Void)?
    private var webView: WKWebView?
    private var renderWindow: NSWindow?
    private var pagePDFs: [Data] = []
    private var layout: StudioPDFLayout?
    private var timeoutWorkItem: DispatchWorkItem?
    private var selfRetainer: StudioPDFExporter?
    private var initialNavigationAllowed = false

    init(resourcesURL: URL? = nil) {
        let resources = resourcesURL ?? Bundle.main.resourceURL ?? Bundle.main.bundleURL
        self.visualEditorDirectory = resources.appendingPathComponent("VisualEditor", isDirectory: true)
        super.init()
    }

    func generate(
        snapshot dictionary: [String: Any],
        completion: @escaping (Result<StudioPDFResult, Error>) -> Void
    ) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in
                guard let self else {
                    completion(.failure(StudioError.message("PDF 내보내기 작업을 시작하지 못했습니다.")))
                    return
                }
                self.generate(snapshot: dictionary, completion: completion)
            }
            return
        }
        guard case .idle = state else {
            completion(.failure(StudioError.message("PDF 내보내기 작업이 이미 진행 중입니다.")))
            return
        }

        self.completion = completion
        self.selfRetainer = self
        self.state = .loading
        do {
            let parsed = try StudioPDFSnapshot(dictionary: dictionary)
            let stylesheetURL = visualEditorDirectory.appendingPathComponent("studio.css")
            guard FileManager.default.fileExists(atPath: stylesheetURL.path) else {
                throw StudioError.message("PDF 출력용 VisualEditor/studio.css를 찾을 수 없습니다.")
            }
            let studioCSS = try String(contentsOf: stylesheetURL, encoding: .utf8)
            self.snapshot = parsed
            self.initialNavigationAllowed = false
            try beginLoading(snapshot: parsed, studioCSS: studioCSS)
            scheduleTimeout()
        } catch {
            finish(.failure(error))
        }
    }

    private func beginLoading(snapshot: StudioPDFSnapshot, studioCSS: String) throws {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false

        let maximumWidth = snapshot.pageSizes.map { $0.widthPt * 96.0 / 72.0 }.max() ?? 800
        let maximumHeight = snapshot.pageSizes.map { $0.heightPt * 96.0 / 72.0 }.max() ?? 1_000
        let frame = NSRect(x: 0, y: 0, width: ceil(maximumWidth), height: ceil(maximumHeight))
        let webView = WKWebView(frame: frame, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.underPageBackgroundColor = .white

        let window = NSWindow(
            contentRect: frame,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )
        window.isReleasedWhenClosed = false
        // Keep the backing layer composited while rendering off screen. WebKit can
        // omit asynchronously decoded raster images from createPDF when every
        // ancestor window is fully transparent, even though text still prints.
        window.alphaValue = 0.01
        window.ignoresMouseEvents = true
        window.contentView = webView
        window.orderBack(nil)

        self.webView = webView
        self.renderWindow = window
        let html = Self.makeHTML(snapshot: snapshot, studioCSS: studioCSS)
        webView.loadHTMLString(html, baseURL: visualEditorDirectory)
    }

    private static func makeHTML(snapshot: StudioPDFSnapshot, studioCSS: String) -> String {
        let pageRules = snapshot.pageSizes.enumerated().map { index, size in
            let width = size.widthPt * 96.0 / 72.0
            let height = size.heightPt * 96.0 / 72.0
            return "body > [data-pdf-page]:nth-child(\(index + 1)) { width: \(width)px !important; height: \(height)px !important; }"
        }.joined(separator: "\n")
        return """
        <!doctype html>
        <html lang="ko">
        <head>
          <meta charset="utf-8">
          <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob: file:; font-src data: file:; style-src 'unsafe-inline' file:; object-src 'none'; frame-src 'none'; connect-src 'none'; media-src 'none'">
          <style>
          \(studioCSS)
          \(snapshot.exportCSS)
          html, body { margin: 0 !important; padding: 0 !important; background: white !important; }
          body { display: flex !important; flex-direction: column !important; align-items: flex-start !important; gap: 0 !important; zoom: 1 !important; }
          body > [data-pdf-page] { flex: none !important; margin: 0 !important; box-sizing: border-box !important; overflow: hidden !important; }
          \(pageRules)
          </style>
        </head>
        <body>\(snapshot.bodyHTML)</body>
        </html>
        """
    }

    private func scheduleTimeout() {
        let workItem = DispatchWorkItem { [weak self] in
            guard let self, self.state != .finished else { return }
            self.finish(.failure(StudioError.message("PDF 출력 준비가 제한 시간 안에 끝나지 않았습니다.")))
        }
        timeoutWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + 45, execute: workItem)
    }

    private func inspectLayout() {
        guard let webView else {
            finish(.failure(StudioError.message("PDF 출력 화면을 준비하지 못했습니다.")))
            return
        }
        let script = """
        return (async () => {
          if (document.fonts && document.fonts.ready) await document.fonts.ready;
          const images = Array.from(document.images);
          const failedImageDecodes = new Set();
          await Promise.all(images.map(async (image, index) => {
            if (!image.complete) {
              await new Promise((resolve) => {
                image.addEventListener('load', resolve, { once: true });
                image.addEventListener('error', resolve, { once: true });
              });
            }
            if (image.naturalWidth > 0 && image.naturalHeight > 0 && typeof image.decode === 'function') {
              try { await image.decode(); } catch (_) { failedImageDecodes.add(index); }
            }
          }));
          document.body.getBoundingClientRect();
          await new Promise((resolve) => setTimeout(resolve, 0));
          const children = Array.from(document.body.children);
          const pages = children.filter((node) => node.hasAttribute('data-pdf-page'));
          const incompleteImageNames = images.flatMap((image, index) =>
            (!image.complete || image.naturalWidth <= 0 || image.naturalHeight <= 0 || failedImageDecodes.has(index))
              ? [`그림 ${index + 1}`]
              : []
          );
          const failedFontNames = document.fonts
            ? Array.from(document.fonts)
                .filter((font) => font.status === 'error')
                .map((font) => String(font.family || '알 수 없는 글꼴').slice(0, 80))
            : [];
          const exceedsPage = (rect, pageRect) =>
            rect.width > 0 && rect.height > 0 && (
              rect.left < pageRect.left - 1 || rect.top < pageRect.top - 1 ||
              rect.right > pageRect.right + 1 || rect.bottom > pageRect.bottom + 1
            );
          const hasOutOfBoundsContent = (page, pageRect) => {
            const elements = Array.from(page.querySelectorAll('*'));
            for (const element of elements) {
              const style = getComputedStyle(element);
              if (style.display === 'none' || style.visibility === 'hidden') continue;
              for (const rect of Array.from(element.getClientRects())) {
                if (exceedsPage(rect, pageRect)) return true;
              }
            }
            const walker = document.createTreeWalker(page, NodeFilter.SHOW_TEXT);
            let node;
            while ((node = walker.nextNode())) {
              if (!(node.nodeValue || '').trim()) continue;
              const parentStyle = node.parentElement ? getComputedStyle(node.parentElement) : null;
              if (parentStyle && (parentStyle.display === 'none' || parentStyle.visibility === 'hidden')) continue;
              const range = document.createRange();
              range.selectNodeContents(node);
              for (const rect of Array.from(range.getClientRects())) {
                if (exceedsPage(rect, pageRect)) return true;
              }
            }
            return false;
          };
          return {
            childCount: children.length,
            pageCount: pages.length,
            incompleteAssets: incompleteImageNames.slice(0, 20),
            incompleteAssetCount: incompleteImageNames.length,
            failedFonts: failedFontNames.slice(0, 20),
            failedFontCount: failedFontNames.length,
            textCharacterCount: (document.body.innerText || '').trim().length,
            pages: pages.map((page) => {
              const rect = page.getBoundingClientRect();
              const contentOutOfBounds = hasOutOfBoundsContent(page, rect);
              return {
                x: rect.left + window.scrollX,
                y: rect.top + window.scrollY,
                width: rect.width,
                height: rect.height,
                clipped: page.scrollWidth > page.clientWidth + 1 || page.scrollHeight > page.clientHeight + 1 || contentOutOfBounds
              };
            })
          };
        })();
        """
        webView.callAsyncJavaScript(script, arguments: [:], in: nil, in: .page) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.finish(.failure(StudioError.message("PDF 페이지 배치를 확인하지 못했습니다: \(error.localizedDescription)")))
            case .success(let value):
                do {
                    let layout = try self.parseLayout(value)
                    self.layout = layout
                    self.state = .rendering
                    self.renderPage(at: 0)
                } catch {
                    self.finish(.failure(error))
                }
            }
        }
    }

    private func parseLayout(_ value: Any?) throws -> StudioPDFLayout {
        guard let snapshot,
              let dictionary = value as? [String: Any],
              let rawPages = dictionary["pages"] as? [[String: Any]],
              let pageCount = (dictionary["pageCount"] as? NSNumber)?.intValue,
              let childCount = (dictionary["childCount"] as? NSNumber)?.intValue,
              pageCount == snapshot.pageSizes.count,
              childCount == pageCount,
              rawPages.count == pageCount else {
            throw StudioError.message("PDF 페이지 구조가 준비된 문서와 일치하지 않습니다.")
        }
        let incompleteAssets = dictionary["incompleteAssets"] as? [String] ?? []
        let failedFonts = dictionary["failedFonts"] as? [String] ?? []
        let incompleteAssetCount = (dictionary["incompleteAssetCount"] as? NSNumber)?.intValue ?? incompleteAssets.count
        let failedFontCount = (dictionary["failedFontCount"] as? NSNumber)?.intValue ?? failedFonts.count
        var incompleteResources = incompleteAssets + failedFonts
        let hiddenResourceCount = max(0, incompleteAssetCount - incompleteAssets.count) + max(0, failedFontCount - failedFonts.count)
        if hiddenResourceCount > 0 {
            incompleteResources.append("그 밖에 \(hiddenResourceCount)개")
        }
        guard incompleteResources.isEmpty else {
            throw StudioError.message("PDF에 포함할 이미지나 글꼴을 불러오지 못했습니다.\n" + incompleteResources.joined(separator: "\n"))
        }
        let pages = try rawPages.enumerated().map { index, rawPage in
            guard let x = Self.cgFloat(rawPage["x"]),
                  let y = Self.cgFloat(rawPage["y"]),
                  let width = Self.cgFloat(rawPage["width"]),
                  let height = Self.cgFloat(rawPage["height"]),
                  x.isFinite,
                  y.isFinite,
                  width.isFinite,
                  height.isFinite,
                  width > 0,
                  height > 0 else {
                throw StudioError.message("PDF \(index + 1)쪽의 화면 배치를 측정하지 못했습니다.")
            }
            let expected = snapshot.pageSizes[index]
            let expectedWidth = expected.widthPt * 96.0 / 72.0
            let expectedHeight = expected.heightPt * 96.0 / 72.0
            guard abs(width - expectedWidth) <= 1.0,
                  abs(height - expectedHeight) <= 1.0 else {
                throw StudioError.message("PDF \(index + 1)쪽의 화면 크기와 용지 크기가 일치하지 않습니다.")
            }
            return StudioPDFLayoutPage(
                rect: CGRect(x: x, y: y, width: width, height: height),
                clipped: (rawPage["clipped"] as? Bool) == true
            )
        }
        if let clippedIndex = pages.firstIndex(where: { $0.clipped }) {
            throw StudioError.message("PDF \(clippedIndex + 1)쪽에서 내용 잘림이 감지되었습니다. 출력하지 않았습니다.")
        }
        return StudioPDFLayout(
            pages: pages,
            incompleteAssets: incompleteAssets,
            textCharacterCount: (dictionary["textCharacterCount"] as? NSNumber)?.intValue ?? 0
        )
    }

    private static func cgFloat(_ value: Any?) -> CGFloat? {
        guard let number = value as? NSNumber else { return nil }
        return CGFloat(number.doubleValue)
    }

    private func renderPage(at index: Int) {
        guard let webView, let snapshot, let layout else {
            finish(.failure(StudioError.message("PDF 페이지 렌더링 상태가 올바르지 않습니다.")))
            return
        }
        guard index < layout.pages.count else {
            completeDocument(snapshot: snapshot, layout: layout)
            return
        }
        let configuration = WKPDFConfiguration()
        configuration.rect = layout.pages[index].rect
        webView.createPDF(configuration: configuration) { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure(let error):
                self.finish(.failure(StudioError.message("PDF \(index + 1)쪽을 만들지 못했습니다: \(error.localizedDescription)")))
            case .success(let data):
                do {
                    guard let document = CGPDFDocument(CGDataProvider(data: data as CFData)!),
                          document.numberOfPages == 1 else {
                        throw StudioError.message("PDF \(index + 1)쪽의 렌더링 결과가 올바르지 않습니다.")
                    }
                    self.pagePDFs.append(data)
                    self.renderPage(at: index + 1)
                } catch {
                    self.finish(.failure(error))
                }
            }
        }
    }

    private func completeDocument(snapshot: StudioPDFSnapshot, layout: StudioPDFLayout) {
        do {
            let data = try Self.composePDF(pagePDFs: pagePDFs, pageSizes: snapshot.pageSizes)
            guard let document = PDFDocument(data: data), document.pageCount == snapshot.pageSizes.count else {
                throw StudioError.message("완성된 PDF의 페이지 수를 확인하지 못했습니다.")
            }
            if layout.textCharacterCount > 0 {
                let searchable = document.string?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !searchable.isEmpty else {
                    throw StudioError.message("PDF에서 문자를 검색하거나 복사할 수 없어 출력을 중단했습니다.")
                }
            }
            finish(.success(StudioPDFResult(data: data, warnings: snapshot.warnings, pageCount: document.pageCount)))
        } catch {
            finish(.failure(error))
        }
    }

    static func composePDF(pagePDFs: [Data], pageSizes: [StudioPDFPageSize]) throws -> Data {
        guard !pagePDFs.isEmpty, pagePDFs.count == pageSizes.count else {
            throw StudioError.message("PDF 페이지 데이터와 용지 정보가 일치하지 않습니다.")
        }
        let output = NSMutableData()
        guard let consumer = CGDataConsumer(data: output as CFMutableData),
              let context = CGContext(consumer: consumer, mediaBox: nil, nil) else {
            throw StudioError.message("PDF 문서를 만들기 위한 출력 공간을 준비하지 못했습니다.")
        }

        for (index, pair) in zip(pagePDFs, pageSizes).enumerated() {
            let (data, size) = pair
            guard let provider = CGDataProvider(data: data as CFData),
                  let document = CGPDFDocument(provider),
                  document.numberOfPages == 1,
                  let page = document.page(at: 1) else {
                throw StudioError.message("PDF \(index + 1)쪽의 데이터를 읽지 못했습니다.")
            }
            let source = page.getBoxRect(.mediaBox)
            guard source.width.isFinite,
                  source.height.isFinite,
                  source.width > 0,
                  source.height > 0 else {
                throw StudioError.message("PDF \(index + 1)쪽의 렌더링 크기가 올바르지 않습니다.")
            }
            let sourceRatio = source.width / source.height
            let destinationRatio = size.widthPt / size.heightPt
            guard abs(sourceRatio - destinationRatio) <= 0.01 else {
                throw StudioError.message("PDF \(index + 1)쪽의 화면 비율과 용지 비율이 일치하지 않습니다.")
            }
            var mediaBox = CGRect(x: 0, y: 0, width: size.widthPt, height: size.heightPt)
            let pageInfo = [kCGPDFContextMediaBox as String: NSData(bytes: &mediaBox, length: MemoryLayout<CGRect>.size)] as CFDictionary
            context.beginPDFPage(pageInfo)
            context.saveGState()
            context.scaleBy(x: size.widthPt / source.width, y: size.heightPt / source.height)
            context.translateBy(x: -source.minX, y: -source.minY)
            context.drawPDFPage(page)
            context.restoreGState()
            context.endPDFPage()
        }
        context.closePDF()
        let result = output as Data
        guard !result.isEmpty else {
            throw StudioError.message("완성된 PDF 데이터가 비어 있습니다.")
        }
        return result
    }

    private func finish(_ result: Result<StudioPDFResult, Error>) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { [weak self] in self?.finish(result) }
            return
        }
        guard state != .finished else { return }
        state = .finished
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        webView?.stopLoading()
        webView?.navigationDelegate = nil
        webView?.uiDelegate = nil
        renderWindow?.orderOut(nil)
        renderWindow?.close()
        renderWindow = nil
        webView = nil
        snapshot = nil
        layout = nil
        pagePDFs.removeAll(keepingCapacity: false)
        let callback = completion
        completion = nil
        callback?(result)
        selfRetainer = nil
    }
}

extension StudioPDFExporter: WKNavigationDelegate, WKUIDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard case .loading = state else { return }
        inspectLayout()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        finish(.failure(StudioError.message("PDF 출력 화면을 불러오지 못했습니다: \(error.localizedDescription)")))
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        finish(.failure(StudioError.message("PDF 출력 화면을 열지 못했습니다: \(error.localizedDescription)")))
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard !initialNavigationAllowed else {
            decisionHandler(.cancel)
            return
        }
        initialNavigationAllowed = true
        guard let url = navigationAction.request.url,
              url.isFileURL || url.scheme == "about" else {
            decisionHandler(.cancel)
            finish(.failure(StudioError.message("PDF 출력 화면에서 외부 주소 접근을 차단했습니다.")))
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

final class StudioPDFPreviewWindowController: NSWindowController {
    private let result: StudioPDFResult
    private let suggestedFilename: String
    private let initialDirectoryURL: URL?
    private let statusLabel = NSTextField(labelWithString: "")
    private let saveButton = NSButton(title: "PDF 저장…", target: nil, action: nil)

    init(
        result: StudioPDFResult,
        suggestedFilename: String,
        initialDirectoryURL: URL? = nil
    ) throws {
        guard let document = PDFDocument(data: result.data), document.pageCount == result.pageCount else {
            throw StudioError.message("미리 볼 PDF 데이터를 읽지 못했습니다.")
        }
        self.result = result
        self.suggestedFilename = suggestedFilename.hasSuffix(".pdf") ? suggestedFilename : suggestedFilename + ".pdf"
        self.initialDirectoryURL = initialDirectoryURL

        let window = NSWindow(
            contentRect: NSRect(x: 0, y: 0, width: 980, height: 760),
            styleMask: [.titled, .closable, .miniaturizable, .resizable],
            backing: .buffered,
            defer: false
        )
        window.title = "PDF 미리보기 — \(self.suggestedFilename)"
        window.minSize = NSSize(width: 700, height: 520)
        window.center()
        super.init(window: window)
        window.isReleasedWhenClosed = false
        buildInterface(document: document)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    private func buildInterface(document: PDFDocument) {
        guard let window else { return }
        let root = NSView(frame: NSRect(origin: .zero, size: window.contentLayoutRect.size))
        root.autoresizingMask = [.width, .height]
        window.contentView = root

        let summary = NSTextField(wrappingLabelWithString: warningSummary)
        summary.font = .systemFont(ofSize: 12)
        summary.textColor = result.warnings.isEmpty ? .secondaryLabelColor : .systemOrange
        summary.maximumNumberOfLines = 3
        summary.toolTip = warningSummary
        let pageLabel = NSTextField(labelWithString: "\(result.pageCount)쪽")
        pageLabel.font = .systemFont(ofSize: 12, weight: .medium)
        pageLabel.textColor = .secondaryLabelColor
        saveButton.target = self
        saveButton.action = #selector(savePDF)
        saveButton.bezelStyle = .rounded
        let header = NSStackView(views: [pageLabel, summary, NSView(), saveButton])
        header.orientation = .horizontal
        header.alignment = .centerY
        header.spacing = 10
        header.edgeInsets = NSEdgeInsets(top: 10, left: 14, bottom: 10, right: 14)

        let pdfView = PDFView()
        pdfView.document = document
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.displaysPageBreaks = true
        pdfView.backgroundColor = .windowBackgroundColor
        pdfView.setAccessibilityLabel("생성된 PDF 미리보기")

        statusLabel.font = .systemFont(ofSize: 12)
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.stringValue = "미리보기 생성 시점의 내용입니다. 수정 후에는 PDF를 다시 생성하세요."

        for view in [header, pdfView, statusLabel] {
            view.translatesAutoresizingMaskIntoConstraints = false
            root.addSubview(view)
        }
        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: root.topAnchor),
            header.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            header.heightAnchor.constraint(greaterThanOrEqualToConstant: 54),
            pdfView.topAnchor.constraint(equalTo: header.bottomAnchor),
            pdfView.leadingAnchor.constraint(equalTo: root.leadingAnchor),
            pdfView.trailingAnchor.constraint(equalTo: root.trailingAnchor),
            pdfView.bottomAnchor.constraint(equalTo: statusLabel.topAnchor),
            statusLabel.leadingAnchor.constraint(equalTo: root.leadingAnchor, constant: 14),
            statusLabel.trailingAnchor.constraint(equalTo: root.trailingAnchor, constant: -14),
            statusLabel.bottomAnchor.constraint(equalTo: root.bottomAnchor, constant: -9),
            statusLabel.heightAnchor.constraint(greaterThanOrEqualToConstant: 18)
        ])
    }

    private var warningSummary: String {
        guard !result.warnings.isEmpty else { return "출력 전 검사에서 발견된 경고가 없습니다." }
        return "경고: " + result.warnings.joined(separator: " · ")
    }

    @objc private func savePDF() {
        guard let window else { return }
        let panel = NSSavePanel()
        panel.title = "PDF 저장"
        panel.prompt = "저장"
        panel.message = "같은 이름을 선택하고 대치를 승인하면 기존 PDF를 바꿉니다."
        panel.allowedContentTypes = [.pdf]
        panel.canCreateDirectories = true
        panel.isExtensionHidden = false
        panel.directoryURL = initialDirectoryURL
        panel.nameFieldStringValue = suggestedFilename
        panel.beginSheetModal(for: window) { [weak self] response in
            guard let self, response == .OK, let outputURL = panel.url else { return }
            do {
                try ApprovedFileSave.write(to: outputURL, replacing: FileManager.default.fileExists(atPath: outputURL.path)) { target in
                    try Self.saveNewPDF(data: self.result.data, to: target)
                }
                self.statusLabel.stringValue = "저장 완료: \(outputURL.path)"
                self.statusLabel.textColor = .systemGreen
            } catch {
                self.statusLabel.stringValue = error.localizedDescription
                self.statusLabel.textColor = .systemRed
                let alert = NSAlert(error: error)
                alert.beginSheetModal(for: window)
            }
        }
    }

    static func saveNewPDF(data: Data, to outputURL: URL) throws {
        guard !data.isEmpty else {
            throw StudioError.message("저장할 PDF 데이터가 비어 있습니다.")
        }
        let directory = outputURL.deletingLastPathComponent()
        guard FileManager.default.fileExists(atPath: directory.path) else {
            throw StudioError.message("PDF를 저장할 폴더가 없습니다.")
        }
        guard !FileManager.default.fileExists(atPath: outputURL.path) else {
            throw StudioError.message("같은 이름의 파일이 이미 있습니다. 기존 파일은 덮어쓸 수 없습니다.")
        }

        let temporaryURL = directory.appendingPathComponent(".HWPXStudio-PDF-\(UUID().uuidString).tmp")
        defer { try? FileManager.default.removeItem(at: temporaryURL) }
        guard FileManager.default.createFile(atPath: temporaryURL.path, contents: data, attributes: [.posixPermissions: 0o600]) else {
            throw StudioError.message("PDF 임시 파일을 만들지 못했습니다.")
        }
        do {
            try FileManager.default.linkItem(at: temporaryURL, to: outputURL)
        } catch {
            if FileManager.default.fileExists(atPath: outputURL.path) {
                throw StudioError.message("같은 이름의 파일이 이미 있습니다. 기존 파일은 덮어쓸 수 없습니다.")
            }
            throw StudioError.message("PDF를 저장하지 못했습니다: \(error.localizedDescription)")
        }
    }
}
