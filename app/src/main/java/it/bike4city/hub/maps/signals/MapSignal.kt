package it.bike4city.hub.maps.signals

import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Rappresenta una segnalazione sulla mappa (POI o Criticità).
 * Usiamo 'var' e valori predefiniti per la piena compatibilità con Firestore.
 */
@IgnoreExtraProperties
data class MapSignal(
    var id: String = "",
    var kind: String = "poi",
    var category: String = "",
    var lat: Double = 0.0,
    var lng: Double = 0.0,
    var geohash: String = "",
    var title: String = "",
    var description: String = "",
    var link: String = "",
    var status: String = "pending",
    var createdBy: String = "",
    var routeId: String? = null,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var expiresAt: Long? = null
)
