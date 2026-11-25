//! Finestre di dialogo: preferenze e server, opzioni di caricamento, file sul server, conferme.

use super::{spawn_bg, State};
use adw::prelude::*;
use gtk::{gio, glib};
use panaru::format_bytes;
use panaru::gokapi::{
    is_local_host, parse_server_url, GokapiClient, GokapiError, GokapiFile, UploadOptions,
};
use panaru::store::Server;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::AtomicBool;
use std::sync::Arc;
use uuid::Uuid;

const EXPIRY_CHOICES: [u32; 6] = [1, 3, 7, 14, 30, 0];
const DOWNLOAD_CHOICES: [u32; 5] = [1, 3, 5, 10, 0];

fn expiry_label(d: u32) -> String {
    match d {
        0 => "Mai".into(),
        1 => "1 giorno".into(),
        n => format!("{n} giorni"),
    }
}

fn downloads_label(n: u32) -> String {
    if n == 0 {
        "Illimitati".into()
    } else {
        n.to_string()
    }
}

fn combo(title: &str, labels: &[String], selected: usize) -> adw::ComboRow {
    let refs: Vec<&str> = labels.iter().map(|s| s.as_str()).collect();
    let row = adw::ComboRow::builder()
        .title(title)
        .model(&gtk::StringList::new(&refs))
        .build();
    row.set_selected(selected as u32);
    row
}

fn index_of(choices: &[u32], value: u32) -> usize {
    choices.iter().position(|&c| c == value).unwrap_or(0)
}

/// Finestra di dialogo con barra del titolo, pulsanti Annulla/azione e contenuto scorrevole.
fn sheet(title: &str, action: &str, content: &impl IsA<gtk::Widget>) -> (adw::Dialog, gtk::Button) {
    let cancel = gtk::Button::with_label("Annulla");
    let ok = gtk::Button::with_label(action);
    ok.add_css_class("suggested-action");
    let header = adw::HeaderBar::builder()
        .show_end_title_buttons(false)
        .show_start_title_buttons(false)
        .build();
    header.pack_start(&cancel);
    header.pack_end(&ok);
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    view.set_content(Some(content));
    let dialog = adw::Dialog::builder()
        .title(title)
        .content_width(460)
        .child(&view)
        .build();
    let d = dialog.clone();
    cancel.connect_clicked(move |_| {
        d.close();
    });
    (dialog, ok)
}

// ------------------------------------------------------------------ opzioni di caricamento

pub fn upload_options(state: &Rc<State>, paths: Vec<PathBuf>) {
    let count = panaru::queue::Engine::expand(&paths).len();
    let servers = state.servers.all();
    let target = state.target_server();
    let prefs = state.prefs.borrow().clone();

    let group = adw::PreferencesGroup::builder()
        .title(if count == 1 {
            "Carica 1 file".to_string()
        } else {
            format!("Carica {count} file")
        })
        .build();
    let names: Vec<String> = servers.iter().map(|s| s.name.clone()).collect();
    let server_row = combo(
        "Server",
        &names,
        target
            .and_then(|t| servers.iter().position(|s| s.id == t.id))
            .unwrap_or(0),
    );
    let expiry_row = combo(
        "Scadenza",
        &EXPIRY_CHOICES
            .iter()
            .map(|&d| expiry_label(d))
            .collect::<Vec<_>>(),
        index_of(&EXPIRY_CHOICES, prefs.default_expiry_days),
    );
    let downloads_row = combo(
        "Download consentiti",
        &DOWNLOAD_CHOICES
            .iter()
            .map(|&n| downloads_label(n))
            .collect::<Vec<_>>(),
        index_of(&DOWNLOAD_CHOICES, prefs.default_downloads),
    );
    let password_row = adw::PasswordEntryRow::builder()
        .title("Password (facoltativa)")
        .build();
    group.add(&server_row);
    group.add(&expiry_row);
    group.add(&downloads_row);
    group.add(&password_row);
    let page = adw::PreferencesPage::new();
    page.add(&group);

    let (dialog, ok) = sheet("Opzioni di condivisione", "Carica", &page);
    ok.set_sensitive(count > 0);
    let pw = password_row.clone();
    let ok2 = ok.clone();
    password_row.connect_changed(move |row| {
        let valid = UploadOptions::is_valid_password(&row.text());
        ok2.set_sensitive(valid && count > 0);
        if valid {
            pw.remove_css_class("error")
        } else {
            pw.add_css_class("error")
        }
    });
    let st = state.clone();
    let d = dialog.clone();
    ok.connect_clicked(move |_| {
        let Some(server) = servers.get(server_row.selected() as usize) else {
            return;
        };
        let password = password_row.text().to_string();
        let opts = UploadOptions {
            expiry_days: EXPIRY_CHOICES[expiry_row.selected() as usize],
            allowed_downloads: DOWNLOAD_CHOICES[downloads_row.selected() as usize],
            password: Some(password).filter(|p| !p.is_empty()),
        };
        st.upload(paths.clone(), server.id, opts);
        d.close();
    });
    dialog.present(state.parent().as_ref());
}

