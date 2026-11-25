import Foundation

/// Validazione e normalizzazione dell'indirizzo di un server Gokapi
/// (stesse regole del client Android: vedi `ServerUrl.kt`).
public enum ServerURL {
    public enum Invalid: Error, Equatable {
        case empty, malformed, unsupportedScheme, credentialsInURL, insecurePublicHost

        public var message: String {
            switch self {
            case .empty: return String(localized: "Inserisci l'indirizzo del server.")
            case .malformed: return String(localized: "Indirizzo non valido.")
            case .unsupportedScheme: return String(localized: "Sono supportati solo indirizzi http:// e https://.")
            case .credentialsInURL: return String(localized: "Non inserire nome utente o password nell'indirizzo.")
            case .insecurePublicHost:
                return String(localized: "HTTP (non cifrato) è consentito solo per indirizzi della rete locale. Usa https://.")
            }
        }
    }

    public struct Valid: Equatable, Sendable {
        /// Indirizzo base con slash finale (es. `https://host/gokapi/`).
        public let url: URL
        public let isInsecureLocal: Bool
        /// Forma canonica salvata, senza slash finale.
        public var canonical: String {
            var s = url.absoluteString
            while s.hasSuffix("/") { s.removeLast() }
            return s
        }
    }

    private static let stripSuffixes = ["/api", "/admin", "/login", "/d", "/uploadrequest", "/apikeys", "/users", "/logs"]

    public static func parse(_ input: String) -> Result<Valid, Invalid> {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return .failure(.empty) }
        var text = trimmed
        if let r = text.range(of: "://") {
            let scheme = text[..<r.lowerBound].lowercased()
            guard scheme == "http" || scheme == "https" else { return .failure(.unsupportedScheme) }
        } else {
            text = "https://" + text
        }
        guard var comps = URLComponents(string: text), let host = comps.host, !host.isEmpty else {
            return .failure(.malformed)
        }
        if comps.user != nil || comps.password != nil { return .failure(.credentialsInURL) }
        comps.scheme = comps.scheme?.lowercased()
        comps.query = nil
        comps.fragment = nil
        var path = comps.path
        while path.hasSuffix("/") { path.removeLast() }
        var changed = true
        while changed {
            changed = false
            for s in stripSuffixes where path.lowercased().hasSuffix(s) {
                path.removeLast(s.count)
                while path.hasSuffix("/") { path.removeLast() }
                changed = true
            }
        }
        comps.path = path + "/"
        guard let url = comps.url else { return .failure(.malformed) }
        let insecure = comps.scheme != "https"
        if insecure && !isLocalHost(host) { return .failure(.insecurePublicHost) }
        return .success(Valid(url: url, isInsecureLocal: insecure))
    }

    /// Host di reti private/locali (RFC 1918, loopback, link-local, CGNAT/Tailscale, ULA, mDNS).
    public static func isLocalHost(_ host: String) -> Bool {
        let h = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        if h == "localhost" || [".localhost", ".local", ".lan", ".home.arpa", ".internal"].contains(where: h.hasSuffix) {
            return true
        }
        let parts = h.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        if parts.count == 4, parts.allSatisfy({ $0 != nil && (0...255).contains($0!) }) {
            let a = parts[0]!, b = parts[1]!
            return a == 10 || a == 127 || (a == 172 && (16...31).contains(b)) || (a == 192 && b == 168)
                || (a == 169 && b == 254) || (a == 100 && (64...127).contains(b))
        }
        if h.contains(":") {
            return h == "::1" || h.hasPrefix("fc") || h.hasPrefix("fd") || h.hasPrefix("fe80")
        }
        return false
    }
}

/// Suddivisione in chunk secondo i vincoli di Gokapi (vedi docs/GOKAPI_API.md).
public enum ChunkPlanner {
    public static let mb: Int64 = 1024 * 1024
    public static let minChunk: Int64 = 5 * mb
    public static let fallbackChunkMB = 45
    private static let multipartOverhead: Int64 = 64 * 1024

    public static func chunkSize(_ config: ServerConfig?) -> Int64 {
        let preferredMB = (config?.maxChunkSizeMB ?? 0) > 0 ? config!.maxChunkSizeMB : fallbackChunkMB
        var size = Int64(preferredMB) * mb
        if let maxMB = config?.maxFileSizeMB, maxMB > 0 {
            size = min(size, Int64(maxMB) * mb - multipartOverhead)
        }
        return max(size, minChunk)
    }

    public static func exceedsLimit(_ size: Int64, _ config: ServerConfig?) -> Bool {
        guard let maxMB = config?.maxFileSizeMB, maxMB > 0 else { return false }
        return size > Int64(maxMB) * mb
    }

    public struct Chunk: Equatable, Sendable {
        public let offset: Int64
        public let length: Int64
    }

    public static func plan(size: Int64, chunkSize: Int64) -> [Chunk] {
        precondition(size >= 0 && chunkSize > 0)
        if size == 0 { return [Chunk(offset: 0, length: 0)] }
        var out: [Chunk] = []
        var offset: Int64 = 0
        while offset < size {
            let len = min(chunkSize, size - offset)
            out.append(Chunk(offset: offset, length: len))
            offset += len
        }
        return out
    }

    /// Nome file per l'header `filename`: `base64:` se contiene caratteri non ASCII.
    public static func encodeFileName(_ name: String) -> String {
        var clean = name.replacingOccurrences(of: "\r", with: " ").replacingOccurrences(of: "\n", with: " ")
        if clean.trimmingCharacters(in: .whitespaces).isEmpty { clean = "file" }
        let ascii = clean.unicodeScalars.allSatisfy { $0.value >= 0x20 && $0.value <= 0x7E }
        if ascii && !clean.hasPrefix("base64:") { return clean }
        return "base64:" + Data(clean.utf8).base64EncodedString()
    }
}
