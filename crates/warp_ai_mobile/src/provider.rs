//! Multi-Provider Abstraction (Anthropic & OpenAI-compatible formats).

use crate::client::{append_utf8_bytes_safely, MessagesError, SseChunkEvent};
use crate::profile::{ModelProfile, ProviderKind};
use futures_util::StreamExt;
use serde_json::Value;
use std::time::Duration;
use tokio_util::sync::CancellationToken;

/// Provider dispatcher client capable of querying Anthropic or OpenAI-compatible backends.
pub struct ProviderDispatcher {
    connect_timeout: Duration,
    request_timeout: Duration,
}

impl ProviderDispatcher {
    pub fn new() -> Self {
        Self {
            connect_timeout: Duration::from_secs(8),
            request_timeout: Duration::from_secs(30),
        }
    }

    /// Default Anthropic endpoint URL.
    pub const ANTHROPIC_ENDPOINT: &'static str = "https://api.anthropic.com/v1/messages";
    /// Default OpenAI endpoint URL.
    pub const OPENAI_ENDPOINT: &'static str = "https://api.openai.com/v1/chat/completions";

    /// Helper to resolve target endpoint for a given profile.
    pub fn endpoint_for_profile(profile: &ModelProfile) -> String {
        if let Some(ref custom_url) = profile.endpoint_url {
            if !custom_url.trim().is_empty() {
                return custom_url.trim().to_string();
            }
        }
        match profile.provider {
            ProviderKind::Anthropic => Self::ANTHROPIC_ENDPOINT.to_string(),
            ProviderKind::OpenAi => Self::OPENAI_ENDPOINT.to_string(),
            ProviderKind::CustomOpenAi => {
                profile.endpoint_url.clone().unwrap_or_else(|| Self::OPENAI_ENDPOINT.to_string())
            }
        }
    }

    /// Executes a multi-turn streaming completion request dispatched based on profile provider.
    pub async fn stream_multi_turn<F>(
        &self,
        profile: &ModelProfile,
        api_key: &str,
        system_prompt: &str,
        messages: &[Value],
        cancel: CancellationToken,
        on_event: F,
    ) -> Result<String, MessagesError>
    where
        F: FnMut(SseChunkEvent),
    {
        match profile.provider {
            ProviderKind::Anthropic => {
                self.stream_anthropic(profile, api_key, system_prompt, messages, cancel, on_event)
                    .await
            }
            ProviderKind::OpenAi | ProviderKind::CustomOpenAi => {
                self.stream_openai(profile, api_key, system_prompt, messages, cancel, on_event)
                    .await
            }
        }
    }

    async fn stream_anthropic<F>(
        &self,
        profile: &ModelProfile,
        api_key: &str,
        system_prompt: &str,
        messages: &[Value],
        cancel: CancellationToken,
        mut on_event: F,
    ) -> Result<String, MessagesError>
    where
        F: FnMut(SseChunkEvent),
    {
        let endpoint = Self::endpoint_for_profile(profile);
        let mut body_map = serde_json::json!({
            "model": profile.model_name,
            "max_tokens": profile.max_tokens,
            "temperature": profile.temperature,
            "stream": true,
            "messages": messages
        });
        if !system_prompt.is_empty() {
            body_map["system"] = Value::String(system_prompt.to_string());
        }

        let client = reqwest::Client::builder()
            .connect_timeout(self.connect_timeout)
            .timeout(self.request_timeout)
            .build()
            .map_err(|e| MessagesError::Network(format!("client build: {}", e)))?;

        let req = client
            .post(&endpoint)
            .header("Content-Type", "application/json")
            .header("Anthropic-Version", "2023-06-01")
            .header("X-Api-Key", api_key)
            .json(&body_map);

        let response = tokio::select! {
            _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
            resp = req.send() => resp.map_err(|e| MessagesError::Network(format!("send: {}", e)))?,
        };

        let status = response.status();
        if !status.is_success() {
            let text = tokio::select! {
                _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
                t = response.text() => t.map_err(|e| MessagesError::Network(format!("error body: {}", e)))?,
            };
            return Err(MessagesError::HttpStatus(status.as_u16(), text));
        }

        let mut stream = response.bytes_stream();
        let mut raw_bytes_buffer = Vec::new();
        let mut buffer = String::new();
        let mut accumulated_text = String::new();

        while let Some(item) = tokio::select! {
            _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
            next = stream.next() => next,
        } {
            let bytes = item.map_err(|e| MessagesError::Network(format!("stream read: {}", e)))?;
            append_utf8_bytes_safely(&mut raw_bytes_buffer, &mut buffer, &bytes);

            while let Some(end_idx) = buffer.find("\n\n") {
                let event_block = buffer[..end_idx].to_string();
                buffer.drain(..end_idx + 2);
                let events = crate::client::extract_sse_events(&event_block);
                for ev in events {
                    if let SseChunkEvent::TextDelta { ref text } = ev {
                        accumulated_text.push_str(text);
                    }
                    on_event(ev);
                }
            }
        }

        Ok(accumulated_text)
    }

