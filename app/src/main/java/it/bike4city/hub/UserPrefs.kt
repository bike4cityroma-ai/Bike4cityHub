package it.bike4city.hub

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.bike4city.hub.maps.TfStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class UserPrefs(private val context: Context) {

    private val mapStyleKey = stringPreferencesKey("map_style")

    val mapStyle: Flow<TfStyle> = context.dataStore.data
        .map { preferences ->
            val styleId = preferences[mapStyleKey] ?: TfStyle.CYCLE.id
            TfStyle.values().first { it.id == styleId }
        }

    suspend fun setMapStyle(style: TfStyle) {
        context.dataStore.edit {
            it[mapStyleKey] = style.id
        }
    }
}