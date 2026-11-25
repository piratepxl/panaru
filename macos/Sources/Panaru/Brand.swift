import AppKit
import SwiftUI

/// Token del brand (brand/colors/tokens.json), con varianti chiaro/scuro.
enum PanaruColors {
    /// Arancio dell'icona (#EC5F36): grafica, bagliori e vetro tinto. Per testo/azioni usare `arancio`.
    static let brand = Color(nsColor: NSColor(hex: 0xEC5F36))
    static let arancio = dynamic(light: 0xCD3D13, dark: 0xEE724E)
    static let arancioTenue = dynamic(light: 0xFCDDD5, dark: 0x5A2616)
    static let sabbia = dynamic(light: 0xF1E7D6, dark: 0x3A3530)
    static let surface = dynamic(light: 0xFAF6EF, dark: 0x24211E)
    static let secondaryText = dynamic(light: 0x6E665D, dark: 0xB5AA9C)
    static let success = dynamic(light: 0x4F7A52, dark: 0x8DBB8F)
    static let error = dynamic(light: 0xA3263A, dark: 0xF08C99)
    static let transfer = dynamic(light: 0x2F6E7E, dark: 0x7EC0CF)
    static let warning = dynamic(light: 0x8C600F, dark: 0xE2B65A)

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(hex: dark) : NSColor(hex: light)
        })
    }
}

extension NSColor {
    convenience init(hex: UInt32) {
        self.init(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

/// Immagini del brand copiate nel bundle da scripts/build-app.sh (template: si tingono col colore).
enum BrandImage {
    static func named(_ name: String) -> NSImage? {
        guard let img = NSImage(named: name) else { return nil }
        img.isTemplate = true
        return img
    }
}

/// Simbolo del paniere: su macOS è l'icona dell'app (illustrazione ufficiale);
/// senza bundle (es. `swift run`) ripiega su un simbolo di sistema.
struct SymbolView: View {
    var height: CGFloat = 48
    var body: some View {
        if Bundle.main.bundleURL.pathExtension == "app" {
            Image(nsImage: NSApp.applicationIconImage).resizable().scaledToFit().frame(height: height * 1.25)
        } else {
            Image(systemName: "basket.fill").resizable().scaledToFit().frame(height: height).foregroundStyle(PanaruColors.arancio)
        }
    }
}

struct WordmarkView: View {
    var height: CGFloat = 20
    var body: some View {
        if let img = BrandImage.named("Wordmark") {
            Image(nsImage: img).resizable().scaledToFit().frame(height: height).foregroundStyle(.primary)
        } else {
            Text("panaru").font(.system(size: height, weight: .semibold, design: .rounded))
        }
    }
}
