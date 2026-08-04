//! Model Profiles & Presets for Warp AI Mobile (Issue #15).

use serde::{Deserialize, Serialize};

/// Provider type for AI Model Profiles.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ProviderKind {
    Anthropic,
    OpenAi,
    CustomOpenAi,
}

impl ProviderKind {
    pub fn as_str(&self) -> &'static str {
        match self {
            ProviderKind::Anthropic => "anthropic",
            ProviderKind::OpenAi => "openai",
            ProviderKind::CustomOpenAi => "custom_openai",
        }
    }
}

/// Configuration model representing an AI model profile.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ModelProfile {
    pub id: String,
    pub name: String,
    pub provider: ProviderKind,
    pub model_name: String,
    pub endpoint_url: Option<String>,
    pub temperature: f32,
    pub max_tokens: u32,
    pub top_p: Option<f32>,
    pub context_window: u32,
    pub supports_tools: bool,
    pub supports_streaming: bool,
    pub is_builtin: bool,
}

impl ModelProfile {
    pub fn validate(&self) -> Result<(), String> {
        if self.id.trim().is_empty() {
            return Err("Profile ID cannot be empty".into());
        }
        if self.name.trim().is_empty() {
            return Err("Profile name cannot be empty".into());
        }
        if self.model_name.trim().is_empty() {
            return Err("Model name cannot be empty".into());
        }
        if self.max_tokens == 0 {
            return Err("max_tokens must be greater than 0".into());
        }
        if self.temperature < 0.0 || self.temperature > 1.0 {
            return Err("temperature must be between 0.0 and 1.0".into());
        }
        if let Some(tp) = self.top_p {
            if tp < 0.0 || tp > 1.0 {
                return Err("top_p must be between 0.0 and 1.0".into());
            }
        }
        if self.provider == ProviderKind::CustomOpenAi {
            if let Some(ref url) = self.endpoint_url {
                if url.trim().is_empty() {
                    return Err("Custom endpoint URL cannot be empty".into());
                }
            } else {
                return Err("Custom OpenAI provider requires an endpoint URL".into());
            }
        }
        Ok(())
    }

    /// Presets (built-in profiles).
    pub fn builtin_profiles() -> Vec<ModelProfile> {
        vec![
            ModelProfile {
                id: "claude-3-5-sonnet".to_string(),
                name: "Claude 3.5 Sonnet".to_string(),
                provider: ProviderKind::Anthropic,
                model_name: "claude-3-5-sonnet-20241022".to_string(),
                endpoint_url: None,
                temperature: 0.7,
                max_tokens: 8192,
                top_p: Some(0.99),
                context_window: 200000,
                supports_tools: true,
                supports_streaming: true,
                is_builtin: true,
            },
            ModelProfile {
                id: "claude-3-5-haiku".to_string(),
                name: "Claude 3.5 Haiku".to_string(),
                provider: ProviderKind::Anthropic,
                model_name: "claude-3-5-haiku-20241022".to_string(),
                endpoint_url: None,
                temperature: 0.5,
                max_tokens: 4096,
                top_p: Some(0.99),
                context_window: 200000,
                supports_tools: true,
                supports_streaming: true,
                is_builtin: true,
            },
            ModelProfile {
                id: "gpt-4o".to_string(),
                name: "GPT-4o".to_string(),
                provider: ProviderKind::OpenAi,
                model_name: "gpt-4o".to_string(),
                endpoint_url: None,
                temperature: 0.7,
                max_tokens: 4096,
                top_p: Some(1.0),
                context_window: 128000,
                supports_tools: true,
                supports_streaming: true,
                is_builtin: true,
            },
            ModelProfile {
                id: "gpt-4o-mini".to_string(),
                name: "GPT-4o Mini".to_string(),
                provider: ProviderKind::OpenAi,
                model_name: "gpt-4o-mini".to_string(),
                endpoint_url: None,
                temperature: 0.5,
                max_tokens: 4096,
                top_p: Some(1.0),
                context_window: 128000,
                supports_tools: true,
                supports_streaming: true,
                is_builtin: true,
            },
            ModelProfile {
                id: "ollama-local".to_string(),
                name: "Ollama Local (llama3)".to_string(),
                provider: ProviderKind::CustomOpenAi,
                model_name: "llama3".to_string(),
                endpoint_url: Some("http://10.0.2.2:11434/v1/chat/completions".to_string()),
                temperature: 0.7,
                max_tokens: 4096,
                top_p: Some(0.9),
                context_window: 8192,
                supports_tools: false,
                supports_streaming: true,
                is_builtin: true,
            },
        ]
    }

