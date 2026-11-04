import Foundation

struct ReleaseUpdate {
    let version: String
    let notes: String
    let url: URL

    static func components(_ value: String) -> [Int]? {
        let text = value.hasPrefix("v") ? String(value.dropFirst()) : value
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3, parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy({ $0.isASCII && $0.isNumber }) }),
              let major = Int(parts[0]), let minor = Int(parts[1]), let patch = Int(parts[2]) else { return nil }
        return [major, minor, patch]
    }

    static func isNewer(_ candidate: String, than current: String) -> Bool {
        guard let a = components(candidate), let b = components(current) else { return false }
        return b.lexicographicallyPrecedes(a)
    }

    static func shouldCheck(last: Date?, now: Date = Date()) -> Bool {
        guard let last = last else { return true }
        return now.timeIntervalSince(last) >= 86400 || last > now
    }

    static func parse(_ data: Data) throws -> ReleaseUpdate {
        struct Payload: Decodable {
            let tag_name: String
            let body: String?
            let draft: Bool
            let prerelease: Bool
            let html_url: String
        }
        let value = try JSONDecoder().decode(Payload.self, from: data)
        guard !value.draft, !value.prerelease, components(value.tag_name) != nil,
              let url = URL(string: value.html_url), url.scheme == "https", url.host == "github.com",
              url.user == nil, url.password == nil, url.port == nil,
              url.path == "/HyeonWooNa0861/DocumentStudio/releases/tag/\(value.tag_name)" else {
            throw URLError(.cannotParseResponse)
        }
        return ReleaseUpdate(version: value.tag_name, notes: String((value.body ?? "변경 내용은 릴리스 페이지에서 확인하세요.").prefix(6000)), url: url)
    }

    static func fetch(completion: @escaping (Result<ReleaseUpdate, Error>) -> Void) {
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/HyeonWooNa0861/DocumentStudio/releases/latest")!)
        request.timeoutInterval = 15
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("DocumentStudio-UpdateCheck", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            let result: Result<ReleaseUpdate, Error> = Result {
                if let error = error { throw error }
                guard let response = response as? HTTPURLResponse, response.statusCode == 200,
                      let data = data, data.count <= 1_000_000 else { throw URLError(.badServerResponse) }
                return try parse(data)
            }
            DispatchQueue.main.async { completion(result) }
        }.resume()
    }
}