// ------------------------------------------------------------------ preferenze

pub fn preferences(state: &Rc<State>, servers_page: bool) {
    let dialog = adw::PreferencesDialog::new();
    dialog.set_search_enabled(false);

    // Generale
    let general = adw::PreferencesPage::builder()
        .title("Generale")
        .icon_name("preferences-system-symbolic")
        .build();
    let defaults = adw::PreferencesGroup::builder()
        .title("Caricamento")
        .description("Valori usati quando carichi senza scegliere le opzioni.")
        .build();
    let prefs = state.prefs.borrow().clone();
    let expiry = combo(
        "Scadenza predefinita",
        &EXPIRY_CHOICES
            .iter()
            .map(|&d| expiry_label(d))
            .collect::<Vec<_>>(),
        index_of(&EXPIRY_CHOICES, prefs.default_expiry_days),
    );
    let downloads = combo(
        "Download consentiti",
        &DOWNLOAD_CHOICES
            .iter()
            .map(|&n| downloads_label(n))
            .collect::<Vec<_>>(),
        index_of(&DOWNLOAD_CHOICES, prefs.default_downloads),
    );
    let ask = adw::SwitchRow::builder()
        .title("Chiedi le opzioni a ogni caricamento")
        .subtitle("Scadenza, download e password prima di caricare")
        .active(prefs.ask_options)
        .build();
    let concurrent = adw::SpinRow::with_range(1.0, 5.0, 1.0);
    concurrent.set_title("Caricamenti contemporanei");
    concurrent.set_value(prefs.max_concurrent as f64);
    defaults.add(&expiry);
    defaults.add(&downloads);
    defaults.add(&ask);
    defaults.add(&concurrent);
    let after = adw::PreferencesGroup::builder()
        .title("Quando il link è pronto")
        .build();
    let copy = adw::SwitchRow::builder()
        .title("Copia il link negli appunti")
        .active(prefs.copy_link)
        .build();
    let notify = adw::SwitchRow::builder()
        .title("Mostra una notifica")
        .subtitle("Quando Panaru non è in primo piano")
        .active(prefs.notify)
        .build();
    after.add(&copy);
    after.add(&notify);
    general.add(&defaults);
    general.add(&after);

    let st = state.clone();
    let save = Rc::new(move || {
        {
            let mut p = st.prefs.borrow_mut();
            p.default_expiry_days = EXPIRY_CHOICES[expiry.selected() as usize];
            p.default_downloads = DOWNLOAD_CHOICES[downloads.selected() as usize];
            p.ask_options = ask.is_active();
            p.max_concurrent = concurrent.value() as u32;
            p.copy_link = copy.is_active();
            p.notify = notify.is_active();
        }
        st.save_prefs();
    });
    dialog.connect_closed(move |_| save());

    // Server
    let servers_page_w = adw::PreferencesPage::builder()
        .title("Server")
        .icon_name("network-server-symbolic")
        .build();
    let group = adw::PreferencesGroup::builder()
        .title("Server Gokapi")
        .description("Le chiavi API sono conservate nel portachiavi di sistema.")
        .build();
    let add = gtk::Button::builder()
        .icon_name("list-add-symbolic")
        .tooltip_text("Aggiungi server")
        .valign(gtk::Align::Center)
        .build();
    add.add_css_class("flat");
    group.set_header_suffix(Some(&add));
    servers_page_w.add(&group);
    let page = Rc::new(ServersPage {
        group,
        rows: Default::default(),
        dialog: dialog.clone(),
    });
    fill_servers(state, &page);
    let (st, pg) = (state.clone(), page.clone());
    add.connect_clicked(move |_| {
        let (st2, pg2) = (st.clone(), pg.clone());
        server_editor(
            &st,
            None,
            &pg.dialog,
            Rc::new(move || fill_servers(&st2, &pg2)),
        );
    });

    dialog.add(&general);
    dialog.add(&servers_page_w);
    if servers_page {
        dialog.set_visible_page(&servers_page_w);
    }
    dialog.present(state.parent().as_ref());
    if servers_page && state.servers.is_empty() {
        let (st2, pg2) = (state.clone(), page.clone());
        server_editor(
            state,
            None,
            &dialog,
            Rc::new(move || fill_servers(&st2, &pg2)),
        );
    }
}

