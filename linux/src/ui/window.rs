//! Finestra principale: area di rilascio, caricamenti in corso e cronologia.

use super::{dialogs, State};
use adw::prelude::*;
use gtk::{gdk, gio, glib};
use panaru::format_bytes;
use panaru::history::{UploadRecord, UploadState};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::path::PathBuf;
use std::rc::Rc;
use uuid::Uuid;

/// Azione di una riga della cronologia.
type RowAction = Box<dyn Fn(&Rc<State>)>;

pub struct MainWindow {
    pub window: adw::ApplicationWindow,
    pub toasts: adw::ToastOverlay,
    stack: gtk::Stack,
    title: adw::WindowTitle,
    server_picker: gtk::DropDown,
    server_ids: RefCell<Vec<Uuid>>,
    updating_picker: Cell<bool>,
    active_box: gtk::Box,
    active_list: gtk::ListBox,
    overall: gtk::ProgressBar,
    overall_label: gtk::Label,
    overall_box: gtk::Box,
    history_box: gtk::Box,
    history_list: gtk::ListBox,
    sync_spinner: gtk::Spinner,
    drop_overlay: gtk::Revealer,
    drop_label: gtk::Label,
    progress: RefCell<HashMap<Uuid, (gtk::ProgressBar, gtk::Label)>>,
}

fn section_label(text: &str) -> gtk::Label {
    let l = gtk::Label::builder()
        .label(text.to_uppercase())
        .xalign(0.0)
        .hexpand(true)
        .build();
    l.add_css_class("section-label");
    l
}

impl MainWindow {
    pub fn new(state: &Rc<State>) -> Rc<Self> {
        // ---- barra del titolo
        let title = adw::WindowTitle::new("Panaru", "");
        let header = adw::HeaderBar::new();
        header.set_title_widget(Some(&title));

        let upload = gtk::Button::builder()
            .action_name("app.open-files")
            .tooltip_text("Scegli i file da caricare (Ctrl+O)")
            .build();
        upload.set_child(Some(
            &adw::ButtonContent::builder()
                .icon_name("document-send-symbolic")
                .label("Carica")
                .build(),
        ));
        upload.add_css_class("suggested-action");
        header.pack_start(&upload);

        let menu = gio::Menu::new();
        let section = gio::Menu::new();
        section.append(Some("Carica con opzioni…"), Some("app.upload-with-options"));
        section.append(Some("File sul server"), Some("app.server-files"));
        section.append(Some("Sincronizza cronologia"), Some("app.sync"));
        section.append(Some("Svuota cronologia…"), Some("app.clear-history"));
        menu.append_section(None, &section);
        let section = gio::Menu::new();
        section.append(Some("Server"), Some("app.servers"));
        section.append(Some("Preferenze"), Some("app.preferences"));
        section.append(Some("Informazioni su Panaru"), Some("app.about"));
        section.append(Some("Esci"), Some("app.quit"));
        menu.append_section(None, &section);
        let menu_button = gtk::MenuButton::builder()
            .icon_name("open-menu-symbolic")
            .menu_model(&menu)
            .primary(true)
            .tooltip_text("Menu principale")
            .build();
        header.pack_end(&menu_button);

        let server_picker = gtk::DropDown::builder()
            .tooltip_text("Server di destinazione")
            .build();
        header.pack_end(&server_picker);

        // ---- benvenuto
        let add_server = gtk::Button::builder()
            .label("Aggiungi server")
            .halign(gtk::Align::Center)
            .action_name("app.servers")
            .build();
        add_server.add_css_class("suggested-action");
        add_server.add_css_class("pill");
        let welcome = adw::StatusPage::builder()
            .icon_name(panaru::APP_ID)
            .title("Benvenuto in Panaru")
            .description("Collega il tuo server Gokapi con indirizzo e chiave API: da quel momento basta trascinare un file qui per avere il link.")
            .child(&add_server)
            .build();

        // ---- contenuto principale
        let drop_zone = Self::drop_zone();
        drop_zone.set_action_name(Some("app.open-files"));

        let sync_spinner = gtk::Spinner::new();
        let active_list = boxed_list();
        let overall = gtk::ProgressBar::new();
        overall.add_css_class("slim");
        let overall_label = gtk::Label::builder().xalign(0.0).build();
        overall_label.add_css_class("caption");
        overall_label.add_css_class("dim-label");
        let overall_box = gtk::Box::new(gtk::Orientation::Vertical, 6);
        overall_box.append(&overall);
        overall_box.append(&overall_label);
        let active_box = gtk::Box::new(gtk::Orientation::Vertical, 8);
        active_box.append(&section_label("In corso"));
        active_box.append(&overall_box);
        active_box.append(&active_list);

        let history_list = boxed_list();
        let sync_button = gtk::Button::builder()
            .icon_name("view-refresh-symbolic")
            .action_name("app.sync")
            .tooltip_text("Aggiorna la cronologia con i file presenti sul server, anche quelli caricati da altri dispositivi (F5)")
            .build();
        sync_button.add_css_class("flat");
        sync_button.add_css_class("circular");
        let history_header = gtk::Box::new(gtk::Orientation::Horizontal, 6);
        history_header.append(&section_label("Cronologia"));
        history_header.append(&sync_spinner);
        history_header.append(&sync_button);
        let history_box = gtk::Box::new(gtk::Orientation::Vertical, 8);
        history_box.append(&history_header);
        history_box.append(&history_list);

        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(24)
            .margin_top(18)
            .margin_bottom(24)
            .margin_start(12)
            .margin_end(12)
            .build();
        column.append(&drop_zone);
        column.append(&active_box);
        column.append(&history_box);
        let clamp = adw::Clamp::builder()
            .maximum_size(760)
            .child(&column)
            .build();
        let scroller = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&clamp)
            .vexpand(true)
            .build();

