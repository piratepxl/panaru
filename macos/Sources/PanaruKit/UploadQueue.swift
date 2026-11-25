import Foundation
import Observation
import UniformTypeIdentifiers

public enum UploadState: String, Codable, Sendable { case queued, uploading, completed, failed, cancelled }

/// Voce della coda e della cronologia (salvata in `history.json`, senza password).
public struct UploadRecord: Codable, Identifiable, Hashable, Sendable {
    public var id: UUID
    public var serverId: UUID
    public var serverName: String
    public var sourcePath: String
    public var fileName: String
    public var sizeBytes: Int64
    public var mimeType: String?
    public var expiryDays: Int
    public var allowedDownloads: Int
    public var hasPassword: Bool
    public var state: UploadState
    public var errorMessage: String?
    public var fileId: String?
    public var url: String?
    public var expiresAt: Date?
    public var unlimitedTime: Bool = false
    public var unlimitedDownloads: Bool = false
    public var downloadsRemaining: Int?
    public var removedFromServer: Bool = false
    /// Rimossa dalla cronologia: resta salvata per non essere riaggiunta dalla sincronizzazione.
    /// Opzionale per compatibilità con le cronologie salvate prima della sincronizzazione.
    public var hidden: Bool?
    public var createdAt: Date
    public var completedAt: Date?

    public var isActive: Bool { state == .queued || state == .uploading }
    public var isHidden: Bool { hidden == true }
    /// Voce importata dal server: caricata da un altro dispositivo o dal web di Gokapi.
    public var fromOtherDevice: Bool { sourcePath.isEmpty }
    /// Voce che corrisponde a un file sul server (può essere sincronizzata).
    var isSynced: Bool { state == .completed && fileId != nil }
    public var isExpired: Bool {
        removedFromServer || (!unlimitedTime && (expiresAt.map { $0 <= Date() } ?? false))
    }
    public var hasUsableLink: Bool { state == .completed && url != nil && !isExpired }
}

public struct LiveProgress: Equatable, Sendable {
    public var sent: Int64
    public var total: Int64
    public var fraction: Double { total > 0 ? min(1, Double(sent) / Double(total)) : 0 }
}

/// Coda di upload con concorrenza configurabile, annullamento, retry e cronologia locale.
/// Le password restano solo in memoria.
@MainActor
@Observable
public final class UploadQueue {
    public private(set) var records: [UploadRecord] = []
    public private(set) var live: [UUID: LiveProgress] = [:]
    public var maxConcurrent: Int = 2 { didSet { pump() } }
    /// Chiamato quando un upload termina (successo o errore): copia link, notifiche…
    public var onFinished: ((UploadRecord) -> Void)?

    private let servers: ServerStore
    private let fileURL: URL
    private var tasks: [UUID: Task<Void, Never>] = [:]
    private var passwords: [UUID: String] = [:]
    private var userCancelled: Set<UUID> = []

    public init(servers: ServerStore, directory: URL = AppPaths.supportDirectory) {
        self.servers = servers
        self.fileURL = directory.appendingPathComponent("history.json")
        records = JSONFile.load([UploadRecord].self, from: fileURL) ?? []
        // Upload rimasti "in corso" da un'esecuzione precedente: interrotti (Gokapi non li riprende).
        var changed = false
        for i in records.indices where records[i].isActive {
            records[i].state = .failed
            records[i].errorMessage = String(localized: "Caricamento interrotto. Usa Riprova per ricominciare.")
            changed = true
        }
        if changed { persist() }
    }

    public var active: [UploadRecord] { records.filter(\.isActive) }
    public var history: [UploadRecord] { records.filter { !$0.isActive && !$0.isHidden } }

    public var overall: LiveProgress {
        let a = active
        return LiveProgress(sent: a.reduce(0) { $0 + (live[$1.id]?.sent ?? 0) },
                            total: a.reduce(0) { $0 + (live[$1.id]?.total ?? $1.sizeBytes) })
    }

