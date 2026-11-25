//! Interfaccia GTK 4 / libadwaita. Lo stato vive sul thread principale ([`State`]); le operazioni
//! di rete girano su thread dedicati e tornano qui con canali async (`spawn_bg`, eventi della coda).

mod dialogs;
pub mod style;
mod window;

use adw::prelude::*;
use gtk::{gio, glib};
use panaru::gokapi::{GokapiError, UploadOptions};
use panaru::history::History;
use panaru::queue::{Engine, Outcome};
use panaru::store::{KeyringStore, Paths, Prefs, Server, ServerStore};
use std::cell::{Cell, RefCell};
use std::future::Future;
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;

pub use window::MainWindow;

/// Azione dell'app: riceve lo stato e il parametro facoltativo.
type StateAction = Box<dyn Fn(&Rc<State>, Option<&glib::Variant>)>;

thread_local! {
    static STATE: RefCell<Option<Rc<State>>> = const { RefCell::new(None) };
}

/// Esegue `f` su un thread di lavoro e ne attende il risultato senza bloccare l'interfaccia.
pub fn spawn_bg<T: Send + 'static>(
    f: impl FnOnce() -> T + Send + 'static,
) -> impl Future<Output = Option<T>> {
    let (tx, rx) = async_channel::bounded(1);
    std::thread::spawn(move || {
        let _ = tx.send_blocking(f());
    });
    async move { rx.recv().await.ok() }
}

pub struct State {
    pub app: adw::Application,
    pub paths: Paths,
    pub servers: ServerStore,
    pub prefs: RefCell<Prefs>,
    pub engine: RefCell<Engine>,
    window: RefCell<Option<Rc<MainWindow>>>,
    /// Mantiene viva l'app a finestra chiusa finché ci sono upload in corso.
    hold: RefCell<Option<gio::ApplicationHoldGuard>>,
    last_sync: Cell<Option<Instant>>,
    pub syncing: Cell<bool>,
}

impl State {
    pub fn install(app: &adw::Application) {
        let paths = Paths::system();
        let servers = ServerStore::new(&paths, Arc::new(KeyringStore));
        let prefs = Prefs::load(&paths);
        let (tx, rx) = async_channel::unbounded();
        let mut engine = Engine::new(History::load(&paths), servers.clone(), tx);
        engine.max_concurrent = prefs.max_concurrent.max(1) as usize;
        let state = Rc::new(State {
            app: app.clone(),
            paths,
            servers,
            prefs: RefCell::new(prefs),
            engine: RefCell::new(engine),
            window: RefCell::new(None),
            hold: RefCell::new(None),
            last_sync: Cell::new(None),
            syncing: Cell::new(false),
        });
        STATE.with(|s| *s.borrow_mut() = Some(state.clone()));
        state.install_actions();

        // Eventi dei thread di upload → stato e interfaccia.
        let weak = Rc::downgrade(&state);
        glib::spawn_future_local(async move {
            while let Ok(ev) = rx.recv().await {
                let Some(state) = weak.upgrade() else { break };
                let outcome = state.engine.borrow_mut().handle(ev);
                if let Some(o) = outcome {
                    state.on_outcome(o);
                }
            }
        });
    }

    pub fn get(app: &adw::Application) -> Rc<State> {
        let _ = app;
        STATE
            .with(|s| s.borrow().clone())
            .expect("stato inizializzato in startup")
    }

    pub fn window(&self) -> Option<Rc<MainWindow>> {
        self.window.borrow().clone()
    }

    pub fn present(self: &Rc<Self>) {
        let win = self.window.borrow().clone();
        let win = match win {
            Some(w) => w,
            None => {
                let w = MainWindow::new(self);
                *self.window.borrow_mut() = Some(w.clone());
                w
            }
        };
        win.window.present();
        self.refresh();
        self.sync_all(false);
    }

