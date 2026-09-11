import Foundation
import Security

enum Wire {
    static let auth: UInt8 = 1, config: UInt8 = 2, packet: UInt8 = 3, ping: UInt8 = 4, pong: UInt8 = 5
    static func frame(_ kind: UInt8, _ payload: Data) throws -> Data {
        guard (1...6).contains(kind), payload.count <= 4096 else { throw SeverError.invalidFrame }
        let padding = Int.random(in: 0...511)
        var out = Data([1, kind, UInt8(payload.count >> 8), UInt8(payload.count & 255), UInt8(padding >> 8), UInt8(padding & 255), 0, 0])
        out.append(payload)
        var bytes = [UInt8](repeating: 0, count: padding)
        if padding > 0 && SecRandomCopyBytes(kSecRandomDefault, padding, &bytes) != errSecSuccess { throw SeverError.invalidFrame }
        out.append(contentsOf: bytes)
        return out
    }
    static func header(_ data: Data) throws -> (UInt8, Int, Int) {
        let b = [UInt8](data)
        guard b.count == 8, b[0] == 1, (1...6).contains(b[1]), b[6] == 0, b[7] == 0 else { throw SeverError.invalidFrame }
        let n = Int(b[2]) * 256 + Int(b[3]), pad = Int(b[4]) * 256 + Int(b[5])
        guard n <= 4096, pad <= 511 else { throw SeverError.invalidFrame }
        return (b[1], n, pad)
    }
    static func decode(_ data: Data) throws -> (UInt8, Data) {
        guard (8...4615).contains(data.count) else { throw SeverError.invalidFrame }
        let (kind, length, padding) = try header(Data(data.prefix(8)))
        guard data.count == 8 + length + padding else { throw SeverError.invalidFrame }
        return (kind, Data(data.dropFirst(8).prefix(length)))
    }
    static func address(_ text: String) throws -> [UInt8] {
        let pieces = text.split(separator: ".", omittingEmptySubsequences: false)
        let bytes = pieces.compactMap { UInt8($0) }
        guard pieces.count == 4, bytes.count == 4 else { throw SeverError.invalidFrame }
        return bytes
    }
    static func validate(_ packet: Data, address: [UInt8], source: Bool) throws {
        let b = [UInt8](packet)
        guard b.count >= 20, b.count <= 1280, b[0] >> 4 == 4 else { throw SeverError.invalidFrame }
        let ihl = Int(b[0] & 15) * 4
        guard ihl >= 20, ihl <= b.count, Int(b[2]) * 256 + Int(b[3]) == b.count,
              Array(b[(source ? 12 : 16)..<(source ? 16 : 20)]) == address else { throw SeverError.invalidFrame }
    }
}
