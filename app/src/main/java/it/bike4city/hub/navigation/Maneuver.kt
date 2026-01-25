package it.bike4city.hub.navigation

import org.maplibre.android.geometry.LatLng

enum class TurnType {
    STRAIGHT,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    UTURN
}

data class Maneuver(
    val index: Int,          // indice nella lista points
    val at: LatLng,          // coordinata del punto manovra
    val type: TurnType,
    val deltaDeg: Double     // cambio bearing (segno: + destra, - sinistra)
) {
    fun italianInstruction(): String = when (type) {
        TurnType.STRAIGHT -> "prosegui dritto"
        TurnType.SLIGHT_LEFT -> "tieni la sinistra"
        TurnType.LEFT -> "gira a sinistra"
        TurnType.SHARP_LEFT -> "svolta decisa a sinistra"
        TurnType.SLIGHT_RIGHT -> "tieni la destra"
        TurnType.RIGHT -> "gira a destra"
        TurnType.SHARP_RIGHT -> "svolta decisa a destra"
        TurnType.UTURN -> "fai inversione a U"
    }
}
