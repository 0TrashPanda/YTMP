// YTMP for Windows and Linux (docs/implementation/apps/desktop-app.md): the web app from your YTMP
// server in a window, with the YouTube Music module running on this computer, so every
// YouTube request comes from here. A setup page (desktop/setup/) asks for the server first.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod frame;
#[cfg(windows)]
mod media;
mod module;
mod updates;

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use module::{Kind, Sidecars};
use tauri::ipc::CapabilityBuilder;
use tauri::{AppHandle, Manager, RunEvent, Url, Webview};
use tauri_plugin_dialog::DialogExt;
use tauri_plugin_opener::OpenerExt;

/// What the app keeps: the server, this computer's id and secret for the server (it asks for a
/// song of this computer's music with them), and the version it was installing (updates.rs).
#[derive(Default, serde::Serialize, serde::Deserialize)]
pub(crate) struct Settings {
    server: Option<String>,
    #[serde(default)]
    pub(crate) installing: Option<String>,
    #[serde(default)]
    device_id: Option<String>,
    #[serde(default)]
    device_secret: Option<String>,
}

/// Where the window reaches this computer's host (the `ytmphost` protocol).
#[cfg(windows)]
pub(crate) const HOST_BASE: &str = "http://ytmphost.localhost/";
#[cfg(not(windows))]
pub(crate) const HOST_BASE: &str = "ytmphost://localhost/";

fn random_hex(bytes: usize) -> String {
    let mut buffer = vec![0u8; bytes];
    getrandom::fill(&mut buffer).expect("random bytes");
    buffer.iter().map(|b| format!("{b:02x}")).collect()
}

/// This computer's id (in the IDs of its songs) and its secret, made once.
pub(crate) fn device(app: &AppHandle) -> (String, String) {
    let state = app.state::<State>();
    let made = {
        let mut settings = state.settings.lock().unwrap();
        if settings.device_id.is_none() || settings.device_secret.is_none() {
            settings.device_id = Some(random_hex(8));
            settings.device_secret = Some(random_hex(24));
            true
        } else {
            false
        }
    };
    if made {
        save(app);
    }
    let settings = state.settings.lock().unwrap();
    (settings.device_id.clone().unwrap(), settings.device_secret.clone().unwrap())
}

pub(crate) struct State {
    pub(crate) settings: Mutex<Settings>,
    /// Server origins that may call the app's commands (a capability each, added once).
    allowed: Mutex<HashSet<String>>,
}

fn settings_file(app: &AppHandle) -> PathBuf {
    app.path().app_config_dir().unwrap_or_default().join("settings.json")
}

pub(crate) fn save(app: &AppHandle) {
    let file = settings_file(app);
    let _ = std::fs::create_dir_all(file.parent().unwrap());
    let state = app.state::<State>();
    let text = serde_json::to_string_pretty(&*state.settings.lock().unwrap()).unwrap();
    let _ = std::fs::write(file, text);
}

/// "192.168.1.10:8080" → "http://192.168.1.10:8080", like the Android app.
fn normalize(input: &str) -> Result<Url, String> {
    let trimmed = input.trim().trim_end_matches('/');
    let text = if trimmed.contains("://") { trimmed.to_string() } else { format!("http://{trimmed}") };
    let url = Url::parse(&text).map_err(|_| format!("\"{trimmed}\" isn't a server address"))?;
    if !matches!(url.scheme(), "http" | "https") || url.host().is_none() {
        return Err(format!("\"{trimmed}\" isn't a server address"));
    }
    Ok(url)
}

/// The server answers (any page, even an error page, means it's there). Answers its address
/// after redirects: a server behind Caddy sends http:// to https://, and the page that ends up
/// there is the one that needs the app's commands.
fn reachable(url: &Url) -> Result<Url, String> {
    let host = url.host_str().unwrap_or_default().to_string();
    let landed = match ureq::get(url.as_str()).timeout(Duration::from_secs(8)).call() {
        Ok(response) | Err(ureq::Error::Status(_, response)) => response.get_url().to_string(),
        Err(_) => return Err(format!("Can't reach {host}. Is the address right, and is the server on?")),
    };
    Ok(Url::parse(&landed).map(|landed| same_path_at(url, &landed)).unwrap_or_else(|_| url.clone()))
}

