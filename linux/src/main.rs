//! Panaru per Linux: client nativo GTK 4 / libadwaita per server Gokapi self-hosted.

mod ui;

use adw::prelude::*;
use gtk::{gdk, gio, glib};
use panaru::APP_ID;

fn main() -> glib::ExitCode {
    let app = adw::Application::builder()
        .application_id(APP_ID)
        // `panaru file…` (anche da "Apri con" del file manager) carica i file nell'istanza attiva.
        .flags(gio::ApplicationFlags::HANDLES_OPEN)
        .build();

    // `panaru --upload` (azione "Carica file…" del lanciatore): apre il selettore nell'istanza attiva.
    app.add_main_option(
        "upload",
        glib::Char::from(b'u'),
        glib::OptionFlags::NONE,
        glib::OptionArg::None,
        "Scegli i file da caricare",
        None,
    );
    app.connect_handle_local_options(|app, options| {
        if options.contains("upload") {
            if app.register(gio::Cancellable::NONE).is_ok() {
                app.activate();
                app.activate_action("open-files", None);
            }
            return std::ops::ControlFlow::Break(glib::ExitCode::SUCCESS);
        }
        std::ops::ControlFlow::Continue(())
    });

    app.connect_startup(|app| {
        ui::style::install();
        if let Some(display) = gdk::Display::default() {
            // Icone dell'app durante lo sviluppo, senza installazione (installate: /usr/share/icons/hicolor).
            let theme = gtk::IconTheme::for_display(&display);
            for dir in ["scalable/apps", "symbolic/apps"] {
                theme.add_search_path(format!(
                    "{}/data/icons/hicolor/{dir}",
                    env!("CARGO_MANIFEST_DIR")
                ));
            }
        }
        gtk::Window::set_default_icon_name(APP_ID);
        ui::State::install(app);
    });
    app.connect_activate(|app| ui::State::get(app).present());
    app.connect_open(|app, files, _| {
        let state = ui::State::get(app);
        state.present();
        let paths: Vec<_> = files.iter().filter_map(|f| f.path()).collect();
        state.receive(paths);
    });
    app.run()
}
