package dev.warp.mobile.ai

enum class ProviderKind {
    ANTHROPIC,
    OPENAI,
    CUSTOM_OPENAI;

    fun toKeyProvider(): String = when (this) {
        ANTHROPIC -> "anthropic"
        OPENAI -> "openai"
        CUSTOM_OPENAI -> "custom"
    }

    companion object {
        fun fromString(value: String): ProviderKind = when (value.lowercase()) {
            "anthropic" -> ANTHROPIC
            "openai" -> OPENAI
            "custom_openai", "custom" -> CUSTOM_OPENAI
            else -> ANTHROPIC
        }
    }
}

data class ModelProfile(
    val id: String,
    val name: String,
    val provider: ProviderKind,
    val modelName: String,
    val endpointUrl: String? = null,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 4096,
    val topP: Float? = 0.99f,
    val contextWindow: Int = 128000,
    val supportsTools: Boolean = true,
    val supportsStreaming: Boolean = true,
    val isBuiltin: Boolean = true
) {
    fun validate(): Boolean {
        if (id.isBlank() || name.isBlank() || modelName.isBlank()) return false
        if (maxTokens <= 0) return false
        if (temperature !in 0.0f..1.0f) return false
        topP?.let { if (it !in 0.0f..1.0f) return false }
        if (provider == ProviderKind.CUSTOM_OPENAI && endpointUrl.isNullOrBlank()) return false
        return true
    }

    fun toJson(): String {
        return try {
            val obj = org.json.JSONObject().apply {
                put("id", id)
                put("name", name)
                put("provider", provider.name.lowercase())
                put("model_name", modelName)
                if (!endpointUrl.isNullOrBlank()) put("endpoint_url", endpointUrl)
                put("temperature", temperature.toDouble())
                put("max_tokens", maxTokens)
                if (topP != null) put("top_p", topP.toDouble())
                put("context_window", contextWindow)
                put("supports_tools", supportsTools)
                put("supports_streaming", supportsStreaming)
                put("is_builtin", isBuiltin)
            }
            val str = obj.toString()
            if (!str.isNullOrBlank() && str.startsWith("{") && str.endsWith("}")) str else buildManualJson()
        } catch (_: Throwable) {
            buildManualJson()
        }
    }

    private fun buildManualJson(): String = buildString {
        append("{")
        append("\"id\":\"").append(id).append("\",")
        append("\"name\":\"").append(name).append("\",")
        append("\"provider\":\"").append(provider.name.lowercase()).append("\",")
        append("\"model_name\":\"").append(modelName).append("\",")
        if (!endpointUrl.isNullOrBlank()) append("\"endpoint_url\":\"").append(endpointUrl).append("\",")
        append("\"temperature\":").append(temperature).append(",")
        append("\"max_tokens\":").append(maxTokens).append(",")
        if (topP != null) append("\"top_p\":").append(topP).append(",")
        append("\"context_window\":").append(contextWindow).append(",")
        append("\"supports_tools\":").append(supportsTools).append(",")
        append("\"supports_streaming\":").append(supportsStreaming).append(",")
        append("\"is_builtin\":").append(isBuiltin)
        append("}")
    }

    companion object {
        fun fromJson(jsonStr: String): ModelProfile {
            if (jsonStr.isBlank()) {
                return ModelProfile(id = "", name = "", provider = ProviderKind.ANTHROPIC, modelName = "")
            }

            var parsedViaJsonObject = false
            var id = ""
            var name = ""
            var providerStr = ""
            var modelName = ""
            var endpointUrl: String? = null
            var temp = 0.7f
            var maxTokens = 4096
            var topPVal: Float? = null
            var contextWin = 128000
            var tools = true
            var streaming = true
            var builtin = false

            try {
                val json = org.json.JSONObject(jsonStr)
                fun getString(key: String): String {
                    if (!json.has(key) || json.isNull(key)) return ""
                    val v = json.opt(key) ?: return ""
                    val s = v.toString()
                    return if (s == "null") "" else s
                }

                id = getString("id")
                name = getString("name")
                providerStr = getString("provider")
                modelName = getString("model_name")

                endpointUrl = if (json.has("endpoint_url") && !json.isNull("endpoint_url")) {
                    val ep = json.opt("endpoint_url")?.toString()
                    if (ep.isNullOrBlank() || ep == "null") null else ep
                } else null

                temp = if (json.has("temperature") && !json.isNull("temperature")) {
                    val t = json.optDouble("temperature", 0.7)
                    if (t.isNaN()) 0.7f else t.toFloat()
                } else 0.7f

                maxTokens = if (json.has("max_tokens") && !json.isNull("max_tokens")) {
                    json.optInt("max_tokens", 4096)
                } else 4096

                topPVal = if (json.has("top_p") && !json.isNull("top_p")) {
                    val p = json.optDouble("top_p", 0.99)
                    if (p.isNaN()) null else p.toFloat()
                } else null

                contextWin = if (json.has("context_window") && !json.isNull("context_window")) {
                    json.optInt("context_window", 128000)
                } else 128000

                tools = if (json.has("supports_tools") && !json.isNull("supports_tools")) {
                    json.optBoolean("supports_tools", true)
                } else true

                streaming = if (json.has("supports_streaming") && !json.isNull("supports_streaming")) {
                    json.optBoolean("supports_streaming", true)
                } else true

                builtin = if (json.has("is_builtin") && !json.isNull("is_builtin")) {
                    json.optBoolean("is_builtin", false)
                } else false

                if (id.isNotBlank() || name.isNotBlank() || modelName.isNotBlank()) {
                    parsedViaJsonObject = true
                }
            } catch (_: Throwable) {}

            if (!parsedViaJsonObject) {
                fun extractValue(key: String): String {
                    val regex = Regex("\"$key\"\\s*:\\s*(?:\"((?:[^\"]|\\\\.)*)\"|([^,\\s}\\]]+))")
                    val match = regex.find(jsonStr) ?: return ""
                    val quoted = match.groupValues.getOrNull(1)
                    val unquoted = match.groupValues.getOrNull(2)
                    val raw = if (!quoted.isNullOrEmpty()) quoted else unquoted ?: ""
                    val s = raw.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
                    return if (s.equals("null", ignoreCase = true)) "" else s
                }

                fun extractInt(key: String, default: Int): Int {
                    val v = extractValue(key)
                    return v.toIntOrNull() ?: default
                }

                fun extractFloat(key: String, default: Float): Float {
                    val v = extractValue(key)
                    return v.toFloatOrNull() ?: default
                }

                fun extractBool(key: String, default: Boolean): Boolean {
                    val v = extractValue(key)
                    return v.toBooleanStrictOrNull() ?: default
                }

                id = extractValue("id")
                name = extractValue("name")
                providerStr = extractValue("provider")
                modelName = extractValue("model_name")
                val endpointRaw = extractValue("endpoint_url")
                endpointUrl = if (endpointRaw.isBlank() || endpointRaw.equals("null", ignoreCase = true)) null else endpointRaw
                temp = extractFloat("temperature", 0.7f)
                maxTokens = extractInt("max_tokens", 4096)
                val topPRaw = extractValue("top_p")
                topPVal = if (jsonStr.contains("\"top_p\"")) topPRaw.toFloatOrNull() else null
                contextWin = extractInt("context_window", 128000)
                tools = extractBool("supports_tools", true)
                streaming = extractBool("supports_streaming", true)
                builtin = extractBool("is_builtin", false)
            }

            return ModelProfile(
                id = id,
                name = name,
                provider = ProviderKind.fromString(providerStr),
                modelName = modelName,
                endpointUrl = endpointUrl,
                temperature = temp,
                maxTokens = maxTokens,
                topP = topPVal,
                contextWindow = contextWin,
                supportsTools = tools,
                supportsStreaming = streaming,
                isBuiltin = builtin
            )
        }

        val CLAUDE_3_5_SONNET = ModelProfile(
            id = "claude-3-5-sonnet",
            name = "Claude 3.5 Sonnet",
            provider = ProviderKind.ANTHROPIC,
            modelName = "claude-3-5-sonnet-20241022",
            temperature = 0.7f,
            maxTokens = 8192,
            topP = 0.99f,
            contextWindow = 200000,
            supportsTools = true,
            supportsStreaming = true,
            isBuiltin = true
        )

        val CLAUDE_3_5_HAIKU = ModelProfile(
            id = "claude-3-5-haiku",
            name = "Claude 3.5 Haiku",
            provider = ProviderKind.ANTHROPIC,
            modelName = "claude-3-5-haiku-20241022",
            temperature = 0.5f,
            maxTokens = 4096,
            topP = 0.99f,
            contextWindow = 200000,
            supportsTools = true,
            supportsStreaming = true,
            isBuiltin = true
        )

        val GPT_4O = ModelProfile(
            id = "gpt-4o",
            name = "GPT-4o",
            provider = ProviderKind.OPENAI,
            modelName = "gpt-4o",
            temperature = 0.7f,
            maxTokens = 4096,
            topP = 0.99f,
            contextWindow = 128000,
            supportsTools = true,
            supportsStreaming = true,
            isBuiltin = true
        )

        val GPT_4O_MINI = ModelProfile(
            id = "gpt-4o-mini",
            name = "GPT-4o mini",
            provider = ProviderKind.OPENAI,
            modelName = "gpt-4o-mini",
            temperature = 0.7f,
            maxTokens = 4096,
            topP = 0.99f,
            contextWindow = 128000,
            supportsTools = true,
            supportsStreaming = true,
            isBuiltin = true
        )

        val OLLAMA_LOCAL = ModelProfile(
            id = "ollama-local",
            name = "Ollama Local (qwen2.5)",
            provider = ProviderKind.CUSTOM_OPENAI,
            modelName = "qwen2.5-coder:7b",
            endpointUrl = "http://127.0.0.1:11434/v1/chat/completions",
            temperature = 0.7f,
            maxTokens = 4096,
            topP = 0.9f,
            contextWindow = 32000,
            supportsTools = false,
            supportsStreaming = true,
            isBuiltin = true
        )

        val BUILTIN_PROFILES = listOf(
            CLAUDE_3_5_SONNET,
            CLAUDE_3_5_HAIKU,
            GPT_4O,
            GPT_4O_MINI,
            OLLAMA_LOCAL
        )
    }
}
