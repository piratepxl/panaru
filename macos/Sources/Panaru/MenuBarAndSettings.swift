import PanaruKit
import SwiftUI

// MARK: - Barra dei menu

struct MenuBarLabel: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        // Glifo derivato dall'icona dell'app; variante con freccia durante i trasferimenti.
        if let img = BrandImage.named(model.queue.active.isEmpty ? "MenuBarIcon" : "MenuBarIconActive") {
            Image(nsImage: img)
        } else {
            Image(systemName: model.queue.active.isEmpty ? "basket" : "arrow.up.circle")
        }
    }
}

/// Accesso rapido: stato degli upload, ultimi link, drop e selettore (pannello di vetro).
struct MenuBarContent: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings
    @LocalState private var targeted = false

    var body: some View {
        GlassGroup(spacing: 10) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    WordmarkView(height: 14)
                    Spacer()
                    if model.servers.servers.count > 1 { ServerMenu().controlSize(.small) }
                }
                .padding(.horizontal, 4)

                dropArea

                if !model.queue.active.isEmpty {
                    ForEach(model.queue.active.prefix(4)) { ActiveRow(record: $0) }
                }

                let recent = model.queue.history.filter(\.hasUsableLink).prefix(5)
                if !recent.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        SectionLabel(title: String(localized: "Link recenti")).padding(.bottom, 4)
                        ForEach(Array(recent)) { RecentLinkRow(record: $0) }
                    }
                    .padding(8)
                    .panaruGlass(cornerRadius: 16)
                }

                HStack(spacing: 8) {
                    Button { openWindow(id: "main"); NSApp.activate(ignoringOtherApps: true) } label: {
                        Label(String(localized: "Apri Panaru"), systemImage: "macwindow")
                    }
                    .panaruGlassButton()
                    Spacer()
                    Button { openSettings(); NSApp.activate(ignoringOtherApps: true) } label: { Image(systemName: "gearshape") }
                        .panaruGlassButton(circle: true)
                        .help(String(localized: "Impostazioni"))
                    Button { NSApp.terminate(nil) } label: { Image(systemName: "power") }
                        .panaruGlassButton(circle: true)
                        .help(String(localized: "Esci da Panaru"))
                }
            }
            .padding(14)
        }
        .frame(width: 340)
        .background { AmbientBackground() }
        .animation(.spring(duration: 0.3), value: targeted)
    }

    private var dropArea: some View {
        Button { model.chooseFiles() } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(PanaruColors.brand.gradient).frame(width: 38, height: 38)
                    Image(systemName: targeted ? "arrow.down" : "arrow.up")
                        .font(.headline.weight(.bold)).foregroundStyle(.white)
                        .contentTransition(.symbolEffect(.replace))
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.servers.servers.isEmpty ? String(localized: "Configura un server") : String(localized: "Trascina qui o fai clic per scegliere"))
                        .font(.callout.weight(.semibold))
                    if let name = model.targetServer?.name {
                        Text(name).font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .panaruGlass(cornerRadius: 18, tint: PanaruColors.brand.opacity(targeted ? 0.40 : 0.16), interactive: true)
        .scaleEffect(targeted ? 1.02 : 1)
        .disabled(model.servers.servers.isEmpty)
        .onDrop(of: [.fileURL], isTargeted: $targeted) { providers in
            loadURLs(providers) { model.receive($0) }
            return true
        }
    }
}

/// Riga "link recente": un clic copia il link, con evidenziazione al passaggio del mouse.
struct RecentLinkRow: View {
    @Environment(AppModel.self) private var model
    let record: UploadRecord
    @LocalState private var hover = false
    @LocalState private var copied = false

