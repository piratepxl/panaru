import PanaruKit
import SwiftUI
import UniformTypeIdentifiers

/// Finestra principale: area di rilascio, coda e cronologia, in stile vetro (macOS 26+).
struct MainView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openSettings) private var openSettings
    @LocalState private var dropTargeted = false
    @LocalState private var selection = Set<UUID>()

    var body: some View {
        @Bindable var model = model
        Group {
            if model.servers.servers.isEmpty {
                onboarding
            } else {
                content
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .panaruWindowBackground()
        .toolbar { toolbar }
        .hidingWindowTitle()
        .onDrop(of: [.fileURL], isTargeted: $dropTargeted) { providers in
            loadURLs(providers) { model.receive($0) }
            return true
        }
        .overlay { if dropTargeted { dropOverlay.transition(.opacity.combined(with: .scale(scale: 0.98))) } }
        .animation(.spring(duration: 0.35), value: dropTargeted)
        .animation(.spring(duration: 0.35), value: model.queue.active.map(\.id))
        .onAppear { model.syncAll() }
        .sheet(isPresented: Binding(get: { !model.pendingURLs.isEmpty }, set: { if !$0 { model.pendingURLs = [] } })) {
            OptionsSheet(urls: model.pendingURLs)
        }
        .alert(model.banner ?? "", isPresented: Binding(get: { model.banner != nil }, set: { if !$0 { model.banner = nil } })) {
            Button(String(localized: "Apri Impostazioni")) { openSettings() }
            Button(String(localized: "Chiudi"), role: .cancel) {}
        }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            WordmarkView(height: 17).padding(.horizontal, 6)
        }
        if !model.servers.servers.isEmpty {
            ToolbarItem(placement: .primaryAction) { ServerMenu() }
            ToolbarItem(placement: .primaryAction) {
                Button { model.chooseFiles() } label: {
                    Label(String(localized: "Carica file"), systemImage: "arrow.up")
                }
                .labelStyle(.titleAndIcon)
                .panaruProminentButton()
                .help(String(localized: "Scegli i file da caricare (⌘O)"))
            }
        }
    }

    // MARK: Benvenuto

    private var onboarding: some View {
        VStack(spacing: 16) {
            SymbolView(height: 80)
                .shadow(color: PanaruColors.brand.opacity(0.35), radius: 24, y: 10)
            Text(String(localized: "Benvenuto in Panaru")).font(.largeTitle.weight(.bold))
            Text(String(localized: "Collega il tuo server Gokapi con indirizzo e chiave API: da quel momento basta trascinare un file qui per avere il link."))
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 380)
            Button { openSettings() } label: {
                Label(String(localized: "Aggiungi server"), systemImage: "plus").padding(.horizontal, 6)
            }
            .controlSize(.large)
            .panaruProminentButton()
            .padding(.top, 4)
        }
        .padding(36)
        .panaruGlass(cornerRadius: 28)
        .padding(32)
    }

    // MARK: Contenuto

    private var content: some View {
        List(selection: $selection) {
            dropZone
                .plainRow(top: 8, bottom: 14)
                .selectionDisabled()

            if !model.queue.active.isEmpty {
                SectionLabel(title: String(localized: "In corso")).plainRow(bottom: 4).selectionDisabled()
                if model.queue.active.count > 1 {
                    OverallProgressRow().plainRow().selectionDisabled()
                }
                ForEach(model.queue.active) { ActiveRow(record: $0).plainRow().selectionDisabled() }
            }

            if !model.queue.history.isEmpty {
                SectionLabel(title: String(localized: "Cronologia")) {
                    if model.syncing { ProgressView().controlSize(.small) }
                    Button { model.syncAll(manual: true) } label: {
                        Label(String(localized: "Sincronizza"), systemImage: "arrow.triangle.2.circlepath")
                    }
                    .controlSize(.small)
                    .panaruGlassButton()
                    .disabled(model.syncing)
                    .help(String(localized: "Aggiorna la cronologia con i file presenti sul server, anche quelli caricati da altri dispositivi"))
                }
                .plainRow(top: 10, bottom: 4)
                .selectionDisabled()
                ForEach(model.queue.history) { HistoryRow(record: $0).plainRow().tag($0.id) }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .contextMenu(forSelectionType: UUID.self) { ids in
            let links = model.queue.history.filter { ids.contains($0.id) && $0.hasUsableLink }.compactMap(\.url)
            if !links.isEmpty {
                Button(links.count == 1 ? String(localized: "Copia link") : String(localized: "Copia \(links.count) link")) { model.copy(links) }
                ShareLink(items: links.compactMap { URL(string: $0) }) { Text(String(localized: "Condividi…")) }
            }
        }
        .onCopyCommand {
            let links = model.queue.history.filter { selection.contains($0.id) && $0.hasUsableLink }.compactMap(\.url)
            return links.isEmpty ? [] : [NSItemProvider(object: links.joined(separator: "\n") as NSString)]
        }
    }

    private var dropZone: some View {
        Button { model.chooseFiles() } label: {
            HStack(spacing: 16) {
                ZStack {
                    Circle().fill(PanaruColors.brand.gradient).frame(width: 52, height: 52)
                        .shadow(color: PanaruColors.brand.opacity(0.4), radius: 10, y: 4)
                    Image(systemName: "arrow.up").font(.title2.weight(.bold)).foregroundStyle(.white)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(String(localized: "Trascina qui file o cartelle")).font(.title3.weight(.semibold))
                    Text(String(localized: "Anche sull'icona nel Dock, o dal Finder: clic destro → Servizi → Carica con Panaru."))
                        .font(.callout).foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(18)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .panaruGlass(cornerRadius: 22, tint: PanaruColors.brand.opacity(0.18), interactive: true)
    }

    private var dropOverlay: some View {
        VStack(spacing: 12) {
            SymbolView(height: 64)
                .shadow(color: PanaruColors.brand.opacity(0.45), radius: 20, y: 8)
            Text(String(localized: "Rilascia per caricare su \(model.targetServer?.name ?? "")"))
                .font(.title2.weight(.semibold))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .panaruGlass(cornerRadius: 26, tint: PanaruColors.brand.opacity(0.35))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous)
            .strokeBorder(PanaruColors.brand.opacity(0.7), style: StrokeStyle(lineWidth: 2, dash: [8, 6])))
        .padding(14)
        .allowsHitTesting(false)
    }
}

extension View {
    /// Riga di List senza separatori né sfondo: il vetro della riga fa da superficie.
    func plainRow(top: CGFloat = 4, bottom: CGFloat = 4) -> some View {
        self
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets(top: top, leading: 16, bottom: bottom, trailing: 16))
    }
}

/// Carica gli URL dei file trascinati.
func loadURLs(_ providers: [NSItemProvider], completion: @escaping @MainActor ([URL]) -> Void) {
    let group = DispatchGroup()
    let lock = NSLock()
    var urls: [URL] = []
    for p in providers where p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
        group.enter()
        _ = p.loadObject(ofClass: URL.self) { url, _ in
            if let url { lock.withLock { urls.append(url) } }
            group.leave()
        }
    }
    group.notify(queue: .main) { MainActor.assumeIsolated { completion(urls) } }
}

struct ServerMenu: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        Menu {
            ForEach(model.servers.servers) { s in
                Button { model.selectedServerId = s.id } label: {
                    if s.id == model.targetServer?.id { Label(s.name, systemImage: "checkmark") } else { Text(s.name) }
                }
            }
        } label: {
            Label(model.targetServer?.name ?? "", systemImage: "server.rack")
        }
        .fixedSize()
        .help(String(localized: "Server di destinazione"))
    }
}


