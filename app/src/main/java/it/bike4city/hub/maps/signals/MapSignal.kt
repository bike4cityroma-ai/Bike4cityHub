package it.bike4city.hub.maps.signals

data class MapSignal(
    val id: String = "",
    val kind: String = "poi",         // "poi" | "critical"
    val category: String = "",        // es: "fontanella", "buche", "parcheggio_selvaggio"
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val title: String = "",           // breve
    val description: String = "",     // breve
    val link: String = "",            // opzionale: sito web o info extra
    val status: String = "pending",   // "pending" (in attesa di admin) | "active" (pubblico) | "resolved" | "expired"
    val createdBy: String = "",
    val routeId: String? = null,      // se creato durante una registrazione, punta all'ID del percorso
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null       // null = non scade
)
