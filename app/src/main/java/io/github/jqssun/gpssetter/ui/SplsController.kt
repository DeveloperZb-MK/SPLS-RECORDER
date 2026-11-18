package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.spls.SplsLocation
import io.github.jqssun.gpssetter.spls.SplsStore
import io.github.jqssun.gpssetter.spls.SplsSession
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything SPLS needs from the screen: the permission steps, the CSV import and export and the delete.
 * Must be created while the activity is created, because it registers the result launchers.
 */
class SplsController(private val activity: ComponentActivity) {

    private var stage = 0
    private var asked = false
    private var settingsAsked = false
    private var attemptActive = false
    private var permissionDialog: androidx.appcompat.app.AlertDialog? = null
    private var finished: ((Boolean) -> Unit)? = null
    private var changed: (() -> Unit)? = null
    private var toastRef: Toast? = null

    /** Shows a short message inside the SPLS pop-up (about 5 seconds). Set by the pop-up while it is open. */
    var notifier: ((String) -> Unit)? = null

    private val deniedAttempts = mutableMapOf<String, Int>()
    private var openingAppInfo = false

    private val settingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        openingAppInfo = false
        // Re-check grants after the user returns; do not immediately launch another prompt.
        setupStep()
    }
    private val permLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        if (result[fine] == false && handleRuntimeDenial(fine)) return@registerForActivityResult
        setupStep()
    }
    private val bgLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionGranted ->
        val permission = when (stage) {
            2 -> Manifest.permission.ACCESS_BACKGROUND_LOCATION
            3 -> Manifest.permission.POST_NOTIFICATIONS
            else -> null
        }
        if (!permissionGranted && permission != null && handleRuntimeDenial(permission)) return@registerForActivityResult
        setupStep()
    }
    private val batteryLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { setupStep() }
    private val exportLauncher = activity.registerForActivityResult(ActivityResultContracts.CreateDocument()) { uri -> if (uri != null) doExport(uri) }
    private val importLauncher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) doImport(uri) }

    private fun appCtx(): Context? = activity.applicationContext

    /** One message at a time: a new message replaces the old one instead of queuing behind it. */
    private fun say(msg: String) {
        val n = notifier
        if (n != null) {
            n(msg)
            return
        }
        val c = appCtx() ?: return
        toastRef?.cancel()
        toastRef = Toast.makeText(c, msg, Toast.LENGTH_LONG).also { it.show() }
    }

    // ---------------------------------------------------------------- enabling SPLS

    /** Checks and asks for everything SPLS needs, one step after the other. [done] gets true when all steps passed. */
    fun enable(done: (Boolean) -> Unit) {
        permissionDialog?.setOnCancelListener(null)
        permissionDialog?.dismiss()
        permissionDialog = null
        finished = done
        attemptActive = true
        stage = 0
        asked = false
        settingsAsked = false
        setupStep()
    }

    /** Cancels an unfinished permission attempt without affecting an existing recording. */
    fun cancelEnable(): Boolean {
        if (!attemptActive) return false
        attemptActive = false
        finished = null
        permissionDialog?.setOnCancelListener(null)
        permissionDialog?.dismiss()
        permissionDialog = null
        return true
    }

    private fun advance(next: Int) {
        stage = next
        asked = false
        settingsAsked = false
    }

    private fun end(ok: Boolean, message: String? = null) {
        if (message != null) say(message)
        attemptActive = false
        permissionDialog?.setOnCancelListener(null)
        permissionDialog?.dismiss()
        permissionDialog = null
        val f = finished
        finished = null
        f?.invoke(ok)
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /** After repeated denials, Android may stop displaying the runtime permission dialog.
     * Once that happens, send the user to this app's details page automatically. */
    private fun handleRuntimeDenial(permission: String): Boolean {
        val count = (deniedAttempts[permission] ?: 0) + 1
        deniedAttempts[permission] = count
        val permanentlyBlocked = !activity.shouldShowRequestPermissionRationale(permission)
        if (count >= 2 && permanentlyBlocked && !openingAppInfo && attemptActive) {
            openingAppInfo = true
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
            settingsLauncher.launch(intent)
            return true
        }
        return false
    }

    private fun hasBlockedRuntimePermission(): Boolean {
        return deniedAttempts.any { (permission, count) ->
            count >= 2 && !granted(activity, permission) &&
                !activity.shouldShowRequestPermissionRationale(permission)
        }
    }

    private fun openAppInfo() {
        if (openingAppInfo) return
        openingAppInfo = true
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName))
        settingsLauncher.launch(intent)
    }

    private data class AccessRequirement(
        val title: String,
        val explanation: String,
        val granted: Boolean
    )

    private fun storageUsable(c: Context): Boolean = runCatching {
        val dir = c.filesDir
        if (!(dir.exists() || dir.mkdirs()) || !dir.isDirectory || !dir.canRead() || !dir.canWrite()) {
            false
        } else {
            val probe = java.io.File.createTempFile("spls_storage_check_", ".tmp", dir)
            probe.delete()
        }
    }.getOrDefault(false)

    /** Re-evaluates every required access; the summary contains only items still missing. */
    private fun requirements(c: Context): List<AccessRequirement> {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        return buildList {
            add(AccessRequirement("Foreground location",
                "Allows SPLS to obtain location updates while the app is in use.",
                granted(c, Manifest.permission.ACCESS_FINE_LOCATION)))
            if (Build.VERSION.SDK_INT >= 29) add(AccessRequirement(
                "Background location (Allow all the time)",
                "Allows SPLS to continue recording when the app is in the background or the screen is off.",
                granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)))
            if (Build.VERSION.SDK_INT >= 33) add(AccessRequirement(
                "Notifications",
                "Allows Android to show the ongoing recording notification and recording status.",
                granted(c, Manifest.permission.POST_NOTIFICATIONS)))
            add(AccessRequirement("Battery optimization exemption",
                "Helps prevent Android from restricting or stopping long-running background recording.",
                pm.isIgnoringBatteryOptimizations(c.packageName)))
            add(AccessRequirement("Recording storage",
                "The app must be able to create, read and update its private CSV recording files. This is a storage check, not an Android permission.",
                storageUsable(c)))
        }
    }

    /** Replaces the former one-permission-at-a-time failure pop-ups with one live summary. */
    private fun showMissingSummary() {
        if (!attemptActive) return
        val c = appCtx() ?: run { end(false); return }
        val missing = requirements(c).filterNot { it.granted }
        if (missing.isEmpty()) {
            advance(5)
            setupStep()
            return
        }
        val message = missing.joinToString("\n\n") {
            "✗ ${it.title}\n${it.explanation}"
        } + "\n\nSPLS Recording will remain OFF until all requirements are satisfied."
        permissionDialog?.setOnCancelListener(null)
        permissionDialog = MaterialAlertDialogBuilder(activity)
            .setTitle("SPLS Recording – Required Access")
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(if (hasBlockedRuntimePermission()) "Open App Settings" else "Open Settings") { _, _ ->
                permissionDialog = null
                if (hasBlockedRuntimePermission()) {
                    openAppInfo()
                } else {
                    stage = 0
                    asked = false
                    settingsAsked = false
                    setupStep()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> end(false) }
            .setOnCancelListener { end(false) }
            .show()
    }

    private fun setupStep() {
        val c = appCtx()
        val ctx = activity
        if (!attemptActive) return
        if (c == null || ctx == null) {
            end(false)
            return
        }
        while (true) {
            when (stage) {
                0 -> { // mocking and the system hook must be off: tell the user exactly what to switch off
                    val items = SplsGuard.blockers()
                    if (items.isEmpty()) {
                        advance(1)
                        continue
                    }
                    SplsGuard.showStopFirst(ctx, items) {
                        // Acknowledging the blocker warning must leave SPLS disabled.
                        SplsSession.setFeatureEnabled(false)
                        end(false)
                    }
                    return
                }
                1 -> { // foreground location
                    if (granted(c, Manifest.permission.ACCESS_FINE_LOCATION)) { advance(2); continue }
                    if (!asked) {
                        asked = true
                        // Returning with Back is not a permanent denial. Re-request the Android runtime prompt.
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        return
                    }
                    showMissingSummary()
                    return
                }
                2 -> { // background location is requested separately on modern Android
                    if (Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) { advance(3); continue }
                    if (!asked) {
                        asked = true
                        // Reopen the Android background-location permission screen on each retry.
                        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        return
                    }
                    showMissingSummary()
                    return
                }
                3 -> { // notifications are requested separately on Android 13+
                    if (Build.VERSION.SDK_INT < 33 || granted(c, Manifest.permission.POST_NOTIFICATIONS)) { advance(4); continue }
                    if (!asked) {
                        asked = true
                        // Retry the Android notification permission prompt instead of opening App info.
                        bgLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        return
                    }
                    showMissingSummary()
                    return
                }
                4 -> { // unrestricted battery use
                    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isIgnoringBatteryOptimizations(c.packageName)) { advance(5); continue }
                    if (!asked) {
                        asked = true
                        batteryLauncher.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName)))
                        return
                    }
                    showMissingSummary()
                    return
                }
                5 -> { // verify app-private CSV storage before allowing SPLS to turn on
                    if (storageUsable(c)) end(true) else showMissingSummary()
                    return
                }
                else -> {
                    end(true)
                    return
                }
            }
        }
    }

    // ---------------------------------------------------------------- CSV import and export, delete

    private var exportId: String? = null

    private fun safeName(name: String): String {
        val n = name.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return (if (n.isEmpty()) "spls_recorder_records" else "spls_recorder_$n") + ".csv"
    }

    fun exportLocation(loc: SplsLocation) {
        exportId = loc.id
        exportLauncher.launch(safeName(loc.name))
    }

    fun import(onChanged: () -> Unit) {
        changed = onChanged
        importLauncher.launch(arrayOf("*/*"))
    }

    private fun doExport(uri: Uri) {
        val c = appCtx() ?: return
        val id = exportId ?: return
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { SplsStore.exportTo(c, id, uri) }
            withContext(Dispatchers.Main) {
                result.onSuccess { say("Exported $it records (the file contains your real coordinates).") }
                    .onFailure { say("Export failed: ${it.message}") }
            }
        }
    }

    private fun doImport(uri: Uri) {
        val c = appCtx() ?: return
        val ctx = activity ?: return
        SplsPrompts.askName(ctx, "Name the imported record", "Imported records", SplsStore.locations(c).map { it.name }, { name ->
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { SplsStore.importAsLocation(c, uri, name) }
                withContext(Dispatchers.Main) {
                    result.onSuccess { say("Imported ${it.first} records, skipped ${it.second}.") }
                        .onFailure { say("Import failed: ${it.message}") }
                    changed?.invoke()
                }
            }
        })
    }

    fun deleteLocation(loc: SplsLocation, onChanged: () -> Unit) {
        val ctx = activity ?: return
        SplsPrompts.confirm(
            ctx, "Delete \"${loc.name}\"?",
            "The record and all of its data are removed. The Network-Linked settings are switched off if the data is no longer enough. This cannot be undone.",
            "Delete"
        ) {
            val c = appCtx()
            if (c != null) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    SplsStore.deleteLocation(c, loc.id)
                    withContext(Dispatchers.Main) {
                        say("Record deleted.")
                        onChanged()
                    }
                }
            }
        }
    }
}