        let stack = gtk::Stack::builder()
            .transition_type(gtk::StackTransitionType::Crossfade)
            .build();
        stack.add_named(&welcome, Some("welcome"));
        stack.add_named(&scroller, Some("main"));

        // ---- sovrapposizione durante il trascinamento
        let drop_label = gtk::Label::new(Some("Rilascia per caricare"));
        let drop_icon = gtk::Image::from_icon_name(panaru::APP_ID);
        drop_icon.set_pixel_size(96);
        let drop_content = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(16)
            .valign(gtk::Align::Center)
            .halign(gtk::Align::Center)
            .build();
        drop_content.append(&drop_icon);
        drop_content.append(&drop_label);
        let drop_frame = gtk::Box::builder().hexpand(true).vexpand(true).build();
        drop_frame.add_css_class("drop-overlay");
        drop_content.set_hexpand(true);
        drop_frame.append(&drop_content);
        let drop_overlay = gtk::Revealer::builder()
            .transition_type(gtk::RevealerTransitionType::Crossfade)
            .child(&drop_frame)
            .can_target(false)
            .build();

        let overlay = gtk::Overlay::new();
        overlay.set_child(Some(&stack));
        overlay.add_overlay(&drop_overlay);

        let toasts = adw::ToastOverlay::new();
        toasts.set_child(Some(&overlay));
        let view = adw::ToolbarView::new();
        view.add_top_bar(&header);
        view.set_content(Some(&toasts));

        let window = adw::ApplicationWindow::builder()
            .application(&state.app)
            .title("Panaru")
            .default_width(680)
            .default_height(720)
            .width_request(360)
            .height_request(420)
            .content(&view)
            .build();

