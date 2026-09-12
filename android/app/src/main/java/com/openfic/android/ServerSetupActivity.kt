package com.openfic.android

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.openfic.android.storage.AppPreferencesStore
import com.openfic.android.storage.UiSource
import com.openfic.android.storage.normalizeServerUrl
import com.openfic.android.databinding.ActivityServerSetupBinding
import com.openfic.android.web.BackendProbe
import com.openfic.android.web.ProbeResult
import kotlinx.coroutines.launch

/**
 * Server address editor. Shown automatically on first launch and re-openable from the
 * error screen or from the SPA's settings page via the `openficAndroidHost` bridge.
 */
class ServerSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerSetupBinding
    private lateinit var preferences: AppPreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityServerSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferencesStore(this)
        applyWindowInsets()

        val existing = preferences.read()
        binding.serverUrlInput.setText(existing.serverUrl.orEmpty())
        binding.uiSourceGroup.check(
            if (existing.uiSource == UiSource.SERVER) {
                R.id.uiSourceServer
            } else {
                R.id.uiSourceBundled
            },
        )
        binding.uiSourceGroup.setOnCheckedChangeListener { _, _ -> renderUiSourceHint() }
        renderUiSourceHint()

        binding.testButton.setOnClickListener { runProbe() }
        binding.saveButton.setOnClickListener { save() }
    }

    /**
     * The form scrolls, so insets go on the scrolling child rather than the ScrollView: that
     * keeps the background painting behind the system bars while the fields stay reachable.
     */
    private fun applyWindowInsets() {
        val content = binding.setupRoot.getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(binding.setupRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            content.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }

    private fun selectedUiSource(): UiSource =
        if (binding.uiSourceGroup.checkedRadioButtonId == R.id.uiSourceServer) {
            UiSource.SERVER
        } else {
            UiSource.BUNDLED
        }

    private fun renderUiSourceHint() {
        binding.uiSourceHint.setText(
            when (selectedUiSource()) {
                UiSource.BUNDLED -> R.string.ui_source_bundled_hint
                UiSource.SERVER -> R.string.ui_source_server_hint
            },
        )
    }

    private fun currentServerUrl(): String =
        normalizeServerUrl(binding.serverUrlInput.text?.toString().orEmpty())

    private fun runProbe() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isEmpty()) {
            showProbeResult(getString(R.string.probe_empty), isError = true)
            return
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            showProbeResult(getString(R.string.probe_bad_scheme), isError = true)
            return
        }

        setBusy(true)
        showProbeResult(getString(R.string.probe_running), isError = false)

        lifecycleScope.launch {
            val result = BackendProbe.probe(serverUrl)
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            when (result) {
                is ProbeResult.Reachable -> showProbeResult(
                    result.serverVersion?.let { getString(R.string.probe_ok_version, it) }
                        ?: getString(R.string.probe_ok),
                    isError = false,
                )

                is ProbeResult.Unreachable -> showProbeResult(
                    getString(R.string.probe_failed, result.reason),
                    isError = true,
                )
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.testButton.isEnabled = !busy
        binding.saveButton.isEnabled = !busy
        binding.setupProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showProbeResult(message: String, isError: Boolean) {
        binding.testResult.visibility = View.VISIBLE
        binding.testResult.text = message
        binding.testResult.setTextColor(
            getColor(if (isError) R.color.setup_error else R.color.setup_success),
        )
    }

    private fun save() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isEmpty()) {
            showProbeResult(getString(R.string.probe_empty), isError = true)
            return
        }
        preferences.saveServerUrl(serverUrl)
        preferences.saveUiSource(selectedUiSource())
        setResult(RESULT_OK)
        finish()
    }
}
