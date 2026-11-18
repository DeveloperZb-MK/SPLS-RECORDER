package io.github.jqssun.gpssetter.access

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.textfield.TextInputLayout
import com.splsrecorder.zb.R
import com.splsrecorder.zb.databinding.ActivityAccessBinding
import io.github.jqssun.gpssetter.ui.MapActivity
import com.splsrecorder.zb.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Launcher screen. Shows, one at a time:
 * checking (splash), request form, waiting for approval, declined / no connection.
 */
class AccessActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessBinding
    private var pollJob: Job? = null
    private var messageAction: (() -> Unit)? = null
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Hidden data collected in the background (never shown in the UI)
    @Volatile private var isLocationFetched = false
    private var realAddress = ""
    private var locationJob: Job? = null
    private var locationPermissionAsked = false
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) fetchRealLocation()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupErrorClearing()
        binding.buttonSubmit.setOnClickListener { submitRequest() }
        binding.buttonCancelRequest.setOnClickListener {
            pollJob?.cancel()
            AccessStore.clearRequest(this)
            showPanel(Panel.FORM)
        }
        binding.buttonMessage.setOnClickListener { messageAction?.invoke() }

        if (intent.getBooleanExtra(EXTRA_EXPIRED, false)) {
            Toast.makeText(this, R.string.access_session_ended, Toast.LENGTH_LONG).show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        startLaunchFlow()
    }

    // ---------------- Launch logic ----------------

    private fun startLaunchFlow() {
        if (AccessStore.getToken(this) == null) {
            attemptRestore()
            return
        }
        showPanel(Panel.SPLASH)
        lifecycleScope.launch {
            when (AccessVerifier.verify(this@AccessActivity)) {
                VerifyResult.Valid -> {
                    AccessWorker.schedule(this@AccessActivity)
                    openMain()
                }
                VerifyResult.Rejected -> {
                    AccessGuard.revoke(this@AccessActivity)
                    showPanel(Panel.FORM)
                }
                VerifyResult.NoToken -> showFormOrWaiting()
                VerifyResult.Error -> showMessage(
                    R.string.access_offline_title,
                    R.string.access_offline_text,
                    R.string.access_retry
                ) { startLaunchFlow() }
            }
        }
    }

    /** No saved token: ask the server whether this device already has an approved session. */
    private fun attemptRestore() {
        showPanel(Panel.SPLASH)
        lifecycleScope.launch {
            val token = AccessVerifier.restoreSession(this@AccessActivity)
            if (token != null) {
                AccessStore.saveToken(this@AccessActivity, token)
                AccessStore.clearRequest(this@AccessActivity)
                AccessWorker.schedule(this@AccessActivity)
                openMain()
            } else {
                showFormOrWaiting()
            }
        }
    }

    private fun showFormOrWaiting() {
        if (AccessStore.getRequestId(this) != -1) showWaiting() else showPanel(Panel.FORM)
    }

    private fun openMain() {
        startActivity(Intent(this, MapActivity::class.java))
        finish()
    }

    // ---------------- Request form ----------------

    /** Removes a field's error as soon as the user edits that field. */
    private fun setupErrorClearing() {
        listOf(
            binding.layoutName to binding.inputName,
            binding.layoutEmail to binding.inputEmail,
            binding.layoutMobile to binding.inputMobile,
            binding.layoutCity to binding.inputCity,
            binding.layoutCountry to binding.inputCountry
        ).forEach { (layout, input) ->
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (layout.error != null) layout.error = null
                }
            })
        }
    }

    private fun submitRequest() {
        val name = binding.inputName.text.toString().trim()
        val email = binding.inputEmail.text.toString().trim()
        val mobile = binding.inputMobile.text.toString().trim()
        val city = binding.inputCity.text.toString().trim()
        val country = binding.inputCountry.text.toString().trim()

        // Step 3: validate every field; errors are shown under the field (red border + message)
        var ok = validate(binding.layoutName, name, R.string.access_invalid_name) { it.length >= 3 }
        ok = validate(binding.layoutEmail, email, R.string.access_invalid_email) {
            Patterns.EMAIL_ADDRESS.matcher(it).matches()
        } && ok
        ok = validate(binding.layoutMobile, mobile, R.string.access_invalid_mobile) {
            MOBILE_REGEX.matches(cleanMobile(it))
        } && ok
        ok = validate(binding.layoutCity, city) && ok
        ok = validate(binding.layoutCountry, country) && ok
        if (!ok) return

        binding.buttonSubmit.isEnabled = false
        lifecycleScope.launch {
            try {
                // Step 4: location is mandatory. If the background fetch is still running, wait for it.
                locationJob?.join()
                if (!isLocationFetched) {
                    toast(R.string.access_location_required)
                    ensureLocationFetched() // retry (re-asks permission only if it was never granted)
                    return@launch
                }

                val response = AccessClient.api.requestAccess(
                    RequestAccessBody(
                        name, email, cleanMobile(mobile), city, country,
                        getDeviceId(this@AccessActivity),
                        getDeviceName(),
                        realAddress
                    )
                )
                val body = response.parsedBody()
                val id = body?.requestId
                if (body?.success == true && id != null) {
                    AccessStore.saveRequestId(this@AccessActivity, id)
                    showWaiting()
                } else {
                    val text = body?.error ?: body?.message ?: getString(R.string.access_send_failed)
                    Toast.makeText(this@AccessActivity, text, Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(this@AccessActivity, R.string.access_no_connection, Toast.LENGTH_LONG).show()
            } finally {
                binding.buttonSubmit.isEnabled = true
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    /** Empty -> "Required"; filled but invalid -> the given message. Returns true when valid. */
    private fun validate(
        layout: TextInputLayout,
        value: String,
        invalidRes: Int? = null,
        isValid: (String) -> Boolean = { true }
    ): Boolean {
        val message = when {
            value.isEmpty() -> getString(R.string.access_required)
            invalidRes != null && !isValid(value) -> getString(invalidRes)
            else -> null
        }
        layout.error = message
        return message == null
    }

    /** Removes spaces, dashes and brackets: "+44 (7700) 900-123" -> "+447700900123". */
    private fun cleanMobile(value: String) = value.replace(Regex("[\\s\\-()]"), "")

    // ---------------- Background location (invisible) ----------------

    /** Called whenever the form becomes visible. */
    private fun ensureLocationFetched() {
        if (isLocationFetched || locationJob?.isActive == true) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        when {
            granted -> fetchRealLocation()
            !locationPermissionAsked -> {
                locationPermissionAsked = true
                locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            // Permission was denied: stays false, Submit will show the error toast
        }
    }

    private fun fetchRealLocation() {
        locationJob?.cancel()
        isLocationFetched = false
        locationJob = lifecycleScope.launch {
            try {
                val location = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { currentLocation() } ?: return@launch
                val address = reverseGeocode(location) ?: return@launch
                realAddress = address
                isLocationFetched = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Location fetch failed")
            }
        }
    }

    /** Fused provider first (Google / microG); if that fails or times out, plain Android LocationManager. */
    private suspend fun currentLocation(): Location? =
        withTimeoutOrNull(FUSED_TIMEOUT_MS) { fusedLocation() } ?: systemLocation()

    @SuppressLint("MissingPermission") // checked in ensureLocationFetched()
    private suspend fun fusedLocation(): Location? = try {
        val client = LocationServices.getFusedLocationProviderClient(this)
        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        fresh ?: suspendCancellableCoroutine { cont ->
            client.lastLocation
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Timber.w(e, "Fused location failed, falling back to LocationManager")
        null
    }

    /** Safety net that does not need Google Play services or microG. */
    @SuppressLint("MissingPermission") // checked in ensureLocationFetched()
    private suspend fun systemLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (Build.VERSION.SDK_INT >= 30) {
            for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                val fresh = try {
                    if (!lm.isProviderEnabled(provider)) null
                    else withTimeoutOrNull(SYSTEM_FIX_TIMEOUT_MS) {
                        suspendCancellableCoroutine<Location?> { cont ->
                            val signal = CancellationSignal()
                            cont.invokeOnCancellation { signal.cancel() }
                            lm.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(this@AccessActivity)) {
                                if (cont.isActive) cont.resume(it)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "LocationManager.getCurrentLocation failed for %s", provider)
                    null
                }
                if (fresh != null) return fresh
            }
        }
        // Last resort: newest last-known fix from any provider
        return listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
    }

    /**
     * Full address line, e.g. "Hazara town Quetta, Balochistan, Pakistan".
     * Native Geocoder first; if it is missing, fails or returns nothing, falls back to Nominatim (OpenStreetMap).
     */
    private suspend fun reverseGeocode(location: Location): String? =
        withTimeoutOrNull(GEOCODER_TIMEOUT_MS) { nativeGeocode(location) }
            ?: nominatimGeocode(location)

    private suspend fun nativeGeocode(location: Location): String? {
        if (!Geocoder.isPresent()) return null
        return try {
            val geocoder = Geocoder(this, Locale.getDefault())
            val line: String? = if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull()?.getAddressLine(0))
                            }
                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(location.latitude, location.longitude, 1)
                        ?.firstOrNull()?.getAddressLine(0)
                }
            }
            line?.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Native geocoder failed")
            null
        }
    }

    /** Fallback for devices without a geocoding backend (e.g. microG without one configured). */
    private suspend fun nominatimGeocode(location: Location): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(
                "https://nominatim.openstreetmap.org/reverse?format=json&zoom=18" +
                    "&lat=${location.latitude}&lon=${location.longitude}"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                // Nominatim's usage policy requires an identifying User-Agent
                setRequestProperty("User-Agent", "khpalGps/" + BuildConfig.TAG_NAME + " (com.khpalgps.zb)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(json).optString("display_name").takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Nominatim geocoding failed")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------- Waiting + polling ----------------

    private fun showWaiting() {
        showPanel(Panel.WAITING)
        startPolling()
    }

    /** Polls every 5 seconds while the screen is visible. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val requestId = AccessStore.getRequestId(this@AccessActivity)
                if (requestId == -1) return@repeatOnLifecycle
                while (true) {
                    val body = try {
                        AccessClient.api.checkStatus(
                            CheckStatusBody(requestId, getDeviceId(this@AccessActivity))
                        ).parsedBody()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }

                    when (body?.status) {
                        "approved" -> {
                            val token = body?.token
                            if (!token.isNullOrBlank()) {
                                pollJob?.cancel()
                                AccessStore.saveToken(this@AccessActivity, token)
                                AccessStore.clearRequest(this@AccessActivity)
                                AccessWorker.schedule(this@AccessActivity)
                                openMain()
                                return@repeatOnLifecycle
                            }
                        }
                        "declined" -> {
                            pollJob?.cancel()
                            showMessage(
                                R.string.access_declined_title,
                                R.string.access_declined_text,
                                R.string.access_new_request
                            ) {
                                AccessStore.clearRequest(this@AccessActivity)
                                showPanel(Panel.FORM)
                            }
                            return@repeatOnLifecycle
                        }
                        else -> Unit // pending, unclear answer or network error: try again
                    }
                    delay(5_000)
                }
            }
        }
    }

    // ---------------- Panels ----------------

    private enum class Panel { SPLASH, FORM, WAITING, MESSAGE }

    private fun showPanel(panel: Panel) {
        binding.panelSplash.visibility = if (panel == Panel.SPLASH) View.VISIBLE else View.GONE
        binding.panelForm.visibility = if (panel == Panel.FORM) View.VISIBLE else View.GONE
        if (panel == Panel.FORM) ensureLocationFetched()
        binding.panelWaiting.visibility = if (panel == Panel.WAITING) View.VISIBLE else View.GONE
        binding.panelMessage.visibility = if (panel == Panel.MESSAGE) View.VISIBLE else View.GONE
    }

    private fun showMessage(title: Int, text: Int, button: Int, action: () -> Unit) {
        binding.messageTitle.setText(title)
        binding.messageText.setText(text)
        binding.buttonMessage.setText(button)
        messageAction = action
        showPanel(Panel.MESSAGE)
    }

    companion object {
        const val EXTRA_EXPIRED = "access_expired"
        private const val LOCATION_TIMEOUT_MS = 35_000L
        private const val FUSED_TIMEOUT_MS = 10_000L
        private const val SYSTEM_FIX_TIMEOUT_MS = 6_000L
        private const val GEOCODER_TIMEOUT_MS = 10_000L
        // Optional "+", then 7-15 digits (E.164 allows at most 15), any country
        private val MOBILE_REGEX = Regex("^\\+?\\d{7,15}$")
    }
}
