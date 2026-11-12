// YTMP for Windows and Linux (docs/implementation/desktop-app.md): the web app from your YTMP
// server in a window, with the YouTube Music module running on this computer, so every
// YouTube request comes from here. A setup page (desktop/setup/) asks for the server first.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(windows)]
mod media;
mod module;

use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use module::Module;
use tauri::ipc::CapabilityBuilder;
use tauri::webview::NewWindowResponse;
use tauri::{AppHandle, Manager, RunEvent, Url, WebviewUrl, WebviewWindow, WebviewWindowBuilder};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogResult};
use tauri_plugin_opener::OpenerExt;
use tauri_plugin_updater::UpdaterExt;

/// What the app keeps: the server, and a release you chose to skip.
#[derive(Default, serde::Serialize, serde::Deserialize)]
struct Settings {
    server: Option<String>,
    skipped_version: Option<String>,
}

struct State {
    settings: Mutex<Settings>,
    /// Server origins that may call the app's commands (a capability each, added once).
    allowed: Mutex<HashSet<String>>,
}

fn settings_file(app: &AppHandle) -> PathBuf {
    app.path().app_config_dir().unwrap_or_default().join("settings.json")
}

fn save(app: &AppHandle) {
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
            .permission("allow-media-update");
        if let Err(e) = app.add_capability(capability) {
            eprintln!("can't allow {origin}: {e}");
        }
    }
    drop(allowed);
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.navigate(url.clone());
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
fn change_server(window: WebviewWindow) {
    let setup = if cfg!(windows) { "http://tauri.localhost/index.html?change=1" } else { "tauri://localhost/index.html?change=1" };
    let _ = window.navigate(Url::parse(setup).unwrap());
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

/// Builds with the updater key look for new versions (and the web app says so).
fn updates_on() -> bool {
    option_env!("YTMP_UPDATER_PUBKEY").is_some_and(|k| !k.is_empty())
}

/// window.YtmpDesktop for the web app (frontend/src/lib/ownModule.svelte.ts, native.ts), and on
/// Windows the Media Session passed to the app (media.rs).
fn bridge_script(app: &AppHandle) -> String {
    let version = app.package_info().version.to_string();
    let platform = if cfg!(windows) { "windows" } else { "linux" };
    let updates = updates_on();
    #[cfg(windows)]
    let media = media::SCRIPT;
    #[cfg(not(windows))]
    let media = "";
    format!(
        r#"(() => {{
  if (!/^https?:$/.test(location.protocol)) return;
  const invoke = (cmd, args) => window.__TAURI_INTERNALS__.invoke(cmd, args);
  window.YtmpDesktop = Object.freeze({{
    version: {version:?},
    platform: {platform:?},
    moduleRequest: (path) => invoke('module_request', {{ path }}),
    changeServer: () => invoke('change_server'),
    updates: {updates},
  }});
  {media}
}})();"#
    )
}

/// The newest release with a desktop update (its latest.json) and its notes. From GitHub's
/// list of releases, like the Android app (Updates.kt): 0.x releases are pre-releases, which
/// GitHub's /releases/latest skips.
fn newest_release() -> Result<(Url, String), String> {
    let releases: serde_json::Value = ureq::get("https://api.github.com/repos/0TrashPanda/YTMP/releases?per_page=10")
        .set("User-Agent", "YTMP desktop")
        .timeout(Duration::from_secs(15))
        .call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())
        .and_then(|text| serde_json::from_str(&text).map_err(|e| e.to_string()))?;
    for release in releases.as_array().into_iter().flatten().filter(|r| r["draft"] != true) {
        let assets = release["assets"].as_array().into_iter().flatten();
        if let Some(asset) = assets.into_iter().find(|a| a["name"] == "latest.json") {
            let url = asset["browser_download_url"].as_str().unwrap_or_default().parse().map_err(|_| "bad URL".to_string())?;
            return Ok((url, release["body"].as_str().unwrap_or_default().to_string()));
        }
    }
    Err("no release with a desktop update".into())
}

