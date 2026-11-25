import Foundation

/// Risposta di `GET /api/info/version`.
public struct VersionInfo: Codable, Equatable, Sendable {
    public var version: String
    public var versionInt: Int

    enum CodingKeys: String, CodingKey { case version = "Version", versionInt = "VersionInt" }

    public init(version: String, versionInt: Int) {
        self.version = version
        self.versionInt = versionInt
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(String.self, forKey: .version) ?? ""
        versionInt = try c.decodeIfPresent(Int.self, forKey: .versionInt) ?? 0
    }

    /// I nomi file in base64 per `/chunk/complete` sono supportati da Gokapi 2.0.1.
    public var supportsBase64FileNames: Bool { versionInt >= 20001 }
}

/// Risposta di `GET /api/info/config` (dimensioni in megabyte).
public struct ServerConfig: Codable, Equatable, Sendable {
    public var maxFileSizeMB: Int
    public var maxChunkSizeMB: Int
    public var endToEndEncryptionEnabled: Bool

    enum CodingKeys: String, CodingKey {
        case maxFileSizeMB = "MaxFilesize", maxChunkSizeMB = "MaxChunksize", endToEndEncryptionEnabled = "EndToEndEncryptionEnabled"
    }

    public init(maxFileSizeMB: Int, maxChunkSizeMB: Int, endToEndEncryptionEnabled: Bool = false) {
        self.maxFileSizeMB = maxFileSizeMB
        self.maxChunkSizeMB = maxChunkSizeMB
        self.endToEndEncryptionEnabled = endToEndEncryptionEnabled
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        maxFileSizeMB = try c.decodeIfPresent(Int.self, forKey: .maxFileSizeMB) ?? 0
        maxChunkSizeMB = try c.decodeIfPresent(Int.self, forKey: .maxChunkSizeMB) ?? 0
        endToEndEncryptionEnabled = try c.decodeIfPresent(Bool.self, forKey: .endToEndEncryptionEnabled) ?? false
    }
}

/// Oggetto `File` di Gokapi (campi usati da Panaru).
public struct GokapiFile: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var sizeBytes: Int64
    public var urlDownload: String
    public var urlHotlink: String
    public var uploadDate: Int64
    public var expireAt: Int64
    public var unlimitedTime: Bool
    public var unlimitedDownloads: Bool
    public var downloadsRemaining: Int
    public var isPasswordProtected: Bool
    public var isEndToEndEncrypted: Bool
    public var contentType: String
    public var isPendingDeletion: Bool

    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", sizeBytes = "SizeBytes", urlDownload = "UrlDownload", urlHotlink = "UrlHotlink"
        case uploadDate = "UploadDate", expireAt = "ExpireAt", unlimitedTime = "UnlimitedTime"
        case unlimitedDownloads = "UnlimitedDownloads", downloadsRemaining = "DownloadsRemaining"
        case isPasswordProtected = "IsPasswordProtected", isEndToEndEncrypted = "IsEndToEndEncrypted"
        case contentType = "ContentType", isPendingDeletion = "IsPendingDeletion"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        sizeBytes = try c.decodeIfPresent(Int64.self, forKey: .sizeBytes) ?? 0
        urlDownload = try c.decodeIfPresent(String.self, forKey: .urlDownload) ?? ""
        urlHotlink = try c.decodeIfPresent(String.self, forKey: .urlHotlink) ?? ""
        uploadDate = try c.decodeIfPresent(Int64.self, forKey: .uploadDate) ?? 0
        expireAt = try c.decodeIfPresent(Int64.self, forKey: .expireAt) ?? 0
        unlimitedTime = try c.decodeIfPresent(Bool.self, forKey: .unlimitedTime) ?? false
        unlimitedDownloads = try c.decodeIfPresent(Bool.self, forKey: .unlimitedDownloads) ?? false
        downloadsRemaining = try c.decodeIfPresent(Int.self, forKey: .downloadsRemaining) ?? 0
        isPasswordProtected = try c.decodeIfPresent(Bool.self, forKey: .isPasswordProtected) ?? false
        isEndToEndEncrypted = try c.decodeIfPresent(Bool.self, forKey: .isEndToEndEncrypted) ?? false
        contentType = try c.decodeIfPresent(String.self, forKey: .contentType) ?? ""
        isPendingDeletion = try c.decodeIfPresent(Bool.self, forKey: .isPendingDeletion) ?? false
    }
}

struct UploadResult: Decodable {
    var fileInfo: GokapiFile?
    enum CodingKeys: String, CodingKey { case fileInfo = "FileInfo" }
}

struct ErrorBody: Decodable {
    var message: String?
    var code: Int?
    enum CodingKeys: String, CodingKey { case message = "ErrorMessage", code = "ErrorCode" }
}

/// Opzioni di condivisione: 0 = illimitato. La password è solo ASCII stampabile (header HTTP).
public struct UploadOptions: Equatable, Sendable {
    public var expiryDays: Int
    public var allowedDownloads: Int
    public var password: String?

    public init(expiryDays: Int = 14, allowedDownloads: Int = 1, password: String? = nil) {
        self.expiryDays = max(0, expiryDays)
        self.allowedDownloads = max(0, allowedDownloads)
        self.password = password?.isEmpty == true ? nil : password
    }

    public static func isValidPassword(_ value: String) -> Bool {
        value.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value <= 0x7E }
    }
}

/// Errori tipizzati del client, ognuno con un messaggio comprensibile.
public enum GokapiError: Error, Equatable, Sendable {
    /// 401: chiave errata, revocata o senza il permesso richiesto.
    case unauthorized
    case notFound
    case fileTooLarge(maxMB: Int?)
    case rateLimited
    case server(status: Int, code: Int?, message: String)
    case network(String)
    case tls(String)
    case sourceUnavailable(String)
    case protocolError(String)
    case cancelled

    var isRetryable: Bool {
        switch self {
        case .network: return true
        case .server(let status, _, _): return status >= 500
        default: return false
        }
    }
}

extension GokapiError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case .unauthorized:
            return String(localized: "Chiave API rifiutata: è errata, revocata o non ha il permesso richiesto.")
        case .notFound:
            return String(localized: "Il file non esiste più sul server o è scaduto.")
        case .fileTooLarge:
            return String(localized: "Il file supera la dimensione massima consentita dal server.")
        case .rateLimited:
            return String(localized: "Troppi tentativi: il server chiede di attendere qualche minuto.")
        case .server(_, _, let message):
            return String(localized: "Errore del server: \(message)")
        case .network:
            return String(localized: "Server non raggiungibile. Controlla la connessione e riprova.")
        case .tls:
            return String(localized: "Connessione sicura non riuscita: il certificato del server non è attendibile.")
        case .sourceUnavailable:
            return String(localized: "Il file non è più leggibile (spostato, eliminato o modificato durante il caricamento).")
        case .protocolError:
            return String(localized: "Il server non risponde come Gokapi. Controlla l'indirizzo.")
        case .cancelled:
            return String(localized: "Annullato")
        }
    }
}