struct ServersPage {
    group: adw::PreferencesGroup,
    rows: std::cell::RefCell<Vec<adw::ActionRow>>,
    dialog: adw::PreferencesDialog,
}

fn fill_servers(state: &Rc<State>, page: &Rc<ServersPage>) {
    for r in page.rows.borrow_mut().drain(..) {
        page.group.remove(&r);
    }
    for server in state.servers.all() {
        let row = adw::ActionRow::builder()
            .title(&server.name)
            .subtitle(&server.url)
            .use_markup(false)
            .activatable(true)
            .build();
        if server.is_default {
            let tag = gtk::Label::new(Some("Predefinito"));
            tag.add_css_class("other-device");
            tag.set_valign(gtk::Align::Center);
            row.add_suffix(&tag);
        }
        row.add_suffix(&gtk::Image::from_icon_name("go-next-symbolic"));
        let (st, pg) = (state.clone(), page.clone());
        row.connect_activated(move |_| {
            let (st2, pg2) = (st.clone(), pg.clone());
            server_editor(
                &st,
                Some(server.clone()),
                &pg.dialog,
                Rc::new(move || fill_servers(&st2, &pg2)),
            );
        });
        page.group.add(&row);
        page.rows.borrow_mut().push(row);
    }
    if state.servers.is_empty() {
        let row = adw::ActionRow::builder()
            .title("Nessun server")
            .subtitle("Aggiungi il tuo server Gokapi con il pulsante +")
            .build();
        page.group.add(&row);
        page.rows.borrow_mut().push(row);
    }
}

