package it.bike4city.hub

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "bike4city_prefs"
    private const val KEY_WELCOMED = "user_welcomed"

    fun hasSeenWelcome(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WELCOMED, false)
    }

    fun setHasSeenWelcome(context: Context, hasSeen: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_WELCOMED, hasSeen).apply()
    }
}
