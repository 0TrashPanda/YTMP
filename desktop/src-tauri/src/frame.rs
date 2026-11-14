// The app's window (docs/implementation/apps/desktop-app.md, "Its own title bar"): no system frame, but
// its own title bar (desktop/setup/titlebar.html, with the update arrow) above the web app,
// each in a web view of its own. Closing it first lets the page stop playing the room
// (`before_quit`), so the room doesn't play on for nobody.

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Mutex;
use std::time::Duration;

use tauri::webview::{NewWindowResponse, PageLoadEvent};
use tauri::window::WindowBuilder;
use tauri::{AppHandle, LogicalPosition, LogicalSize, Manager, PhysicalPosition, PhysicalSize, WebviewBuilder, WebviewUrl, Window, WindowEvent};
use tauri_plugin_opener::OpenerExt;

use crate::updates;

/// The title bar's height (logical pixels).
pub const BAR: f64 = 32.0;

/// Every web view of the app gets the same arguments: WebView2 refuses a second set in the same
/// profile. WebView2's own media controls would show as "msedgewebview2.exe": the app shows them
/// instead (media.rs). The first three are Tauri's defaults, which this replaces. Sound may start
/// without a tap: the app opens playing where it left off.
#[cfg(windows)]
pub const BROWSER_ARGS: &str = "--disable-features=msWebOOUI,msPdfOOUI,msSmartScreenProtection,HardwareMediaKeyHandling --autoplay-policy=no-user-gesture-required";

/// The window has been shown (once the page loaded, or after a while anyway).
static SHOWN: AtomicBool = AtomicBool::new(false);
/// Closing: the page has been told, the app ends.
static QUITTING: AtomicBool = AtomicBool::new(false);
/// What the title bar last heard: 0 not yet, 1 normal, 2 maximized.
static MAXIMIZED: AtomicU8 = AtomicU8::new(0);

/// Waits for the page's answer when the app closes (`quit_ready`).
#[derive(Default)]
pub struct QuitWait(Mutex<Option<std::sync::mpsc::Sender<()>>>);

/// Opens the window, hidden until the page has loaded (the splash window shows meanwhile).
/// [bridge]: the script that gives the web app `window.YtmpDesktop`.
pub fn open(app: &AppHandle, bridge: &str) -> tauri::Result<Window> {
    let width = 1280.0;
    let height = 820.0;
    let window = WindowBuilder::new(app, "main")
        .title("YTMP")
        .inner_size(width, height)
        .min_inner_size(360.0, 560.0)
        .decorations(false)
        .visible(false)
        .build()?;

    let bar = WebviewBuilder::new("titlebar", WebviewUrl::App("titlebar.html".into()));
    #[cfg(windows)]
    let bar = bar.additional_browser_args(BROWSER_ARGS);
    window.add_child(bar, LogicalPosition::new(0.0, 0.0), LogicalSize::new(width, BAR))?;

    let opener = app.clone();
    let page = WebviewBuilder::new("app", WebviewUrl::App("index.html".into()))
        .initialization_script(bridge)
        // Links that open a new window (YouTube, GitHub, …) go to the browser.
        .on_new_window(move |url, _| {
            let _ = opener.opener().open_url(url.as_str(), None::<&str>);
            NewWindowResponse::Deny
        })
        .on_page_load(|webview, payload| {
            if payload.event() == PageLoadEvent::Finished {
                show(webview.app_handle());
            }
        });
    #[cfg(windows)]
    let page = page.additional_browser_args(BROWSER_ARGS);
    window.add_child(page, LogicalPosition::new(0.0, BAR), LogicalSize::new(width, height - BAR))?;
    layout(&window);

    let resized = window.clone();
    window.on_window_event(move |event| match event {
        WindowEvent::Resized(_) | WindowEvent::ScaleFactorChanged { .. } => layout(&resized),
        WindowEvent::CloseRequested { api, .. } => {
            api.prevent_close();
            if QUITTING.swap(true, Ordering::SeqCst) {
                return; // Already on its way out.
            }
            let app = resized.app_handle().clone();
            tauri::async_runtime::spawn(async move {
                before_quit(&app, false).await;
                app.exit(0);
            });
        }
        _ => {}
    });

    // A page that never finishes loading still gets its window.
    let later = app.clone();
    tauri::async_runtime::spawn(async move {
        updates::sleep(Duration::from_secs(20)).await;
        show(&later);
    });
    Ok(window)
}

