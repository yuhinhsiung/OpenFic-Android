package com.openfic.android

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.openfic.android.databinding.ActivityInstancesBinding
import com.openfic.android.databinding.ItemInstanceBinding
import com.openfic.android.storage.AppPreferencesStore
import com.openfic.android.storage.BackendInstance
import com.openfic.android.storage.localizedContext

/**
 * The Android counterpart of the desktop client's "instances" menu: lists the saved backends,
 * lets one be activated, and routes to add/edit.
 *
 * Tapping a row activates that backend — the primary action — while the pencil edits it.
 * Rows are inflated into a plain LinearLayout rather than a RecyclerView: a user has a
 * handful of backends at most, and it keeps the module free of another dependency.
 */
class InstancesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInstancesBinding
    private lateinit var preferences: AppPreferencesStore

    /** Set when this screen was opened with nothing configured — see [editLauncher]. */
    private var openedWithNoInstances = false

    private val editLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // Adding the very first instance is the first-run flow: the user typed an address and
        // saved, so take them into the app instead of asking for a second confirming tap on
        // the row that just appeared.
        if (openedWithNoInstances && preferences.read().instances.isNotEmpty()) {
            setResult(RESULT_OK)
            finish()
            return@registerForActivityResult
        }
        render()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(localizedContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityInstancesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AppPreferencesStore(this)
        openedWithNoInstances = preferences.read().instances.isEmpty()
        applyWindowInsets()

        binding.addButton.setOnClickListener { openEditor(instanceId = null) }
        binding.emptyAddButton.setOnClickListener { openEditor(instanceId = null) }
        // This screen is reached from the web UI, so the system back gesture is the only way
        // out; give it a visible affordance too.
        binding.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val state = preferences.read()
        binding.instanceList.removeAllViews()

        binding.emptyState.visibility = if (state.instances.isEmpty()) View.VISIBLE else View.GONE
        binding.instanceList.visibility = if (state.instances.isEmpty()) View.GONE else View.VISIBLE

        state.instances.forEach { instance ->
            val row = ItemInstanceBinding.inflate(LayoutInflater.from(this), binding.instanceList, false)
            val isActive = instance.id == state.activeInstanceId

            row.instanceName.text = instance.name
            row.instanceUrl.text = instance.url
            row.activeIndicator.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
            row.instanceRow.isSelected = isActive

            row.instanceRow.setOnClickListener { activate(instance) }
            row.editButton.setOnClickListener { openEditor(instance.id) }

            binding.instanceList.addView(row.root)
        }
    }

    private fun activate(instance: BackendInstance) {
        if (preferences.read().activeInstanceId == instance.id) {
            finish()
            return
        }
        preferences.setActiveInstance(instance.id)
        setResult(RESULT_OK)
        finish()
    }

    private fun openEditor(instanceId: String?) {
        val intent = Intent(this, InstanceEditActivity::class.java)
        if (instanceId != null) intent.putExtra(InstanceEditActivity.EXTRA_INSTANCE_ID, instanceId)
        editLauncher.launch(intent)
    }

    private fun applyWindowInsets() {
        val content = binding.instancesRoot
        val base = Rect(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            content.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            content.updatePadding(
                left = base.left + bars.left,
                top = base.top + bars.top,
                right = base.right + bars.right,
                bottom = base.bottom + bars.bottom,
            )
            insets
        }
    }
}
