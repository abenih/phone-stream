package dev.gamecontrols.link.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.gamecontrols.link.LinkApp
import dev.gamecontrols.link.R
import dev.gamecontrols.link.databinding.ActivityMainBinding
import dev.gamecontrols.link.host.CaptureService
import dev.gamecontrols.link.host.HostPhase
import dev.gamecontrols.link.host.HostState
import dev.gamecontrols.link.net.LanBinding
import dev.gamecontrols.link.protocol.Invitation
import java.net.SocketException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as LinkApp
    private lateinit var binding: ActivityMainBinding
    private var role: String? = null
    private var consentSession = 0L
    private var copiedInvitation: String? = null
    private var targets = emptyList<Target>()

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed && app.host.state.value.phase == HostPhase.PAIRED) requestProjection()
        else app.host.report("Visible capture notifications are required. No capture was started.")
    }
    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = consentSession
        consentSession = 0
        if (id == 0L || !app.host.matchesSession(id)) {
            app.host.report("Stale screen-sharing consent was discarded.")
        } else if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            app.host.startCapture(id, result.resultCode, requireNotNull(result.data))
        } else app.host.stop("Screen-sharing consent was cancelled. Nothing was started.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        role = savedInstanceState?.getString("role")
        if (app.host.state.value.phase != HostPhase.IDLE) role = "host"
        binding.hostRole.setOnClickListener { role = "host"; render(app.host.state.value) }
        binding.clientRole.setOnClickListener { role = "client"; render(app.host.state.value) }
        binding.openInvitation.setOnClickListener { selectLan() }
        binding.copyInvitation.setOnClickListener {
            app.host.state.value.invitation?.encode()?.let { value ->
                val clip = ClipData.newPlainText(getString(R.string.copy_label), value)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                copiedInvitation = value
                app.host.report("Private invitation copied. Share only through a trusted channel; it expires in this pairing window.")
            }
        }
        binding.startCapture.setOnClickListener {
            CaptureService.ensureChannel(this)
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else requestProjection()
        }
        binding.accessibilitySettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.allowControl.setOnClickListener { approveControl() }
        binding.revokeControl.setOnClickListener { app.host.revokeControl("Host user revoked control.") }
        binding.stopAll.setOnClickListener { app.host.stop("Host user stopped the session.") }
        binding.connect.setOnClickListener { reviewConnection() }
        loadTargets()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.host.state.collect(::render) }
        }
        render(app.host.state.value)
    }

    private fun selectLan() {
        try {
            val candidates = LanBinding.hostCandidates(this)
            if (candidates.isEmpty()) {
                app.host.report("No supported existing Wi-Fi/Ethernet/hotspot IPv4 interface. No networking was changed.")
                return
            }
            AlertDialog.Builder(this).setTitle(R.string.select_lan)
                .setItems(candidates.map { it.toString() }.toTypedArray()) { _, index ->
                    app.host.openInvitation(candidates[index])
                }.setNegativeButton(R.string.cancel, null).show()
        } catch (_: SocketException) {
            app.host.report("Android could not enumerate the existing LAN interfaces.")
        } catch (_: SecurityException) {
            app.host.report("Android denied access to LAN interface information.")
        }
    }

    private fun requestProjection() {
        if (app.host.state.value.phase != HostPhase.PAIRED) {
            app.host.report("Pair a Client before starting screen sharing.")
            return
        }
        CaptureService.ensureChannel(this)
        if (!CaptureService.notificationsVisible(this)) {
            app.host.report("Enable this app's screen-sharing notification channel in Android settings before capture.")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        consentSession = app.host.requestProjection()
        val request = if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        projectionConsent.launch(request)
    }

    private fun approveControl() {
        val target = targets.getOrNull(binding.target.selectedItemPosition)
        if (target == null) { app.host.report("Select a user-installed control target."); return }
        AlertDialog.Builder(this).setTitle(R.string.control_confirm_title)
            .setMessage(getString(R.string.control_confirm, target.toString()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.allow) { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(target.packageName)
                if (launch == null) {
                    app.host.report("The selected app is no longer launchable.")
                } else {
                    try {
                        app.host.requestControl(target.packageName)
                        startActivity(launch)
                    } catch (_: IllegalStateException) {
                        app.host.revokeControl("Capture or Accessibility is no longer ready for control.")
                    } catch (_: android.content.ActivityNotFoundException) {
                        app.host.revokeControl("The selected app could not be opened.")
                    } catch (_: SecurityException) {
                        app.host.revokeControl("Android did not allow the selected app to open.")
                    }
                }
            }.show()
    }

    private fun reviewConnection() {
        if (app.host.state.value.phase != HostPhase.IDLE) {
            app.host.report("Stop Host mode before becoming a Client.")
            return
        }
        val invitation = try {
            Invitation.parse(binding.clientInvitation.text.toString())
        } catch (_: IllegalArgumentException) {
            app.host.report("Invalid invitation. Paste the complete, unmodified private Host invitation.")
            return
        }
        AlertDialog.Builder(this).setTitle(R.string.review_title)
            .setMessage(getString(R.string.review_body, invitation.host, invitation.port, invitation.pin))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.connect) { _, _ ->
                binding.clientInvitation.text.clear()
                app.pendingClientInvitation = invitation
                startActivity(Intent(this, ViewerActivity::class.java))
            }.show()
    }

    private fun loadTargets() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        targets = packageManager.queryIntentActivities(query, 0).mapNotNull { info ->
            val application = info.activityInfo.applicationInfo
            if (application.packageName == packageName ||
                application.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) null
            else Target(info.loadLabel(packageManager).toString(), application.packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        binding.target.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, targets).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun render(state: HostState) {
        binding.hostSection.visibility = if (role == "host") View.VISIBLE else View.GONE
        binding.clientSection.visibility = if (role == "client") View.VISIBLE else View.GONE
        binding.hostRole.isSelected = role == "host"
        binding.clientRole.isSelected = role == "client"
        binding.clientRole.isEnabled = state.phase == HostPhase.IDLE
        binding.status.text = state.status
        binding.invitation.text = state.invitation?.encode() ?: getString(R.string.invite_empty)
        binding.openInvitation.isEnabled = state.phase == HostPhase.IDLE
        binding.copyInvitation.isEnabled = state.invitation != null
        binding.startCapture.isEnabled = state.phase == HostPhase.PAIRED && consentSession == 0L
        binding.accessibilitySettings.isEnabled = state.phase == HostPhase.IDLE
        binding.allowControl.isEnabled = state.controlReady && targets.isNotEmpty()
        binding.revokeControl.isEnabled = state.phase == HostPhase.STREAMING
        binding.stopAll.isEnabled = state.phase !in setOf(HostPhase.IDLE, HostPhase.STOPPING)
        if (state.invitation == null) clearOwnedClipboard()
    }

    private fun clearOwnedClipboard() {
        val copied = copiedInvitation ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        val current = clipboard.primaryClip
        if (current != null && current.itemCount == 1 && current.getItemAt(0).text?.toString() == copied) {
            clipboard.clearPrimaryClip()
        }
        copiedInvitation = null
    }

    override fun onStop() {
        clearOwnedClipboard()
        app.host.hostUiStopped()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("role", role)
        super.onSaveInstanceState(outState)
    }

    private data class Target(val label: String, val packageName: String) {
        override fun toString() = "$label ($packageName)"
    }
}