        let this = Rc::new(Self {
            window,
            toasts,
            stack,
            title,
            server_picker,
            server_ids: RefCell::new(Vec::new()),
            updating_picker: Cell::new(false),
            active_box,
            active_list,
            overall,
            overall_label,
            overall_box,
            history_box,
            history_list,
            sync_spinner,
            drop_overlay,
            drop_label,
            progress: RefCell::new(HashMap::new()),
        });
        this.connect(state);
        this
    }

    fn drop_zone() -> gtk::Button {
        let badge = gtk::Image::from_icon_name("go-up-symbolic");
        badge.set_pixel_size(24);
        badge.add_css_class("drop-badge");
        let title = gtk::Label::builder()
            .label("Trascina qui file o cartelle")
            .xalign(0.0)
            .wrap(true)
            .build();
        title.add_css_class("title-3");
        let subtitle = gtk::Label::builder()
            .label("Oppure fai clic per sceglierli. Puoi anche usare «Apri con → Panaru» dal file manager.")
            .xalign(0.0)
            .wrap(true)
            .build();
        subtitle.add_css_class("dim-label");
        let texts = gtk::Box::new(gtk::Orientation::Vertical, 4);
        texts.set_valign(gtk::Align::Center);
        texts.append(&title);
        texts.append(&subtitle);
        let content = gtk::Box::new(gtk::Orientation::Horizontal, 16);
        content.append(&badge);
        content.append(&texts);
        let button = gtk::Button::builder().child(&content).build();
        button.add_css_class("flat");
        button.add_css_class("drop-zone");
        button
    }

    fn connect(self: &Rc<Self>, state: &Rc<State>) {
        // Trascinamento di file da qualsiasi app.
        let target = gtk::DropTarget::new(gdk::FileList::static_type(), gdk::DragAction::COPY);
        let weak = Rc::downgrade(self);
        let st = Rc::downgrade(state);
        target.connect_enter(move |_, _, _| {
            if let (Some(w), Some(s)) = (weak.upgrade(), st.upgrade()) {
                let name = s.target_server().map(|s| s.name).unwrap_or_default();
                w.drop_label.set_label(&if name.is_empty() {
                    "Rilascia per caricare".into()
                } else {
                    format!("Rilascia per caricare su {name}")
                });
                w.drop_overlay.set_reveal_child(true);
            }
            gdk::DragAction::COPY
        });
        let weak = Rc::downgrade(self);
        target.connect_leave(move |_| {
            if let Some(w) = weak.upgrade() {
                w.drop_overlay.set_reveal_child(false);
            }
        });
        let weak = Rc::downgrade(self);
        let st = Rc::downgrade(state);
        target.connect_drop(move |_, value, _, _| {
            if let Some(w) = weak.upgrade() {
                w.drop_overlay.set_reveal_child(false);
            }
            let Ok(list) = value.get::<gdk::FileList>() else {
                return false;
            };
            let paths: Vec<PathBuf> = list.files().iter().filter_map(|f| f.path()).collect();
            if let Some(s) = st.upgrade() {
                s.receive(paths);
            }
            true
        });
        self.window.add_controller(target);

        // Scelta del server di destinazione.
        let weak = Rc::downgrade(self);
        let st = Rc::downgrade(state);
        self.server_picker.connect_selected_notify(move |dd| {
            let (Some(w), Some(s)) = (weak.upgrade(), st.upgrade()) else {
                return;
            };
            if w.updating_picker.get() {
                return;
            }
            let id = w.server_ids.borrow().get(dd.selected() as usize).copied();
            if let Some(id) = id {
                s.prefs.borrow_mut().selected_server = Some(id);
                s.save_prefs();
                w.title
                    .set_subtitle(&s.target_server().map(|s| s.name).unwrap_or_default());
            }
        });

        // Chiudere la finestra durante un caricamento non lo interrompe.
        let st = Rc::downgrade(state);
        self.window.connect_close_request(move |win| {
            if let Some(s) = st.upgrade() {
                if s.hold_while_uploading() {
                    win.set_visible(false);
                    return glib::Propagation::Stop;
                }
            }
            glib::Propagation::Proceed
        });
    }

    // ------------------------------------------------------------------ aggiornamento

    pub fn refresh(self: &Rc<Self>, state: &Rc<State>) {
        let servers = state.servers.all();
        self.stack.set_visible_child_name(if servers.is_empty() {
            "welcome"
        } else {
            "main"
        });
        let target = state.target_server();
        self.title
            .set_subtitle(&target.as_ref().map(|s| s.name.clone()).unwrap_or_default());

        // Selettore dei server (visibile solo con più server).
        self.updating_picker.set(true);
        let names: Vec<&str> = servers.iter().map(|s| s.name.as_str()).collect();
        self.server_picker
            .set_model(Some(&gtk::StringList::new(&names)));
        *self.server_ids.borrow_mut() = servers.iter().map(|s| s.id).collect();
        if let Some(i) = target.and_then(|t| servers.iter().position(|s| s.id == t.id)) {
            self.server_picker.set_selected(i as u32);
        }
        self.server_picker.set_visible(servers.len() > 1);
        self.updating_picker.set(false);

        let engine = state.engine.borrow();
        // In corso.
        self.active_list.remove_all();
        self.progress.borrow_mut().clear();
        let active = engine.history.active();
        self.active_box.set_visible(!active.is_empty());
        self.overall_box.set_visible(active.len() > 1);
        for r in &active {
            self.active_list.append(&self.active_row(state, r));
        }
        drop(engine);
        self.update_overall(state);

        // Cronologia.
        let engine = state.engine.borrow();
        self.history_list.remove_all();
        let visible = engine.history.visible();
        self.history_box
            .set_visible(!visible.is_empty() || state.syncing.get());
        for r in visible {
            self.history_list.append(&history_row(state, r));
        }
        self.sync_spinner.set_visible(state.syncing.get());
        self.sync_spinner.set_spinning(state.syncing.get());
    }

    pub fn update_progress(&self, state: &Rc<State>, id: Uuid) {
        let engine = state.engine.borrow();
        if let (Some((bar, label)), Some((sent, total))) =
            (self.progress.borrow().get(&id), engine.live.get(&id))
        {
            bar.set_fraction(if *total > 0 {
                *sent as f64 / *total as f64
            } else {
                0.0
            });
            let server = engine
                .history
                .get(id)
                .map(|r| r.server_name.clone())
                .unwrap_or_default();
            label.set_label(&format!(
                "{} di {} · {}",
                format_bytes(*sent),
                format_bytes(*total),
                server
            ));
        }
        drop(engine);
        self.update_overall(state);
    }

    fn update_overall(&self, state: &Rc<State>) {
        let (sent, total) = state.engine.borrow().overall();
        self.overall.set_fraction(if total > 0 {
            sent as f64 / total as f64
        } else {
            0.0
        });
        self.overall_label.set_label(&format!(
            "Totale: {} di {}",
            format_bytes(sent),
            format_bytes(total)
        ));
    }

    fn active_row(&self, state: &Rc<State>, r: &UploadRecord) -> gtk::ListBoxRow {
        let icon = file_icon(&r.file_name, 32);
        let name = gtk::Label::builder()
            .label(&r.file_name)
            .xalign(0.0)
            .ellipsize(gtk::pango::EllipsizeMode::Middle)
            .build();
        name.add_css_class("heading");
        let bar = gtk::ProgressBar::new();
        bar.add_css_class("slim");
        let detail = gtk::Label::builder().xalign(0.0).build();
        detail.add_css_class("caption");
        detail.add_css_class("dim-label");
        if r.state == UploadState::Queued {
            detail.set_label("In attesa…");
        } else {
            detail.set_label(&format!(
                "{} · {}",
                format_bytes(r.size_bytes),
                r.server_name
            ));
        }
        let texts = gtk::Box::new(gtk::Orientation::Vertical, 6);
        texts.set_hexpand(true);
        texts.append(&name);
        texts.append(&bar);
        texts.append(&detail);
        let cancel = gtk::Button::builder()
            .icon_name("process-stop-symbolic")
            .valign(gtk::Align::Center)
            .tooltip_text("Annulla caricamento")
            .build();
        cancel.add_css_class("flat");
        cancel.add_css_class("circular");
        let id = r.id;
        let st = Rc::downgrade(state);
        cancel.connect_clicked(move |_| {
            if let Some(s) = st.upgrade() {
                s.engine.borrow_mut().cancel(id);
                s.refresh();
            }
        });
        let content = gtk::Box::new(gtk::Orientation::Horizontal, 12);
        content.add_css_class("active-row");
        content.append(&icon);
        content.append(&texts);
        content.append(&cancel);
        self.progress.borrow_mut().insert(r.id, (bar, detail));
        gtk::ListBoxRow::builder()
            .child(&content)
            .activatable(false)
            .build()
    }
}