    var body: some View {
        Button {
            model.copy([record.url!])
            withAnimation { copied = true }
            Task { try? await Task.sleep(for: .seconds(1.2)); withAnimation { copied = false } }
        } label: {
            HStack(spacing: 10) {
                FileIcon(path: record.fromOtherDevice ? "" : record.sourcePath, name: record.fileName, size: 22)
                Text(record.fileName).lineLimit(1).truncationMode(.middle)
                Spacer()
                Image(systemName: copied ? "checkmark" : "link")
                    .foregroundStyle(copied ? PanaruColors.success : .secondary)
                    .contentTransition(.symbolEffect(.replace))
            }
            .padding(.horizontal, 8).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(.primary.opacity(hover ? 0.08 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .help(String(localized: "Copia link"))
    }
}

// MARK: - Impostazioni

struct SettingsView: View {
    var body: some View {
        TabView {
            GeneralSettings().tabItem { Label(String(localized: "Generale"), systemImage: "gearshape") }
            ServersSettings().tabItem { Label(String(localized: "Server"), systemImage: "server.rack") }
            AboutView().tabItem { Label(String(localized: "Informazioni"), systemImage: "info.circle") }
        }
        .frame(width: 560, height: 440)
    }
}

struct GeneralSettings: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        @Bindable var prefs = model.prefs
        Form {
            Section(String(localized: "Condivisione")) {
                Toggle(String(localized: "Copia automaticamente il link negli appunti"), isOn: $prefs.autoCopyLink)
                Toggle(String(localized: "Mostra notifiche"), isOn: $prefs.notify)
                Toggle(String(localized: "Carica subito i file trascinati sul Dock o inviati dai Servizi"), isOn: $prefs.quickUpload)
                Stepper(String(localized: "Caricamenti contemporanei: \(prefs.maxConcurrent)"), value: $prefs.maxConcurrent, in: 1...4)
                    .onChange(of: prefs.maxConcurrent) { _, v in model.queue.maxConcurrent = v }
            }
            Section(String(localized: "Opzioni predefinite")) {
                OptionsFields(expiry: $prefs.defaultExpiryDays, downloads: $prefs.defaultDownloads)
            }
            Section {
                Button(String(localized: "Svuota cronologia…"), role: .destructive) { model.queue.clearHistory() }
                Text(String(localized: "I file restano sul server fino alla scadenza: viene svuotato solo l'elenco su questo Mac."))
                    .font(.caption).foregroundStyle(PanaruColors.secondaryText)
            }
        }
        .formStyle(.grouped)
    }
}

struct ServersSettings: View {
    @Environment(AppModel.self) private var model
    @LocalState private var editing: EditorTarget?
    @LocalState private var browsing: Server?
    @LocalState private var confirmRemove: Server?

    struct EditorTarget: Identifiable { let id = UUID(); let server: Server? }

    var body: some View {
        VStack(alignment: .leading) {
            List {
                ForEach(model.servers.servers) { s in
                    HStack {
                        Image(systemName: "server.rack").foregroundStyle(PanaruColors.arancio)
                        VStack(alignment: .leading) {
                            HStack {
                                Text(s.name).font(.headline)
                                if s.isDefault {
                                    Text(String(localized: "Predefinito")).font(.caption)
                                        .padding(.horizontal, 6).padding(.vertical, 1)
                                        .background(Capsule().fill(PanaruColors.arancioTenue))
                                }
                            }
                            Text(s.baseURL).font(.caption).foregroundStyle(PanaruColors.secondaryText)
                        }
                        Spacer()
                        Menu {
                            Button(String(localized: "Modifica…")) { editing = EditorTarget(server: s) }
                            Button(String(localized: "File sul server…")) { browsing = s }
                            if !s.isDefault { Button(String(localized: "Usa come predefinito")) { model.servers.setDefault(s.id) } }
                            Divider()
                            Button(String(localized: "Rimuovi…"), role: .destructive) { confirmRemove = s }
                        } label: { Image(systemName: "ellipsis.circle") }
                            .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
                    }
                    .padding(.vertical, 4)
                }
            }
            HStack {
                Button { editing = EditorTarget(server: nil) } label: { Label(String(localized: "Aggiungi server"), systemImage: "plus") }
                Spacer()
                Text(String(localized: "Le chiavi API sono salvate nel Portachiavi di macOS."))
                    .font(.caption).foregroundStyle(PanaruColors.secondaryText)
            }
        }
        .padding()
        .sheet(item: $editing) { ServerEditor(existing: $0.server) }
        .sheet(item: $browsing) { ServerFilesView(server: $0) }
        .confirmationDialog(String(localized: "Rimuovere \(confirmRemove?.name ?? "")?"), isPresented: Binding(get: { confirmRemove != nil }, set: { if !$0 { confirmRemove = nil } })) {
            Button(String(localized: "Rimuovi"), role: .destructive) {
                if let s = confirmRemove { model.servers.remove(s.id) }
            }
        } message: {
            Text(String(localized: "La chiave viene eliminata da questo Mac. I file sul server non vengono toccati; la cronologia resta."))
        }
    }
}

/// Aggiunta/modifica server: il salvataggio richiede una verifica riuscita quando cambiano indirizzo o chiave.
struct ServerEditor: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let existing: Server?
    @LocalState private var name = ""
    @LocalState private var url = ""
    @LocalState private var key = ""
    @LocalState private var makeDefault = false
    @LocalState private var checking = false
    @LocalState private var result: ConnectionCheck?
    @LocalState private var checkedFor: String?
    @LocalState private var saveError: String?

