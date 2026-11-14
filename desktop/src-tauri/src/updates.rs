// New versions (docs/implementation/apps/desktop-app.md#packaging-updates-and-releases). At the start the splash window
// looks for one and installs it right there, before the window opens (like Discord). While the
// app runs it looks every few hours: a new version downloads quietly, and the green arrow in the
// title bar (frame.rs) offers it; *Restart now* installs it. Before installing, the page stops
// playing the room (frame::before_quit), so the room doesn't play on for nobody.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use tauri::{AppHandle, Manager, PhysicalPosition, Url, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_updater::{Update, UpdaterExt};

use crate::module::Sidecars;
use crate::{frame, save, State};

/// Builds with the updater key look for new versions (and the web app says so).
pub fn on() -> bool {
    option_env!("YTMP_UPDATER_PUBKEY").is_some_and(|k| !k.is_empty())
}

/// A downloaded version, ready to install.
struct Ready {
    update: Update,
    bytes: Vec<u8>,
    notes: String,
}

/// The app's update state.
#[derive(Default)]
pub struct Updates {
    ready: Mutex<Option<Ready>>,
    /// What the splash window shows (it asks once it's loaded: see `splash_state`).
    splash: Mutex<SplashText>,
}

#[derive(Clone, Default, serde::Serialize)]
pub struct SplashText {
    what: String,
    progress: Option<f64>,
    under: String,
}

/// The version that's downloaded and ready, if any (for the title bar's arrow).
pub fn ready_version(app: &AppHandle) -> Option<String> {
    app.state::<Updates>().ready.lock().unwrap().as_ref().map(|r| r.update.version.clone())
}

// ---------------------------------------------------------------------------------------------
// The splash window

const SPLASH: &str = "splash";

/// Opens the small window that shows the app starting (and updating).
pub fn open_splash(app: &AppHandle, what: &str) {
    set_splash(app, what, None, "");
    if app.get_webview_window(SPLASH).is_some() {
        return;
    }
    let builder = WebviewWindowBuilder::new(app, SPLASH, WebviewUrl::App("splash.html".into()))
        .title("YTMP")
        .inner_size(240.0, 280.0)
        .resizable(false)
        .maximizable(false)
        .decorations(false)
        .center()
        .focused(true);
    #[cfg(windows)]
    let builder = builder.additional_browser_args(frame::BROWSER_ARGS);
    if let Err(e) = builder.build() {
        eprintln!("splash: {e}");
    }
}

/// Shows [what] in the splash window, how far ([progress] 0–1, or none), and a line [under] it.
pub fn set_splash(app: &AppHandle, what: &str, progress: Option<f64>, under: &str) {
    let text = SplashText { what: what.into(), progress, under: under.into() };
    *app.state::<Updates>().splash.lock().unwrap() = text.clone();
    if let Some(splash) = app.get_webview_window(SPLASH) {
        let _ = splash.eval(format!("window.show && window.show({})", splash_args(&text)));
    }
}

fn splash_args(text: &SplashText) -> String {
    let json = |v: &str| serde_json::to_string(v).unwrap_or_default();
    let progress = text.progress.map_or("null".to_string(), |p| p.clamp(0.0, 1.0).to_string());
    format!("{}, {progress}, {}", json(&text.what), json(&text.under))
}

pub fn close_splash(app: &AppHandle) {
    if let Some(splash) = app.get_webview_window(SPLASH) {
        let _ = splash.close();
    }
}

/// The splash page just loaded: what it should show.
#[tauri::command]
pub fn splash_state(app: AppHandle) -> SplashText {
    app.state::<Updates>().splash.lock().unwrap().clone()
}

// ---------------------------------------------------------------------------------------------
// Looking for a new version

const RELEASES: &str = "https://github.com/0TrashPanda/YTMP/releases";

/// One look at a time (the one at the start, every few hours, and *Check for updates*).
static CHECKING: AtomicBool = AtomicBool::new(false);

/// A new version and its release notes, or none when this is the newest. [timeout] for each request.
async fn find(app: &AppHandle, timeout: Duration) -> Result<Option<(Update, String)>, String> {
    let (endpoint, notes) = tauri::async_runtime::spawn_blocking(move || newest_release(timeout)).await.map_err(|e| e.to_string())??;
    let updater = app.updater_builder().endpoints(vec![endpoint]).map_err(|e| e.to_string())?.timeout(timeout).build().map_err(|e| e.to_string())?;
    let update = updater.check().await.map_err(|e| e.to_string())?;
    // The release's notes as they are now (edited after the release, see CLAUDE.md).
    Ok(update.map(|update| (update, notes.replace("\r\n", "\n"))))
}

