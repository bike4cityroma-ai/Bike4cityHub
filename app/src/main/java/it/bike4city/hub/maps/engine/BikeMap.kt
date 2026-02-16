package it.bike4city.hub.maps.engine

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import it.bike4city.hub.maps.ThunderforestMapLibre
import it.bike4city.hub.maps.signals.MapSignal
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

enum class MapEngine {
    MAPLIBRE,
    OSMAND
}

/**
 * Facciata unica della mappa per l'Hub.
 * ViewRouteScreen deve chiamare SOLO questa, mai direttamente i motori.
 */
@Composable
fun BikeMap(
    modifier: Modifier = Modifier,
    engine: MapEngine = MapEngine.MAPLIBRE,
    points: List<LatLng> = emptyList(),
    signals: List<MapSignal> = emptyList(),
    startPoint: LatLng? = null,
    finishPoint: LatLng? = null,
    progressPoint: LatLng? = null,
    initialCenter: LatLng = LatLng(41.9028, 12.4964),
    initialBounds: LatLngBounds? = null,
    showMyLocation: Boolean = false,
    followMyLocation: Boolean = false
) {
    when (engine) {
        MapEngine.MAPLIBRE -> ThunderforestMapLibre(
            modifier = modifier,
            points = points,
            signals = signals,
            startPoint = startPoint,
            finishPoint = finishPoint,
            progressPoint = progressPoint,
            initialCenter = initialCenter,
            initialBounds = initialBounds,
            showMyLocation = showMyLocation,
            followMyLocation = followMyLocation
        )

        MapEngine.OSMAND -> OsmAndBikeMapPlaceholder(
            modifier = modifier
        )
    }
}

/**
 * Placeholder temporaneo: serve solo per avere lo "slot" OsmAnd già pronto
 * senza cambiare dipendenze o rompere build.
 */
@Composable
private fun OsmAndBikeMapPlaceholder(
    modifier: Modifier = Modifier
) {
    // Niente Text qui per non introdurre nuove import/material.
    // Se vuoi una cosa visibile, dimmelo e lo facciamo bene con Material3.
}