    var body: some View {
        let parsed = ServerURL.parse(url)
        let valid = try? parsed.get()
        let changed = existing == nil || url.trimmingCharacters(in: .whitespaces) != existing?.baseURL || !key.isEmpty
        let verified: Bool = { if case .ok = result, checkedFor == url + "\u{0}" + key { return true }; return false }()
        Form {
            TextField(String(localized: "Nome"), text: $name, prompt: Text(String(localized: "es. Personale, Lavoro")))
            TextField(String(localized: "Indirizzo"), text: $url, prompt: Text("https://files.example.com"))
                .onChange(of: url) { result = nil }
            if case .failure(let e) = parsed, !url.isEmpty {
                Text(e.message).font(.caption).foregroundStyle(PanaruColors.error)
            } else if valid?.isInsecureLocal == true {
                Text(String(localized: "Connessione non cifrata (HTTP): consentita solo sulla rete locale.")).font(.caption).foregroundStyle(PanaruColors.warning)
            }
            SecureField(String(localized: "Chiave API"), text: $key,
                        prompt: Text(existing == nil ? String(localized: "Creala in Gokapi → API, con il permesso Upload") : String(localized: "Lascia vuoto per mantenere quella salvata")))
                .onChange(of: key) { result = nil }
            Toggle(String(localized: "Usa come predefinito"), isOn: $makeDefault)
            HStack {
                Button(String(localized: "Verifica connessione")) {
                    guard let valid else { return }
                    checking = true
                    Task {
                        if key.isEmpty, let existing { result = await model.servers.checkSaved(existing.id, url: valid) }
                        else { result = await model.servers.check(valid, apiKey: key) }
                        checkedFor = url + "\u{0}" + key
                        checking = false
                    }
                }
                .disabled(valid == nil || (existing == nil && key.isEmpty) || checking)
                if checking { ProgressView().controlSize(.small) }
            }
            if let result { CheckResultView(result: result) }
            if let saveError { Text(saveError).foregroundStyle(PanaruColors.error) }
            Text(String(localized: "Permessi facoltativi della chiave: View (stato dei file), Delete (eliminazione), Download (download)."))
                .font(.caption).foregroundStyle(PanaruColors.secondaryText)
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button(String(localized: "Annulla")) { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button(String(localized: "Salva")) {
                    guard let valid else { return }
                    do {
                        if let existing {
                            try model.servers.update(existing.id, name: name, url: valid, newApiKey: key)
                            if makeDefault { model.servers.setDefault(existing.id) }
                        } else {
                            let s = try model.servers.add(name: name, url: valid, apiKey: key, makeDefault: makeDefault)
                            if model.selectedServerId == nil { model.selectedServerId = s.id }
                            model.syncAll(manual: true) // mostra subito i file già presenti sul server
                        }
                        dismiss()
                    } catch {
                        saveError = error.localizedDescription
                    }
                }
                .disabled(valid == nil || (changed && !verified))
            }
        }
        .onAppear {
            name = existing?.name ?? ""
            url = existing?.baseURL ?? ""
            makeDefault = existing?.isDefault ?? model.servers.servers.isEmpty
        }
    }
}