    pub fn default_profile() -> ModelProfile {
        Self::builtin_profiles()
            .into_iter()
            .find(|p| p.id == "claude-3-5-sonnet")
            .expect("Default profile must exist")
    }

    pub fn from_json(json: &str) -> Result<Self, String> {
        let profile: ModelProfile = serde_json::from_str(json)
            .map_err(|e| format!("Failed to parse ModelProfile JSON: {}", e))?;
        profile.validate()?;
        Ok(profile)
    }

    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_default()
    }
}

/// Registry to manage profiles and active profile selection.
#[derive(Debug, Clone)]
pub struct ModelProfileRegistry {
    profiles: Vec<ModelProfile>,
    active_profile_id: String,
}

impl ModelProfileRegistry {
    pub fn new() -> Self {
        let profiles = ModelProfile::builtin_profiles();
        Self {
            profiles,
            active_profile_id: "claude-3-5-sonnet".to_string(),
        }
    }

    pub fn active_profile(&self) -> ModelProfile {
        self.profiles
            .iter()
            .find(|p| p.id == self.active_profile_id)
            .cloned()
            .unwrap_or_else(ModelProfile::default_profile)
    }

    pub fn set_active_profile_id(&mut self, id: &str) -> bool {
        if self.profiles.iter().any(|p| p.id == id) {
            self.active_profile_id = id.to_string();
            true
        } else {
            false
        }
    }

    pub fn register_custom_profile(&mut self, profile: ModelProfile) -> Result<(), String> {
        profile.validate()?;
        if let Some(idx) = self.profiles.iter().position(|p| p.id == profile.id) {
            self.profiles[idx] = profile;
        } else {
            self.profiles.push(profile);
        }
        Ok(())
    }

    pub fn list_profiles(&self) -> &[ModelProfile] {
        &self.profiles
    }
}

impl Default for ModelProfileRegistry {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_builtin_profiles_count_and_validity() {
        let presets = ModelProfile::builtin_profiles();
        assert_eq!(presets.len(), 5);
        for p in &presets {
            assert!(p.validate().is_ok(), "Profile {} failed validation", p.id);
        }
    }

    #[test]
    fn test_profile_json_roundtrip() {
        let preset = ModelProfile::default_profile();
        let json = preset.to_json();
        let restored = ModelProfile::from_json(&json).expect("Roundtrip parse failed");
        assert_eq!(preset, restored);
    }

    #[test]
    fn test_validation_rejects_invalid_params() {
        let mut p = ModelProfile::default_profile();
        p.max_tokens = 0;
        assert!(p.validate().is_err());

        p.max_tokens = 4096;
        p.temperature = 1.5;
        assert!(p.validate().is_err());

        p.temperature = -0.1;
        assert!(p.validate().is_err());

        p.temperature = 0.7;
        p.id = "  ".to_string();
        assert!(p.validate().is_err());
    }

    #[test]
    fn test_registry_active_selection() {
        let mut reg = ModelProfileRegistry::new();
        assert_eq!(reg.active_profile().id, "claude-3-5-sonnet");

        assert!(reg.set_active_profile_id("claude-3-5-haiku"));
        assert_eq!(reg.active_profile().id, "claude-3-5-haiku");

        assert!(!reg.set_active_profile_id("non-existent-profile"));
        assert_eq!(reg.active_profile().id, "claude-3-5-haiku");
    }

    #[test]
    fn test_custom_profile_registration() {
        let mut reg = ModelProfileRegistry::new();
        let custom = ModelProfile {
            id: "custom-ollama-mistral".to_string(),
            name: "Ollama Mistral".to_string(),
            provider: ProviderKind::CustomOpenAi,
            model_name: "mistral".to_string(),
            endpoint_url: Some("http://192.168.1.100:11434/v1/chat/completions".to_string()),
            temperature: 0.7,
            max_tokens: 2048,
            top_p: Some(0.9),
            context_window: 8192,
            supports_tools: false,
            supports_streaming: true,
            is_builtin: false,
        };

        assert!(reg.register_custom_profile(custom.clone()).is_ok());
        assert!(reg.set_active_profile_id("custom-ollama-mistral"));
        assert_eq!(reg.active_profile().name, "Ollama Mistral");
    }
}
