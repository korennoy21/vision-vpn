import SwiftUI
import NetworkExtension
import UniformTypeIdentifiers

@main struct VisionVPNApp: App {
    var body: some Scene { WindowGroup { VisionRootView() } }
}

@MainActor final class VPNModel: ObservableObject {
    @Published var status = "Отключено"
    @Published var profileName = "Профиль не добавлен"
    @Published var endpoint = "—"
    @Published var error: String?
    @Published var busy = false
    @Published var connected = false
    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    init() {
        observer = NotificationCenter.default.addObserver(forName: .NEVPNStatusDidChange, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
        if let data = try? Vault.load(), let p = try? Profile.parse(data) {
            profileName = p.name; endpoint = p.endpoints.first?.host ?? "—"
        }
        Task { await load() }
    }

    func load() async {
        do {
            let values = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = values.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == (Bundle.main.bundleIdentifier! + ".tunnel") }
            refresh()
        } catch { self.error = error.localizedDescription }
    }

    func refresh() {
        switch manager?.connection.status {
        case .connected: status = "Подключено"; connected = true
        case .connecting: status = "Подключение…"; connected = false
        case .reasserting: status = "Восстановление…"; connected = true
        case .disconnecting: status = "Отключение…"; connected = false
        default: status = "Отключено"; connected = false
        }
    }

    func importProfile(_ data: Data) {
        guard !connected else { error = "Сначала отключите VPN"; return }
        do {
            let p = try Profile.parse(data)
            _ = try Vault.save(data)
            profileName = p.name; endpoint = p.endpoints.first?.host ?? "—"
        } catch { self.error = error.localizedDescription }
    }

    func importAny(_ raw: String) {
        let value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("https://") { importURL(value); return }
        if value.hasPrefix("{") { importProfile(Data(value.utf8)); return }
        if value.hasPrefix("[Interface]") || value.contains("client\n") || value.hasPrefix("vless://") || value.hasPrefix("vmess://") || value.hasPrefix("trojan://") || value.hasPrefix("ss://") {
            error = "Конфиг распознан. Движок этого протокола будет подключён в следующем этапе VISION; текущая сборка не помечает его как рабочее соединение."
            return
        }
        error = "Не удалось определить формат конфигурации"
    }

    func importURL(_ raw: String) {
        guard !busy else { return }
        guard !connected else { error = "Сначала отключите VPN"; return }
        busy = true
        Task {
            defer { busy = false }
            do { importProfile(try await Subscription.fetch(raw)) }
            catch { self.error = "Не удалось получить профиль. Проверьте ссылку, интернет и доступ к панели." }
        }
    }

    func updateProfile() {
        do {
            guard let url = try Profile.parse(Vault.load()).subscriptionURL else { error = "У профиля нет ссылки обновления"; return }
            importURL(url)
        } catch { self.error = error.localizedDescription }
    }

    func toggle() {
        if connected || manager?.connection.status == .connecting || manager?.connection.status == .reasserting {
            manager?.connection.stopVPNTunnel(); return
        }
        connect()
    }

