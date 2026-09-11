import Foundation
import Network
import NetworkExtension
import Security

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let queue = DispatchQueue(label: "app.vision.tunnel")
    private var connection: WebTransport?
    private var profile: Profile?
    private var generation = 0
    private var endpointIndex = 0
    private var stopped = false
    private var configured = false
    private var readingPackets = false
    private var address: [UInt8] = []
    private var startCompletion: ((Error?) -> Void)?
    private var timer: DispatchSourceTimer?
    private var lastReceived = Date()
    private var heartbeatPolicy = Heartbeat()
    private var retry: DispatchWorkItem?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        queue.async {
            do {
                self.profile = try Profile.parse(Vault.load())
                self.startCompletion = completionHandler
                self.stopped = false
                self.endpointIndex = 0
                self.attempt()
            } catch { completionHandler(error) }
        }
    }
    private func current(_ id: Int) -> Bool { !stopped && generation == id }
    private func attempt() {
        guard !stopped, let profile else { return }
        generation += 1
        let id = generation
        configured = false
        let endpoint = profile.endpoints[endpointIndex % profile.endpoints.count]
        endpointIndex += 1
        let conn = WebTransport(queue: queue)
        connection = conn
        lastReceived = Date(); heartbeatPolicy = Heartbeat()
        conn.onReady = { [weak self] in
            guard let self, self.current(id) else { return }
            self.readFrame(id)
        }
        conn.onError = { [weak self] in self?.fail(id) }
        conn.start(endpoint: endpoint, profile: profile)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self, self.current(id) else { return }
            let elapsed = Date().timeIntervalSince(self.lastReceived)
            if elapsed > (self.configured ? 90 : 15) { self.fail(id); return }
            if self.configured && self.heartbeatPolicy.due() {
                self.heartbeatPolicy.pingSent()
                var nonce = [UInt8](repeating: 0, count: 8)
                if SecRandomCopyBytes(kSecRandomDefault, 8, &nonce) != errSecSuccess { self.fail(id); return }
                self.send(Wire.ping, Data(nonce), id: id) {}
            }
        }
        self.timer = timer; timer.resume()
    }
    private func fail(_ id: Int) {
        guard current(id) else { return }
        generation += 1
        configured = false
        connection?.cancel(); connection = nil
        timer?.cancel(); timer = nil
        if let completion = startCompletion, endpointIndex >= (profile?.endpoints.count ?? 1) {
            startCompletion = nil; stopped = true
            completion(SeverError.connectionFailed)
            return
        }
        if startCompletion == nil { reasserting = true }
        let work = DispatchWorkItem { [weak self] in self?.attempt() }
        retry = work
        queue.asyncAfter(deadline: .now() + min(15, Double(endpointIndex)), execute: work)
    }
    private func send(_ kind: UInt8, _ payload: Data, id: Int, completion: @escaping () -> Void) {
        guard current(id), let connection else { return }
        do {
            connection.send(try Wire.frame(kind, payload), completion: { [weak self] error in
                guard let self, self.current(id) else { return }
                if error != nil { self.fail(id) } else {
                    if kind == Wire.packet { self.heartbeatPolicy.packetSent() }
                    completion()
                }
            })
        } catch { fail(id) }
    }
    private func readFrame(_ id: Int) {
        guard current(id), let connection else { return }
        connection.receive { [weak self] result in
            guard let self, self.current(id) else { return }
            do {
                let data = try result.get()
                let (kind, payload) = try Wire.decode(data)
                self.lastReceived = Date()
                self.handle(kind, payload, id: id)
            } catch { self.fail(id) }
        }
    }
    private func handle(_ kind: UInt8, _ data: Data, id: Int) {
        do {
            if !configured {
                guard kind == Wire.config,
                      let config = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let ip = config["ip"] as? String, let dns = config["dns"] as? String,
                      config["mtu"] as? Int == 1280, config["prefix"] as? Int == 24 else { throw SeverError.invalidFrame }
                address = try Wire.address(ip); _ = try Wire.address(dns)
                guard Array(address.prefix(3)) == [10,77,0], (2...254).contains(address[3]) else { throw SeverError.invalidFrame }
                let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.77.0.1")
                settings.mtu = 1280
                settings.ipv4Settings = NEIPv4Settings(addresses: [ip], subnetMasks: ["255.255.255.0"])
                settings.ipv4Settings?.includedRoutes = [NEIPv4Route.default()]
                settings.ipv6Settings = NEIPv6Settings(addresses: ["fd77::2"], networkPrefixLengths: [128])
                settings.ipv6Settings?.includedRoutes = [NEIPv6Route.default()]
                settings.dnsSettings = NEDNSSettings(servers: [dns])
                settings.dnsSettings?.matchDomains = [""]
                setTunnelNetworkSettings(settings) { error in
                    self.queue.async {
                        guard self.current(id) else { return }
                        if error != nil { self.fail(id); return }
                        self.configured = true; self.reasserting = false
                        if let completion = self.startCompletion { self.startCompletion = nil; completion(nil) }
                        self.readPackets(id); self.readFrame(id)
                    }
                }
            } else {
                if kind == Wire.packet {
                    try Wire.validate(data, address: address, source: false)
                    guard packetFlow.writePackets([data], withProtocols: [NSNumber(value: AF_INET)]) else { throw SeverError.invalidFrame }
                } else if kind != Wire.pong || data.count != 8 { throw SeverError.invalidFrame }
                readFrame(id)
            }
        } catch { fail(id) }
    }
    private func readPackets(_ id: Int) {
        guard current(id), configured, !readingPackets else { return }
        readingPackets = true
        packetFlow.readPackets { packets, protocols in
            self.queue.async {
                self.readingPackets = false
                guard !self.stopped, self.configured else { return }
                let activeID = self.generation
                // Deliberately discard IPv6 captured by the default IPv6 route.
                let ipv4 = zip(packets, protocols).filter { $0.1.int32Value == AF_INET }.map { $0.0 }
                self.sendPackets(ipv4, index: 0, id: activeID)
            }
        }
    }
    private func sendPackets(_ packets: [Data], index: Int, id: Int) {
        guard current(id) else { return }
        guard index < packets.count else { readPackets(id); return }
        do {
            try Wire.validate(packets[index], address: address, source: true)
            send(Wire.packet, packets[index], id: id) { self.sendPackets(packets, index: index + 1, id: id) }
        } catch { fail(id) }
    }
    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        queue.async {
            self.stopped = true; self.generation += 1
            self.retry?.cancel(); self.timer?.cancel(); self.connection?.cancel()
            self.retry = nil; self.timer = nil; self.connection = nil
            if let start = self.startCompletion { self.startCompletion = nil; start(SeverError.connectionFailed) }
            completionHandler()
        }
    }
}
