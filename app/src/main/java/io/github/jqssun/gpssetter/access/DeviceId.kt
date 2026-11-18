package io.github.jqssun.gpssetter.access

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/** Step 1 helper: the Android ID of this device. */
@SuppressLint("HardwareIds")
fun getDeviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

/** Device name such as "Samsung SM-G998B" (Build.MANUFACTURER + Build.MODEL). */
fun getDeviceName(): String {
    val maker = Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }
    val model = Build.MODEL.orEmpty().trim()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}
