package it.bike4city.hub.data

import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/* -----------------------------
 * TRACK POINT - Per pendenze e tipi strada
 * ----------------------------- */
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val ele: Double = 0.0,
    val roadType: String? = null, // es. "asphalt", "unpaved", "cycleway"
    val slope: Double = 0.0       // pendenza in percentuale
)

/* -----------------------------
 * BACHECA
 * ----------------------------- */
@IgnoreExtraProperties
data class BoardMessage(
    val title: String = "",
    val body: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val status: String = "published",
    @ServerTimestamp val createdAt: Date? = null,
    @ServerTimestamp val updatedAt: Date? = null
)

/* -----------------------------
 * PROFILO (NUOVO) - ALLINEATO AL WEB
 * users/{uid}
 * ----------------------------- */
@IgnoreExtraProperties
data class Membership(
    val issuedAt: Date? = null,
    val number: String = "",
    val paymentStatus: String = "unpaid", // unpaid / paid / ...
    val status: String = "pending",       // pending / active / expired / ...
    val tier: String = "standard",
    val validFrom: String = "",
    val validUntil: String = "",
    val validUntilTs: Date? = null
)

@IgnoreExtraProperties
data class UserProfileWeb(
    val address: String = "",
    val approvedAt: Date? = null,
    val birthDate: String = "",
    val city: String = "",
    val createdAt: Date? = null,

    val displayName: String = "",
    val email: String = "",
    val firstName: String = "",
    val fiscalCode: String = "",
    val lastName: String = "",

    val membership: Membership = Membership(),

    // se sul web li tieni ancora “flat”, li lasciamo per compatibilità
    val membershipNumber: String = "",
    val membershipValidUntil: String = "",
    val membershipValidUntilTs: Date? = null,

    val newsletterOptIn: Boolean = false,
    val phone: String = "",
    val privacyAccepted: Boolean = false,

    val role: String = "member",
    val source: String = "",
    val status: String = "pending",
    val updatedAt: Date? = null,
    val zip: String = ""
)

/* -----------------------------
 * PROFILO (VECCHIO) - LEGACY
 * lo teniamo per non romper subito UI/repo vecchi.
 * ----------------------------- */
@IgnoreExtraProperties
data class UserProfileLegacy(
    val attivo: Boolean = false,
    val city: String = "",
    val cognome: String = "",
    val createdAt: Date? = null,          // FIX: era String -> Date?
    val codfiscal: String = "",
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
    val updatedAt: Date? = null,          // FIX: era String -> Date?
    val cardImageUrl: String = ""         // mantenuto per upload card
)

/* -----------------------------
 * PERCORSI / TRACCE - ALLINEATO A bike4city-social-hub
 * ----------------------------- */
@IgnoreExtraProperties
data class Route(
    var title: String = "",
    var description: String = "",
    var gpxText: String = "",

    var distanceKm: Double? = 0.0,
    var ascentM: Double? = 0.0,
    var descentM: Double? = 0.0,
    
    var bbox: List<String>? = null,
    var poiCount: Int = 0,
    var status: String = "",            // "public", "accepted", "recorded", ecc.
    var b4cCategory: String? = null,    // "BIKE4CITY" o "COMMUNITY"

    var createdAt: Date? = null,
    @ServerTimestamp var updatedAt: Date? = null,
    var publishedAt: Date? = null,

    var ownerUid: String? = null,
    var createdByUid: String? = null,
    
    var suggestedByUid: String? = null, 
    var suggestedByName: String? = null,
    var suggestedByEmail: String? = null,
    var publishedMemberRouteId: String? = null,

    var source: String? = null,
    var routeType: String? = null,
    var surfaceType: String? = null,
    var surfaceStats: Any? = null,
    
    var difficulty: String? = null,
    var isOfficial: Boolean = false,
    var routeMode: String? = null
) {
    @get:Exclude var id: String = ""
}