    async fn stream_openai<F>(
        &self,
        profile: &ModelProfile,
        api_key: &str,
        system_prompt: &str,
        messages: &[Value],
        cancel: CancellationToken,
        mut on_event: F,
    ) -> Result<String, MessagesError>
    where
        F: FnMut(SseChunkEvent),
    {
        let endpoint = Self::endpoint_for_profile(profile);
        let mut openai_messages = Vec::new();
        if !system_prompt.is_empty() {
            openai_messages.push(serde_json::json!({
                "role": "system",
                "content": system_prompt
            }));
        }
        for msg in messages {
            openai_messages.push(msg.clone());
        }

        let body_map = serde_json::json!({
            "model": profile.model_name,
            "max_tokens": profile.max_tokens,
            "temperature": profile.temperature,
            "stream": true,
            "messages": openai_messages
        });

        let client = reqwest::Client::builder()
            .connect_timeout(self.connect_timeout)
            .timeout(self.request_timeout)
            .build()
            .map_err(|e| MessagesError::Network(format!("client build: {}", e)))?;

        let mut req = client
            .post(&endpoint)
            .header("Content-Type", "application/json");

        if !api_key.trim().is_empty() {
            req = req.header("Authorization", format!("Bearer {}", api_key));
        }

        let response = tokio::select! {
            _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
            resp = req.json(&body_map).send() => resp.map_err(|e| MessagesError::Network(format!("send: {}", e)))?,
        };

        let status = response.status();
        if !status.is_success() {
            let text = tokio::select! {
                _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
                t = response.text() => t.map_err(|e| MessagesError::Network(format!("error body: {}", e)))?,
            };
            return Err(MessagesError::HttpStatus(status.as_u16(), text));
        }

        let mut stream = response.bytes_stream();
        let mut raw_bytes_buffer = Vec::new();
        let mut buffer = String::new();
        let mut accumulated_text = String::new();

        while let Some(item) = tokio::select! {
            _ = cancel.cancelled() => return Err(MessagesError::Cancelled),
            next = stream.next() => next,
        } {
            let bytes = item.map_err(|e| MessagesError::Network(format!("stream read: {}", e)))?;
            append_utf8_bytes_safely(&mut raw_bytes_buffer, &mut buffer, &bytes);

            while let Some(end_idx) = buffer.find('\n') {
                let line = buffer[..end_idx].trim().to_string();
                buffer.drain(..end_idx + 1);
                if line.is_empty() {
                    continue;
                }
                if line == "data: [DONE]" {
                    break;
                }
                if let Some(text_chunk) = parse_openai_sse_line(&line) {
                    accumulated_text.push_str(&text_chunk);
                    on_event(SseChunkEvent::TextDelta { text: text_chunk });
                }
            }
        }

        Ok(accumulated_text)
    }
}

impl Default for ProviderDispatcher {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper function to parse an OpenAI SSE stream line (`data: {"choices":[{"delta":{"content":"..."}}]}`).
pub fn parse_openai_sse_line(line: &str) -> Option<String> {
    let data_str = line.strip_prefix("data: ")
        .or_else(|| line.strip_prefix("data:"))?;
    if data_str.trim() == "[DONE]" {
        return None;
    }
    let parsed: Value = serde_json::from_str(data_str.trim()).ok()?;
    let choices = parsed.get("choices")?.as_array()?;
    let first = choices.first()?;
    let delta = first.get("delta")?;
    delta.get("content")?.as_str().map(String::from)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::ModelProfile;

    #[test]
    fn test_endpoint_resolution() {
        let sonnet = ModelProfile::default_profile();
        assert_eq!(
            ProviderDispatcher::endpoint_for_profile(&sonnet),
            "https://api.anthropic.com/v1/messages"
        );

        let gpt4o = ModelProfile::builtin_profiles()
            .into_iter()
            .find(|p| p.id == "gpt-4o")
            .unwrap();
        assert_eq!(
            ProviderDispatcher::endpoint_for_profile(&gpt4o),
            "https://api.openai.com/v1/chat/completions"
        );

        let ollama = ModelProfile::builtin_profiles()
            .into_iter()
            .find(|p| p.id == "ollama-local")
            .unwrap();
        assert_eq!(
            ProviderDispatcher::endpoint_for_profile(&ollama),
            "http://10.0.2.2:11434/v1/chat/completions"
        );
    }

    #[test]
    fn test_parse_openai_sse_line() {
        let line = "data: {\"id\":\"chatcmpl-123\",\"choices\":[{\"delta\":{\"content\":\"Hello world\"}}]}";
        assert_eq!(parse_openai_sse_line(line), Some("Hello world".to_string()));

        let done_line = "data: [DONE]";
        assert_eq!(parse_openai_sse_line(done_line), None);

        let empty_line = "data: {}";
        assert_eq!(parse_openai_sse_line(empty_line), None);
    }
}
