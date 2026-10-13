use std::sync::{Arc, Mutex, MutexGuard, PoisonError};

use effectcraft_engine::{Session, config::DirConfig};
use serde_json::{Value, json};

uniffi::setup_scaffolding!();

#[derive(uniffi::Record)]
pub struct PreviewFrame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

#[derive(uniffi::Object)]
pub struct MobileSession {
    session: Mutex<Session>,
    session_file: std::path::PathBuf,
    startup_error: Mutex<Option<String>>,
}

#[uniffi::export]
impl MobileSession {
    #[uniffi::constructor]
    pub fn new(config_dir: String) -> Arc<Self> {
        let config_dir = std::path::PathBuf::from(config_dir);
        let mut session = effectcraft_host::session();
        session.models_dir = Some(config_dir.join("models"));
        session.config = Some(Arc::new(DirConfig::new(config_dir)));
        session.load_settings();
        let session_file = session.config.as_ref().and_then(|config| config.dir()).unwrap_or_default().join("mobile-session.ecproj");
        let mut startup_error = None;
        if session_file.is_file()
            && let Err(error) = session.execute("file.open", json!({"path": session_file.to_string_lossy()}))
        {
            startup_error = Some(error.to_string());
        }
        Arc::new(Self { session: Mutex::new(session), session_file, startup_error: Mutex::new(startup_error) })
    }

    /// Dispatch an existing engine command. The result is always a JSON envelope.
    pub fn execute(&self, command: String, params_json: String) -> String {
        let params = match serde_json::from_str::<Value>(&params_json) {
            Ok(params) => params,
            Err(error) => return json!({"ok": false, "error": error.to_string()}).to_string(),
        };

        let mut session = self.session();
        let result = session.execute(&command, params);
        match result {
            Ok(value) => json!({"ok": true, "value": value}).to_string(),
            Err(error) => json!({"ok": false, "error": error.to_string()}).to_string(),
        }
    }

    /// Return the canonical project JSON after commands have completed.
    pub fn project_json(&self) -> String {
        self.session().project.to_json()
    }

    /// Render the active composition on the CPU at a bounded preview resolution.
    pub fn render_preview(&self) -> Option<PreviewFrame> {
        let session = self.session();
        let comp_id = session.active_comp_id()?;
        let comp = session.project.comp(comp_id)?;
        let scale = (512.0 / comp.width.max(1) as f64).min(512.0 / comp.height.max(1) as f64).min(1.0);
        let mut renderer = effectcraft_engine::render::Renderer::new(
            &session.project,
            session.footage.as_ref(),
            effectcraft_engine::render::RenderOpts { scale, motion_blur: false, draft: true, ..Default::default() },
        );
        renderer.expr = session.expr.as_deref();
        renderer.cache = Some(&session.layer_cache);
        let frame = renderer.comp_frame(comp_id, session.time());
        if frame.is_empty() {
            return None;
        }
        Some(PreviewFrame { width: frame.width, height: frame.height, rgba: frame.to_rgba8_over([0.07, 0.08, 0.09]) })
    }

    /// Write the current project to private app storage for crash recovery.
    pub fn save_recovery(&self) -> String {
        let session = self.session();
        let Some(config) = session.config.as_deref() else {
            return json!({"ok": false, "error": "Session recovery storage is unavailable"}).to_string();
        };
        match config.files().write(&self.session_file, session.project.to_json().as_bytes()) {
            Ok(()) => json!({"ok": true}).to_string(),
            Err(error) => json!({"ok": false, "error": error.to_string()}).to_string(),
        }
    }

    /// Return a compact editor model intended for native project, composition, and layer panels.
    pub fn overview_json(&self) -> String {
        let session = self.session();
        let compositions: Vec<Value> = session
            .project
            .items
            .values()
            .filter_map(|item| {
                let comp = item.as_comp()?;
                Some(json!({
                    "id": item.id.0,
                    "name": item.name,
                    "width": comp.width,
                    "height": comp.height,
                    "duration": comp.duration.seconds(),
                    "currentTime": session.state.times.get(&item.id).copied().unwrap_or_default().seconds(),
                    "layers": comp.layers.iter().map(|layer| json!({
                        "id": layer.id.0,
                        "name": layer.name,
                        "selected": session.state.selected_layers.contains(&layer.id),
                    })).collect::<Vec<_>>(),
                }))
            })
            .collect();
        json!({"activeComposition": session.active_comp_id().map(|id| id.0), "compositions": compositions}).to_string()
    }

    /// List the engine's command IDs and parameter descriptions for the native command palette.
    pub fn commands_json(&self) -> String {
        let session = self.session();
        let commands: Vec<Value> = effectcraft_engine::command_specs()
            .into_iter()
            .filter(|spec| !spec.menu.is_empty())
            .map(|spec| {
                json!({
                    "id": spec.id,
                    "label": spec.label,
                    "menu": spec.menu,
                    "params": spec.params,
                    "enabled": session.is_enabled(spec.id),
                })
            })
            .collect();
        Value::Array(commands).to_string()
    }

    pub fn startup_error(&self) -> String {
        self.startup_error.lock().unwrap_or_else(PoisonError::into_inner).take().unwrap_or_default()
    }
}

impl MobileSession {
    fn session(&self) -> MutexGuard<'_, Session> {
        self.session.lock().unwrap_or_else(PoisonError::into_inner)
    }
}

#[cfg(test)]
mod tests {
    use super::MobileSession;
    use serde_json::Value;
    use std::sync::Arc;

    fn test_session() -> (Arc<MobileSession>, std::path::PathBuf) {
        let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos();
        let dir = std::env::temp_dir().join(format!("effectcraft-android-test-{stamp}"));
        std::fs::create_dir_all(&dir).unwrap();
        let session = MobileSession::new(dir.to_string_lossy().into_owned());
        (session, dir)
    }

    #[test]
    fn command_bridge_mutates_the_shared_project() {
        let (session, dir) = test_session();
        let result = session.execute("comp.new".into(), r#"{"name":"Main","width":1280,"height":720,"duration":5}"#.into());
        let result: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(result["ok"], true);

        let project: Value = serde_json::from_str(&session.project_json()).unwrap();
        assert!(project["items"].as_object().is_some_and(|items| !items.is_empty()));
        let preview = session.render_preview().unwrap();
        assert!(preview.width <= 512 && preview.height <= 512);
        drop(session);
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn invalid_command_parameters_return_an_error_envelope() {
        let (session, dir) = test_session();
        let result = session.execute("comp.new".into(), "{".into());
        let result: Value = serde_json::from_str(&result).unwrap();
        assert_eq!(result["ok"], false);
        assert!(result["error"].as_str().is_some());
        drop(session);
        std::fs::remove_dir_all(dir).unwrap();
    }
}
