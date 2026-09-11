import Foundation

// System WebSocket stack. Its TLS fingerprint is Apple's, not a claimed browser clone.
final class WebTransport: NSObject, URLSessionWebSocketDelegate {
    private let queue: DispatchQueue
    private var session: URLSession?
    private var task: URLSessionWebSocketTask?
    var onReady: (() -> Void)?
    var onError: (() -> Void)?
    init(queue: DispatchQueue) { self.queue = queue }

    func start(endpoint: Endpoint, profile: Profile) {
        var parts = URLComponents()
        parts.scheme = "wss"; parts.host = endpoint.host; parts.port = Int(endpoint.port); parts.path = endpoint.path
        guard let url = parts.url else { onError?(); return }
        var request = URLRequest(url: url)
        let credentials = Data("\(profile.user):\(profile.token)".utf8).base64EncodedString()
        request.setValue("Basic " + credentials, forHTTPHeaderField: "Authorization")
        let config = URLSessionConfiguration.ephemeral
        config.tlsMinimumSupportedProtocolVersion = .TLSv13
        config.tlsMaximumSupportedProtocolVersion = .TLSv13
        config.httpCookieStorage = nil; config.urlCache = nil
        config.urlCredentialStorage = nil
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.webSocketTask(with: request)
        task.maximumMessageSize = 4615
        self.task = task
        task.resume()
    }
    func send(_ bytes: Data, completion: @escaping (Error?) -> Void) {
        guard let task else { completion(SeverError.connectionFailed); return }
        task.send(.data(bytes)) { error in self.queue.async { completion(error) } }
    }
    func receive(completion: @escaping (Result<Data, Error>) -> Void) {
        guard let task else { completion(.failure(SeverError.connectionFailed)); return }
        task.receive { result in
            self.queue.async {
                switch result {
                case .success(.data(let bytes)): completion(.success(bytes))
                case .success: completion(.failure(SeverError.invalidFrame))
                case .failure(let error): completion(.failure(error))
                }
            }
        }
    }
    func cancel() {
        task?.cancel(with: .goingAway, reason: nil); task = nil
        session?.invalidateAndCancel(); session = nil
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        queue.async { self.onReady?() }
    }
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        queue.async { self.onError?() }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil { queue.async { self.onError?() } }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        // Never forward the bearer-equivalent profile credential to a redirect target.
        completionHandler(nil)
    }
}
