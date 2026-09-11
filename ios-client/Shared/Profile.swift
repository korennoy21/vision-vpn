import Foundation

struct Endpoint: Codable { let host: String; let port: UInt16; let path: String }
struct Profile: Codable {
    let version: Int
    let `protocol`: String
    let transport: String
    let name: String
    let endpoints: [Endpoint]
    let user: String
    let token: String
    let subscriptionURL: String?
    enum CodingKeys: String, CodingKey {
        case version, `protocol`, transport, name, endpoints, user, token
        case subscriptionURL = "subscription_url"
    }
    static func parse(_ data: Data) throws -> Profile {
        guard data.count <= 65536 else { throw SeverError.invalidProfile }
        let p = try JSONDecoder().decode(Profile.self, from: data)
        guard p.version == 2, p.protocol == "sever1", p.transport == "wss", (1...16).contains(p.endpoints.count),
              (32...256).contains(p.token.count),
              p.user.range(of: "^[A-Za-z0-9_-]{1,64}$", options: .regularExpression) != nil,
              p.endpoints.allSatisfy({ $0.port > 0 && $0.host.range(of: "^[A-Za-z0-9.-]{1,253}$", options: .regularExpression) != nil && $0.path.range(of: "^/[A-Za-z0-9][A-Za-z0-9/_-]{0,126}$", options: .regularExpression) != nil && !$0.path.hasPrefix("/_") })
        else { throw SeverError.invalidProfile }
        if let url = p.subscriptionURL { _ = try Subscription.validate(url) }
        return p
    }
}

enum SeverError: Error, LocalizedError {
    case invalidProfile, invalidFrame, connectionFailed, subscriptionUnavailable, keychain(Int32)
    var errorDescription: String? {
        switch self {
        case .invalidProfile: return "Неверный профиль или неподключённый протокол. Текущая сборка подключает VISION Secure."
        case .invalidFrame: return "Некорректный ответ сервера."
        case .subscriptionUnavailable: return "Профиль недоступен. Проверьте интернет и доступ в панели."
        case .connectionFailed: return "Не удалось подключиться к серверу."
        case .keychain: return "Не удалось сохранить профиль в Keychain."
        }
    }
}
