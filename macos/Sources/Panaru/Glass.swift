import SwiftUI

// Stile "Liquid Glass" di macOS 26+, con ripiego sui materiali di macOS 14–15.
// Tutte le viste passano da qui: un solo punto per regolare l'aspetto del vetro.

extension View {
    /// Pannello di vetro con angoli continui. `tint` colora leggermente il vetro (es. arancio del brand).
    @ViewBuilder
    func panaruGlass(cornerRadius: CGFloat = 16, tint: Color? = nil, interactive: Bool = false) -> some View {
        if #available(macOS 26, *) {
            self.glassEffect(Glass.regular.tint(tint).interactive(interactive),
                             in: .rect(cornerRadius: cornerRadius, style: .continuous))
        } else {
            let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            self
                .background(.regularMaterial, in: shape)
                .background((tint ?? .clear).opacity(0.18), in: shape)
                .overlay(shape.strokeBorder(.white.opacity(0.18), lineWidth: 0.75))
                .shadow(color: .black.opacity(0.08), radius: 8, y: 3)
        }
    }

    /// Vetro a capsula per controlli compatti (badge, chip).
    @ViewBuilder
    func panaruGlassCapsule(tint: Color? = nil) -> some View {
        if #available(macOS 26, *) {
            self.glassEffect(Glass.regular.tint(tint), in: .capsule)
        } else {
            self.background(.regularMaterial, in: Capsule())
                .background((tint ?? .clear).opacity(0.18), in: Capsule())
        }
    }

    /// Pulsante principale: vetro "prominente" tinto d'arancio.
    @ViewBuilder
    func panaruProminentButton() -> some View {
        if #available(macOS 26, *) {
            self.buttonStyle(.glassProminent).tint(PanaruColors.arancio)
        } else {
            self.buttonStyle(.borderedProminent).tint(PanaruColors.arancio)
        }
    }

    /// Pulsante secondario di vetro (icone, azioni rapide).
    @ViewBuilder
    func panaruGlassButton(circle: Bool = false) -> some View {
        if #available(macOS 26, *) {
            self.buttonStyle(.glass).buttonBorderShape(circle ? .circle : .capsule)
        } else {
            self.buttonStyle(.borderless)
        }
    }

    /// Fondo della finestra: traslucido sul desktop (macOS 15+) con bagliori caldi del brand.
    @ViewBuilder
    func panaruWindowBackground() -> some View {
        if #available(macOS 15, *) {
            self.background { AmbientBackground() }
                .containerBackground(.ultraThinMaterial, for: .window)
        } else {
            self.background { AmbientBackground().background(.ultraThinMaterial) }
        }
    }
}

extension View {
    /// Nasconde il titolo della finestra (il wordmark nella toolbar lo sostituisce), macOS 15+.
    @ViewBuilder
    func hidingWindowTitle() -> some View {
        if #available(macOS 15, *) { self.toolbar(removing: .title) } else { self }
    }
}

/// Contenitore che fonde i pannelli di vetro vicini (macOS 26+); altrimenti trasparente.
struct GlassGroup<Content: View>: View {
    var spacing: CGFloat = 12
    @ViewBuilder var content: Content

    var body: some View {
        if #available(macOS 26, *) {
            GlassEffectContainer(spacing: spacing) { content }
        } else {
            content
        }
    }
}

/// Bagliori sfumati dietro al vetro: danno profondità e un'eco del colore dell'icona.
struct AmbientBackground: View {
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let strength = scheme == .dark ? 0.30 : 0.22
        GeometryReader { geo in
            let side = max(geo.size.width, geo.size.height)
            ZStack {
                RadialGradient(colors: [PanaruColors.brand.opacity(strength), .clear],
                               center: .topLeading, startRadius: 0, endRadius: side * 0.75)
                RadialGradient(colors: [Color(red: 1.0, green: 0.72, blue: 0.35).opacity(strength * 0.7), .clear],
                               center: .bottomTrailing, startRadius: 0, endRadius: side * 0.7)
                RadialGradient(colors: [PanaruColors.transfer.opacity(strength * 0.35), .clear],
                               center: UnitPoint(x: 0.85, y: 0.1), startRadius: 0, endRadius: side * 0.45)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

/// Intestazione di sezione minimale, in maiuscoletto.
struct SectionLabel<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 8) {
            Text(title.uppercased())
                .font(.caption.weight(.semibold))
                .tracking(0.6)
                .foregroundStyle(.secondary)
            Spacer()
            trailing
        }
        .padding(.horizontal, 6)
    }
}

extension SectionLabel where Trailing == EmptyView {
    init(title: String) { self.init(title: title) { EmptyView() } }
}
