package io.github.jqssun.gpssetter.access

import android.content.Context
import android.content.SharedPreferences

/**
 * Private SharedPreferences for the access system.
 * Kept separate from the recorder settings file so access credentials remain isolated.
 */
object AccessStore {
    private const val FILE = "khpal_access_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_REQUEST_ID = "request_id"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun saveToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    /** Returns -1 when there is no saved request. */
    fun getRequestId(context: Context): Int =
        prefs(context).getInt(KEY_REQUEST_ID, -1)

    fun saveRequestId(context: Context, id: Int) {
        prefs(context).edit().putInt(KEY_REQUEST_ID, id).apply()
    }

    fun clearRequest(context: Context) {
        prefs(context).edit().remove(KEY_REQUEST_ID).apply()
    }

    /** Clears the token and the request id (only this access file, not the app settings). */
    fun clearAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