    pub fn refresh(self: &Rc<Self>) {
        if let Some(w) = self.window() {
            w.refresh(self);
        }
    }

    pub fn toast(&self, text: &str) {
        if let Some(w) = self.window() {
            w.toasts.add_toast(adw::Toast::new(text));
        }
    }

    pub fn parent(&self) -> Option<gtk::Widget> {
        self.window().map(|w| w.window.clone().upcast())
    }

    /// Server di destinazione: quello scelto nella finestra, altrimenti il predefinito.
    pub fn target_server(&self) -> Option<Server> {
        let selected = self.prefs.borrow().selected_server;
        selected
            .and_then(|id| self.servers.get(id))
            .or_else(|| self.servers.default_server())
    }

    pub fn save_prefs(&self) {
        let prefs = self.prefs.borrow();
        prefs.save(&self.paths);
        self.engine.borrow_mut().max_concurrent = prefs.max_concurrent.max(1) as usize;
    }

    // ------------------------------------------------------------------ upload

    /// File ricevuti (selettore, trascinamento, "Apri con"): carica subito o chiede le opzioni.
    pub fn receive(self: &Rc<Self>, paths: Vec<PathBuf>) {
        if paths.is_empty() {
            return;
        }
        let Some(server) = self.target_server() else {
            self.toast("Aggiungi prima un server Gokapi nelle Preferenze.");
            dialogs::preferences(self, true);
            return;
        };
        if self.prefs.borrow().ask_options {
            dialogs::upload_options(self, paths);
        } else {
            let p = self.prefs.borrow();
            let opts = UploadOptions {
                expiry_days: p.default_expiry_days,
                allowed_downloads: p.default_downloads,
                password: None,
            };
            drop(p);
            self.upload(paths, server.id, opts);
        }
    }

    pub fn upload(self: &Rc<Self>, paths: Vec<PathBuf>, server: Uuid, options: UploadOptions) {
        let ids = self.engine.borrow_mut().enqueue(&paths, server, &options);
        if ids.is_empty() {
            self.toast("Nessun file da caricare.");
        }
        self.refresh();
    }

    pub fn choose_files(self: &Rc<Self>) {
        let dialog = gtk::FileDialog::builder()
            .title("Scegli i file da caricare")
            .modal(true)
            .build();
        let parent = self.window().map(|w| w.window.clone());
        let state = self.clone();
        dialog.open_multiple(parent.as_ref(), gio::Cancellable::NONE, move |res| {
            if let Ok(model) = res {
                let paths = (0..model.n_items())
                    .filter_map(|i| model.item(i).and_downcast::<gio::File>())
                    .filter_map(|f| f.path())
                    .collect();
                state.receive(paths);
            }
        });
    }

    fn on_outcome(self: &Rc<Self>, outcome: Outcome) {
        match outcome {
            Outcome::Progress(id) => {
                if let Some(w) = self.window() {
                    w.update_progress(self, id);
                }
                return;
            }
            Outcome::Completed(r) => {
                let url = r.url.clone().unwrap_or_default();
                let (copy, notify) = {
                    let p = self.prefs.borrow();
                    (p.copy_link, p.notify)
                };
                if copy {
                    self.copy_text(&url);
                }
                let text = if copy {
                    format!("Link di «{}» copiato", r.file_name)
                } else {
                    format!("«{}» caricato", r.file_name)
                };
                self.toast(&text);
                if notify && !self.window_active() {
                    let n = gio::Notification::new("Link pronto");
                    n.set_body(Some(&format!(
                        "{}{}",
                        r.file_name,
                        if copy {
                            " — copiato negli appunti"
                        } else {
                            ""
                        }
                    )));
                    n.add_button_with_target_value(
                        "Copia link",
                        "app.copy-link",
                        Some(&url.to_variant()),
                    );
                    n.add_button_with_target_value(
                        "Apri",
                        "app.open-link",
                        Some(&url.to_variant()),
                    );
                    self.app.send_notification(Some(&r.id.to_string()), &n);
                }
            }
            Outcome::Failed(r) => {
                let msg = format!(
                    "«{}»: {}",
                    r.file_name,
                    r.error_message.clone().unwrap_or_default()
                );
                self.toast(&msg);
                if self.prefs.borrow().notify && !self.window_active() {
                    let n = gio::Notification::new("Caricamento non riuscito");
                    n.set_body(Some(&msg));
                    self.app.send_notification(Some(&r.id.to_string()), &n);
                }
            }
            Outcome::Cancelled(_) => {}
        }
        self.refresh();
        self.release_hold_if_idle();
    }