/// Icona del file: quella del Finder se il file è sul disco, altrimenti quella del tipo (dall'estensione).
struct FileIcon: View {
    let path: String
    let name: String
    var size: CGFloat = 34

    var body: some View {
        Image(nsImage: icon).resizable().interpolation(.high).frame(width: size, height: size)
    }

    private var icon: NSImage {
        if !path.isEmpty, FileManager.default.fileExists(atPath: path) { return NSWorkspace.shared.icon(forFile: path) }
        let ext = (name as NSString).pathExtension
        return NSWorkspace.shared.icon(for: UTType(filenameExtension: ext) ?? .data)
    }
}

/// Barra di avanzamento sottile e arrotondata, con riempimento sfumato.
struct SlimProgress: View {
    var fraction: Double?
    var tint: Color = PanaruColors.transfer

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(.primary.opacity(0.08))
                if let fraction {
                    Capsule().fill(tint.gradient)
                        .frame(width: max(6, geo.size.width * min(1, max(0, fraction))))
                        .animation(.smooth(duration: 0.3), value: fraction)
                } else {
                    ProgressView().progressViewStyle(.linear).tint(tint)
                }
            }
        }
        .frame(height: 6)
    }
}

struct OverallProgressRow: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        let p = model.queue.overall
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(String(localized: "Totale")).font(.callout.weight(.semibold))
                Spacer()
                Text(String(localized: "\(bytes(p.sent)) di \(bytes(p.total))")).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            }
            SlimProgress(fraction: p.fraction)
        }
        .padding(14)
        .panaruGlass(cornerRadius: 16, tint: PanaruColors.transfer.opacity(0.10))
    }
}

