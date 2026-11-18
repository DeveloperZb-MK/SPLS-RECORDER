package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.splsrecorder.zb.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val UPDATE_CHECK = "update_check_enabled"
    private const val LAST_NOTIFIED_UPDATE = "last_notified_update"


    // The recorder reads and writes its own settings in-process. Keep the existing
    // preference file name so users retain their saved SPLS records and configuration.
    private val pref: SharedPreferences by lazy {
        val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
        gsApp.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,2)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    var isUpdateCheckEnabled: Boolean
        get() = pref.getBoolean(UPDATE_CHECK, true)
        set(value) = pref.edit().putBoolean(UPDATE_CHECK, value).apply()

    var lastNotifiedUpdate: String?
        get() = pref.getString(LAST_NOTIFIED_UPDATE, null)
        set(value) = pref.edit().putString(LAST_NOTIFIED_UPDATE, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    /** Raw string value of one of the value settings (null = not set). */
    fun getRaw(key: String): String? = pref.getString(key, null)

    fun setRaw(key: String, value: String?) {
        val e = pref.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }

    fun update(start:Boolean, la: Double, ln: Double) {
        runInBackground {
            val prefEditor = pref.edit()
            prefEditor.putFloat(LATITUDE, la.toFloat())
            prefEditor.putFloat(LONGITUDE, ln.toFloat())
            prefEditor.putBoolean(START, start)
            prefEditor.apply()
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }

}