struct CheckResultView: View {
    let result: ConnectionCheck
    var body: some View {
        switch result {
        case .ok(let v, let c):
            VStack(alignment: .leading, spacing: 4) {
                Label(String(localized: "Connesso · Gokapi \(v.version)"), systemImage: "checkmark.circle.fill").foregroundStyle(PanaruColors.success)
                if c.maxFileSizeMB > 0 {
                    Text(String(localized: "Dimensione massima per file: \(bytes(Int64(c.maxFileSizeMB) * 1024 * 1024))")).font(.caption)
                }
                if !v.supportsBase64FileNames {
                    Label(String(localized: "Le versioni di Gokapi precedenti alla 2.0.1 non supportano nomi file con caratteri speciali."), systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(PanaruColors.warning)
                }
                if c.endToEndEncryptionEnabled {
                    Label(String(localized: "Il server usa la cifratura end-to-end, ma i file caricati tramite API (quindi da Panaru) vengono salvati non cifrati."), systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(PanaruColors.warning)
                }
            }
        case .failed(let e):
            Label(e.errorDescription ?? "", systemImage: "exclamationmark.triangle.fill").foregroundStyle(PanaruColors.error)
        }
    }
}

/// File sul server (`/files/list`): copia link, download (permesso DOWNLOAD), eliminazione.
struct ServerFilesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let server: Server
    @LocalState private var files: [GokapiFile]?
    @LocalState private var error: String?
    @LocalState private var status: String?

    var body: some View {
        VStack(alignment: .leading) {
            HStack {
                Text(server.name).font(.headline)
                Spacer()
                Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                Button(String(localized: "Chiudi")) { dismiss() }
            }
            if let error {
                Text(error).foregroundStyle(PanaruColors.error).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let files {
                if files.isEmpty {
                    Text(String(localized: "Nessun file attivo su questo server.")).frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List(files) { f in
                        HStack {
                            VStack(alignment: .leading) {
                                Text(f.name).lineLimit(1)
                                Text("\(bytes(f.sizeBytes)) · " + (f.unlimitedDownloads ? String(localized: "Download illimitati") : String(localized: "Download rimasti: \(f.downloadsRemaining)")))
                                    .font(.caption).foregroundStyle(PanaruColors.secondaryText)
                            }
                            Spacer()
                            Button { model.copy([f.urlDownload]) } label: { Image(systemName: "doc.on.doc") }.buttonStyle(.borderless).help(String(localized: "Copia link"))
                            if !f.isEndToEndEncrypted {
                                Button { download(f) } label: { Image(systemName: "arrow.down.circle") }.buttonStyle(.borderless).help(String(localized: "Scarica"))
                            }
                        }
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if let status { Text(status).font(.caption).foregroundStyle(PanaruColors.secondaryText) }
        }
        .padding()
        .frame(width: 520, height: 420)
        .task { await load() }
    }

    private func load() async {
        error = nil
        guard let client = model.servers.client(for: server.id) else { return }
        do { files = try await client.listFiles() } catch let e as GokapiError {
            error = e == .unauthorized ? String(localized: "Questa chiave API non può elencare i file: assegnale il permesso View in Gokapi.") : e.errorDescription
        } catch { self.error = error.localizedDescription }
    }

    private func download(_ f: GokapiFile) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = f.name
        guard panel.runModal() == .OK, let dest = panel.url, let client = model.servers.client(for: server.id) else { return }
        status = String(localized: "Download di \(f.name)…")
        Task {
            do {
                try await client.download(id: f.id, to: dest) { sent, total in
                    Task { @MainActor in status = String(localized: "Download di \(f.name): \(bytes(sent)) / \(bytes(total))") }
                }
                status = String(localized: "Scaricato: \(f.name)")
                NSWorkspace.shared.activateFileViewerSelecting([dest])
            } catch {
                status = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }
}

struct AboutView: View {
    var body: some View {
        VStack(spacing: 10) {
            SymbolView(height: 64)
                .shadow(color: PanaruColors.brand.opacity(0.35), radius: 18, y: 8)
            WordmarkView(height: 22)
            Text("Files in. Links out.").font(.headline).foregroundStyle(PanaruColors.arancio)
            Text(String(localized: "Versione \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev")"))
                .font(.caption)
            Text(String(localized: "Client open source per server Gokapi self-hosted. Nessuna analitica, nessun cloud di terze parti."))
                .multilineTextAlignment(.center).font(.callout).foregroundStyle(PanaruColors.secondaryText).frame(maxWidth: 360)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background { AmbientBackground() }
    }
}
