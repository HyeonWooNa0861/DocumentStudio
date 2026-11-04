import AppKit

struct TextBlock: Codable {
    let id: String
    let label: String
    let text: String
    let editable: Bool
    let reason: String?
}

struct DocumentSnapshot: Codable {
    let source: String
    let blocks: [TextBlock]
    let warnings: [String]
}

struct TextEdit: Codable { let id: String; let text: String }
private struct EditRequest: Codable { let edits: [TextEdit] }
private struct OutputResponse: Codable {
    let output: String
    let warnings: [String]?
}

struct ConversionResult {
    let outputURL: URL
    let warnings: [String]
}

struct VisualDocumentSnapshot {
    static let maximumBytes = 64 * 1024 * 1024

    let model: [String: Any]
    let schemaVersion: Int
    let sourceSha256: String
}

enum StudioError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self { case .message(let text): return text }
    }
}

private final class CollectedData {
    private var data = Data()
    private let lock = NSLock()
    func set(_ value: Data) { lock.lock(); data = value; lock.unlock() }
    func get() -> Data { lock.lock(); defer { lock.unlock() }; return data }
}

final class EngineClient {
    private let resources: URL
    private let queue = DispatchQueue(label: "local.nahw.hwpxstudio.engine", qos: .userInitiated)

    init(resourcesURL: URL? = nil) {
        resources = resourcesURL ?? Bundle.main.resourceURL ?? Bundle.main.bundleURL
    }

    private func run(_ arguments: [String]) throws -> Data {
        let java = resources.appendingPathComponent("Runtime/bin/java")
        let engine = resources.appendingPathComponent("Engine")
        guard FileManager.default.isExecutableFile(atPath: java.path),
              FileManager.default.fileExists(atPath: engine.appendingPathComponent("engine.jar").path) else {
            throw StudioError.message("변환 실행 환경을 찾을 수 없습니다. 앱 전체를 함께 보관해 주세요.")
        }
        let process = Process()
        process.executableURL = java
        process.arguments = ["-Xmx768m", "-Dfile.encoding=UTF-8", "-cp",
                             engine.appendingPathComponent("engine.jar").path + ":" + engine.appendingPathComponent("lib/*").path,
                             "local.nahw.hwpxstudio.StudioEngine"] + arguments
        let out = Pipe(), err = Pipe()
        process.standardOutput = out
        process.standardError = err
        try process.run()
        let stderr = CollectedData()
        let readers = DispatchGroup()
        readers.enter()
        DispatchQueue.global(qos: .userInitiated).async {
            stderr.set(err.fileHandleForReading.readDataToEndOfFile())
            readers.leave()
        }
        let stdout = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        readers.wait()
        guard process.terminationStatus == 0 else {
            let detail = String(data: stderr.get(), encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
            throw StudioError.message(detail.flatMap { $0.isEmpty ? nil : $0 } ?? "문서를 처리하지 못했습니다. 파일 형식을 확인해 주세요.")
        }
        return stdout
    }

    private func perform<T>(_ body: @escaping () throws -> T, completion: @escaping (Result<T, Error>) -> Void) {
        queue.async {
            let result = Result { try body() }
            DispatchQueue.main.async { completion(result) }
        }
    }

    func convert(inputURL: URL, outputURL: URL, completion: @escaping (Result<ConversionResult, Error>) -> Void) {
        perform({
            let data = try self.run(["convert", inputURL.path, outputURL.path])
            let result = try JSONDecoder().decode(OutputResponse.self, from: data)
            return ConversionResult(outputURL: URL(fileURLWithPath: result.output), warnings: result.warnings ?? [])
        }, completion: completion)
    }

    func inspect(inputURL: URL, completion: @escaping (Result<DocumentSnapshot, Error>) -> Void) {
        perform({
            try JSONDecoder().decode(DocumentSnapshot.self, from: self.run(["inspect", inputURL.path]))
        }, completion: completion)
    }

    func visualSnapshot(inputURL: URL, completion: @escaping (Result<VisualDocumentSnapshot, Error>) -> Void) {
        perform({
            let data = try self.run(["visual", inputURL.path])
            return try self.validateVisualSnapshot(data)
        }, completion: completion)
    }

    func save(inputURL: URL, outputURL: URL, edits: [TextEdit], replacing: Bool = false, completion: @escaping (Result<URL, Error>) -> Void) {
        perform({
            let request = FileManager.default.temporaryDirectory.appendingPathComponent("HWPXStudio-\(UUID().uuidString).json")
            defer { try? FileManager.default.removeItem(at: request) }
            let data = try JSONEncoder().encode(EditRequest(edits: edits))
            try data.write(to: request, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: request.path)
            try ApprovedFileSave.write(to: outputURL, replacing: replacing) { target in
                _ = try self.run(["edit", inputURL.path, request.path, target.path])
            }
            return outputURL
        }, completion: completion)
    }


    func saveVisual(
        inputURL: URL,
        outputURL: URL,
        request: [String: Any],
        replacing: Bool = false,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        do {
            let data = try JSONSerialization.data(withJSONObject: request, options: [.sortedKeys])
            saveVisual(inputURL: inputURL, outputURL: outputURL, requestData: data, replacing: replacing, completion: completion)
        } catch {
            completion(.failure(StudioError.message("시각 편집 요청을 JSON으로 만들 수 없습니다: \(error.localizedDescription)")))
        }
    }

    func saveVisual(
        inputURL: URL,
        outputURL: URL,
        requestData: Data,
        replacing: Bool = false,
        completion: @escaping (Result<URL, Error>) -> Void
    ) {
        perform({
            _ = try self.validateVisualRequest(requestData)
            let requestURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("HWPXStudio-Visual-\(UUID().uuidString).json")
            defer { try? FileManager.default.removeItem(at: requestURL) }
            try requestData.write(to: requestURL, options: [.atomic])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: requestURL.path)
            try ApprovedFileSave.write(to: outputURL, replacing: replacing) { target in
                _ = try self.run(["visual-edit", inputURL.path, requestURL.path, target.path])
            }
            return outputURL
        }, completion: completion)
    }

