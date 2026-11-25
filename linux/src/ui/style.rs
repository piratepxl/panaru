//! Stile dell'app: accento arancio del brand (brand/colors/tokens.json) e componenti propri.
//! L'accento cambia con il tema chiaro/scuro (GTK 4.14 non supporta le media query CSS).

use gtk::gdk;

const BASE: &str = r#"
.drop-zone {
  border-radius: 18px;
  padding: 18px 20px;
  background-color: alpha(#EC5F36, 0.09);
  border: 2px dashed alpha(#EC5F36, 0.45);
  transition: background-color 150ms ease, border-color 150ms ease;
}
.drop-zone:hover { background-color: alpha(#EC5F36, 0.15); border-color: alpha(#EC5F36, 0.7); }
.drop-badge {
  background-color: #EC5F36;
  color: white;
  border-radius: 999px;
  min-width: 48px;
  min-height: 48px;
}
.drop-overlay {
  background-color: alpha(#EC5F36, 0.20);
  border: 3px dashed #EC5F36;
  border-radius: 24px;
  margin: 12px;
}
.drop-overlay label { font-size: 1.4em; font-weight: 700; }
.section-label { font-size: 0.82em; font-weight: 700; letter-spacing: 0.06em; opacity: 0.6; }
.other-device {
  color: @accent_color;
  background-color: alpha(#EC5F36, 0.12);
  border-radius: 999px;
  padding: 1px 8px;
  font-size: 0.82em;
  font-weight: 600;
}
.status-error { color: @error_color; }
.active-row { padding: 10px 12px; }
progressbar.slim trough, progressbar.slim progress { min-height: 6px; border-radius: 999px; }
progressbar.slim progress { background-color: #2F6E7E; }
.dark progressbar.slim progress { background-color: #7EC0CF; }
"#;

const LIGHT: &str = r#"
@define-color accent_bg_color #CD3D13;
@define-color accent_fg_color #FFFFFF;
@define-color accent_color #CD3D13;
"#;

const DARK: &str = r#"
@define-color accent_bg_color #EE724E;
@define-color accent_fg_color #24211E;
@define-color accent_color #EE724E;
"#;

pub fn install() {
    let Some(display) = gdk::Display::default() else {
        return;
    };
    let base = gtk::CssProvider::new();
    base.load_from_string(BASE);
    gtk::style_context_add_provider_for_display(
        &display,
        &base,
        gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
    );

    let accent = gtk::CssProvider::new();
    gtk::style_context_add_provider_for_display(
        &display,
        &accent,
        gtk::STYLE_PROVIDER_PRIORITY_APPLICATION,
    );
    let manager = adw::StyleManager::default();
    let apply = move |m: &adw::StyleManager| {
        accent.load_from_string(if m.is_dark() { DARK } else { LIGHT })
    };
    apply(&manager);
    manager.connect_dark_notify(apply);
}