    fn window_active(&self) -> bool {
        self.window()
            .is_some_and(|w| w.window.is_visible() && w.window.is_active())
    }

    /// Finestra chiusa con upload in corso: l'app resta attiva in background fino alla fine.
    pub fn hold_while_uploading(&self) -> bool {
        if self.engine.borrow().is_idle() {
            return false;
        }
        if self.hold.borrow().is_none() {
            *self.hold.borrow_mut() = Some(self.app.hold());
            let n = gio::Notification::new("Panaru continua a caricare");
            n.set_body(Some("Riceverai una notifica quando i link saranno pronti."));
            self.app.send_notification(Some("background"), &n);
        }
        true
    }

    fn release_hold_if_idle(&self) {
        if self.engine.borrow().is_idle() {
            self.hold.borrow_mut().take();
            self.app.withdraw_notification("background");
        }
    }

    pub fn copy_text(&self, text: &str) {
        if let Some(display) = gtk::gdk::Display::default() {
            display.clipboard().set_text(text);
        }
    }

    pub fn open_uri(&self, uri: &str) {
        let parent = self.window().map(|w| w.window.clone());
        gtk::UriLauncher::new(uri).launch(parent.as_ref(), gio::Cancellable::NONE, |_| {});
    }

    pub fn show_in_folder(&self, path: &str) {
        let parent = self.window().map(|w| w.window.clone());
        gtk::FileLauncher::new(Some(&gio::File::for_path(path))).open_containing_folder(
            parent.as_ref(),
            gio::Cancellable::NONE,
            |_| {},
        );
    }

    // ------------------------------------------------------------ server

    /// Sincronizza la cronologia con i file dei server (al massimo ogni 30 s, salvo richiesta).
    pub fn sync_all(self: &Rc<Self>, manual: bool) {
        if self.syncing.get() || self.servers.is_empty() {
            return;
        }
        if !manual
            && self
                .last_sync
                .get()
                .is_some_and(|t| t.elapsed() < Duration::from_secs(30))
        {
            return;
        }
        self.last_sync.set(Some(Instant::now()));
        self.syncing.set(true);
        self.refresh();
        let state = self.clone();
        glib::spawn_future_local(async move {
            let mut no_permission = Vec::new();
            for server in state.servers.all() {
                let Some(client) = state.servers.client(server.id) else {
                    continue;
                };
                match spawn_bg(move || client.list_files()).await {
                    Some(Ok(files)) => {
                        state.engine.borrow_mut().history.apply_sync(
                            server.id,
                            &server.name,
                            &files,
                        );
                    }
                    Some(Err(GokapiError::Unauthorized)) => no_permission.push(server.name.clone()),
                    _ => {}
                }
            }
            state.syncing.set(false);
            if manual && !no_permission.is_empty() {
                state.toast(&format!(
                    "Sincronizzazione non disponibile per {}: assegna il permesso View alla chiave API.",
                    no_permission.join(", ")
                ));
            }
            state.refresh();
        });
    }