/// [url]'s path on [landed]'s scheme, host and port (a redirect may also have gone to a page).
fn same_path_at(url: &Url, landed: &Url) -> Url {
    let mut moved = url.clone();
    if moved.set_scheme(landed.scheme()).is_err() || moved.set_host(landed.host_str()).is_err() || moved.set_port(landed.port()).is_err() {
        return url.clone();
    }
    moved
}

/// Lets this server's pages use the app's commands, then opens it.
fn open_server(app: &AppHandle, url: &Url) {
    let origin = url.origin().ascii_serialization();
    let state = app.state::<State>();
    let mut allowed = state.allowed.lock().unwrap();
    if allowed.insert(origin.clone()) {
        let capability = CapabilityBuilder::new(format!("server-{}", allowed.len()))
            .remote(format!("{origin}/*"))
            .local(false)
            .window("main")
            .permission("allow-module-request")
            .permission("allow-change-server")
            .permission("allow-media-update")
            .permission("allow-pick-folder")
            .permission("allow-open-logs")
            .permission("allow-read-logs")
            .permission("allow-check-updates")
            .permission("allow-quit-ready");
        if let Err(e) = app.add_capability(capability) {
            eprintln!("can't allow {origin}: {e}");
        }
    }
    drop(allowed);
    if let Some(page) = app.get_webview("app") {
        let _ = page.navigate(url.clone());
    }
}

#[derive(serde::Serialize)]
struct SetupState {
    address: String,
    error: String,
}

/// The setup page asks on opening: opens the saved server when it answers, else says why.
#[tauri::command]
async fn open_saved(app: AppHandle, change: bool) -> SetupState {
    let saved = app.state::<State>().settings.lock().unwrap().server.clone();
    let Some(saved) = saved else { return SetupState { address: String::new(), error: String::new() } };
    if change {
        return SetupState { address: saved, error: String::new() };
    }
    let url = match normalize(&saved) {
        Ok(url) => url,
        Err(error) => return SetupState { address: saved, error },
    };
    let checked = url.clone();
    match tauri::async_runtime::spawn_blocking(move || reachable(&checked)).await.unwrap_or(Ok(url)) {
        Ok(url) => {
            open_server(&app, &url);
            SetupState { address: saved, error: String::new() }
        }
        Err(error) => SetupState { address: saved, error },
    }
}

/// Connect on the setup page: checks the address, keeps it and opens it.
#[tauri::command]
async fn connect(app: AppHandle, address: String) -> Result<(), String> {
    let url = normalize(&address)?;
    let url = tauri::async_runtime::spawn_blocking(move || reachable(&url)).await.map_err(|e| e.to_string())??;
    app.state::<State>().settings.lock().unwrap().server = Some(url.as_str().trim_end_matches('/').to_string());
    save(&app);
    open_server(&app, &url);
    Ok(())
}

/// *Change server* in the web app: back to the setup page, with the address filled in.
#[tauri::command]
fn change_server(page: Webview) {
    let setup = if cfg!(windows) { "http://tauri.localhost/index.html?change=1" } else { "tauri://localhost/index.html?change=1" };
    let _ = page.navigate(Url::parse(setup).unwrap());
}

/// The page's Media Session changed: on Windows the app shows it (media.rs); elsewhere the web view does that itself.
#[tauri::command]
fn media_update(app: AppHandle, update: serde_json::Value) {
    #[cfg(windows)]
    if let Ok(update) = serde_json::from_value(update) {
        media::update(&app, update);
    }
    #[cfg(not(windows))]
    let _ = (app, update);
}

/// *Add folder* for the music on this computer: the system's folder picker.
#[tauri::command]
async fn pick_folder(app: AppHandle) -> Option<String> {
    tauri::async_runtime::spawn_blocking(move || app.dialog().file().blocking_pick_folder())
        .await
        .ok()
        .flatten()
        .and_then(|path| path.into_path().ok())
        .map(|path| path.to_string_lossy().into_owned())
}

