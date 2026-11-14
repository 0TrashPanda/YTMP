//! Windows' media controls (media keys, the volume flyout, the lock screen) for the page's
//! player. WebView2 has its own, but Windows names those after WebView2 ("msedgewebview2.exe"),
//! not after YTMP. So WebView2's are off (main.rs), the initialization script passes the page's
//! Media Session to [update], and the app shows it as itself; a button pressed there goes back
//! to the page's Media Session handlers (`window.__ytmpMediaAction`). Linux keeps WebKitGTK's
//! own MPRIS, which already shows the app's name.

use std::sync::Mutex;
use std::time::Duration;

use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};
use tauri::{AppHandle, Manager, Window};

pub struct Media(Mutex<Option<MediaControls>>);

// Windows' media controls are agile COM objects: usable from any thread.
unsafe impl Send for Media {}
unsafe impl Sync for Media {}

/// What the page's Media Session says now (the initialization script sends it on every change).
#[derive(serde::Deserialize)]
pub struct State {
    title: Option<String>,
    artist: Option<String>,
    album: Option<String>,
    artwork: Option<String>,
    /// "playing", "paused" or "none".
    state: String,
    /// Seconds, from setPositionState.
    duration: Option<f64>,
    position: Option<f64>,
}

/// Sets up the controls for [window]; without them (an error) the media keys do nothing.
pub fn setup(app: &AppHandle, window: &Window) {
    let controls = window.hwnd().map_err(|e| e.to_string()).and_then(|hwnd| {
        let config = PlatformConfig { display_name: "YTMP", dbus_name: "ytmp", hwnd: Some(hwnd.0 as *mut std::ffi::c_void) };
        MediaControls::new(config).map_err(|e| e.to_string())
    });
    let mut controls = match controls {
        Ok(controls) => controls,
        Err(e) => {
            eprintln!("media controls: {e}");
            app.manage(Media(Mutex::new(None)));
            return;
        }
    };
    let handle = app.clone();
    let attached = controls.attach(move |event| {
        let (action, seconds) = match event {
            MediaControlEvent::Play => ("play", None),
            MediaControlEvent::Pause => ("pause", None),
            MediaControlEvent::Toggle => ("toggle", None),
            MediaControlEvent::Next => ("nexttrack", None),
            MediaControlEvent::Previous => ("previoustrack", None),
            MediaControlEvent::Stop => ("stop", None),
            MediaControlEvent::Seek(SeekDirection::Forward) => ("seekforward", None),
            MediaControlEvent::Seek(SeekDirection::Backward) => ("seekbackward", None),
            MediaControlEvent::SeekBy(SeekDirection::Forward, by) => ("seekforward", Some(by.as_secs_f64())),
            MediaControlEvent::SeekBy(SeekDirection::Backward, by) => ("seekbackward", Some(by.as_secs_f64())),
            MediaControlEvent::SetPosition(MediaPosition(at)) => ("seekto", Some(at.as_secs_f64())),
            _ => return,
        };
        if let Some(page) = handle.get_webview("app") {
            let seconds = seconds.map_or("undefined".to_string(), |s| s.to_string());
            let _ = page.eval(format!("window.__ytmpMediaAction && window.__ytmpMediaAction({action:?}, {seconds})"));
        }
    });
    if let Err(e) = attached {
        eprintln!("media controls: {e}");
    }
    let _ = controls.set_playback(MediaPlayback::Stopped);
    app.manage(Media(Mutex::new(Some(controls))));
}

/// The page's Media Session changed (main.rs, `media_update`).
pub fn update(app: &AppHandle, update: State) {
    let Some(media) = app.try_state::<Media>() else { return };
    let mut controls = media.0.lock().unwrap();
    let Some(controls) = controls.as_mut() else { return };
    let duration = update.duration.filter(|d| d.is_finite() && *d > 0.0).map(Duration::from_secs_f64);
    let _ = controls.set_metadata(MediaMetadata {
        // Empty, not left out: left out keeps the last song's.
        title: Some(update.title.as_deref().unwrap_or("")),
        artist: Some(update.artist.as_deref().unwrap_or("")),
        album: Some(update.album.as_deref().unwrap_or("")),
        cover_url: update.artwork.as_deref().filter(|url| url.starts_with("http")),
        duration,
    });
    let progress = update.position.filter(|p| p.is_finite() && *p >= 0.0).map(|p| MediaPosition(Duration::from_secs_f64(p)));
    let playback = match update.state.as_str() {
        "playing" => MediaPlayback::Playing { progress },
        "paused" => MediaPlayback::Paused { progress },
        _ => MediaPlayback::Stopped,
    };
    let _ = controls.set_playback(playback);
}

/// The initialization script's part: passes the page's Media Session to [media_update] and the
/// buttons back to its handlers. The page uses the Media Session API as in any browser.
pub const SCRIPT: &str = r#"
  (() => {
    const session = navigator.mediaSession;
    if (!session) return;
    const proto = Object.getPrototypeOf(session);
    const handlers = {};
    let position = null;
    let pending = false;
    let last = '';
    const send = () => {
      if (pending) return;
      pending = true;
      queueMicrotask(() => {
        pending = false;
        const meta = session.metadata;
        const art = meta && meta.artwork && meta.artwork.length ? meta.artwork[meta.artwork.length - 1].src : null;
        const update = {
          title: meta ? meta.title : null,
          artist: meta ? meta.artist : null,
          album: meta ? meta.album : null,
          artwork: art ? new URL(art, location.href).href : null,
          state: session.playbackState,
          duration: position ? position.duration : null,
          position: position ? position.position : null,
        };
        // The page sets the same state again and again: only changes go to the app.
        const text = JSON.stringify(update);
        if (text === last) return;
        last = text;
        invoke('media_update', { update }).catch(() => {});
      });
    };
    for (const name of ['metadata', 'playbackState']) {
      const own = Object.getOwnPropertyDescriptor(proto, name);
      Object.defineProperty(session, name, {
        configurable: true,
        get: () => own.get.call(session),
        set: (value) => { own.set.call(session, value); send(); },
      });
    }
    const setActionHandler = proto.setActionHandler;
    session.setActionHandler = (action, handler) => {
      handlers[action] = handler;
      try { setActionHandler.call(session, action, handler); } catch (e) { /* not every action exists */ }
    };
    const setPositionState = proto.setPositionState;
    session.setPositionState = (state) => {
      position = state && state.duration ? { duration: state.duration, position: state.position || 0 } : null;
      if (setPositionState) setPositionState.call(session, state);
      send();
    };
    window.__ytmpMediaAction = (action, seconds) => {
      if (action === 'toggle') action = session.playbackState === 'playing' ? 'pause' : 'play';
      const handler = handlers[action];
      if (handler) handler({ action, seekTime: seconds, seekOffset: seconds });
    };
  })();
"#;