/// Aggiunta o modifica di un server, con verifica della connessione prima del salvataggio.
fn server_editor(
    state: &Rc<State>,
    existing: Option<Server>,
    parent: &adw::PreferencesDialog,
    on_saved: Rc<dyn Fn()>,
) {
    let editing = existing.is_some();
    let name = adw::EntryRow::builder()
        .title("Nome")
        .text(existing.as_ref().map(|s| s.name.as_str()).unwrap_or(""))
        .build();
    let address = adw::EntryRow::builder()
        .title("Indirizzo (quello che apri nel browser)")
        .text(existing.as_ref().map(|s| s.url.as_str()).unwrap_or(""))
        .input_purpose(gtk::InputPurpose::Url)
        .build();
    let key = adw::PasswordEntryRow::builder()
        .title(if editing {
            "Chiave API (lascia vuoto per non cambiarla)"
        } else {
            "Chiave API"
        })
        .build();
    let default = adw::SwitchRow::builder()
        .title("Server predefinito")
        .active(
            existing
                .as_ref()
                .map(|s| s.is_default)
                .unwrap_or(state.servers.is_empty()),
        )
        .build();
    let group = adw::PreferencesGroup::builder()
        .description("Crea la chiave in Gokapi → API con il permesso Upload. Facoltativi: View (cronologia e file sul server), Delete, Download.")
        .build();
    group.add(&name);
    group.add(&address);
    group.add(&key);
    group.add(&default);

    let status = gtk::Label::builder()
        .wrap(true)
        .xalign(0.0)
        .margin_top(6)
        .build();
    status.add_css_class("caption");
    let spinner = gtk::Spinner::new();
    let verify = gtk::Button::with_label("Verifica connessione");
    verify.add_css_class("pill");
    let verify_box = gtk::Box::new(gtk::Orientation::Horizontal, 12);
    verify_box.set_halign(gtk::Align::Center);
    verify_box.set_margin_top(12);
    verify_box.append(&verify);
    verify_box.append(&spinner);
    let status_group = adw::PreferencesGroup::new();
    let status_box = gtk::Box::new(gtk::Orientation::Vertical, 6);
    status_box.append(&verify_box);
    status_box.append(&status);
    status_group.add(&status_box);

    let page = adw::PreferencesPage::new();
    page.add(&group);
    page.add(&status_group);
    let remove_button = gtk::Button::with_label("Rimuovi questo server");
    remove_button.add_css_class("destructive-action");
    remove_button.add_css_class("pill");
    remove_button.set_halign(gtk::Align::Center);
    let remove_group = adw::PreferencesGroup::new();
    remove_group.add(&remove_button);
    if editing {
        page.add(&remove_group);
    }

    let (dialog, save) = sheet(
        if editing {
            "Modifica server"
        } else {
            "Aggiungi server"
        },
        "Salva",
        &page,
    );
    let id = existing.as_ref().map(|s| s.id).unwrap_or_else(Uuid::new_v4);

    // Verifica: versione (qualsiasi permesso) + configurazione (permesso Upload).
    let check = {
        let (st, address, key, status, spinner) = (
            state.clone(),
            address.clone(),
            key.clone(),
            status.clone(),
            spinner.clone(),
        );
        Rc::new(move |on_ok: Box<dyn Fn(String)>| {
            let url = match parse_server_url(&address.text()) {
                Ok(u) => u,
                Err(e) => {
                    status.set_label(&e.to_string());
                    status.add_css_class("status-error");
                    return;
                }
            };
            let typed = key.text().trim().to_string();
            let api_key = if typed.is_empty() {
                st.servers.api_key(id)
            } else {
                Some(typed)
            };
            let Some(api_key) = api_key else {
                status.set_label("Inserisci la chiave API.");
                status.add_css_class("status-error");
                return;
            };
            let client = GokapiClient::new(&url.url, &api_key);
            spinner.set_spinning(true);
            status.remove_css_class("status-error");
            status.set_label("Verifica in corso…");
            let (status, spinner) = (status.clone(), spinner.clone());
            let canonical = url.canonical();
            let insecure = url.is_insecure_local;
            glib::spawn_future_local(async move {
                let res = spawn_bg(move || {
                    client
                        .version()
                        .and_then(|v| client.config().map(|c| (v, c)))
                })
                .await;
                spinner.set_spinning(false);
                match res {
                    Some(Ok((v, c))) => {
                        let mut text = format!(
                            "Connesso a Gokapi {} · file fino a {} MB.",
                            v.version, c.max_file_size_mb
                        );
                        if !v.supports_base64_file_names() {
                            text.push_str(" Versione vecchia: aggiorna Gokapi alla 2.0.1 o successiva per i nomi file non ASCII.");
                        }
                        if c.end_to_end_encryption_enabled {
                            text.push_str(
                                " Attenzione: i file caricati via API non sono cifrati end-to-end.",
                            );
                        }
                        if insecure {
                            text.push_str(" Connessione HTTP in rete locale (non cifrata).");
                        }
                        status.set_label(&text);
                        on_ok(canonical);
                    }
                    Some(Err(e)) => {
                        status.add_css_class("status-error");
                        status.set_label(&match e {
                            GokapiError::Unauthorized => {
                                "Chiave API rifiutata, o senza il permesso Upload.".to_string()
                            }
                            e => e.to_string(),
                        });
                    }
                    None => {}
                }
            });
        })
    };
    let c = check.clone();
    verify.connect_clicked(move |_| c(Box::new(|_| {})));

    let (st, d, key2, saved) = (state.clone(), dialog.clone(), key.clone(), on_saved.clone());
    save.connect_clicked(move |_| {
        let (st, d, name, default, key, on_saved) = (
            st.clone(),
            d.clone(),
            name.clone(),
            default.clone(),
            key2.clone(),
            saved.clone(),
        );
        check(Box::new(move |canonical| {
            let host = url::Url::parse(&canonical)
                .ok()
                .and_then(|u| u.host_str().map(String::from))
                .unwrap_or_default();
            let mut n = name.text().trim().to_string();
            if n.is_empty() {
                n = if is_local_host(&host) {
                    format!("{host} (locale)")
                } else {
                    host.clone()
                };
            }
            let typed = key.text().trim().to_string();
            let server = Server {
                id,
                name: n,
                url: canonical,
                is_default: default.is_active(),
            };
            match st
                .servers
                .save(server, Some(typed.as_str()).filter(|k| !k.is_empty()))
            {
                Ok(()) => {
                    if default.is_active() {
                        st.prefs.borrow_mut().selected_server = Some(id);
                        st.save_prefs();
                    }
                    on_saved();
                    st.refresh();
                    st.sync_all(true);
                    d.close();
                }
                Err(e) => st.toast(&e),
            }
        }));
    });

    let (st, d, refill) = (state.clone(), dialog.clone(), on_saved.clone());
    let server_name = existing
        .as_ref()
        .map(|s| s.name.clone())
        .unwrap_or_default();
    remove_button.connect_clicked(move |_| {
        let confirm = adw::AlertDialog::new(
            Some("Rimuovere il server?"),
            Some(&format!("«{server_name}» e la sua chiave API verranno rimossi da Panaru. I file sul server non vengono toccati.")),
        );
        confirm.add_response("cancel", "Annulla");
        confirm.add_response("remove", "Rimuovi");
        confirm.set_response_appearance("remove", adw::ResponseAppearance::Destructive);
        let (st, d2, refill) = (st.clone(), d.clone(), refill.clone());
        confirm.connect_response(None, move |_, resp| {
            let d = &d2;
            if resp == "remove" {
                st.servers.remove(id);
                if st.prefs.borrow().selected_server == Some(id) {
                    st.prefs.borrow_mut().selected_server = None;
                    st.save_prefs();
                }
                refill();
                st.refresh();
                d.close();
            }
        });
        confirm.present(Some(&d));
    });

    dialog.present(Some(parent));
}