fn boxed_list() -> gtk::ListBox {
    let l = gtk::ListBox::new();
    l.set_selection_mode(gtk::SelectionMode::None);
    l.add_css_class("boxed-list");
    l
}

/// Icona del tipo di file ricavata dal nome (immagine, video, PDF, archivio…).
fn file_icon(name: &str, size: i32) -> gtk::Image {
    let (content_type, _) = gio::content_type_guess(Some(name), None);
    let icon = gio::content_type_get_icon(&content_type);
    let image = gtk::Image::from_gicon(&icon);
    image.set_pixel_size(size);
    image
}

fn format_time(unix_s: i64) -> String {
    glib::DateTime::from_unix_local(unix_s)
        .and_then(|d| d.format("%-d %b %Y, %H:%M"))
        .map(|s| s.to_string())
        .unwrap_or_default()
}

fn status_line(r: &UploadRecord) -> String {
    match r.state {
        UploadState::Failed => r.error_message.clone().unwrap_or_default(),
        UploadState::Cancelled => "Annullato".into(),
        UploadState::Completed => {
            if r.removed_from_server {
                return "Non più sul server".into();
            }
            if r.is_expired() {
                return "Scaduto".into();
            }
            let mut parts = Vec::new();
            if r.unlimited_time {
                parts.push("Nessuna scadenza".to_string());
            } else if let Some(e) = r.expires_at {
                parts.push(format!("Scade il {}", format_time(e)));
            }
            if r.unlimited_downloads {
                parts.push("Download illimitati".into());
            } else if let Some(n) = r.downloads_remaining {
                parts.push(format!("Download rimasti: {n}"));
            }
            if r.has_password {
                parts.push("Password".into());
            }
            parts.join(" · ")
        }
        _ => String::new(),
    }
}

