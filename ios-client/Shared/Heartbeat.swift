import Foundation

final class Heartbeat {
    private var idleInterval = 0.0
    private var idleDeadline = 0.0
    private var probeDeadline = 0.0
    init() { pingSent() }
    func packetSent() { idleDeadline = ProcessInfo.processInfo.systemUptime + idleInterval }
    func pingSent() {
        let now = ProcessInfo.processInfo.systemUptime
        idleInterval = Double.random(in: 20...35)
        idleDeadline = now + idleInterval
        probeDeadline = now + Double.random(in: 50...65)
    }
    func due() -> Bool { ProcessInfo.processInfo.systemUptime >= min(idleDeadline, probeDeadline) }
}