/// At the start, in the splash window: a new version downloads and installs right away, before
/// the app's window opens. False when there's none (or it couldn't look): the app opens.
pub async fn install_at_start(app: &AppHandle) -> bool {
    if CHECKING.swap(true, Ordering::SeqCst) {
        return false;
    }
    set_splash(app, "Checking for updates…", None, "");
    // Not long: without internet the app still opens quickly.
    let found = find(app, Duration::from_secs(6)).await;
    CHECKING.store(false, Ordering::SeqCst);
    let (update, _) = match found {
        Ok(Some(found)) => found,
        Ok(None) => return false,
        Err(e) => {
            eprintln!("update check: {e}");
            return false;
        }
    };
    let version = update.version.clone();
    set_splash(app, &format!("Downloading YTMP {version}"), Some(0.0), "");
    let shown = app.clone();
    let mut got = 0usize;
    let mut last = -1i64;
    let downloaded = update
        .download(
            move |chunk, total| {
                got += chunk;
                let Some(total) = total.filter(|t| *t > 0) else { return };
                let percent = (got as f64 / total as f64 * 100.0) as i64;
                if percent != last {
                    last = percent;
                    let under = format!("{} of {} MB", got / 1_000_000, total / 1_000_000);
                    set_splash(&shown, &format!("Downloading YTMP {version}"), Some(got as f64 / total as f64), &under);
                }
            },
            || {},
        )
        .await;
    match downloaded {
        Ok(bytes) => {
            install(app, update, bytes);
            true
        }
        Err(e) => {
            // Opens as this version; it tries again next time.
            eprintln!("update download: {e}");
            false
        }
    }
}

/// Every few hours while the app runs: a new version downloads, and the title bar offers it.
pub async fn look_now_and_then(app: AppHandle) {
    loop {
        sleep(Duration::from_secs(6 * 60 * 60)).await;
        if let Err(e) = look(&app).await {
            eprintln!("update check: {e}");
        }
    }
}

/// Looks for a new version; when there is one it downloads (if it isn't already) and the arrow
/// in the title bar shows. True when there is one.
async fn look(app: &AppHandle) -> Result<bool, String> {
    if ready_version(app).is_some() {
        return Ok(true);
    }
    if CHECKING.swap(true, Ordering::SeqCst) {
        return Err("It's already looking.".into());
    }
    let found = find(app, Duration::from_secs(15)).await;
    let Some((update, notes)) = found.inspect_err(|_| CHECKING.store(false, Ordering::SeqCst))? else {
        CHECKING.store(false, Ordering::SeqCst);
        return Ok(false);
    };
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        match update.download(|_, _| {}, || {}).await {
            Ok(bytes) => {
                let version = update.version.clone();
                *app.state::<Updates>().ready.lock().unwrap() = Some(Ready { update, bytes, notes });
                frame::update_ready(&app, &version);
            }
            Err(e) => eprintln!("update download: {e}"),
        }
        CHECKING.store(false, Ordering::SeqCst);
    });
    Ok(true)
}

/// *Settings → App → Check for updates*: looks now. True when there's a new version (it
/// downloads, and the arrow in the title bar offers it), false when this is the newest.
#[tauri::command]
pub async fn check_updates(app: AppHandle) -> Result<bool, String> {
    if !on() {
        return Err("This build doesn't update itself.".into());
    }
    look(&app).await
}

// ---------------------------------------------------------------------------------------------
// Installing

/// Installs [update] and starts the new version. The splash window says so meanwhile.
fn install(app: &AppHandle, update: Update, bytes: Vec<u8>) {
    set_splash(app, &format!("Installing YTMP {}…", update.version), None, "");
    // The new version's splash says it's done (see `opened`).
    app.state::<State>().settings.lock().unwrap().installing = Some(update.version.clone());
    save(app);
    // The installer replaces the module's and the host's files: they mustn't be running then.
    app.state::<Sidecars>().stop();
    // On Windows this starts the installer (quietly: the splash window shows it) and ends the
    // app; the installer starts the new version when it's done.
    if let Err(e) = update.install(bytes) {
        eprintln!("update install: {e}");
        app.state::<State>().settings.lock().unwrap().installing = None;
        save(app);
        set_splash(app, "The update didn't install", None, &e.to_string());
        std::thread::sleep(Duration::from_secs(4));
    }
    // The module was stopped for it: start over (as the new version, or as this one).
    app.restart();
}