// ------------------------------------------------------------------ file sul server

pub fn server_files(state: &Rc<State>, server: Server) {
    let list = gtk::ListBox::new();
    list.add_css_class("boxed-list");
    list.set_selection_mode(gtk::SelectionMode::None);
    let status = adw::StatusPage::builder().title("Caricamento…").build();
    let stack = gtk::Stack::new();
    let clamp = adw::Clamp::builder()
        .child(&list)
        .margin_top(12)
        .margin_bottom(12)
        .margin_start(12)
        .margin_end(12)
        .build();
    let scroller = gtk::ScrolledWindow::builder()
        .child(&clamp)
        .hscrollbar_policy(gtk::PolicyType::Never)
        .vexpand(true)
        .build();
    stack.add_named(&status, Some("status"));
    stack.add_named(&scroller, Some("list"));

    let header = adw::HeaderBar::new();
    let view = adw::ToolbarView::new();
    view.add_top_bar(&header);
    let toasts = adw::ToastOverlay::new();
    toasts.set_child(Some(&stack));
    view.set_content(Some(&toasts));
    let dialog = adw::Dialog::builder()
        .title(format!("File su {}", server.name))
        .content_width(560)
        .content_height(520)
        .child(&view)
        .build();

    let Some(client) = state.servers.client(server.id) else {
        return;
    };
    let st = state.clone();
    let parent = dialog.clone();
    glib::spawn_future_local(async move {
        let c = client.clone();
        match spawn_bg(move || c.list_files()).await {
            Some(Ok(files)) if files.is_empty() => {
                status.set_title("Nessun file attivo");
                status.set_icon_name(Some("folder-symbolic"));
            }
            Some(Ok(files)) => {
                for f in files.into_iter().filter(|f| !f.is_pending_deletion) {
                    list.append(&server_file_row(&st, &client, &f, &toasts, &parent));
                }
                stack.set_visible_child_name("list");
            }
            Some(Err(GokapiError::Unauthorized)) => {
                status.set_title("Permesso mancante");
                status.set_description(Some("Questa chiave API non può elencare i file: assegnale il permesso View in Gokapi."));
                status.set_icon_name(Some("dialog-password-symbolic"));
            }
            Some(Err(e)) => {
                status.set_title("Errore");
                status.set_description(Some(&e.to_string()));
                status.set_icon_name(Some("network-error-symbolic"));
            }
            None => {}
        }
    });
    dialog.present(state.parent().as_ref());
}