fn history_row(state: &Rc<State>, r: &UploadRecord) -> adw::ActionRow {
    let when = format_time(r.completed_at.unwrap_or(r.created_at) / 1000);
    let mut subtitle = format!(
        "{} · {} · {}",
        format_bytes(r.size_bytes),
        r.server_name,
        when
    );
    let status = status_line(r);
    if !status.is_empty() {
        subtitle.push('\n');
        subtitle.push_str(&status);
    }
    let row = adw::ActionRow::builder()
        .title(&r.file_name)
        .subtitle(&subtitle)
        .use_markup(false)
        .title_lines(1)
        .subtitle_lines(3)
        .build();

    let icon = file_icon(&r.file_name, 32);
    if !r.has_usable_link() && r.state == UploadState::Completed {
        icon.set_opacity(0.5);
    }
    let badge_name = match r.state {
        UploadState::Completed if r.has_usable_link() => "emblem-ok-symbolic",
        UploadState::Failed => "dialog-warning-symbolic",
        _ => "",
    };
    let prefix = gtk::Overlay::new();
    prefix.set_child(Some(&icon));
    if !badge_name.is_empty() {
        let badge = gtk::Image::from_icon_name(badge_name);
        badge.set_pixel_size(14);
        badge.set_halign(gtk::Align::End);
        badge.set_valign(gtk::Align::End);
        badge.add_css_class(if r.state == UploadState::Failed {
            "error"
        } else {
            "success"
        });
        prefix.add_overlay(&badge);
    }
    prefix.set_valign(gtk::Align::Center);
    row.add_prefix(&prefix);

    if r.from_other_device() {
        let tag = gtk::Label::new(Some("Altro dispositivo"));
        tag.set_tooltip_text(Some("Caricato da un altro dispositivo o dal web di Gokapi"));
        tag.add_css_class("other-device");
        tag.set_valign(gtk::Align::Center);
        row.add_suffix(&tag);
    }

    // Azioni della riga.
    let actions = gio::SimpleActionGroup::new();
    let id = r.id;
    let add = |name: &str, f: RowAction| {
        let a = gio::SimpleAction::new(name, None);
        let st = Rc::downgrade(state);
        a.connect_activate(move |_, _| {
            if let Some(s) = st.upgrade() {
                f(&s);
            }
        });
        actions.add_action(&a);
    };
    let menu = gio::Menu::new();
    if let (true, Some(url)) = (r.has_usable_link(), r.url.clone()) {
        let u = url.clone();
        add(
            "copy",
            Box::new(move |s| {
                s.copy_text(&u);
                s.toast("Link copiato");
            }),
        );
        let u = url.clone();
        add("open", Box::new(move |s| s.open_uri(&u)));
        let name = r.file_name.clone();
        let server = r.server_name.clone();
        add(
            "delete",
            Box::new(move |s| dialogs::confirm_delete(s, id, &name, &server)),
        );
        menu.append(Some("Copia link"), Some("row.copy"));
        menu.append(Some("Apri link"), Some("row.open"));
        menu.append(Some("Elimina dal server…"), Some("row.delete"));

        let copy = gtk::Button::builder()
            .icon_name("edit-copy-symbolic")
            .valign(gtk::Align::Center)
            .tooltip_text("Copia link")
            .build();
        copy.add_css_class("flat");
        copy.set_action_name(Some("row.copy"));
        row.add_suffix(&copy);
        row.set_activatable_widget(Some(&copy));
    }
    if matches!(r.state, UploadState::Failed | UploadState::Cancelled) && !r.from_other_device() {
        add(
            "retry",
            Box::new(move |s| {
                if !s.engine.borrow_mut().retry(id) {
                    s.toast("Questo caricamento era protetto da password e la password non viene conservata: carica di nuovo il file.");
                }
                s.refresh();
            }),
        );
        menu.append(Some("Riprova"), Some("row.retry"));
    }
    if !r.from_other_device() {
        let path = r.source_path.clone();
        add("show", Box::new(move |s| s.show_in_folder(&path)));
        menu.append(Some("Mostra nella cartella"), Some("row.show"));
    }
    add(
        "remove",
        Box::new(move |s| {
            s.engine.borrow_mut().history.remove(id);
            s.refresh();
        }),
    );
    menu.append(Some("Rimuovi dalla cronologia"), Some("row.remove"));
    row.insert_action_group("row", Some(&actions));

    let more = gtk::MenuButton::builder()
        .icon_name("view-more-symbolic")
        .menu_model(&menu)
        .valign(gtk::Align::Center)
        .tooltip_text("Altre azioni")
        .build();
    more.add_css_class("flat");
    row.add_suffix(&more);
    row
}
