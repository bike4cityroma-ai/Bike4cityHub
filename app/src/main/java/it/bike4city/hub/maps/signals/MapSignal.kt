package it.bike4city.hub.maps.signals

import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Rappresenta una segnalazione sulla mappa (POI o Criticità).
 * Utilizziamo 'var' e valori predefiniti per permettere a Firebase Firestore
 * di serializzare/deserializzare correttamente l'oggetto anche dopo l'offuscamento R8.
 */
@IgnoreExtraProperties
data class MapSignal(
    var id: String = "",
    var kind: String = "poi",         // "poi" | "critical"
    var category: String = "",        // es: "fontanella", "buche", "parcheggio_selvaggio"
    var lat: Double = 0.0,
    var lng: Double = 0.0,
    var title: String = "",           // breve
    var description: String = "",     // breve
    var link: String = "",            // opzionale: sito web o info extra
    var status: String = "pending",   // "pending" | "active" | "resolved" | "expired"
    var createdBy: String = "",
    var routeId: String? = null,      // se creato durante una registrazione
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var expiresAt: Long? = null       // null = non scade
)