struct ActiveRow: View {
    @Environment(AppModel.self) private var model
    let record: UploadRecord
    var body: some View {
        HStack(spacing: 12) {
            FileIcon(path: record.sourcePath, name: record.fileName, size: 30)
            VStack(alignment: .leading, spacing: 6) {
                Text(record.fileName).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                if let p = model.queue.live[record.id] {
                    SlimProgress(fraction: p.fraction)
                    Text("\(bytes(p.sent)) / \(bytes(p.total)) · \(record.serverName)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                } else {
                    SlimProgress(fraction: nil)
                    Text(String(localized: "In attesa…")).font(.caption).foregroundStyle(.secondary)
                }
            }
            Button { model.queue.cancel(record.id) } label: { Image(systemName: "xmark") }
                .panaruGlassButton(circle: true)
                .help(String(localized: "Annulla caricamento"))
        }
        .padding(12)
        .panaruGlass(cornerRadius: 16)
    }
}

struct HistoryRow: View {
    @Environment(AppModel.self) private var model
    let record: UploadRecord
    @LocalState private var confirmDelete = false
    @LocalState private var copied = false

    var body: some View {
        HStack(spacing: 12) {
            FileIcon(path: record.fromOtherDevice ? "" : record.sourcePath, name: record.fileName)
                .overlay(alignment: .bottomTrailing) { statusBadge.offset(x: 4, y: 4) }
                .opacity(record.hasUsableLink || record.state != .completed ? 1 : 0.55)
            VStack(alignment: .leading, spacing: 3) {
                Text(record.fileName).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Text("\(bytes(record.sizeBytes)) · \(record.serverName) · \((record.completedAt ?? record.createdAt).formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                HStack(spacing: 6) {
                    if record.fromOtherDevice {
                        Label(String(localized: "Caricato da un altro dispositivo"), systemImage: "laptopcomputer.and.iphone")
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(PanaruColors.arancio)
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .panaruGlassCapsule(tint: PanaruColors.brand.opacity(0.15))
                    }
                    let status = statusLine
                    if !status.isEmpty {
                        Text(status).font(.caption)
                            .foregroundStyle(record.state == .failed ? PanaruColors.error : .secondary)
                            .lineLimit(2)
                    }
                }
            }
            Spacer(minLength: 8)
            if record.hasUsableLink, let url = record.url {
                Button {
                    model.copy([url])
                    withAnimation(.spring(duration: 0.3)) { copied = true }
                    Task { try? await Task.sleep(for: .seconds(1.5)); withAnimation { copied = false } }
                } label: {
                    Label(copied ? String(localized: "Copiato") : String(localized: "Copia"),
                          systemImage: copied ? "checkmark" : "link")
                        .contentTransition(.symbolEffect(.replace))
                }
                .controlSize(.small)
                .panaruGlassButton()
                .help(String(localized: "Copia link"))
            }
            Menu {
                if record.hasUsableLink, let url = record.url {
                    Button(String(localized: "Copia link")) { model.copy([url]) }
                    ShareLink(item: URL(string: url)!) { Text(String(localized: "Condividi…")) }
                    Button(String(localized: "Apri link")) { model.open(url) }
                    Divider()
                    Button(String(localized: "Elimina dal server…")) { confirmDelete = true }
                }
                if record.state == .failed || record.state == .cancelled {
                    Button(String(localized: "Riprova")) {
                        if !model.queue.retry(record.id) {
                            model.banner = String(localized: "Questo caricamento era protetto da password e la password non viene conservata dopo un riavvio: carica di nuovo il file.")
                        }
                    }
                }
                if !record.fromOtherDevice {
                    Button(String(localized: "Mostra nel Finder")) { NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: record.sourcePath)]) }
                }
                Button(String(localized: "Rimuovi dalla cronologia")) { model.queue.remove(record.id) }
            } label: { Image(systemName: "ellipsis") }
                .menuStyle(.button)
                .menuIndicator(.hidden)
                .controlSize(.small)
                .panaruGlassButton(circle: true)
                .fixedSize()
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .panaruGlass(cornerRadius: 16)
        .confirmationDialog(String(localized: "Eliminare dal server?"), isPresented: $confirmDelete) {
            Button(String(localized: "Elimina"), role: .destructive) {
                Task {
                    do { try await model.queue.deleteFromServer(record.id) } catch {
                        model.banner = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                    }
                }
            }
        } message: {
            Text(String(localized: "“\(record.fileName)” verrà eliminato da \(record.serverName) e il link smetterà di funzionare. L'operazione non è reversibile."))
        }
    }

    @ViewBuilder private var statusBadge: some View {
        let (symbol, color): (String, Color) = switch record.state {
        case .completed: ("checkmark.circle.fill", record.hasUsableLink ? PanaruColors.success : .secondary)
        case .failed: ("exclamationmark.circle.fill", PanaruColors.error)
        default: ("xmark.circle.fill", .secondary)
        }
        Image(systemName: symbol)
            .font(.system(size: 13, weight: .semibold))
            .symbolRenderingMode(.palette)
            .foregroundStyle(.white, color)
            .background(Circle().fill(.background).padding(1))
    }

    private var statusLine: String {
        switch record.state {
        case .failed: return record.errorMessage ?? ""
        case .cancelled: return String(localized: "Annullato")
        case .completed:
            if record.removedFromServer { return String(localized: "Non più sul server") }
            if record.isExpired { return String(localized: "Scaduto") }
            var parts: [String] = []
            if record.unlimitedTime { parts.append(String(localized: "Nessuna scadenza")) }
            else if let e = record.expiresAt { parts.append(String(localized: "Scade il \(e.formatted(date: .abbreviated, time: .shortened))")) }
            if record.unlimitedDownloads { parts.append(String(localized: "Download illimitati")) }
            else if let n = record.downloadsRemaining { parts.append(String(localized: "Download rimasti: \(n)")) }
            if record.hasPassword { parts.append(String(localized: "Password")) }
            return parts.joined(separator: " · ")
        default: return ""
        }
    }
}