/// The page has loaded: the window shows and the splash window goes.
fn show(app: &AppHandle) {
    if SHOWN.swap(true, Ordering::SeqCst) {
        return;
    }
    if let Some(window) = app.get_window("main") {
        let _ = window.show();
        let _ = window.set_focus();
    }
    updates::close_splash(app);
}

/// The title bar over the full width at the top, the page under it.
fn layout(window: &Window) {
    let Ok(size) = window.inner_size() else { return };
    let scale = window.scale_factor().unwrap_or(1.0);
    let bar = (BAR * scale).round() as u32;
    let app = window.app_handle();
    if let Some(title) = app.get_webview("titlebar") {
        let _ = title.set_position(PhysicalPosition::new(0, 0));
        let _ = title.set_size(PhysicalSize::new(size.width, bar));
        let maximized = window.is_maximized().unwrap_or(false);
        let state = if maximized { 2 } else { 1 };
        if MAXIMIZED.swap(state, Ordering::SeqCst) != state {
            let _ = title.eval(format!("window.maximized && window.maximized({maximized})"));
        }
    }
    if let Some(page) = app.get_webview("app") {
        let _ = page.set_position(PhysicalPosition::new(0, bar as i32));
        let _ = page.set_size(PhysicalSize::new(size.width, size.height.saturating_sub(bar)));
    }
}

/// A new version is downloaded: the arrow shows in the title bar.
pub fn update_ready(app: &AppHandle, version: &str) {
    if let Some(title) = app.get_webview("titlebar") {
        let version = serde_json::to_string(version).unwrap_or_default();
        let _ = title.eval(format!("window.updateReady && window.updateReady({version})"));
    }
}

/// The app is closing ([for_update]: to install a new version): the page stops playing the room
/// first (frontend/src/lib/appQuit.ts). A closed app sends no goodbye, and the room would keep
/// a device that played for a minute and more. Waits for the page, a moment at most.
pub async fn before_quit(app: &AppHandle, for_update: bool) {
    let Some(page) = app.get_webview("app") else { return };
    let (tx, rx) = std::sync::mpsc::channel();
    *app.state::<QuitWait>().0.lock().unwrap() = Some(tx);
    let script = format!("window.__ytmpQuit ? window.__ytmpQuit({for_update}) : window.__TAURI_INTERNALS__.invoke('quit_ready')");
    if page.eval(script).is_err() {
        return;
    }
    let _ = tauri::async_runtime::spawn_blocking(move || rx.recv_timeout(Duration::from_millis(2500))).await;
}

/// The page is done before the app closes (`before_quit`).
#[tauri::command]
pub fn quit_ready(app: AppHandle) {
    if let Some(tx) = app.state::<QuitWait>().0.lock().unwrap().take() {
        let _ = tx.send(());
    }
}

/// The title bar's buttons.
#[tauri::command]
pub fn window_action(app: AppHandle, action: String) {
    let Some(window) = app.get_window("main") else { return };
    let _ = match action.as_str() {
        "minimize" => window.minimize(),
        "maximize" if window.is_maximized().unwrap_or(false) => window.unmaximize(),
        "maximize" => window.maximize(),
        "close" => window.close(),
        _ => Ok(()),
    };
}

#[derive(serde::Serialize)]
pub struct FrameState {
    /// The version that's ready to install, if any.
    update: Option<String>,
    maximized: bool,
}

/// The title bar just loaded: what it should show.
#[tauri::command]
pub fn frame_state(app: AppHandle) -> FrameState {
    let maximized = app.get_window("main").and_then(|w| w.is_maximized().ok()).unwrap_or(false);
    FrameState { update: updates::ready_version(&app), maximized }
}