    pub fn delete_from_server(self: &Rc<Self>, id: Uuid) {
        let Some(r) = self.engine.borrow().history.get(id).cloned() else {
            return;
        };
        let (Some(file_id), Some(client)) = (r.file_id.clone(), self.servers.client(r.server_id))
        else {
            return;
        };
        let state = self.clone();
        glib::spawn_future_local(async move {
            match spawn_bg(move || client.delete(&file_id)).await {
                Some(Ok(())) | Some(Err(GokapiError::NotFound)) => {
                    if let Some(rec) = state.engine.borrow_mut().history.get_mut(id) {
                        rec.removed_from_server = true;
                    }
                    state.engine.borrow().history.save();
                    state.toast(&format!("«{}» eliminato dal server", r.file_name));
                }
                Some(Err(GokapiError::Unauthorized)) => state.toast(
                    "La chiave API non può eliminare file: assegnale il permesso Delete in Gokapi.",
                ),
                Some(Err(e)) => state.toast(&e.to_string()),
                None => {}
            }
            state.refresh();
        });
    }

    // ------------------------------------------------------------- azioni

    fn install_actions(self: &Rc<Self>) {
        let app = &self.app;
        let add = |name: &str, f: StateAction, ty: Option<&glib::VariantTy>| {
            let action = gio::SimpleAction::new(name, ty);
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, param| {
                if let Some(s) = weak.upgrade() {
                    f(&s, param);
                }
            });
            app.add_action(&action);
        };
        add("open-files", Box::new(|s, _| s.choose_files()), None);
        add(
            "upload-with-options",
            Box::new(|s, _| {
                let state = s.clone();
                let dialog = gtk::FileDialog::builder()
                    .title("Scegli i file da caricare")
                    .modal(true)
                    .build();
                let parent = s.window().map(|w| w.window.clone());
                dialog.open_multiple(parent.as_ref(), gio::Cancellable::NONE, move |res| {
                    if let Ok(model) = res {
                        let paths: Vec<PathBuf> = (0..model.n_items())
                            .filter_map(|i| model.item(i).and_downcast::<gio::File>())
                            .filter_map(|f| f.path())
                            .collect();
                        if !paths.is_empty() && state.target_server().is_some() {
                            dialogs::upload_options(&state, paths);
                        }
                    }
                });
            }),
            None,
        );
        add("sync", Box::new(|s, _| s.sync_all(true)), None);
        add(
            "preferences",
            Box::new(|s, _| dialogs::preferences(s, false)),
            None,
        );
        add(
            "servers",
            Box::new(|s, _| dialogs::preferences(s, true)),
            None,
        );
        add(
            "server-files",
            Box::new(|s, _| {
                if let Some(server) = s.target_server() {
                    dialogs::server_files(s, server);
                }
            }),
            None,
        );
        add(
            "clear-history",
            Box::new(|s, _| dialogs::confirm_clear(s)),
            None,
        );
        add("about", Box::new(|s, _| dialogs::about(s)), None);
        add(
            "quit",
            Box::new(|s, _| {
                if s.engine.borrow().is_idle() {
                    s.app.quit();
                } else {
                    dialogs::confirm_quit(s);
                }
            }),
            None,
        );
        add(
            "copy-link",
            Box::new(|s, p| {
                if let Some(url) = p.and_then(|v| v.get::<String>()) {
                    s.copy_text(&url);
                    s.toast("Link copiato");
                }
            }),
            Some(glib::VariantTy::STRING),
        );
        add(
            "open-link",
            Box::new(|s, p| {
                if let Some(url) = p.and_then(|v| v.get::<String>()) {
                    s.open_uri(&url);
                }
            }),
            Some(glib::VariantTy::STRING),
        );

        app.set_accels_for_action("app.open-files", &["<Control>o"]);
        app.set_accels_for_action("app.upload-with-options", &["<Control><Shift>o"]);
        app.set_accels_for_action("app.sync", &["<Control>r", "F5"]);
        app.set_accels_for_action("app.preferences", &["<Control>comma"]);
        app.set_accels_for_action("app.quit", &["<Control>q"]);
        app.set_accels_for_action("window.close", &["<Control>w"]);
    }
}