    private func connect() {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                var data = try Vault.load()
                if let url = try Profile.parse(data).subscriptionURL {
                    status = "Обновление профиля…"
                    data = try await Subscription.fetch(url)
                }
                let p = try Profile.parse(data)
                profileName = p.name; endpoint = p.endpoints.first?.host ?? "—"
                let ref = try Vault.save(data)
                if manager == nil { await load() }
                let m = manager ?? NETunnelProviderManager()
                let proto = NETunnelProviderProtocol()
                proto.providerBundleIdentifier = Bundle.main.bundleIdentifier! + ".tunnel"
                proto.serverAddress = p.endpoints[0].host
                proto.passwordReference = ref
                proto.includeAllNetworks = true
                proto.excludeLocalNetworks = false
                m.protocolConfiguration = proto
                m.localizedDescription = "VISION VPN"
                m.isEnabled = true
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
                manager = m
                try m.connection.startVPNTunnel()
                refresh()
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct VisionRootView: View {
    @StateObject private var model = VPNModel()
    @State private var tab = 0
    var body: some View {
        TabView(selection: $tab) {
            VisionHome(model: model).tabItem { Label("Главная", systemImage: "house.fill") }.tag(0)
            ProfilesView(model: model).tabItem { Label("Профили", systemImage: "rectangle.stack.fill") }.tag(1)
            RoutesView().tabItem { Label("Маршруты", systemImage: "point.3.connected.trianglepath.dotted") }.tag(2)
            SettingsView().tabItem { Label("Настройки", systemImage: "gearshape.fill") }.tag(3)
        }
        .tint(.cyan)
        .preferredColorScheme(.dark)
        .alert("VISION VPN", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("OK") { model.error = nil }
        } message: { Text(model.error ?? "") }
    }
}

struct VisionHome: View {
    @ObservedObject var model: VPNModel
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("VISION VPN").font(.system(size: 27, weight: .bold, design: .rounded))
                            Text("СВОБОДА • КОНТРОЛЬ • СКОРОСТЬ").font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer(); Image(systemName: "bell.badge.fill").foregroundStyle(.cyan)
                    }
                    .padding(.bottom, 8)

                    VStack(spacing: 18) {
                        ZStack {
                            Circle().fill((model.connected ? Color.mint : Color.cyan).opacity(0.12)).frame(width: 176, height: 176)
                            Circle().stroke(model.connected ? Color.mint : Color.cyan, lineWidth: 3).frame(width: 154, height: 154)
                            Button(action: model.toggle) {
                                Image(systemName: "power").font(.system(size: 58, weight: .medium)).foregroundStyle(model.connected ? .mint : .cyan)
                            }
                        }
                        Text(model.status).font(.title2.bold()).foregroundStyle(model.connected ? .mint : .white)
                        Button(model.connected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬ", action: model.toggle)
                            .buttonStyle(.borderedProminent).tint(model.connected ? .mint : .cyan).controlSize(.large)
                    }
                    .frame(maxWidth: .infinity).padding(24).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 28))

                    VisionCard(title: "Профиль", value: model.profileName, footnote: "VISION Secure • WSS", icon: "shield.lefthalf.filled")
                    VisionCard(title: "Сервер", value: model.endpoint, footnote: "Автовыбор и failover", icon: "server.rack")
                    VisionCard(title: "Маршрутизация", value: "Весь трафик", footnote: "IP/CIDR правила — следующий этап", icon: "point.3.connected.trianglepath.dotted")
                }.padding(20)
            }
            .background(Color(red: 0.015, green: 0.055, blue: 0.095))
        }
    }
}

struct VisionCard: View {
    let title: String; let value: String; let footnote: String; let icon: String
    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: icon).font(.title2).foregroundStyle(.cyan).frame(width: 42, height: 42).background(Color.cyan.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 3) {
                Text(title.uppercased()).font(.caption2.bold()).foregroundStyle(.cyan)
                Text(value).font(.headline)
                Text(footnote).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(); Image(systemName: "chevron.right").foregroundStyle(.secondary)
        }.padding(18).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22))
    }
}

struct ProfilesView: View {
    @ObservedObject var model: VPNModel
    @State private var importing = false
    @State private var showLink = false
    @State private var showPaste = false
    @State private var showScanner = false
    @State private var link = ""
    @State private var pasted = ""