/// The app started: a line for the splash window when it just updated itself.
pub fn opened(app: &AppHandle) -> Option<String> {
    let installing = app.state::<State>().settings.lock().unwrap().installing.take();
    installing.as_ref()?;
    save(app);
    let version = app.package_info().version.to_string();
    (installing.as_deref() == Some(version.as_str())).then(|| format!("Updated to {version}"))
}

/// What the arrow's popup shows.
#[derive(serde::Serialize)]
pub struct UpdateInfo {
    version: String,
    current: String,
    notes: String,
}

#[tauri::command]
pub fn update_info(app: AppHandle) -> Option<UpdateInfo> {
    let ready = app.state::<Updates>();
    let ready = ready.ready.lock().unwrap();
    ready.as_ref().map(|r| UpdateInfo { version: r.update.version.clone(), current: r.update.current_version.clone(), notes: r.notes.clone() })
}

const POPUP: &str = "update";
const POPUP_WIDTH: f64 = 320.0;
const POPUP_HEIGHT: f64 = 220.0;

/// The arrow in the title bar, at [x] (from the window's left, in its pixels): the popup under
/// it, with the release notes and *Restart now*. Clicked again, it closes.
#[tauri::command]
pub fn open_update(app: AppHandle, x: f64) {
    if let Some(popup) = app.get_webview_window(POPUP) {
        let _ = popup.close();
        return;
    }
    let Some(window) = app.get_window("main") else { return };
    let scale = window.scale_factor().unwrap_or(1.0);
    let (Ok(at), Ok(size)) = (window.inner_position(), window.inner_size()) else { return };
    let width = POPUP_WIDTH * scale;
    // Under the arrow, inside the window.
    let left = (at.x as f64 + x * scale - width + 24.0 * scale).max(at.x as f64 + 8.0 * scale);
    let left = left.min(at.x as f64 + size.width as f64 - width - 8.0 * scale);
    let top = at.y as f64 + (frame::BAR + 4.0) * scale;
    let builder = WebviewWindowBuilder::new(&app, POPUP, WebviewUrl::App("update.html".into()))
        .title("Update YTMP")
        .inner_size(POPUP_WIDTH, POPUP_HEIGHT)
        .resizable(false)
        .decorations(false)
        .skip_taskbar(true)
        .always_on_top(true)
        .focused(true)
        .visible(false);
    #[cfg(windows)]
    let builder = builder.additional_browser_args(frame::BROWSER_ARGS);
    match builder.build() {
        Ok(popup) => {
            let _ = popup.set_position(PhysicalPosition::new(left as i32, top as i32));
            let _ = popup.show();
            let _ = popup.set_focus();
        }
        Err(e) => eprintln!("update popup: {e}"),
    }
}

#[tauri::command]
pub fn close_update(app: AppHandle) {
    if let Some(popup) = app.get_webview_window(POPUP) {
        let _ = popup.close();
    }
}

/// *Restart now*: the page stops playing the room, the splash window shows the install, and the
/// new version starts (playing on where this one stopped: appQuit.ts).
#[tauri::command]
pub async fn install_update(app: AppHandle) {
    close_update(app.clone());
    let Some(ready) = app.state::<Updates>().ready.lock().unwrap().take() else { return };
    frame::before_quit(&app, true).await;
    open_splash(&app, &format!("Installing YTMP {}…", ready.update.version));
    if let Some(window) = app.get_window("main") {
        let _ = window.hide();
    }
    tauri::async_runtime::spawn_blocking(move || install(&app, ready.update, ready.bytes));
}

pub async fn sleep(duration: Duration) {
    let _ = tauri::async_runtime::spawn_blocking(move || std::thread::sleep(duration)).await;
}

// ---------------------------------------------------------------------------------------------
// GitHub's releases

/// The newest release with a desktop update (its latest.json) and its notes. From GitHub's
/// list of releases, like the Android app (Updates.kt): 0.x releases are pre-releases, which
/// GitHub's /releases/latest skips. GitHub's API allows 60 requests an hour per network without
/// signing in, shared by everything on it; past that, the releases feed.
fn newest_release(timeout: Duration) -> Result<(Url, String), String> {
    newest_release_from_api(timeout).or_else(|api| {
        newest_release_from_feed(timeout).map_err(|feed| format!("GitHub didn't answer ({api}; {feed})"))
    })
}

