package dev.warp.mobile

import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.warp.mobile.ai.ModelProfile
import dev.warp.mobile.ai.ModelProfileRepository
import dev.warp.mobile.ai.ProviderKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BYOK SettingsActivity & Model Profile Selector (Issue #15 update).
 */
class SettingsActivity : AppCompatActivity() {
    private val LOG_TAG = "WarpSettings"
    private lateinit var modelSpinner: Spinner
    private lateinit var keyInput: EditText
    private lateinit var openAiKeyInput: EditText
    private lateinit var statusText: TextView
    private lateinit var usageText: TextView

    private var currentProfiles: List<ModelProfile> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)

        title = "Warp AI · Model Profiles & BYOK Settings"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Model Profile Selector
        root.addView(label("Active Model Profile"))
        modelSpinner = Spinner(this)
        root.addView(modelSpinner, lpMatchWrap())

        currentProfiles = ModelProfileRepository.getAllProfiles(this)
        val profileNames = currentProfiles.map { "${it.name} (${it.provider.name.lowercase()})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profileNames)
        modelSpinner.adapter = adapter

        val activeProfile = ModelProfileRepository.getActiveProfile(this)
        val activeIndex = currentProfiles.indexOfFirst { it.id == activeProfile.id }
        if (activeIndex >= 0) {
            modelSpinner.setSelection(activeIndex)
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position in currentProfiles.indices) {
                    val selected = currentProfiles[position]
                    ModelProfileRepository.setActiveProfileId(this@SettingsActivity, selected.id)
                    setStatus("Active model profile: ${selected.name}")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Key Inputs for Anthropic & OpenAI
        root.addView(label("Anthropic API Key (sk-ant-...)"))
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "sk-ant-..."
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        root.addView(keyInput, lpMatchWrap())

        root.addView(label("OpenAI API Key (sk-...)"))
        openAiKeyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            hint = "sk-..."
            setSingleLine(true)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        root.addView(openAiKeyInput, lpMatchWrap())

        lifecycleScope.launch(Dispatchers.IO) {
            val anthropicKey = try { AiKeyStore.load(this@SettingsActivity, "anthropic") } catch (_: Throwable) { null }
            val openAiKey = try { AiKeyStore.load(this@SettingsActivity, "openai") } catch (_: Throwable) { null }

            withContext(Dispatchers.Main) {
                if (!anthropicKey.isNullOrEmpty()) {
                    keyInput.setText(anthropicKey)
                }
                if (!openAiKey.isNullOrEmpty()) {
                    openAiKeyInput.setText(openAiKey)
                }
                setStatus("Keys loaded (${AiKeyStore.redact(anthropicKey)}, ${AiKeyStore.redact(openAiKey)})")
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        btnRow.addView(button("Save") { onSave() }, lpButton())
        btnRow.addView(button("Test") { onTest() }, lpButton())
        btnRow.addView(button("Clear") { onClear() }, lpButton())
        root.addView(btnRow, lpMatchWrap())

        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(statusText, lpMatchWrap())

        usageText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, dp(16), 0, dp(8))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        root.addView(usageText, lpMatchWrap())
        refreshUsageDisplay()

        val resetBtn = button("Reset session counters") {
            AiUsageTracker.resetSession()
            refreshUsageDisplay()
            Toast.makeText(this, "Session counters reset", Toast.LENGTH_SHORT).show()
        }
        root.addView(resetBtn, lpMatchWrap())

        val costWarning = TextView(this).apply {
            text = "Costs & Model Profiles (2026):\n" +
                "  • Claude 3.5 Sonnet: ~\$0.05 per agent turn\n" +
                "  • GPT-4o / GPT-4o Mini: OpenAI compatible\n" +
                "  • Ollama Local: Custom endpoint (http://10.0.2.2:11434)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF888888.toInt())
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(costWarning, lpMatchWrap())

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::usageText.isInitialized) {
            refreshUsageDisplay()
        }
    }

    private fun onSave() {
        val antKey = keyInput.text.toString().trim()
        val oaiKey = openAiKeyInput.text.toString().trim()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (antKey.isNotEmpty()) {
                    AiKeyStore.save(this@SettingsActivity, "anthropic", antKey)
                }
                if (oaiKey.isNotEmpty()) {
                    AiKeyStore.save(this@SettingsActivity, "openai", oaiKey)
                }
                withContext(Dispatchers.Main) {
                    setStatus("Keys saved (Anthropic: ${AiKeyStore.redact(antKey)}, OpenAI: ${AiKeyStore.redact(oaiKey)})")
                    Toast.makeText(this@SettingsActivity, "API Keys saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    setStatus("Save failed: ${e.message}")
                }
            }
        }
    }

    private fun onClear() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AiKeyStore.clearAll(this@SettingsActivity)
                withContext(Dispatchers.Main) {
                    keyInput.setText("")
                    openAiKeyInput.setText("")
                    setStatus("All API keys cleared")
                    Toast.makeText(this@SettingsActivity, "API keys cleared", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "clear failed: ${e.message}")
            }
        }
    }

    private fun onTest() {
        val active = ModelProfileRepository.getActiveProfile(this)
        val key = when (active.provider) {
            ProviderKind.ANTHROPIC -> keyInput.text.toString().trim()
            ProviderKind.OPENAI -> openAiKeyInput.text.toString().trim()
            ProviderKind.CUSTOM_OPENAI -> openAiKeyInput.text.toString().trim()
        }

        if (active.provider != ProviderKind.CUSTOM_OPENAI && key.isEmpty()) {
            setStatus("Enter API key for ${active.provider.name} before testing")
            return
        }

        if (!AiConnectivity.get(this).isOnline()) {
            setStatus("✗ No network — retry connection")
            return
        }

        setStatus("Testing profile ${active.name} (${active.modelName})...")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AnthropicClient.testConnection(if (key.isEmpty()) "custom-local" else key)
            withContext(Dispatchers.Main) {
                val msg = when (result) {
                    is AnthropicClient.TestResult.Ok -> {
                        AiUsageTracker.record(
                            this@SettingsActivity,
                            kind = "ghost",
                            model = active.modelName,
                            inputTokens = result.inputTokens,
                            outputTokens = result.outputTokens,
                            latencyMs = result.latencyMs
                        )
                        "✓ OK (${result.latencyMs} ms, in=${result.inputTokens} out=${result.outputTokens} tokens)"
                    }
                    is AnthropicClient.TestResult.HttpError -> "✗ HTTP ${result.code}: ${result.message.take(80)}"
                    is AnthropicClient.TestResult.NetworkError -> "✗ Network: ${result.message.take(80)}"
                    AnthropicClient.TestResult.MissingKey -> "✗ Missing or empty key"
                }
                setStatus(msg)
                refreshUsageDisplay()
            }
        }
    }

    private fun refreshUsageDisplay() {
        val s = AiUsageTracker.snapshot()
        val active = ModelProfileRepository.getActiveProfile(this)
        usageText.text = buildString {
            append("Active Profile: ${active.name} (${active.modelName})\n")
            append("Session usage (since launch):\n")
            append("  Ghost calls:  ${s.ghostCalls}  (p95 latency ${s.ghostP95Ms}ms)\n")
            append("  Agent calls:  ${s.agentCalls}  (p95 latency ${s.agentP95Ms}ms)\n")
            append("  Input tokens: ${s.inputTokens}\n")
            append("  Output tokens: ${s.outputTokens}")
        }
    }

    private fun setStatus(text: String) {
        statusText.text = text
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun lpMatchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun lpButton() = LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
    ).apply { setMargins(dp(4), 0, dp(4), 0) }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
