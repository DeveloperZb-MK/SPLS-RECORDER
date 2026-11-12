package com.splsrecorder.zb.utils

import android.content.Context
import android.content.SharedPreferences
import com.splsrecorder.zb.App

/** Small key-value store. This app never mocks a location, so the mock flags are always off. */
object PrefManager {

    private val pref: SharedPreferences by lazy {
        App.instance.getSharedPreferences("${App.instance.packageName}_prefs", Context.MODE_PRIVATE)
    }

    val isStarted: Boolean get() = false
    val isSystemHooked: Boolean get() = false

    fun getRaw(key: String): String? = pref.getString(key, null)

    fun setRaw(key: String, value: String?) {
        val e = pref.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }

    var mapType: Int
        get() = pref.getInt(PrefKeys.MAP_TYPE, 1)
        set(v) { pref.edit().putInt(PrefKeys.MAP_TYPE, v).apply() }
}