    var body: some View {
        NavigationStack {
            List {
                Section("Активный профиль") {
                    Label(model.profileName, systemImage: "shield.checkered")
                    Button("Обновить профиль", action: model.updateProfile)
                }
                Section("Добавить") {
                    Button { showLink = true } label: { Label("По ссылке", systemImage: "link") }
                    Button { showScanner = true } label: { Label("Сканировать QR-код", systemImage: "qrcode.viewfinder") }
                    Button { importing = true } label: { Label("Из файла", systemImage: "doc.badge.plus") }
                    Button { showPaste = true } label: { Label("Вставить конфигурацию", systemImage: "doc.on.clipboard") }
                }
                Section("Форматы") {
                    Text("VISION • WireGuard • OpenVPN • VLESS • Trojan • Shadowsocks")
                    Text("В VISION VPN 1.0 встроенным iOS-движком является VISION Secure. Остальные форматы можно хранить и переносить; для подключения нужны подписываемые iOS Engine Packs.").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Профили")
            .fileImporter(isPresented: $importing, allowedContentTypes: [.json, .data, .plainText]) { result in
                do {
                    let url = try result.get(); let granted = url.startAccessingSecurityScopedResource()
                    defer { if granted { url.stopAccessingSecurityScopedResource() } }
                    model.importAny(String(decoding: try Data(contentsOf: url), as: UTF8.self))
                } catch { model.error = error.localizedDescription }
            }
            .sheet(isPresented: $showScanner) { QRScannerView(onCode: { value in showScanner = false; model.importAny(value) }, onCancel: { showScanner = false }) }
            .alert("Ссылка профиля", isPresented: $showLink) {
                TextField("https://panel.example.com/s/…json", text: $link)
                Button("Добавить") { model.importURL(link); link = "" }
                Button("Отмена", role: .cancel) {}
            }
            .sheet(isPresented: $showPaste) {
                NavigationStack {
                    VStack { TextEditor(text: $pasted).font(.system(.body, design: .monospaced)).padding(); Button("Импортировать") { model.importAny(pasted); pasted = ""; showPaste = false }.buttonStyle(.borderedProminent).padding() }
                    .navigationTitle("Конфигурация").toolbar { ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { showPaste = false } } }
                }
            }
        }
    }
}

struct RoutesView: View {
    @AppStorage("vision.ios.allowLocal") private var allowLocal = false
    var body: some View {
        NavigationStack {
            List {
                Section("Режим") {
                    Label("Весь трафик через VPN", systemImage: "globe")
                    Toggle("Разрешить локальную сеть", isOn: $allowLocal)
                }
                Section("Маршруты") {
                    Label("IP и CIDR", systemImage: "point.3.filled.connected.trianglepath.dotted")
                    Label("Домены", systemImage: "link")
                    Text("VISION Secure применяет поддерживаемые маршруты внутри Packet Tunnel. Per-app VPN на обычном iOS ограничен системой.").font(.footnote).foregroundStyle(.secondary)
                }
                Section("Приложения") {
                    Text("Обычное iOS-приложение не может свободно выбирать другие установленные приложения для split tunneling. Per‑App VPN на iOS применяется для управляемых устройств/приложений через системные механизмы Apple.").font(.footnote).foregroundStyle(.secondary)
                }
            }.navigationTitle("Маршруты")
        }
    }
}

struct SettingsView: View {
    @AppStorage("vision.autoConnect") private var autoConnect = false
    @AppStorage("vision.notifications") private var notifications = true
    var body: some View {
        NavigationStack {
            List {
                Section("Соединение") {
                    Toggle("Автоподключение", isOn: $autoConnect)
                    Label("VISION Secure (WSS)", systemImage: "shield.fill")
                    Label("DNS внутри туннеля", systemImage: "network")
                }
                Section("Интерфейс") {
                    Toggle("Уведомления", isOn: $notifications)
                    Label("Системная тема", systemImage: "circle.lefthalf.filled")
                }
                Section("О приложении") {
                    Text("VISION VPN 1.0.0")
                    Text("Новый клиент с совместимым транспортным ядром и архитектурой нескольких VPN-движков.").font(.footnote).foregroundStyle(.secondary)
                }
            }.navigationTitle("Настройки")
        }
    }
}
