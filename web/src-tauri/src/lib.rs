use serde::Serialize;

#[derive(Serialize)]
struct AppInfo {
    name: &'static str,
    version: &'static str,
    api_base: String,
    desktop: bool,
}

/// 桌面端运行时信息，前端可通过 invoke('app_info') 读取。
#[tauri::command]
fn app_info() -> AppInfo {
    AppInfo {
        name: "PicHost",
        version: env!("CARGO_PKG_VERSION"),
        api_base: std::env::var("PICHOST_API_BASE")
            .unwrap_or_else(|_| "http://127.0.0.1:8080".to_string()),
        desktop: true,
    }
}

/// 读取命令行传入的图片路径，便于后续实现“拖拽到程序图标即上传”。
#[tauri::command]
fn startup_files() -> Vec<String> {
    std::env::args()
        .skip(1)
        .filter(|arg| !arg.starts_with('-'))
        .collect()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![app_info, startup_files])
        .run(tauri::generate_context!())
        .expect("启动 PicHost 桌面端失败");
}