/// The logs of this start of the app (module.rs: `host.log`, `module.log`), in the app's log folder.
fn log_dir(app: &AppHandle) -> PathBuf {
    app.path().app_log_dir().unwrap_or_else(|_| std::env::temp_dir())
}

/// Settings → App → *Open logs folder*: the log folder in the system's file manager.
#[tauri::command]
fn open_logs(app: AppHandle) -> Result<(), String> {
    let dir = log_dir(&app);
    let _ = std::fs::create_dir_all(&dir);
    app.opener().open_path(dir.to_string_lossy(), None::<&str>).map_err(|e| e.to_string())
}

/// A log longer than this: only its end is copied (the clipboard and a chat take that much).
const COPIED_LOG_BYTES: usize = 2 * 1024 * 1024;

/// Settings → App → *Copy logs*: the app's version and this start's logs as text, for the window
/// to put on the clipboard. Song names and requests, no passwords or sign-ins.
#[tauri::command]
fn read_logs(app: AppHandle) -> String {
    let dir = log_dir(&app);
    let mut text = format!("YTMP {} ({})
", app.package_info().version, if cfg!(windows) { "Windows" } else { "Linux" });
    for name in ["host.log", "module.log"] {
        let Ok(bytes) = std::fs::read(dir.join(name)) else {
            text.push_str(&format!("
===== {name}: none =====
"));
            continue;
        };
        let start = bytes.len().saturating_sub(COPIED_LOG_BYTES);
        text.push_str(&format!("
===== {name}{} =====
", if start > 0 { " (its end)" } else { "" }));
        text.push_str(&String::from_utf8_lossy(&bytes[start..]));
    }
    text
}

/// `ytmphost://localhost/api/…` (Windows: `http://ytmphost.localhost/api/…`): the window's
/// requests to this computer's host (desktop/host/), passed on with its key: this computer's
/// music, the song cache. Pages of the window may use it (it only shows the server you chose).
fn host_request(app: &AppHandle, request: tauri::http::Request<Vec<u8>>) -> tauri::http::Response<Vec<u8>> {
    let cors = |builder: tauri::http::response::Builder| {
        builder
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE")
            .header("Access-Control-Allow-Headers", "Content-Type, Range")
            .header("Access-Control-Expose-Headers", "Content-Range, Accept-Ranges")
    };
    if request.method() == "OPTIONS" {
        return cors(tauri::http::Response::builder().status(204)).body(vec![]).unwrap_or_default();
    }
    let header = |name: &str| request.headers().get(name).and_then(|v| v.to_str().ok());
    let path = request.uri().path_and_query().map(|p| p.as_str()).unwrap_or("/");
    let (status, headers, body) =
        app.state::<Sidecars>().host.forward(request.method().as_str(), path, header("range"), header("content-type"), request.body());
    let mut response = cors(tauri::http::Response::builder().status(status));
    for (name, value) in headers {
        response = response.header(name, value);
    }
    response.body(body).unwrap_or_default()
}

/// window.YtmpDesktop for the web app (frontend/src/lib/ownModule.svelte.ts, native.ts), and on
/// Windows the Media Session passed to the app (media.rs).
fn bridge_script(app: &AppHandle) -> String {
    let version = app.package_info().version.to_string();
    let platform = if cfg!(windows) { "windows" } else { "linux" };
    let updates = updates::on();
    let (device_id, device_secret) = device(app);
    let host = HOST_BASE.trim_end_matches('/');
    #[cfg(windows)]
    let media = media::SCRIPT;
    #[cfg(not(windows))]
    let media = "";
    format!(
        r#"(() => {{
  if (!/^https?:$/.test(location.protocol)) return;
  const invoke = (cmd, args) => window.__TAURI_INTERNALS__.invoke(cmd, args);
  // Closing (or restarting for an update): the page's handler first, a moment at most (frame.rs).
  let quitHandler = null;
  Object.defineProperty(window, '__ytmpQuit', {{ value: async (forUpdate) => {{
    try {{
      if (quitHandler) await Promise.race([quitHandler(forUpdate), new Promise((done) => setTimeout(done, 2000))]);
    }} catch (e) {{
      console.error(e);
    }}
    invoke('quit_ready');
  }} }});
  window.YtmpDesktop = Object.freeze({{
    version: {version:?},
    platform: {platform:?},
    moduleRequest: (path, method, body) => invoke('module_request', {{ path, method, body }}),
    changeServer: () => invoke('change_server'),
    updates: {updates},
    host: {host:?},
    device: Object.freeze({{ id: {device_id:?}, secret: {device_secret:?} }}),
    pickFolder: () => invoke('pick_folder'),
    openLogs: () => invoke('open_logs'),
    readLogs: () => invoke('read_logs'),
    checkUpdates: () => invoke('check_updates'),
    onQuit: (handler) => {{ quitHandler = handler; }},
    autoplay: true,
  }});
  {media}
}})();"#
    )
}

fn main() {
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_window("main") {
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init());
    // Updates are signed; builds without the key (development) don't look for them.
    if let Some(key) = option_env!("YTMP_UPDATER_PUBKEY").filter(|k| !k.is_empty()) {
        builder = builder.plugin(tauri_plugin_updater::Builder::new().pubkey(key).build());
    }
    let app = builder
        .manage(Sidecars::new())
        .manage(updates::Updates::default())
        .manage(frame::QuitWait::default())
        .invoke_handler(tauri::generate_handler![
            open_saved,
            connect,
            change_server,
            media_update,
            pick_folder,
            open_logs,
            read_logs,
            module::module_request,
            frame::quit_ready,
            frame::window_action,
            frame::frame_state,
            updates::check_updates,
            updates::splash_state,
            updates::update_info,
            updates::open_update,
            updates::close_update,
            updates::install_update,
        ])
        .register_asynchronous_uri_scheme_protocol("ytmphost", |ctx, request, responder| {
            let app = ctx.app_handle().clone();
            std::thread::spawn(move || responder.respond(host_request(&app, request)));
        })
        .setup(|app| {
            let handle = app.handle().clone();
            let settings: Settings = std::fs::read_to_string(settings_file(&handle))
                .ok()
                .and_then(|text| serde_json::from_str(&text).ok())
                .unwrap_or_default();
            app.manage(State { settings: Mutex::new(settings), allowed: Mutex::new(HashSet::new()) });
            module::Module::start(&handle, Kind::Module);
            module::Module::start(&handle, Kind::Host);

            // The splash window first (like Discord): a new version installs right there, before
            // the window opens; otherwise it shows the app starting until the page has loaded.
            let updated = updates::opened(&handle);
            updates::open_splash(&handle, "Starting YTMP…");
            tauri::async_runtime::spawn(async move {
                // Just updated: no need to look again.
                if updates::on() && updated.is_none() && updates::install_at_start(&handle).await {
                    return;
                }
                updates::set_splash(&handle, "Starting YTMP…", None, updated.as_deref().unwrap_or(""));
                let bridge = bridge_script(&handle);
                match frame::open(&handle, &bridge) {
                    #[cfg(windows)]
                    Ok(window) => media::setup(&handle, &window),
                    #[cfg(not(windows))]
                    Ok(_) => {}
                    Err(e) => {
                        eprintln!("window: {e}");
                        handle.dialog().message(format!("YTMP couldn't open its window: {e}")).title("YTMP").blocking_show();
                        handle.exit(1);
                    }
                }
                if updates::on() {
                    updates::look_now_and_then(handle).await;
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("YTMP couldn't start");
    app.run(|app, event| {
        if let RunEvent::Exit = event {
            app.state::<Sidecars>().stop();
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_redirect_moves_the_address_but_keeps_its_path() {
        let typed = normalize("ytmp.example.com/sub").unwrap();
        let landed = Url::parse("https://ytmp.example.com/sub/login").unwrap();
        assert_eq!(same_path_at(&typed, &landed).as_str(), "https://ytmp.example.com/sub");
        let other_port = Url::parse("http://10.0.0.5:8443/").unwrap();
        assert_eq!(same_path_at(&normalize("10.0.0.5:8080").unwrap(), &other_port).as_str(), "http://10.0.0.5:8443/");
    }
}
