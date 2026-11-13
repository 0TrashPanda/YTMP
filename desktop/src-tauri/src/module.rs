//! The YouTube Music module (ytm-module/) on this computer: started with the app on a free
//! port of 127.0.0.1 with a new key, started again when it stops, stopped with the app. The
//! page reaches it only through [request] (WebKitGTK won't let an https page fetch
//! http://127.0.0.1), so it never sees the port or the key.

use std::fs::File;
use std::io::Read;
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Manager};

pub struct Module {
    port: u16,
    key: String,
    ready: AtomicBool,
    stopping: AtomicBool,
    child: Mutex<Option<Child>>,
    #[cfg(windows)]
    job: Option<job::Job>,
}

#[derive(serde::Serialize)]
pub struct Answer {
    status: u16,
    body: String,
}

impl Module {
    pub fn new() -> Self {
        let port = TcpListener::bind("127.0.0.1:0").and_then(|l| l.local_addr()).map(|a| a.port()).unwrap_or(8401);
        let mut bytes = [0u8; 24];
        getrandom::fill(&mut bytes).expect("random key");
        let key = bytes.iter().map(|b| format!("{b:02x}")).collect();
        Module {
            port,
            key,
            ready: AtomicBool::new(false),
            stopping: AtomicBool::new(false),
            child: Mutex::new(None),
            #[cfg(windows)]
            job: job::Job::new(),
        }
    }

    /// Starts it, and keeps it running (in a thread of its own) until [stop].
    pub fn start(app: &AppHandle) {
        let app = app.clone();
        std::thread::spawn(move || {
            let module = app.state::<Module>();
            let log_dir = app.path().app_log_dir().unwrap_or_else(|_| std::env::temp_dir());
            let _ = std::fs::create_dir_all(&log_dir);
            let log = log_dir.join("module.log");
            let _ = File::create(&log); // a fresh log per start of the app
            let mut wait = Duration::from_secs(1);
            while !module.stopping.load(Ordering::SeqCst) {
                let started = Instant::now();
                match module.spawn(&app, &log) {
                    Ok(child) => {
                        #[cfg(windows)]
                        if let Some(job) = &module.job {
                            if !job.add(&child) {
                                eprintln!("YTM module: can't tie it to the app; it may outlive a crash");
                            }
                        }
                        *module.child.lock().unwrap() = Some(child);
                    }
                    Err(e) => eprintln!("YTM module: can't start it: {e}"),
                }
                module.wait_ready();
                // Watch it; when it stops on its own, start it again (a little later each time).
                loop {
                    std::thread::sleep(Duration::from_millis(500));
                    let mut child = module.child.lock().unwrap();
                    match child.as_mut().map(|c| c.try_wait()) {
                        Some(Ok(None)) => continue,
                        _ => {
                            *child = None;
                            break;
                        }
                    }
                }
                module.ready.store(false, Ordering::SeqCst);
                if module.stopping.load(Ordering::SeqCst) {
                    break;
                }
                wait = if started.elapsed() > Duration::from_secs(60) { Duration::from_secs(1) } else { (wait * 2).min(Duration::from_secs(30)) };
                eprintln!("YTM module stopped, starting it again in {} s", wait.as_secs());
                std::thread::sleep(wait);
            }
        });
    }