/// Looks for a new release a moment after starting, and offers it with its release notes.
async fn check_for_update(app: AppHandle) {
    tokio_sleep(Duration::from_secs(8)).await;
    let (endpoint, notes) = match tauri::async_runtime::spawn_blocking(newest_release).await {
        Ok(Ok(found)) => found,
        Ok(Err(e)) => return eprintln!("update check: {e}"),
        Err(_) => return,
    };
    let Ok(updater) = app.updater_builder().endpoints(vec![endpoint]).and_then(|b| b.build()) else { return };
    let update = match updater.check().await {
        Ok(Some(update)) => update,
        Ok(None) => return,
        Err(e) => return eprintln!("update check: {e}"),
    };
    if app.state::<State>().settings.lock().unwrap().skipped_version.as_deref() == Some(update.version.as_str()) {
        return;
    }
    // The release's notes as they are now (edited after the release, see CLAUDE.md).
    let notes = notes.replace("\r\n", "\n");
    let answer = app
        .dialog()
        .message(format!("YTMP {} is out (you have {}).\n\n{}", update.version, update.current_version, notes.trim()))
        .title("Update YTMP")
        .buttons(MessageDialogButtons::YesNoCancelCustom("Update".into(), "Later".into(), "Skip this version".into()))
        .blocking_show_with_result();
    match answer {
        MessageDialogResult::Custom(label) if label == "Update" => {}
        MessageDialogResult::Yes => {}
        MessageDialogResult::Custom(label) if label == "Skip this version" => {
            app.state::<State>().settings.lock().unwrap().skipped_version = Some(update.version.clone());
            save(&app);
            return;
        }
        _ => return,
    }
    let installed = match update.download(|_, _| {}, || {}).await {
        Ok(bytes) => {
            // The installer replaces the module's files: it mustn't be running then.
            app.state::<Module>().stop();
            update.install(bytes)
        }
        Err(e) => Err(e),
    };
    match installed {
        Ok(()) => app.restart(),
        Err(e) => {
            app.dialog().message(format!("The update didn't install: {e}")).title("Update YTMP").blocking_show();
            // The module was stopped for it: start over as this version.
            app.restart();
        }
    }
}

async fn tokio_sleep(duration: Duration) {
    let _ = tauri::async_runtime::spawn_blocking(move || std::thread::sleep(duration)).await;
}

fn main() {
    let mut builder = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
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
        .manage(Module::new())
        .invoke_handler(tauri::generate_handler![open_saved, connect, change_server, media_update, module::module_request])
        .setup(|app| {
            let handle = app.handle().clone();
            let settings: Settings = std::fs::read_to_string(settings_file(&handle))
                .ok()
                .and_then(|text| serde_json::from_str(&text).ok())
                .unwrap_or_default();
            app.manage(State { settings: Mutex::new(settings), allowed: Mutex::new(HashSet::new()) });
            Module::start(&handle);

            let opener = handle.clone();
            let window = WebviewWindowBuilder::new(app, "main", WebviewUrl::App("index.html".into()))
                .title("YTMP")
                .inner_size(1280.0, 820.0)
                .min_inner_size(360.0, 560.0)
                .initialization_script(&bridge_script(&handle))
                // Links that open a new window (YouTube, GitHub, …) go to the browser.
                .on_new_window(move |url, _| {
                    let _ = opener.opener().open_url(url.as_str(), None::<&str>);
                    NewWindowResponse::Deny
                });
            // WebView2's own media controls would show as "msedgewebview2.exe": the app shows them
            // instead (media.rs). The first three are Tauri's defaults, which this replaces.
            #[cfg(windows)]
            let window = window.additional_browser_args(
                "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection,HardwareMediaKeyHandling",
            );
            let window = window.build()?;
            #[cfg(windows)]
            media::setup(&handle, &window);
            #[cfg(not(windows))]
            let _ = window;
            if updates_on() {
                tauri::async_runtime::spawn(check_for_update(handle));
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("YTMP couldn't start");
    app.run(|app, event| {
        if let RunEvent::Exit = event {
            app.state::<Module>().stop();
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
