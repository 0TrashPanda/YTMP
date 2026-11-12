fn main() {
    // The app's own commands, so capabilities can allow them (the server's origin gets one at runtime).
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&["open_saved", "connect", "change_server", "media_update", "module_request"]),
    ))
    .expect("tauri build");
}
