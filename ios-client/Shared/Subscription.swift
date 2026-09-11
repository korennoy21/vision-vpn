import Foundation

final class SubscriptionDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

enum Subscription {
    static func validate(_ raw: String) throws -> URL {
        guard raw.count <= 2048, let parts = URLComponents(string: raw), parts.scheme == "https",
              parts.host != nil, parts.user == nil, parts.password == nil,
              parts.query == nil, parts.fragment == nil,
              parts.percentEncodedPath.range(of: "^/s/[A-Za-z0-9_-]{43}\\.json$", options: .regularExpression) != nil,
              let url = parts.url else { throw SeverError.invalidProfile }
        return url
    }
    static func fetch(_ raw: String) async throws -> Data {
        let url = try validate(raw)
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil; config.urlCache = nil; config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 20
        config.tlsMinimumSupportedProtocolVersion = .TLSv12
        let delegate = SubscriptionDelegate()
        let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: URLRequest(url: url))
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw SeverError.subscriptionUnavailable }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 65536 else { throw SeverError.invalidProfile }
            data.append(byte)
        }
        guard var object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw SeverError.invalidProfile }
        object["subscription_url"] = raw
        let result = try JSONSerialization.data(withJSONObject: object)
        _ = try Profile.parse(result)
        return result
    }
}
