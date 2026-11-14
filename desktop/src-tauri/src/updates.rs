// New versions (docs/implementation/apps/desktop-app.md#packaging-updates-and-releases). At the start the splash window
// looks for one and installs it right there, before the window opens (like Discord), unless
// *Ask before updating* is on. While the app runs it looks every few hours, and when the web app
// says the server was updated. A new version found while the app runs (or asked first) is
// offered in a dialog with *Restart now* and *Later*; it downloads meanwhile, and the green
// arrow in the title bar (frame.rs) offers it again after *Later*. Before installing, the page stops
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

/// A new version: what the dialog shows, and the download once it's done.
struct Found {
    update: Update,
    notes: String,
    bytes: Option<Vec<u8>>,
}

/// The app's update state.
#[derive(Default)]
pub struct Updates {
    found: Mutex<Option<Found>>,
    /// What the splash window shows (it asks once it's loaded: see `splash_state`).
    splash: Mutex<SplashText>,
}

#[derive(Clone, Default, serde::Serialize)]
pub struct SplashText {
    what: String,
    progress: Option<f64>,
    under: String,
}

/// The new version that's offered, if any (for the title bar's arrow).
pub fn found_version(app: &AppHandle) -> Option<String> {
    app.state::<Updates>().found.lock().unwrap().as_ref().map(|f| f.update.version.clone())
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
/// *Restart now* came before the download was done: it installs as soon as it is.
static INSTALL_WHEN_DOWNLOADED: AtomicBool = AtomicBool::new(false);

/// A new version and its release notes, or none when this is the newest. [timeout] for each request.
async fn find(app: &AppHandle, timeout: Duration) -> Result<Option<(Update, String)>, String> {
    let (endpoint, notes) = tauri::async_runtime::spawn_blocking(move || newest_release(timeout)).await.map_err(|e| e.to_string())??;
    let updater = app.updater_builder().endpoints(vec![endpoint]).map_err(|e| e.to_string())?.timeout(timeout).build().map_err(|e| e.to_string())?;
    let update = updater.check().await.map_err(|e| e.to_string())?;
    // The release's notes as they are now (edited after the release, see CLAUDE.md).
    Ok(update.map(|update| (update, notes.replace("\r\n", "\n"))))
}

/// *Settings → App → Ask before updating*: on, a new version found at the start is offered
/// (with *Later*) once the window is open, instead of installing right away.
fn asks_first(app: &AppHandle) -> bool {
    app.state::<State>().settings.lock().unwrap().ask_before_updating
}

/// At the start, in the splash window: a new version downloads and installs right away, before
/// the app's window opens (unless the app asks first: then it's offered once the window is
/// open). False when the app opens.
pub async fn install_at_start(app: &AppHandle) -> bool {
    if CHECKING.swap(true, Ordering::SeqCst) {
        return false;
    }
    set_splash(app, "Checking for updates…", None, "");
    // Not long: without internet the app still opens quickly.
    let found = find(app, Duration::from_secs(6)).await;
    CHECKING.store(false, Ordering::SeqCst);
    let (update, notes) = match found {
        Ok(Some(found)) => found,
        Ok(None) => return false,
        Err(e) => {
            eprintln!("update check: {e}");
            return false;
        }
    };
    if asks_first(app) {
        offer(app, update, notes);
        return false;
    }
    let version = update.version.clone();
    set_splash(app, &format!("Downloading YTMP {version}"), Some(0.0), "");
    let downloaded = update.download(progress(app, &version), || {}).await;
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

/// How far the download is, in the splash window.
fn progress(app: &AppHandle, version: &str) -> impl FnMut(usize, Option<u64>) + Send + 'static {
    let app = app.clone();
    let what = format!("Downloading YTMP {version}");
    let mut got = 0usize;
    let mut last = -1i64;
    move |chunk, total| {
        got += chunk;
        let Some(total) = total.filter(|t| *t > 0) else { return };
        let percent = (got as f64 / total as f64 * 100.0) as i64;
        if percent != last {
            last = percent;
            let under = format!("{} of {} MB", got / 1_000_000, total / 1_000_000);
            set_splash(&app, &what, Some(got as f64 / total as f64), &under);
        }
    }
}

/// Every few hours while the app runs.
pub async fn look_now_and_then(app: AppHandle) {
    loop {
        sleep(Duration::from_secs(6 * 60 * 60)).await;
        if let Err(e) = look(&app, false).await {
            eprintln!("update check: {e}");
        }
    }
}

/// Looks for a new version; a new one is offered right away (the dialog, see `offer`). One
/// that was already offered (and put off with *Later*) is offered again when [again]. True
/// when there is one.
async fn look(app: &AppHandle, again: bool) -> Result<bool, String> {
    if found_version(app).is_some() {
        if again {
            open_dialog(app);
        }
        return Ok(true);
    }
    if CHECKING.swap(true, Ordering::SeqCst) {
        return Err("It's already looking.".into());
    }
    let found = find(app, Duration::from_secs(15)).await;
    CHECKING.store(false, Ordering::SeqCst);
    let Some((update, notes)) = found? else { return Ok(false) };
    offer(app, update, notes);
    Ok(true)
}

/// A new version: the dialog offers it (*Restart now* or *Later*) as soon as the window is open,
/// the arrow in the title bar keeps offering it, and it downloads meanwhile.
fn offer(app: &AppHandle, update: Update, notes: String) {
    let version = update.version.clone();
    *app.state::<Updates>().found.lock().unwrap() = Some(Found { update: update.clone(), notes, bytes: None });
    frame::update_ready(app, &version);
    let shown = app.clone();
    tauri::async_runtime::spawn(async move {
        // At the start the window isn't there yet.
        for _ in 0..120 {
            if frame::shown() {
                open_dialog(&shown);
                break;
            }
            sleep(Duration::from_millis(500)).await;
        }
    });
    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        // How far it is shows only once *Restart now* waits for it (in the splash window).
        let mut shown = progress(&app, &version);
        let downloaded = update
            .download(
                move |chunk, total| {
                    if INSTALL_WHEN_DOWNLOADED.load(Ordering::SeqCst) {
                        shown(chunk, total);
                    }
                },
                || {},
            )
            .await;
        match downloaded {
            Ok(bytes) => {
                let ready = {
                    let updates = app.state::<Updates>();
                    let mut found = updates.found.lock().unwrap();
                    let Some(this) = found.as_mut().filter(|f| f.update.version == version) else { return };
                    this.bytes = Some(bytes);
                    // Under the lock: *Restart now* sets it under the same lock.
                    if INSTALL_WHEN_DOWNLOADED.swap(false, Ordering::SeqCst) { found.take() } else { None }
                };
                if let Some(ready) = ready {
                    let _ = tauri::async_runtime::spawn_blocking(move || install(&app, ready.update, ready.bytes.unwrap_or_default())).await;
                }
            }
            Err(e) => {
                // The next look tries again.
                eprintln!("update download: {e}");
                *app.state::<Updates>().found.lock().unwrap() = None;
                close_dialog(&app);
                frame::update_gone(&app);
                if INSTALL_WHEN_DOWNLOADED.swap(false, Ordering::SeqCst) {
                    set_splash(&app, "The update didn't download", None, &e.to_string());
                    sleep(Duration::from_secs(4)).await;
                    if let Some(window) = app.get_window("main") {
                        let _ = window.show();
                    }
                    close_splash(&app);
                }
            }
        }
    });
}