fn get_text(url: &str, timeout: Duration) -> Result<String, String> {
    ureq::get(url)
        .set("User-Agent", "YTMP desktop")
        .timeout(timeout)
        .call()
        .map_err(|e| e.to_string())?
        .into_string()
        .map_err(|e| e.to_string())
}

fn newest_release_from_api(timeout: Duration) -> Result<(Url, String), String> {
    let text = get_text("https://api.github.com/repos/0TrashPanda/YTMP/releases?per_page=10", timeout)?;
    let releases: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    for release in releases.as_array().into_iter().flatten().filter(|r| r["draft"] != true) {
        let assets = release["assets"].as_array().into_iter().flatten();
        if let Some(asset) = assets.into_iter().find(|a| a["name"] == "latest.json") {
            let url = asset["browser_download_url"].as_str().unwrap_or_default().parse().map_err(|_| "bad URL".to_string())?;
            return Ok((url, release["body"].as_str().unwrap_or_default().to_string()));
        }
    }
    Err("no release with a desktop update".into())
}

/// The same from github.com/…/releases.atom (newest first, drafts left out): the first
/// release whose latest.json is there.
fn newest_release_from_feed(timeout: Duration) -> Result<(Url, String), String> {
    let feed = get_text(&format!("{RELEASES}.atom"), timeout)?;
    for entry in feed.split("<entry>").skip(1).take(10) {
        let Some(tag) = between(entry, "/releases/tag/", "\"") else { continue };
        let url = format!("{RELEASES}/download/{tag}/latest.json");
        let there = matches!(ureq::head(&url).set("User-Agent", "YTMP desktop").timeout(timeout).call(), Ok(r) if r.status() == 200);
        if there {
            let notes = between(entry, "<content type=\"html\">", "</content>").map(|html| html_to_text(&unescape(html))).unwrap_or_default();
            return Ok((url.parse().map_err(|_| "bad URL".to_string())?, notes));
        }
    }
    Err("no release with a desktop update".into())
}

fn between<'a>(text: &'a str, start: &str, end: &str) -> Option<&'a str> {
    let from = text.find(start)? + start.len();
    let length = text[from..].find(end)?;
    Some(&text[from..from + length])
}

fn unescape(text: &str) -> String {
    text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/// Release notes from the feed's HTML as plain text: headings and list items on lines of their
/// own (headings as `## …`, list items as `- …`, like the API's Markdown).
fn html_to_text(html: &str) -> String {
    let html = html.replace("<li>", "- ").replace('\n', " ");
    let mut text = String::new();
    let mut rest = html.as_str();
    while let Some(open) = rest.find('<') {
        text.push_str(&rest[..open]);
        let Some(close) = rest[open..].find('>') else { break };
        let tag = &rest[open + 1..open + close];
        if tag.len() == 2 && tag.starts_with('h') {
            text.push_str("## ");
        }
        if matches!(tag, "/li" | "/p" | "/ul" | "/ol" | "br" | "br/" | "br /") || tag.starts_with("/h") {
            text.push('\n');
        }
        if tag.starts_with("/h") || tag == "/p" || tag == "/ul" {
            text.push('\n');
        }
        rest = &rest[open + close + 1..];
    }
    text.push_str(rest);
    let text = unescape(&text);
    let mut lines: Vec<&str> = Vec::new();
    for line in text.lines().map(str::trim) {
        // At most one empty line in a row.
        if !(line.is_empty() && lines.last().is_some_and(|l| l.is_empty())) {
            lines.push(line);
        }
    }
    lines.join("\n").trim().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn feed_notes_read_like_the_markdown() {
        let html = "<h2>New</h2><ul><li>Home &amp; more</li></ul><h2>Install</h2><p>Get it</p>";
        assert_eq!(html_to_text(html), "## New\n\n- Home & more\n\n## Install\n\nGet it");
    }

    #[test]
    fn the_splash_gets_plain_arguments() {
        let text = SplashText { what: "Downloading \"x\"".into(), progress: Some(1.5), under: String::new() };
        assert_eq!(splash_args(&text), r#""Downloading \"x\"", 1, """#);
    }
}
