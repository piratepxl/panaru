import AppKit
import PanaruKit
import SwiftUI

@main
struct PanaruApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @LocalState private var model = AppModel.shared

    var body: some Scene {
        Window("Panaru", id: "main") {
            MainView()
                .environment(model)
                .frame(minWidth: 520, minHeight: 480)
        }
        .defaultSize(width: 640, height: 680)
        .windowToolbarStyle(.unified(showsTitle: false))
        .commands {
            CommandGroup(after: .newItem) {
                Button(String(localized: "Carica file…")) { model.chooseFiles() }
                    .keyboardShortcut("o")
            }
        }

        MenuBarExtra {
            MenuBarContent().environment(model)
        } label: {
            MenuBarLabel().environment(model)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView().environment(model)
        }
    }
}

/// Integrazioni di sistema: drop sull'icona del Dock, "Apri con" dal Finder, menu Servizi.
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
    }

    /// File trascinati sull'icona del Dock o aperti con "Apri con → Panaru".
    func application(_ application: NSApplication, open urls: [URL]) {
        Task { @MainActor in AppModel.shared.receive(urls) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    /// Servizio "Carica con Panaru" (Finder → clic destro → Servizi). Dichiarato in Info.plist (NSServices).
    @objc func uploadFiles(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = (pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL]) ?? []
        if urls.isEmpty {
            error.pointee = String(localized: "Nessun file da caricare.") as NSString
            return
        }
        Task { @MainActor in AppModel.shared.receive(urls) }
    }
}