    pub fn stop(&self) {
        self.stopping.store(true, Ordering::SeqCst);
        if let Some(mut child) = self.child.lock().unwrap().take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    fn spawn(&self, app: &AppHandle, log: &PathBuf) -> std::io::Result<Child> {
        let (python, deno) = runtime(app);
        let data = app.path().app_data_dir().unwrap_or_else(|_| std::env::temp_dir());
        let cache = app.path().app_cache_dir().unwrap_or_else(|_| std::env::temp_dir());
        let mut command = Command::new(python);
        command
            .args(["-m", "ytmp_ytm.http"])
            .env("YTMP_MODULE_KEY", &self.key)
            .env("YTMP_YTM_HOST", "127.0.0.1")
            .env("YTMP_YTM_PORT", self.port.to_string())
            .env("YTMP_YTM_UPDATE_DIR", data.join("ytm-updates"))
            // This computer's music and song cache (ytmp_ytm/desktop.py), played through ytmpfiles://.
            .env("YTMP_YTM_DESKTOP_DIR", data.join("on-this-computer"))
            .env("YTMP_YTM_DEVICE", crate::device(app).0)
            .env("YTMP_YTM_FILES_BASE", crate::FILES_BASE)
            // The AppImage's AppRun sets these for its own folder; the bundled Python finds its own.
            .env_remove("PYTHONHOME")
            .env_remove("PYTHONPATH")
            .env("PYTHONNOUSERSITE", "1")
            .env("PYTHONPYCACHEPREFIX", cache.join("pycache"))
            .stdin(Stdio::null())
            .stdout(File::options().append(true).open(log)?)
            .stderr(File::options().append(true).open(log)?);
        if let Some(deno) = deno {
            let path = std::env::var_os("PATH").unwrap_or_default();
            let paths = std::iter::once(deno).chain(std::env::split_paths(&path));
            command.env("PATH", std::env::join_paths(paths).unwrap_or(path)).env("YTMP_YTM_JS_RUNTIME", "deno");
        } else if let Ok(runtime) = std::env::var("YTMP_YTM_JS_RUNTIME") {
            command.env("YTMP_YTM_JS_RUNTIME", runtime);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        #[cfg(target_os = "linux")]
        unsafe {
            use std::os::unix::process::CommandExt;
            // Stops with the app, also when the app is killed.
            command.pre_exec(|| {
                libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM);
                Ok(())
            });
        }
        command.spawn()
    }

    fn wait_ready(&self) {
        let until = Instant::now() + Duration::from_secs(60);
        while Instant::now() < until && !self.stopping.load(Ordering::SeqCst) {
            if self.child.lock().unwrap().is_none() {
                return;
            }
            if ureq::get(&format!("http://127.0.0.1:{}/health", self.port)).timeout(Duration::from_secs(2)).call().is_ok() {
                self.ready.store(true, Ordering::SeqCst);
                return;
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    }

    fn wait(&self) -> Result<(), String> {
        let until = Instant::now() + Duration::from_secs(30);
        while !self.ready.load(Ordering::SeqCst) {
            if Instant::now() > until {
                return Err("the YouTube Music module didn't start".into());
            }
            std::thread::sleep(Duration::from_millis(200));
        }
        Ok(())
    }

    fn request(&self, method: &str, path: &str) -> ureq::Request {
        let url = format!("http://127.0.0.1:{}/{}", self.port, path.trim_start_matches('/'));
        ureq::request(method, &url).set("Authorization", &format!("Bearer {}", self.key)).timeout(Duration::from_secs(90))
    }

    /// [method] to the module, with a JSON [body] if any.
    fn call(&self, method: &str, path: &str, body: Option<&str>) -> Result<Answer, String> {
        self.wait()?;
        let request = self.request(method, path);
        let answer = match body {
            Some(body) => request.set("Content-Type", "application/json").send_string(body),
            None => request.call(),
        };
        match answer {
            Ok(r) => Ok(Answer { status: r.status(), body: r.into_string().map_err(|e| e.to_string())? }),
            Err(ureq::Error::Status(status, r)) => Ok(Answer { status, body: r.into_string().unwrap_or_default() }),
            Err(e) => Err(e.to_string()),
        }
    }

    /// A piece of a song or a cover for the window (`ytmpfiles://`, [path] as the page wrote
    /// it, `audio/<song>`): status, headers, bytes. The module answers at most a few MB at a time.
    pub fn file(&self, path: &str, range: Option<&str>) -> (u16, Vec<(String, String)>, Vec<u8>) {
        if let Err(e) = self.wait() {
            return (503, vec![], e.into_bytes());
        }
        let mut request = self.request("GET", &format!("desktop/files/{}", path.trim_start_matches('/')));
        if let Some(range) = range {
            request = request.set("Range", range);
        }
        let response = match request.call() {
            Ok(r) | Err(ureq::Error::Status(_, r)) => r,
            Err(e) => return (502, vec![], e.to_string().into_bytes()),
        };
        let status = response.status();
        let headers = ["Content-Type", "Content-Range", "Accept-Ranges", "Cache-Control"]
            .iter()
            .filter_map(|name| response.header(name).map(|v| (name.to_string(), v.to_string())))
            .collect();
        let mut bytes = Vec::new();
        let _ = response.into_reader().take(64 * 1024 * 1024).read_to_end(&mut bytes);
        (status, headers, bytes)
    }
}

/// The bundled Python and Deno (desktop/scripts/bundle_module.py), or for development the
/// Python in YTMP_DESKTOP_PYTHON (ytm-module/.venv/bin/python) and no Deno.
fn runtime(app: &AppHandle) -> (PathBuf, Option<PathBuf>) {
    let resources = app.path().resource_dir().map(|d| d.join("resources")).unwrap_or_default();
    let python = resources.join(if cfg!(windows) { "python/python.exe" } else { "python/bin/python3.13" });
    if python.exists() {
        let deno = resources.join("deno");
        return (python, deno.exists().then_some(deno));
    }
    let dev = std::env::var_os("YTMP_DESKTOP_PYTHON").map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(concat!(env!("CARGO_MANIFEST_DIR"), "/../../ytm-module/.venv/bin/python")));
    (dev, None)
}

/// Windows has no PR_SET_PDEATHSIG: the module (and the Deno it starts) goes in a job that
/// Windows ends when its last handle closes. The app never closes it, so that happens when the
/// app ends, also when it crashes or is ended in Task Manager.
#[cfg(windows)]
mod job {
    use std::os::windows::io::AsRawHandle;
    use std::process::Child;

    use windows_sys::Win32::Foundation::HANDLE;
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation, SetInformationJobObject,
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    pub struct Job(HANDLE);

    // A job handle can be used from any thread.
    unsafe impl Send for Job {}
    unsafe impl Sync for Job {}

    impl Job {
        pub fn new() -> Option<Job> {
            unsafe {
                let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
                if job.is_null() {
                    return None;
                }
                let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
                info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
                let size = std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32;
                if SetInformationJobObject(job, JobObjectExtendedLimitInformation, &info as *const _ as *const _, size) == 0 {
                    return None;
                }
                Some(Job(job))
            }
        }

        /// Puts [child] in the job (right after it started: what it starts itself joins too).
        pub fn add(&self, child: &Child) -> bool {
            unsafe { AssignProcessToJobObject(self.0, child.as_raw_handle() as HANDLE) != 0 }
        }
    }
}

/// A request to the module for the page: `path` like `search?q=…`, GET unless [method] says
/// otherwise, with a JSON [body]; answers its status and body.
#[tauri::command]
pub async fn module_request(app: AppHandle, path: String, method: Option<String>, body: Option<String>) -> Result<Answer, String> {
    let method = method.filter(|m| ["GET", "POST", "PUT", "DELETE"].contains(&m.as_str())).unwrap_or_else(|| "GET".into());
    tauri::async_runtime::spawn_blocking(move || app.state::<Module>().call(&method, &path, body.as_deref()))
        .await
        .map_err(|e| e.to_string())?
}
