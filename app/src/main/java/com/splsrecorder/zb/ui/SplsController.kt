package com.splsrecorder.zb.ui

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
import com.splsrecorder.zb.spls.SplsLocation
import com.splsrecorder.zb.spls.SplsStore
import com.splsrecorder.zb.utils.PrefManager
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
    private var finished: ((Boolean) -> Unit)? = null
    private var changed: (() -> Unit)? = null
    private var toastRef: Toast? = null

    /** Shows a short message inside the SPLS pop-up (about 5 seconds). Set by the pop-up while it is open. */
    var notifier: ((String) -> Unit)? = null

    private val permLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { setupStep() }
    private val bgLauncher = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { setupStep() }
    private val batteryLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { setupStep() }
    private val settingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { setupStep() }
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
        finished = done
        stage = 0
        asked = false
        settingsAsked = false
        setupStep()
    }

    private fun advance(next: Int) {
        stage = next
        asked = false
        settingsAsked = false
    }

    private fun end(ok: Boolean, message: String? = null) {
        if (message != null) say(message)
        val f = finished
        finished = null
        f?.invoke(ok)
    }

    private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    /** Explains the permission and opens the app's own settings page (always works, also after "don't ask again"). */
    private fun settingsDialog(title: String, message: String, failText: String) {
        val ctx: Context? = activity
        val c = appCtx()
        if (ctx == null || c == null) {
            end(false)
            return
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Open settings") { _, _ ->
                settingsLauncher.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)))
            }
            .setNegativeButton("Cancel") { _, _ -> end(false, failText) }
            .setOnCancelListener { end(false, failText) }
            .show()
    }

    private fun setupStep() {
        val c = appCtx()
        val ctx: Context? = activity
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
                    SplsGuard.showStopFirst(ctx, items)
                    end(false, "Switch off the active hook or mocking first.")
                    return
                }
                1 -> { // location (and notifications on Android 13+)
                    val need = ArrayList<String>()
                    if (!granted(c, Manifest.permission.ACCESS_FINE_LOCATION)) {
                        need.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        need.add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    if (Build.VERSION.SDK_INT >= 33 && !granted(c, Manifest.permission.POST_NOTIFICATIONS)) {
                        need.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (need.isEmpty()) {
                        advance(2)
                        continue
                    }
                    if (!asked) {
                        asked = true
                        permLauncher.launch(need.toTypedArray())
                        return
                    }
                    if (!settingsAsked) {
                        settingsAsked = true
                        settingsDialog(
                            "Allow location",
                            "SPLS needs the location permission. Tap Open settings, then Permissions, then Location, and allow it.",
                            "SPLS needs the location permission."
                        )
                        return
                    }
                    end(false, "SPLS needs the location permission.")
                    return
                }
                2 -> { // background activity: location "all the time"
                    if (Build.VERSION.SDK_INT < 29 || granted(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                        advance(3)
                        continue
                    }
                    if (!asked) {
                        asked = true
                        bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        return
                    }
                    if (!settingsAsked) {
                        settingsAsked = true
                        settingsDialog(
                            "Allow location all the time",
                            "SPLS records in the background, so location access must be set to \"Allow all the time\". " +
                                "Tap Open settings, then Permissions, then Location, and choose Allow all the time.",
                            "SPLS needs location access set to \"Allow all the time\"."
                        )
                        return
                    }
                    end(false, "SPLS needs location access set to \"Allow all the time\" so it can record in the background.")
                    return
                }
                3 -> { // unrestricted battery use
                    val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isIgnoringBatteryOptimizations(c.packageName)) {
                        advance(4)
                        continue
                    }
                    if (!asked) {
                        asked = true
                        batteryLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.packageName))
                        )
                        return
                    }
                    end(false, "SPLS needs unrestricted battery usage for this app, otherwise Android stops the recording.")
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
        return (if (n.isEmpty()) "spls_records" else "spls_$n") + ".csv"
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
        val ctx: Context = activity
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
        val ctx: Context = activity
        SplsPrompts.confirm(
            ctx, "Delete \"${loc.name}\"?",
            "The record and all of its data are removed. This cannot be undone.",
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
