import AppKit
import Observation
import PanaruKit
import SwiftUI
import UserNotifications

/// Preferenze utente (UserDefaults: nessun segreto).
@MainActor
@Observable
final class Preferences {
    private let d = UserDefaults.standard

    var autoCopyLink: Bool { didSet { d.set(autoCopyLink, forKey: "autoCopyLink") } }
    var notify: Bool { didSet { d.set(notify, forKey: "notify") } }
    var maxConcurrent: Int { didSet { d.set(maxConcurrent, forKey: "maxConcurrent") } }
    var defaultExpiryDays: Int { didSet { d.set(defaultExpiryDays, forKey: "defaultExpiryDays") } }
    var defaultDownloads: Int { didSet { d.set(defaultDownloads, forKey: "defaultDownloads") } }
    /// Drop su Dock / Servizi / menu: carica subito con le opzioni predefinite.
    var quickUpload: Bool { didSet { d.set(quickUpload, forKey: "quickUpload") } }

    init() {
        d.register(defaults: ["autoCopyLink": true, "notify": true, "maxConcurrent": 2,
                              "defaultExpiryDays": 14, "defaultDownloads": 1, "quickUpload": true])
        autoCopyLink = d.bool(forKey: "autoCopyLink")
        notify = d.bool(forKey: "notify")
        maxConcurrent = min(4, max(1, d.integer(forKey: "maxConcurrent")))
        defaultExpiryDays = max(0, d.integer(forKey: "defaultExpiryDays"))
        defaultDownloads = max(0, d.integer(forKey: "defaultDownloads"))
        quickUpload = d.bool(forKey: "quickUpload")
    }

    var defaultOptions: UploadOptions { UploadOptions(expiryDays: defaultExpiryDays, allowedDownloads: defaultDownloads) }
}

/// Stato condiviso dell'app: server, coda, preferenze, azioni sui link.
@MainActor
@Observable
final class AppModel {
    static let shared = AppModel()

    let prefs = Preferences()
    let servers = ServerStore()
    let queue: UploadQueue
    /// Server scelto come destinazione nella finestra principale.
    var selectedServerId: UUID?
    /// File in attesa delle opzioni (drop nella finestra con "Chiedi opzioni").
    var pendingURLs: [URL] = []
    var banner: String?

    private init() {
        queue = UploadQueue(servers: servers)
        queue.maxConcurrent = prefs.maxConcurrent
        selectedServerId = servers.defaultServer?.id
        queue.onFinished = { [weak self] record in self?.finished(record) }
        Notifier.requestAuthorizationIfPossible()
        NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { AppModel.shared.syncAll() }
        }
    }

    var targetServer: Server? {
        selectedServerId.flatMap { servers.server($0) } ?? servers.defaultServer
    }

    /// Ingresso unico per file da finestra, Dock, Servizi e Menu Bar.
    func receive(_ urls: [URL], askOptions: Bool = false) {
        let files = urls.filter { $0.isFileURL }
        guard !files.isEmpty else { return }
        guard let server = targetServer else {
            banner = String(localized: "Configura prima un server Gokapi nelle Impostazioni.")
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        if askOptions || !prefs.quickUpload {
            pendingURLs = files
            NSApp.activate(ignoringOtherApps: true)
        } else {
            queue.enqueue(files, serverId: server.id, options: prefs.defaultOptions)
        }
    }

    // MARK: sincronizzazione

    private(set) var syncing = false
    private var lastSync = Date.distantPast

    /// Sincronizza la cronologia con tutti i server: compaiono anche i file caricati da altri
    /// dispositivi. Automatica all'avvio e quando l'app torna attiva (max ogni 30 s).
    func syncAll(manual: Bool = false) {
        guard !syncing, manual || Date().timeIntervalSince(lastSync) > 30 else { return }
        lastSync = Date()
        syncing = true
        Task {
            var noView: [String] = [], failed: [String] = []
            for s in servers.servers {
                switch await queue.sync(serverId: s.id) {
                case .ok: break
                case .noPermission: noView.append(s.name)
                case .failed: failed.append(s.name)
                }
            }
            syncing = false
            guard manual else { return }
            if !noView.isEmpty {
                banner = String(localized: "Per vedere i file caricati da altri dispositivi, la chiave API di \(noView.joined(separator: ", ")) deve avere il permesso View.")
            } else if !failed.isEmpty {
                banner = String(localized: "Sincronizzazione con \(failed.joined(separator: ", ")) non riuscita. Controlla la connessione.")
            }
        }
    }

    func upload(_ urls: [URL], serverId: UUID, options: UploadOptions) {
        queue.enqueue(urls, serverId: serverId, options: options)
        pendingURLs = []
    }

    private func finished(_ r: UploadRecord) {
        if r.state == .completed, let url = r.url {
            if prefs.autoCopyLink { copy([url]) }
            if prefs.notify { Notifier.linkReady(r, copied: prefs.autoCopyLink) }
        } else if r.state == .failed, prefs.notify {
            Notifier.failed(r)
        }
    }

    func copy(_ urls: [String]) {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(urls.joined(separator: "\n"), forType: .string)
    }

    func open(_ url: String) {
        if let u = URL(string: url) { NSWorkspace.shared.open(u) }
    }

    /// Selettore di file nativo (file e cartelle, selezione multipla).
    func chooseFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        panel.prompt = String(localized: "Carica")
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { receive(panel.urls, askOptions: true) }
    }
}

/// Notifiche native (disponibili solo quando l'app gira come bundle .app firmato).
enum Notifier {
    private static var available: Bool { Bundle.main.bundleIdentifier != nil && Bundle.main.bundleURL.pathExtension == "app" }

    static func requestAuthorizationIfPossible() {
        guard available else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func linkReady(_ r: UploadRecord, copied: Bool) {
        let c = UNMutableNotificationContent()
        c.title = String(localized: "Link pronto · \(r.fileName)")
        c.body = copied ? String(localized: "Link copiato negli appunti") : (r.url ?? "")
        post(c, id: r.id.uuidString)
    }

    static func failed(_ r: UploadRecord) {
        let c = UNMutableNotificationContent()
        c.title = String(localized: "Caricamento non riuscito: \(r.fileName)")
        c.body = r.errorMessage ?? ""
        post(c, id: r.id.uuidString)
    }

    private static func post(_ content: UNMutableNotificationContent, id: String) {
        guard available else { return }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: id, content: content, trigger: nil))
    }
}
