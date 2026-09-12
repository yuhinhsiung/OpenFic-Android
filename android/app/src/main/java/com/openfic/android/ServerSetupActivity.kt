package com.openfic.android

import android.graphics.Rect
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
     *
     * Insets are *added* to the padding the layout already declares. `updatePadding`
     * replaces rather than accumulates, so passing the inset values straight through would
     * wipe the layout's own 24dp and leave every field flush against the screen edge —
     * the insets are zero on the left and right in portrait.
     */
    private fun applyWindowInsets() {
        val content = binding.setupRoot.getChildAt(0)
        val base = Rect(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            content.paddingBottom,
        )
        // Width comes from the resources rather than `binding.setupRoot.width`: the insets
        // listener first fires during layout, when the ScrollView has not been measured yet
        // and reports zero — which would compute a negative allowance and silently fall back
        // to the layout's own padding on tablets. Resources reflect the activity's window,
        // so this stays correct in split-screen too.
        val metrics = resources.displayMetrics
        val maxContentWidth = (MAX_CONTENT_WIDTH_DP * metrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.setupRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )

            // On a tablet the fields would otherwise stretch the full width of the screen;
            // grow the side padding instead so the form stays a readable column.
            val spare = (metrics.widthPixels - bars.left - bars.right - maxContentWidth) / 2
            val side = maxOf(base.left, spare)

            content.updatePadding(
                left = side + bars.left,
                top = base.top + bars.top,
                right = side + bars.right,
                bottom = base.bottom + bars.bottom,
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

    private companion object {
        /** Comfortable reading width for a single-column form; wider screens get more margin. */
        const val MAX_CONTENT_WIDTH_DP = 560
    }
}
