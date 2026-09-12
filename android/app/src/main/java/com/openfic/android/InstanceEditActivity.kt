package com.openfic.android

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.openfic.android.databinding.ActivityInstanceEditBinding
import com.openfic.android.storage.AppPreferencesStore
import com.openfic.android.storage.UiSource
import com.openfic.android.storage.localizedContext
import com.openfic.android.storage.normalizeServerUrl
import com.openfic.android.web.BackendProbe
import com.openfic.android.web.ProbeResult
import kotlinx.coroutines.launch

/**
 * Add or edit one saved backend.
 *
 * Launched by [InstancesActivity] with an instance id to edit, or with no extra to create a
 * new one. Saving returns [RESULT_OK] so the caller can refresh its list.
 */
class InstanceEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstanceEditBinding
    private lateinit var preferences: AppPreferencesStore
    private var editingId: String? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityInstanceEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferencesStore(this)
        applyWindowInsets()

        editingId = intent.getStringExtra(EXTRA_INSTANCE_ID)
        val existing = editingId?.let { id -> preferences.read().instances.firstOrNull { it.id == id } }

        binding.instanceNameInput.setText(existing?.name.orEmpty())
        binding.serverUrlInput.setText(existing?.url.orEmpty())
        binding.uiSourceGroup.check(
            if (existing?.uiSource == UiSource.SERVER) R.id.uiSourceServer else R.id.uiSourceBundled,
        )
        binding.uiSourceGroup.setOnCheckedChangeListener { _, _ -> renderUiSourceHint() }
        renderUiSourceHint()

        binding.screenTitle.setText(
            if (existing == null) R.string.instance_add_title else R.string.instance_edit_title,
        )
        binding.deleteButton.visibility = if (existing == null) View.GONE else View.VISIBLE

        binding.testButton.setOnClickListener { runProbe() }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        // Back discards, matching the system back gesture; "Save" is the explicit commit.
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
            when (val result = BackendProbe.probe(serverUrl)) {
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
            if (!isFinishing && !isDestroyed) setBusy(false)
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
        binding.testResult.setTextColor(getColor(if (isError) R.color.setup_error else R.color.setup_success))
    }

    private fun save() {
        val serverUrl = currentServerUrl()
        if (serverUrl.isEmpty()) {
            showProbeResult(getString(R.string.probe_empty), isError = true)
            return
        }
        val name = binding.instanceNameInput.text?.toString().orEmpty()
        val id = editingId
        if (id == null) {
            preferences.addInstance(name, serverUrl, selectedUiSource())
        } else {
            preferences.updateInstance(id, name, serverUrl, selectedUiSource())
        }
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmDelete() {
        val id = editingId ?: return
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.instance_delete_confirm_title)
            .setMessage(R.string.instance_delete_confirm_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                preferences.removeInstance(id)
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    /**
     * Insets are *added* to the padding the layout declares: `updatePadding` replaces rather
     * than accumulates, so passing the inset values straight through would wipe the layout's
     * own 24dp and leave every field flush against the screen edge (the left and right insets
     * are zero in portrait). On wide screens the side padding grows instead, keeping the form
     * a readable column rather than stretching its inputs across a tablet.
     */
    private fun applyWindowInsets() {
        val content = binding.editRoot.getChildAt(0)
        val base = Rect(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            content.paddingBottom,
        )
        val metrics = resources.displayMetrics
        val maxContentWidth = (MAX_CONTENT_WIDTH_DP * metrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.editRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
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

    companion object {
        const val EXTRA_INSTANCE_ID = "instance_id"

        private const val MAX_CONTENT_WIDTH_DP = 560
    }
}
