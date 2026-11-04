import Foundation

@main
struct UpdateTests {
    static func main() throws {
        var count = 0
        func check(_ condition: Bool, _ label: String) {
            precondition(condition, label)
            count += 1
        }
        check(ReleaseUpdate.isNewer("v1.10.0", than: "1.9.9"), "numeric version comparison")
        check(!ReleaseUpdate.isNewer("v1.6.0", than: "1.6.0"), "equal version")
        check(!ReleaseUpdate.isNewer("1.5.9", than: "1.6.0"), "older release")
        for invalid in ["1.7", "1.7.0-beta", "1.7.0.1", "-1.7.0", "１.7.0", "999999999999999999999.0.0"] {
            check(ReleaseUpdate.components(invalid) == nil, "invalid version: \(invalid)")
        }
        let now = Date(timeIntervalSince1970: 200000)
        check(ReleaseUpdate.shouldCheck(last: nil, now: now), "first check")
        check(!ReleaseUpdate.shouldCheck(last: now.addingTimeInterval(-86399), now: now), "daily throttle")
        check(ReleaseUpdate.shouldCheck(last: now.addingTimeInterval(-86400), now: now), "24 hour boundary")
        check(ReleaseUpdate.shouldCheck(last: now.addingTimeInterval(1), now: now), "clock rollback")
        let valid: [String: Any] = ["tag_name":"v1.7.0", "body":"변경 내용", "draft":false, "prerelease":false,
            "html_url":"https://github.com/HyeonWooNa0861/DocumentStudio/releases/tag/v1.7.0"]
        let release = try ReleaseUpdate.parse(JSONSerialization.data(withJSONObject: valid))
        check(release.version == "v1.7.0" && release.notes == "변경 내용", "release decoding")
        for (key, value) in [("draft", true as Any), ("prerelease", true as Any),
                             ("html_url", "https://evil.example/releases/tag/v1.7.0" as Any),
                             ("html_url", "https://github.com/another/repo/releases/tag/v1.7.0" as Any),
                             ("html_url", "http://github.com/HyeonWooNa0861/DocumentStudio/releases/tag/v1.7.0" as Any),
                             ("tag_name", "v1.7.0-beta" as Any)] {
            var fixture = valid; fixture[key] = value
            do { _ = try ReleaseUpdate.parse(JSONSerialization.data(withJSONObject: fixture)); preconditionFailure("Rejected payload accepted") }
            catch { count += 1 }
        }
        do { _ = try ReleaseUpdate.parse(Data("broken".utf8)); preconditionFailure("Malformed JSON accepted") }
        catch { count += 1 }
        print("Update policy tests passed: \(count)")
    }
}