/// *Settings → App → Check for updates* ([quiet]: the web app looking by itself, after the
/// server was updated). True when there's a new version: the dialog offers it right away
/// (looking by itself, not again when it was put off with *Later*); false when this is the newest.
#[tauri::command]
pub async fn check_updates(app: AppHandle, quiet: Option<bool>) -> Result<bool, String> {
    if !on() {
        return Err("This build doesn't update itself.".into());
    }
    look(&app, !quiet.unwrap_or(false)).await
}

/// *Settings → App → Ask before updating*: [on] sets it; says whether it's on.
#[tauri::command]
pub fn ask_before_updating(app: AppHandle, on: Option<bool>) -> bool {
    let Some(on) = on else { return asks_first(&app) };
    app.state::<State>().settings.lock().unwrap().ask_before_updating = on;
    save(&app);
    on
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

/// What the dialog shows.
#[derive(serde::Serialize)]
pub struct UpdateInfo {
    version: String,
    current: String,
    notes: String,
}

#[tauri::command]
pub fn update_info(app: AppHandle) -> Option<UpdateInfo> {
    let found = app.state::<Updates>();
    let found = found.found.lock().unwrap();
    found.as_ref().map(|f| UpdateInfo { version: f.update.version.clone(), current: f.update.current_version.clone(), notes: f.notes.clone() })
}

const DIALOG: &str = "update";
const DIALOG_WIDTH: f64 = 380.0;
const DIALOG_HEIGHT: f64 = 280.0;

/// The update dialog (`update.html`: the release notes, *Restart now* and *Later*), in the
/// middle of the app's window. Only from async code: a window opened from a synchronous
/// command hangs on Windows (that's why the arrow did nothing before).
fn open_dialog(app: &AppHandle) {
    if let Some(dialog) = app.get_webview_window(DIALOG) {
        let _ = dialog.set_focus();
        return;
    }
    let Some(window) = app.get_window("main") else { return };
    if window.is_minimized().unwrap_or(false) {
        let _ = window.unminimize();
    }
    let scale = window.scale_factor().unwrap_or(1.0);
    let (Ok(at), Ok(size)) = (window.outer_position(), window.outer_size()) else { return };
    let left = at.x as f64 + (size.width as f64 - DIALOG_WIDTH * scale) / 2.0;
    let top = at.y as f64 + (size.height as f64 - DIALOG_HEIGHT * scale) / 2.0;
    let builder = WebviewWindowBuilder::new(app, DIALOG, WebviewUrl::App("update.html".into()))
        .title("Update YTMP")
        .inner_size(DIALOG_WIDTH, DIALOG_HEIGHT)
        .resizable(false)
        .minimizable(false)
        .maximizable(false)
        .decorations(false)
        .skip_taskbar(true)
        .focused(true)
        .visible(false);
    // On Windows over the app's window and with it (not over other apps).
    #[cfg(windows)]
    let builder = match window.hwnd() {
        Ok(hwnd) => builder.owner_raw(hwnd),
        Err(_) => builder,
    }
    .additional_browser_args(frame::BROWSER_ARGS);
    // On Linux the GTK window may only be touched on the main thread: on top instead.
    #[cfg(not(windows))]
    let builder = builder.always_on_top(true);
    match builder.build() {
        Ok(dialog) => {
            let _ = dialog.set_position(PhysicalPosition::new(left as i32, top as i32));
            let _ = dialog.show();
            let _ = dialog.set_focus();
        }
        Err(e) => eprintln!("update dialog: {e}"),
    }
}

/// The arrow in the title bar: the dialog again.
#[tauri::command]
pub async fn open_update(app: AppHandle) {
    open_dialog(&app);
}

fn close_dialog(app: &AppHandle) {
    if let Some(dialog) = app.get_webview_window(DIALOG) {
        let _ = dialog.close();
    }
}

/// *Later*: the dialog goes; the arrow in the title bar stays.
#[tauri::command]
pub async fn close_update(app: AppHandle) {
    close_dialog(&app);
}

/// *Restart now*: the page stops playing the room, the splash window shows the install (and the
/// rest of the download, if it isn't done), and the new version starts (playing on where this
/// one stopped: appQuit.ts).
#[tauri::command]
pub async fn install_update(app: AppHandle) {
    close_dialog(&app);
    let Some(version) = found_version(&app) else { return };
    frame::before_quit(&app, true).await;
    let ready = {
        let updates = app.state::<Updates>();
        let mut found = updates.found.lock().unwrap();
        if found.as_ref().is_some_and(|f| f.bytes.is_some()) {
            found.take()
        } else {
            INSTALL_WHEN_DOWNLOADED.store(true, Ordering::SeqCst);
            None
        }
    };
    let what = if ready.is_some() { format!("Installing YTMP {version}…") } else { format!("Downloading YTMP {version}") };
    open_splash(&app, &what);
    if let Some(window) = app.get_window("main") {
        let _ = window.hide();
    }
    if let Some(ready) = ready {
        tauri::async_runtime::spawn_blocking(move || install(&app, ready.update, ready.bytes.unwrap_or_default()));
    }
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