    /// Espande le cartelle nei file contenuti (Gokapi non ha upload di cartelle: ogni file ha il suo link).
    public static func expand(_ urls: [URL]) -> [URL] {
        var out: [URL] = []
        let fm = FileManager.default
        for url in urls {
            var isDir: ObjCBool = false
            guard fm.fileExists(atPath: url.path, isDirectory: &isDir) else { continue }
            if isDir.boolValue {
                let e = fm.enumerator(at: url, includingPropertiesForKeys: [.isRegularFileKey], options: [.skipsHiddenFiles, .skipsPackageDescendants])
                while let f = e?.nextObject() as? URL {
                    if (try? f.resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true { out.append(f) }
                }
            } else {
                out.append(url)
            }
        }
        return out
    }

    @discardableResult
    public func enqueue(_ urls: [URL], serverId: UUID, options: UploadOptions) -> [UUID] {
        guard let server = servers.server(serverId) else { return [] }
        let now = Date()
        var ids: [UUID] = []
        for (i, url) in Self.expand(urls).enumerated() {
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize.map(Int64.init) ?? 0
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
            let r = UploadRecord(
                id: UUID(), serverId: server.id, serverName: server.name, sourcePath: url.path,
                fileName: url.lastPathComponent, sizeBytes: size, mimeType: mime,
                expiryDays: options.expiryDays, allowedDownloads: options.allowedDownloads,
                hasPassword: options.password != nil, state: .queued,
                createdAt: now.addingTimeInterval(Double(i) / 1000)
            )
            if let pw = options.password { passwords[r.id] = pw }
            records.insert(r, at: 0)
            ids.append(r.id)
        }
        persist()
        pump()
        return ids
    }

    public func cancel(_ id: UUID) {
        guard let i = index(id) else { return }
        if records[i].state == .queued {
            records[i].state = .cancelled
            passwords[id] = nil
            persist()
        } else if let t = tasks[id] {
            userCancelled.insert(id)
            t.cancel()
        }
    }

    /// `false` se l'upload era protetto da password e la password non è più disponibile.
    @discardableResult
    public func retry(_ id: UUID) -> Bool {
        guard let i = index(id), records[i].state == .failed || records[i].state == .cancelled else { return false }
        if records[i].hasPassword && passwords[id] == nil { return false }
        records[i].state = .queued
        records[i].errorMessage = nil
        persist()
        pump()
        return true
    }

    /// Rimuove dalla cronologia: le voci ancora sincronizzabili vengono nascoste, le altre eliminate.
    public func remove(_ id: UUID) {
        if let i = index(id), records[i].isSynced { records[i].hidden = true }
        records.removeAll { $0.id == id && !$0.isActive && !$0.isSynced }
        persist()
    }

    public func clearHistory() {
        for i in records.indices where records[i].isSynced { records[i].hidden = true }
        records.removeAll { !$0.isActive && !$0.isSynced }
        persist()
    }

    /// Elimina il file dal server (permesso DELETE) e lo segna nella cronologia.
    public func deleteFromServer(_ id: UUID) async throws {
        guard let r = records.first(where: { $0.id == id }), let fileId = r.fileId else { return }
        guard let client = servers.client(for: r.serverId) else { throw GokapiError.notFound }
        do { try await client.delete(id: fileId) } catch GokapiError.notFound {}
        if let i = index(id) { records[i].removedFromServer = true }
        persist()
    }

    public enum SyncResult: Equatable, Sendable { case ok, noPermission, failed }

    /// Sincronizza la cronologia con i file del server (`/files/list`, permesso VIEW):
    /// importa i file caricati da altri dispositivi o dal web, aggiorna download rimasti e
    /// scadenze, segna i file non più presenti. Le voci rimosse dall'utente restano nascoste.
    @discardableResult
    public func sync(serverId: UUID) async -> SyncResult {
        guard let server = servers.server(serverId), let client = servers.client(for: serverId) else { return .failed }
        let remote: [GokapiFile]
        do {
            remote = try await client.listFiles().filter { !$0.isPendingDeletion }
        } catch GokapiError.unauthorized {
            return .noPermission
        } catch {
            return .failed
        }
        var known: [String: Int] = [:]
        for i in records.indices where records[i].serverId == serverId {
            if let id = records[i].fileId { known[id] = i }
        }
        var imported: [UploadRecord] = []
        for f in remote {
            if let i = known[f.id] {
                records[i].removedFromServer = false
                records[i].downloadsRemaining = f.downloadsRemaining
                records[i].unlimitedDownloads = f.unlimitedDownloads
                records[i].unlimitedTime = f.unlimitedTime
                records[i].hasPassword = f.isPasswordProtected
                if f.expireAt > 0 { records[i].expiresAt = Date(timeIntervalSince1970: TimeInterval(f.expireAt)) }
            } else {
                let uploaded = f.uploadDate > 0 ? Date(timeIntervalSince1970: TimeInterval(f.uploadDate)) : Date()
                imported.append(UploadRecord(
                    id: UUID(), serverId: serverId, serverName: server.name, sourcePath: "",
                    fileName: f.name, sizeBytes: f.sizeBytes, mimeType: f.contentType.isEmpty ? nil : f.contentType,
                    expiryDays: 0, allowedDownloads: 0, hasPassword: f.isPasswordProtected, state: .completed,
                    fileId: f.id, url: f.urlDownload,
                    expiresAt: f.expireAt > 0 ? Date(timeIntervalSince1970: TimeInterval(f.expireAt)) : nil,
                    unlimitedTime: f.unlimitedTime, unlimitedDownloads: f.unlimitedDownloads,
                    downloadsRemaining: f.downloadsRemaining, createdAt: uploaded, completedAt: uploaded
                ))
            }
        }
        let remoteIds = Set(remote.map(\.id))
        for (id, i) in known where records[i].state == .completed && !remoteIds.contains(id) {
            records[i].removedFromServer = true
        }
        records.append(contentsOf: imported)
        records.sort { $0.createdAt > $1.createdAt }
        persist()
        return .ok
    }

    /// Attende la fine di tutti gli upload (usato nei test).
    public func waitUntilIdle() async {
        while !tasks.isEmpty || records.contains(where: { $0.state == .queued }) {
            try? await Task.sleep(for: .milliseconds(20))
        }
    }

    // MARK: motore

    private func index(_ id: UUID) -> Int? { records.firstIndex { $0.id == id } }

    private func pump() {
        while tasks.count < max(1, maxConcurrent),
              let next = records.filter({ $0.state == .queued }).min(by: { $0.createdAt < $1.createdAt }),
              let i = index(next.id) {
            records[i].state = .uploading
            let id = next.id
            tasks[id] = Task { [weak self] in await self?.process(id) }
        }
        persist()
    }

    private func process(_ id: UUID) async {
        guard let r = records.first(where: { $0.id == id }) else { return }
        defer {
            tasks[id] = nil
            live[id] = nil
            pump()
        }
        guard let client = servers.client(for: r.serverId) else {
            return finish(id, error: String(localized: "Il server di questo caricamento è stato rimosso."))
        }
        if r.hasPassword && passwords[id] == nil {
            return finish(id, error: String(localized: "Caricamento interrotto. Usa Riprova per ricominciare."))
        }
        do {
            let config = try await servers.config(for: r.serverId, client: client)
            live[id] = LiveProgress(sent: 0, total: r.sizeBytes)
            let file = try await client.upload(
                fileURL: URL(fileURLWithPath: r.sourcePath), fileName: r.fileName, mimeType: r.mimeType,
                options: UploadOptions(expiryDays: r.expiryDays, allowedDownloads: r.allowedDownloads, password: passwords[id]),
                config: config
            ) { [weak self] sent, total in
                Task { @MainActor in
                    guard let self, self.tasks[id] != nil else { return }
                    self.live[id] = LiveProgress(sent: sent, total: total)
                }
            }
            guard let i = index(id) else { return }
            records[i].state = .completed
            records[i].fileId = file.id
            records[i].url = file.urlDownload
            records[i].sizeBytes = max(records[i].sizeBytes, file.sizeBytes)
            records[i].expiresAt = file.expireAt > 0 ? Date(timeIntervalSince1970: TimeInterval(file.expireAt)) : nil
            records[i].unlimitedTime = file.unlimitedTime
            records[i].unlimitedDownloads = file.unlimitedDownloads
            records[i].downloadsRemaining = file.downloadsRemaining
            records[i].completedAt = Date()
            passwords[id] = nil
            persist()
            onFinished?(records[i])
        } catch {
            let wasUser = userCancelled.remove(id) != nil
            let cancelled = wasUser || Task.isCancelled || (error as? GokapiError) == .cancelled || error is CancellationError
            if cancelled && wasUser {
                if let i = index(id) { records[i].state = .cancelled }
                passwords[id] = nil
                persist()
            } else {
                finish(id, error: (error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
            }
        }
    }

    private func finish(_ id: UUID, error: String) {
        guard let i = index(id) else { return }
        records[i].state = .failed
        records[i].errorMessage = error
        persist()
        onFinished?(records[i])
    }

    private func persist() { JSONFile.save(records, to: fileURL) }
}
