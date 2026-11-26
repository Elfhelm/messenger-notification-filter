package com.example.messengerthreadblocker.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.messengerthreadblocker.R
import com.example.messengerthreadblocker.api.GeminiDecisionClient
import com.example.messengerthreadblocker.data.ApiKeyStore
import com.example.messengerthreadblocker.data.DecisionLogStore
import com.example.messengerthreadblocker.data.GroupMessageStore
import com.example.messengerthreadblocker.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var apiKeyStore: ApiKeyStore
    private lateinit var groupMessageStore: GroupMessageStore
    private val decisionClient = GeminiDecisionClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        apiKeyStore = ApiKeyStore(this)
        groupMessageStore = GroupMessageStore(this)

        // Pre-fill existing key if saved
        apiKeyStore.getApiKey()?.let { existing ->
            binding.etApiKey.setText(existing)
        }

        setupListeners()
        observeDecisionLogs()
        maybeShowInstallOnboardingPrompt()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusUi()
    }

    private fun setupListeners() {
        binding.btnGetGeminiKey.setOnClickListener {
            openGoogleAiStudio()
        }

        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!clipText.isNullOrEmpty()) {
                binding.etApiKey.setText(clipText)
                showKeyMessage("Pasted from clipboard. Tap 'Save & Verify' to test.", isError = false)
            } else {
                showKeyMessage("Clipboard is empty.", isError = true)
            }
        }

        binding.btnSaveVerifyKey.setOnClickListener {
            val candidate = binding.etApiKey.text?.toString()?.trim().orEmpty()
            if (candidate.isEmpty()) {
                showKeyMessage("Please enter or paste your Gemini API key.", isError = true)
                return@setOnClickListener
            }

            binding.btnSaveVerifyKey.isEnabled = false
            binding.btnSaveVerifyKey.text = "Verifying..."
            showKeyMessage("Testing key with Gemini Decision API (gemini-decision-1)...", isError = false)

            lifecycleScope.launch {
                when (val verification = decisionClient.verifyApiKey(candidate)) {
                    is GeminiDecisionClient.VerificationResult.Valid -> {
                        apiKeyStore.saveApiKey(candidate)
                        showKeyMessage("API key verified and saved securely!", isError = false)
                        refreshStatusUi()
                    }
                    is GeminiDecisionClient.VerificationResult.Invalid -> {
                        showKeyMessage("Verification failed: ${verification.reason}", isError = true)
                    }
                }
                binding.btnSaveVerifyKey.isEnabled = true
                binding.btnSaveVerifyKey.text = "Save & Verify"
            }
        }

        binding.btnGrantNotificationAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnClearHistory.setOnClickListener {
            groupMessageStore.clearAll()
            DecisionLogStore.clear()
            refreshStatusUi()
        }

        binding.btnTestDecision.setOnClickListener {
            runSampleDecisionTest()
        }
    }

    private fun openGoogleAiStudio() {
        val url = getString(R.string.gemini_aistudio_url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun maybeShowInstallOnboardingPrompt() {
        if (!apiKeyStore.hasApiKey() && !apiKeyStore.isOnboardingDismissed()) {
            AlertDialog.Builder(this)
                .setTitle("Welcome! Get Free Gemini Access")
                .setMessage(
                    "To filter repetitive Messenger thread notifications, this app uses Google's Gemini Decision API.\n\n" +
                        "You can get a free-tier Gemini subscription (no credit card required, 10,000 requests/day) from Google AI Studio in just a couple of taps."
                )
                .setPositiveButton("Get Free API Key") { _, _ ->
                    openGoogleAiStudio()
                }
                .setNegativeButton("I Already Have a Key", null)
                .show()
        }
    }

    private fun runSampleDecisionTest() {
        val apiKey = apiKeyStore.getApiKey()
        if (apiKey == null) {
            showKeyMessage("Save a valid Gemini API key first.", isError = true)
            return
        }

        binding.btnTestDecision.isEnabled = false
        lifecycleScope.launch {
            val sampleGroup = "Weekend Trip"
            val samplePrev = listOf(
                "Alex: Should we leave at 8am on Saturday?",
                "Sam: 8am works, I'll bring snacks."
            )
            val sampleNew = "Jordan: Sounds good, see you at 8am!"

            val result = decisionClient.evaluateSameThread(
                apiKey = apiKey,
                groupName = sampleGroup,
                previousMessages = samplePrev,
                newMessage = sampleNew
            )
            result.fold(
                onSuccess = { decision ->
                    DecisionLogStore.addEntry(
                        groupName = "$sampleGroup (Test)",
                        messagePreview = sampleNew,
                        probability = decision.probability,
                        blocked = decision.isSameThread,
                        detail = if (decision.isSameThread) {
                            "Would BLOCK (same thread > 50%)"
                        } else {
                            "Would ALLOW (<= 50%)"
                        }
                    )
                },
                onFailure = { err ->
                    DecisionLogStore.addEntry(
                        groupName = "$sampleGroup (Test)",
                        messagePreview = sampleNew,
                        probability = null,
                        blocked = false,
                        detail = "Test error: ${err.message}"
                    )
                }
            )
            binding.btnTestDecision.isEnabled = true
        }
    }

    private fun observeDecisionLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DecisionLogStore.entries.collect { entries ->
                    if (entries.isEmpty()) {
                        binding.tvDecisionLog.text = "No Messenger notifications evaluated yet."
                    } else {
                        binding.tvDecisionLog.text = entries.joinToString("\n\n") { entry ->
                            val probStr = entry.probabilityPercent?.let { "$it%" } ?: "--"
                            val status = if (entry.blocked) "BLOCKED" else "ALLOWED"
                            "[${entry.timestamp}] $status ($probStr) | ${entry.groupName}\n" +
                                "  \"${entry.messagePreview}\"\n" +
                                "  -> ${entry.detail}"
                        }
                    }
                    refreshGroupStats()
                }
            }
        }
    }

    private fun refreshStatusUi() {
        val hasKey = apiKeyStore.hasApiKey()
        val hasNotificationAccess = isNotificationAccessGranted()

        if (hasKey) {
            binding.tvApiKeyBadge.text = "Installed"
            binding.tvApiKeyBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
            binding.tvApiKeyBadge.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
        } else {
            binding.tvApiKeyBadge.text = "Required"
            binding.tvApiKeyBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
            binding.tvApiKeyBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
        }

        if (hasNotificationAccess) {
            binding.tvNotificationBadge.text = "Granted"
            binding.tvNotificationBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
            binding.tvNotificationBadge.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.btnGrantNotificationAccess.text = "Notification Access Settings"
        } else {
            binding.tvNotificationBadge.text = "Not Granted"
            binding.tvNotificationBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
            binding.tvNotificationBadge.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
            binding.btnGrantNotificationAccess.text = "Grant Notification Access"
        }

        if (hasKey && hasNotificationAccess) {
            binding.cardStatusBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.tvOverallStatus.text = "Active: Monitoring Messenger notifications with Gemini Decision 1."
        } else {
            binding.cardStatusBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
            binding.tvOverallStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
            binding.tvOverallStatus.text = "Setup required: Complete Steps 1 and 2 below."
        }

        refreshGroupStats()
    }

    private fun refreshGroupStats() {
        val summaries = groupMessageStore.getGroupSummaries()
        binding.tvGroupStats.text = if (summaries.isEmpty()) {
            "Tracked groups: 0 (10-message rolling buffer per group)"
        } else {
            val detail = summaries.entries.joinToString(", ") { "${it.key} (${it.value}/10)" }
            "Tracked groups (${summaries.size}): $detail"
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun showKeyMessage(message: String, isError: Boolean) {
        binding.tvKeyVerificationResult.visibility = View.VISIBLE
        binding.tvKeyVerificationResult.text = message
        binding.tvKeyVerificationResult.setTextColor(
            ContextCompat.getColor(
                this,
                if (isError) R.color.blocked_text else R.color.allowed_text
            )
        )
    }
}