fn server_file_row(
    state: &Rc<State>,
    client: &GokapiClient,
    f: &GokapiFile,
    toasts: &adw::ToastOverlay,
    parent: &adw::Dialog,
) -> adw::ActionRow {
    let downloads = if f.unlimited_downloads {
        "Download illimitati".to_string()
    } else {
        format!("Download rimasti: {}", f.downloads_remaining)
    };
    let row = adw::ActionRow::builder()
        .title(&f.name)
        .subtitle(format!("{} · {}", format_bytes(f.size_bytes), downloads))
        .use_markup(false)
        .build();
    let (ct, _) = gio::content_type_guess(Some(f.name.as_str()), None);
    let icon = gtk::Image::from_gicon(&gio::content_type_get_icon(&ct));
    icon.set_pixel_size(32);
    row.add_prefix(&icon);

    let copy = gtk::Button::builder()
        .icon_name("edit-copy-symbolic")
        .valign(gtk::Align::Center)
        .tooltip_text("Copia link")
        .build();
    copy.add_css_class("flat");
    let (st, url, t) = (state.clone(), f.url_download.clone(), toasts.clone());
    copy.connect_clicked(move |_| {
        st.copy_text(&url);
        t.add_toast(adw::Toast::new("Link copiato"));
    });
    row.add_suffix(&copy);

    if !f.is_end_to_end_encrypted {
        let download = gtk::Button::builder()
            .icon_name("folder-download-symbolic")
            .valign(gtk::Align::Center)
            .tooltip_text("Scarica")
            .build();
        download.add_css_class("flat");
        let (client, id, name, t, parent) = (
            client.clone(),
            f.id.clone(),
            f.name.clone(),
            toasts.clone(),
            parent.clone(),
        );
        download.connect_clicked(move |_| {
            let dialog = gtk::FileDialog::builder()
                .title("Salva il file")
                .initial_name(name.as_str())
                .modal(true)
                .build();
            let window = parent.root().and_downcast::<gtk::Window>();
            let (client, id, name, t) = (client.clone(), id.clone(), name.clone(), t.clone());
            dialog.save(window.as_ref(), gio::Cancellable::NONE, move |res| {
                let Ok(file) = res else { return };
                let Some(dest) = file.path() else { return };
                let toast = adw::Toast::builder()
                    .title(format!("Download di «{name}»…"))
                    .timeout(0)
                    .build();
                t.add_toast(toast.clone());
                let (t, name) = (t.clone(), name.clone());
                glib::spawn_future_local(async move {
                    let cancel = Arc::new(AtomicBool::new(false));
                    let d = dest.clone();
                    let res =
                        spawn_bg(move || client.download(&id, &d, &cancel, &mut |_, _| {})).await;
                    toast.dismiss();
                    t.add_toast(adw::Toast::new(&match res {
                        Some(Ok(())) => format!("Scaricato: {name}"),
                        Some(Err(GokapiError::Unauthorized)) => {
                            "La chiave API non può scaricare: assegnale il permesso Download."
                                .into()
                        }
                        Some(Err(e)) => e.to_string(),
                        None => String::new(),
                    }));
                });
            });
        });
        row.add_suffix(&download);
    }
    row
}