    private func validateVisualSnapshot(_ data: Data) throws -> VisualDocumentSnapshot {
        guard data.count <= VisualDocumentSnapshot.maximumBytes else {
            throw StudioError.message("문서 시각 모델이 허용 크기(64MB)를 초과합니다. 원본은 변경되지 않았습니다.")
        }
        let object = try validatedJSONObject(data, label: "문서 시각 모델")
        guard let version = object["schemaVersion"] as? NSNumber, version.intValue == 1 else {
            throw StudioError.message("지원하지 않는 문서 시각 모델 버전입니다.")
        }
        let digest = try validatedDigest(object["sourceSha256"], label: "문서 시각 모델")
        guard object["sections"] is [Any], object["warnings"] is [Any] else {
            throw StudioError.message("문서 시각 모델에 sections 또는 warnings 배열이 없습니다.")
        }
        return VisualDocumentSnapshot(
            model: object,
            schemaVersion: version.intValue,
            sourceSha256: digest
        )
    }

    private func validateVisualRequest(_ data: Data) throws -> [String: Any] {
        guard data.count <= 2 * 1024 * 1024 else {
            throw StudioError.message("시각 편집 요청이 허용 크기(2MB)를 초과합니다.")
        }
        let object = try validatedJSONObject(data, label: "시각 편집 요청")
        guard let version = object["schemaVersion"] as? NSNumber, version.intValue == 1 else {
            throw StudioError.message("시각 편집 요청의 schemaVersion은 1이어야 합니다.")
        }
        _ = try validatedDigest(object["sourceSha256"], label: "시각 편집 요청")
        for key in ["edits", "styles", "tables", "structure"] {
            if let value = object[key], !(value is [Any]) {
                throw StudioError.message("시각 편집 요청의 \(key)는 배열이어야 합니다.")
            }
        }
        return object
    }

    private func validatedJSONObject(_ data: Data, label: String) throws -> [String: Any] {
        do {
            let value = try JSONSerialization.jsonObject(with: data, options: [])
            guard let dictionary = value as? [String: Any] else {
                throw StudioError.message("\(label)의 최상위 값은 객체여야 합니다.")
            }
            return dictionary
        } catch let error as StudioError {
            throw error
        } catch {
            throw StudioError.message("\(label)을 읽을 수 없습니다: \(error.localizedDescription)")
        }
    }

    private func validatedDigest(_ value: Any?, label: String) throws -> String {
        guard let digest = value as? String,
              digest.count == 64,
              digest.unicodeScalars.allSatisfy({
                  (48...57).contains($0.value) || (97...102).contains($0.value) || (65...70).contains($0.value)
              }) else {
            throw StudioError.message("\(label)에 올바른 sourceSha256 값이 없습니다.")
        }
        return digest.lowercased()
    }
}


/// Builds a complete sibling file before publishing an explicitly approved replacement.
enum ApprovedFileSave {
    static func write(to destination: URL, replacing: Bool,
                      produce: (URL) throws -> Void) throws {
        let fm = FileManager.default
        if !replacing {
            try produce(destination)
            return
        }
        let attributes = try fm.attributesOfItem(atPath: destination.path)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            throw StudioError.message("일반 파일만 대치할 수 있습니다.")
        }
        let original = try Data(contentsOf: destination)
        let staging = destination.deletingLastPathComponent()
            .appendingPathComponent(".DocumentStudio-\(UUID().uuidString).\(destination.pathExtension)")
        defer { try? fm.removeItem(at: staging) }
        try produce(staging)
        let current = try fm.attributesOfItem(atPath: destination.path)
        guard current[.systemFileNumber] as? NSNumber == attributes[.systemFileNumber] as? NSNumber,
              current[.type] as? FileAttributeType == .typeRegular,
              try Data(contentsOf: destination) == original else {
            throw StudioError.message("저장 중 대상 파일이 변경되었습니다. 다시 확인한 뒤 저장해 주세요.")
        }
        guard rename(staging.path, destination.path) == 0 else {
            throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno))
        }
    }
}