func bytes(_ n: Int64) -> String { ByteCountFormatter.string(fromByteCount: n, countStyle: .file) }

/// Opzioni prima del caricamento (selettore o drop con "Carica subito" disattivato).
struct OptionsSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let urls: [URL]
    @LocalState private var expiry = 14
    @LocalState private var downloads = 1
    @LocalState private var password = ""
    @LocalState private var serverId: UUID?

    var body: some View {
        let count = UploadQueue.expand(urls).count
        Form {
            Section {
                Text(count == 1 ? String(localized: "Carica 1 file") : String(localized: "Carica \(count) file")).font(.headline)
                Picker(String(localized: "Server"), selection: $serverId) {
                    ForEach(model.servers.servers) { Text($0.name).tag(Optional($0.id)) }
                }
            }
            Section {
                OptionsFields(expiry: $expiry, downloads: $downloads)
                SecureField(String(localized: "Password (facoltativa)"), text: $password)
                if !UploadOptions.isValidPassword(password) {
                    Text(String(localized: "Usa solo lettere, numeri e simboli ASCII.")).font(.caption).foregroundStyle(PanaruColors.error)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 420)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(String(localized: "Annulla")) { model.pendingURLs = []; dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(String(localized: "Carica")) {
                    guard let id = serverId ?? model.targetServer?.id else { return }
                    model.upload(urls, serverId: id, options: UploadOptions(expiryDays: expiry, allowedDownloads: downloads, password: password))
                    dismiss()
                }
                .disabled(count == 0 || !UploadOptions.isValidPassword(password))
            }
        }
        .onAppear {
            expiry = model.prefs.defaultExpiryDays
            downloads = model.prefs.defaultDownloads
            serverId = model.targetServer?.id
        }
    }
}

struct OptionsFields: View {
    @Binding var expiry: Int
    @Binding var downloads: Int
    static let expiryChoices = [1, 3, 7, 14, 30, 0]
    static let downloadChoices = [1, 3, 5, 10, 0]

    var body: some View {
        Picker(String(localized: "Scadenza"), selection: $expiry) {
            ForEach(Self.expiryChoices, id: \.self) { d in
                Text(d == 0 ? String(localized: "Mai") : (d == 1 ? String(localized: "1 giorno") : String(localized: "\(d) giorni"))).tag(d)
            }
        }
        Picker(String(localized: "Download consentiti"), selection: $downloads) {
            ForEach(Self.downloadChoices, id: \.self) { n in
                Text(n == 0 ? String(localized: "Illimitati") : "\(n)").tag(n)
            }
        }
    }
}