// ------------------------------------------------------------------ conferme e informazioni

pub fn confirm_delete(state: &Rc<State>, id: Uuid, name: &str, server: &str) {
    let dialog = adw::AlertDialog::new(
        Some("Eliminare dal server?"),
        Some(&format!("«{name}» verrà eliminato da {server} e il link smetterà di funzionare. L'operazione non è reversibile.")),
    );
    dialog.add_response("cancel", "Annulla");
    dialog.add_response("delete", "Elimina");
    dialog.set_response_appearance("delete", adw::ResponseAppearance::Destructive);
    dialog.set_default_response(Some("cancel"));
    let st = state.clone();
    dialog.connect_response(None, move |_, resp| {
        if resp == "delete" {
            st.delete_from_server(id);
        }
    });
    dialog.present(state.parent().as_ref());
}

pub fn confirm_clear(state: &Rc<State>) {
    let dialog = adw::AlertDialog::new(
        Some("Svuotare la cronologia?"),
        Some("Vengono rimosse le voci scadute, eliminate, non riuscite o annullate. I file ancora presenti sul server restano in cronologia."),
    );
    dialog.add_response("cancel", "Annulla");
    dialog.add_response("clear", "Svuota");
    dialog.set_response_appearance("clear", adw::ResponseAppearance::Destructive);
    let st = state.clone();
    dialog.connect_response(None, move |_, resp| {
        if resp == "clear" {
            st.engine.borrow_mut().history.clear();
            st.refresh();
        }
    });
    dialog.present(state.parent().as_ref());
}

pub fn confirm_quit(state: &Rc<State>) {
    let dialog = adw::AlertDialog::new(
        Some("Interrompere i caricamenti?"),
        Some("Ci sono caricamenti in corso: uscendo verranno interrotti e dovranno ripartire da capo."),
    );
    dialog.add_response("cancel", "Continua a caricare");
    dialog.add_response("quit", "Esci");
    dialog.set_response_appearance("quit", adw::ResponseAppearance::Destructive);
    let st = state.clone();
    dialog.connect_response(None, move |_, resp| {
        if resp == "quit" {
            st.app.quit();
        }
    });
    dialog.present(state.parent().as_ref());
}

pub fn about(state: &Rc<State>) {
    let about = adw::AboutDialog::builder()
        .application_name("Panaru")
        .application_icon(panaru::APP_ID)
        .version(env!("CARGO_PKG_VERSION"))
        .comments("Files in. Links out.\n\nClient open source per server Gokapi self-hosted. Nessuna analitica, nessun cloud di terze parti.")
        .license_type(gtk::License::Apache20)
        .developer_name("Piratepixel")
        .build();
    about.present(state.parent().as_ref());
}
