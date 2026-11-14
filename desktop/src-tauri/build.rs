fn main() {
    // The app's own commands, so capabilities can allow them (the server's origin gets one at runtime).
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&["open_saved", "connect", "change_server", "media_update", "module_request", "pick_folder", "check_updates", "quit_ready", "window_action", "frame_state", "splash_state", "update_info", "open_update", "close_update", "install_update"]),
    ))
    .expect("tauri build");
}
