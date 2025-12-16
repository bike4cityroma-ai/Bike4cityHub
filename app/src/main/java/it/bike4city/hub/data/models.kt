package it.bike4city.hub.data

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.Transient

@IgnoreExtraProperties
data class BoardMessage(
    val title: String = "",
    val body: String = "",
    val authorId: String = "",
    val authorName: String = "",
    @ServerTimestamp
    val createdAt: Date? = null
)

@IgnoreExtraProperties
data class UserProfile(
    val attivo: Boolean = true,
    val city: String = "",
    val cognome: String = "",
    val createdAt: String = "",
    val email: String = "",
    val name: String = "",
    val note: String = "",
    val privacy: Boolean = false,
    val role: String = "user",
    val telefono: String = "",
    val tessera: String = "",
    val tesseraScadenza: String = "",
    val tesseraStato: String = "attiva",
    val uid: String = "",
    val updatedAt: String = "",
    val cardImageUrl: String = "" // Maintained from original app code for card upload functionality
)

@IgnoreExtraProperties
data class Route(
    // già presenti
    var title: String = "",
    var description: String = "",
    var gpx: String = "",

    // già presente
    var distanceKm: Double? = 0.0,

    // meglio così: default neutro, e in UI lo “traduco”
    var difficulty: String? = null,

    // --- nuovi campi dal planner / Firestore ---
    var ascent: Double? = null,          // metri
    var descent: Double? = null,         // metri

    var createdAt: Date? = null,         // da timestamp Firestore
    var createdBy: String? = null,
    var createdByEmail: String? = null,

    var isOfficial: Boolean = false,

    var routeMode: String? = null,       // "loop"
    var routeType: String? = null,       // "urban_tour"
    var source: String? = null,          // "planned-web"

    var surfaceType: String? = null,     // "road"
    var surfaceStats: Any? = null,       // lo lasciamo generico finché non lo usi

    // importantissimo se vuoi vedere i tuoi percorsi con la query attuale:
    var ownerUid: String? = null,
) {
    // l'id lo stai già impostando da FirebaseRepo: it.id = d.id
    @get:Exclude var id: String = ""
}
