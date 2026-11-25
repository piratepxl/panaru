import Foundation
import Observation
import Security

// MARK: - Segreti

/// Archivio delle API key. In produzione: Keychain di macOS.
public protocol SecretStore: Sendable {
    func set(_ value: String, account: String) throws
    func get(account: String) throws -> String?
    func delete(account: String)
}

public enum KeychainError: Error, LocalizedError {
    case status(OSStatus)
    public var errorDescription: String? {
        if case .status(let s) = self { return (SecCopyErrorMessageString(s, nil) as String?) ?? "Keychain error \(s)" }
        return nil
    }
}

/// API key nel Keychain (`kSecClassGenericPassword`), mai in UserDefaults o nei file JSON.
public struct KeychainStore: SecretStore {
    public let service: String
    public init(service: String = "app.panaru.macos.apikey") { self.service = service }

    private func base(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public func set(_ value: String, account: String) throws {
        delete(account: account)
        var q = base(account)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        q[kSecAttrLabel as String] = "Panaru — API key Gokapi"
        let s = SecItemAdd(q as CFDictionary, nil)
        guard s == errSecSuccess else { throw KeychainError.status(s) }
    }

    public func get(account: String) throws -> String? {
        var q = base(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        let s = SecItemCopyMatching(q as CFDictionary, &out)
        if s == errSecItemNotFound { return nil }
        guard s == errSecSuccess, let data = out as? Data else { throw KeychainError.status(s) }
        return String(decoding: data, as: UTF8.self)
    }

    public func delete(account: String) {
        SecItemDelete(base(account) as CFDictionary)
    }
}

/// Archivio in memoria, per i test.
public final class MemorySecretStore: SecretStore, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init() {}
    public func set(_ value: String, account: String) throws { lock.withLock { values[account] = value } }
    public func get(account: String) throws -> String? { lock.withLock { values[account] } }
    public func delete(account: String) { _ = lock.withLock { values.removeValue(forKey: account) } }
}

// MARK: - Persistenza JSON

enum JSONFile {
    static func load<T: Decodable>(_ type: T.Type, from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return try? d.decode(T.self, from: data)
    }

    static func save<T: Encodable>(_ value: T, to url: URL) {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        e.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? e.encode(value) else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: url, options: [.atomic])
    }
}

public enum AppPaths {
    /// `~/Library/Application Support/Panaru/`
    public static var supportDirectory: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Panaru", isDirectory: true)
    }
}

// MARK: - Server

public struct Server: Codable, Identifiable, Hashable, Sendable {
    public var id: UUID
    public var name: String
    public var baseURL: String
    public var isDefault: Bool
    public var createdAt: Date

    public var isInsecureLocal: Bool { baseURL.hasPrefix("http://") }
}

public enum ConnectionCheck: Sendable {
    case ok(VersionInfo, ServerConfig)
    case failed(GokapiError)
}

/// Server configurati (JSON senza segreti) + API key nel Keychain.
@MainActor
@Observable
public final class ServerStore {
    public private(set) var servers: [Server] = []
    private let fileURL: URL
    private let secrets: SecretStore
    private let session: URLSession
    private var configCache: [UUID: (Date, ServerConfig)] = [:]

    public init(directory: URL = AppPaths.supportDirectory, secrets: SecretStore = KeychainStore(), session: URLSession = GokapiClient.defaultSession) {
        self.fileURL = directory.appendingPathComponent("servers.json")
        self.secrets = secrets
        self.session = session
        servers = JSONFile.load([Server].self, from: fileURL) ?? []
        sort()
    }

    public var defaultServer: Server? { servers.first(where: \.isDefault) ?? servers.first }
    public func server(_ id: UUID) -> Server? { servers.first { $0.id == id } }

    public func check(_ url: ServerURL.Valid, apiKey: String) async -> ConnectionCheck {
        let client = GokapiClient(baseURL: url.url, apiKey: apiKey.trimmingCharacters(in: .whitespacesAndNewlines), session: session)
        do {
            let v = try await client.version()
            let c = try await client.config()
            return .ok(v, c)
        } catch let e as GokapiError {
            return .failed(e)
        } catch {
            return .failed(.network(error.localizedDescription))
        }
    }

    public func checkSaved(_ id: UUID, url: ServerURL.Valid) async -> ConnectionCheck {
        guard let key = try? secrets.get(account: id.uuidString) else { return .failed(.unauthorized) }
        return await check(url, apiKey: key)
    }

    @discardableResult
    public func add(name: String, url: ServerURL.Valid, apiKey: String, makeDefault: Bool) throws -> Server {
        let s = Server(
            id: UUID(),
            name: name.trimmingCharacters(in: .whitespaces).isEmpty ? (url.url.host ?? "Gokapi") : name.trimmingCharacters(in: .whitespaces),
            baseURL: url.canonical,
            isDefault: servers.isEmpty || makeDefault,
            createdAt: Date()
        )
        try secrets.set(apiKey.trimmingCharacters(in: .whitespacesAndNewlines), account: s.id.uuidString)
        if s.isDefault { for i in servers.indices { servers[i].isDefault = false } }
        servers.append(s)
        persist()
        return s
    }

    public func update(_ id: UUID, name: String, url: ServerURL.Valid, newApiKey: String?) throws {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        if let key = newApiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !key.isEmpty {
            try secrets.set(key, account: id.uuidString)
        }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        servers[i].name = trimmed.isEmpty ? (url.url.host ?? servers[i].name) : trimmed
        servers[i].baseURL = url.canonical
        configCache[id] = nil
        persist()
    }

    public func setDefault(_ id: UUID) {
        for i in servers.indices { servers[i].isDefault = servers[i].id == id }
        persist()
    }

    public func remove(_ id: UUID) {
        let wasDefault = server(id)?.isDefault == true
        servers.removeAll { $0.id == id }
        secrets.delete(account: id.uuidString)
        configCache[id] = nil
        if wasDefault, !servers.isEmpty { servers[0].isDefault = true }
        persist()
    }

    /// Client autenticato; la chiave viene letta dal Keychain solo al bisogno.
    public func client(for id: UUID) -> GokapiClient? {
        guard let s = server(id), let url = URL(string: s.baseURL),
              let key = try? secrets.get(account: id.uuidString) else { return nil }
        return GokapiClient(baseURL: url, apiKey: key, session: session)
    }

    /// Parametri di upload, in cache per 10 minuti; `nil` se il server non espone `/info/config`.
    public func config(for id: UUID, client: GokapiClient) async throws -> ServerConfig? {
        if let (at, cfg) = configCache[id], Date().timeIntervalSince(at) < 600 { return cfg }
        do {
            let cfg = try await client.config()
            configCache[id] = (Date(), cfg)
            return cfg
        } catch GokapiError.notFound {
            return nil
        }
    }

    private func sort() {
        servers.sort { ($0.isDefault ? 0 : 1, $0.name.lowercased()) < ($1.isDefault ? 0 : 1, $1.name.lowercased()) }
    }

    private func persist() {
        sort()
        JSONFile.save(servers, to: fileURL)
    }
}
