@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package it.bike4city.hub.maps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import it.bike4city.hub.R
import it.bike4city.hub.UserPrefs
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.style.expressions.Expression.*

enum class TfStyle(val id: String, val label: String) {
    CYCLE("cycle", "Ciclabili"),
    OUTDOORS("outdoors", "Outdoors"),
    ATLAS("atlas", "Mappa Città")
}

@Composable
fun ThunderforestMapLibre(
    modifier: Modifier = Modifier,
    points: List<LatLng> = emptyList(),
    startPoint: LatLng? = null,
    finishPoint: LatLng? = null,
    progressPoint: LatLng? = null,
    initialCenter: LatLng = LatLng(41.9028, 12.4964),
    initialBounds: LatLngBounds? = null,
    showMyLocation: Boolean = false,
    followMyLocation: Boolean = false
) {
    val ctx = LocalContext.current
    val tfKey = stringResource(R.string.thunderforest_api_key)
    val userPrefs = remember { UserPrefs(ctx) }
    val style by userPrefs.mapStyle.collectAsState(initial = TfStyle.CYCLE)

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        MapLibre.getInstance(ctx)
    }

    var sheetOpen by remember { mutableStateOf(false) }

    val mapView = rememberMapViewWithLifecycle()

    Box(modifier) {

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.uiSettings.isLogoEnabled = false
                        map.uiSettings.isAttributionEnabled = false
                        
                        map.setStyle(buildThunderforestStyle(tfKey, style.id)) { loadedStyle ->
                            addIconsToStyle(loadedStyle)
                            ensureTrackLayer(loadedStyle)
                            updateTrackWithSlopes(loadedStyle, points)
                            updateMarkers(loadedStyle, startPoint, finishPoint, progressPoint)
                            
                            if (initialBounds != null) {
                                map.moveCamera(CameraUpdateFactory.newLatLngBounds(initialBounds, 100))
                            } else if (points.isEmpty()) {
                                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                                    .target(initialCenter)
                                    .zoom(13.5)
                                    .build()
                            }
                        }
                    }
                }
            }
        )

        AttributionBar(
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp)
        )

        FloatingActionButton(
            onClick = { sheetOpen = true },
            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = "Stile mappa")
        }

        if (sheetOpen) {
            ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Stile mappa", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(10.dp))

                    TfOption(
                        title = "Mappa Città (Atlas)",
                        desc = "Ideale per orientarsi in città, con POI",
                        selected = style == TfStyle.ATLAS
                    ) {
                        scope.launch {
                            userPrefs.setMapStyle(TfStyle.ATLAS)
                            sheetOpen = false
                        }
                    }

                    TfOption(
                        title = "Ciclabili (Cycle)",
                        desc = "Evidenzia le piste ciclabili e i percorsi",
                        selected = style == TfStyle.CYCLE
                    ) {
                        scope.launch {
                            userPrefs.setMapStyle(TfStyle.CYCLE)
                            sheetOpen = false
                        }
                    }

                    TfOption(
                        title = "Outdoors",
                        desc = "Più adatto a escursioni e sentieri",
                        selected = style == TfStyle.OUTDOORS
                    ) {
                        scope.launch {
                            userPrefs.setMapStyle(TfStyle.OUTDOORS)
                            sheetOpen = false
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    LaunchedEffect(mapView, showMyLocation) {
        if (!showMyLocation) return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                val locationComponent = map.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(ctx, style).build()
                )
                locationComponent.isLocationComponentEnabled = true
                locationComponent.renderMode = RenderMode.COMPASS
            }
        }
    }

    LaunchedEffect(mapView, followMyLocation) {
        mapView.getMapAsync { map ->
            if (map.style?.isFullyLoaded == true && map.locationComponent.isLocationComponentActivated) {
                if (followMyLocation) {
                    val cameraUpdate = org.maplibre.android.camera.CameraPosition.Builder()
                        .zoom(16.5)
                        .tilt(45.0)
                        .build()
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraUpdate), 1000)
                    map.locationComponent.cameraMode = CameraMode.TRACKING_GPS
                } else {
                    map.locationComponent.cameraMode = CameraMode.NONE
                    map.animateCamera(CameraUpdateFactory.tiltTo(0.0))
                }
            }
        }
    }

    LaunchedEffect(style) {
        reloadStyle(mapView, tfKey, style, points, startPoint, finishPoint, progressPoint)
    }

    LaunchedEffect(points) {
        mapView.getMapAsync { map ->
            map.style?.let { updateTrackWithSlopes(it, points) }
        }
    }

    LaunchedEffect(startPoint, finishPoint, progressPoint) {
        mapView.getMapAsync { map ->
            map.style?.let { updateMarkers(it, startPoint, finishPoint, progressPoint) }
        }
    }
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapView(context).apply { id = R.id.map_view }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(Bundle())
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return mapView
}

private fun reloadStyle(
    mapView: MapView,
    tfKey: String,
    style: TfStyle,
    points: List<LatLng>,
    startPoint: LatLng?,
    finishPoint: LatLng?,
    progressPoint: LatLng?
) {
    mapView.getMapAsync { map ->
        map.setStyle(buildThunderforestStyle(tfKey, style.id), Style.OnStyleLoaded {
            addIconsToStyle(it)
            ensureTrackLayer(it)
            updateTrackWithSlopes(it, points)
            updateMarkers(it, startPoint, finishPoint, progressPoint)
        })
    }
}

private fun buildThunderforestStyle(tfKey: String, tfStyleId: String): Style.Builder {
    val tileUrl = "https://tile.thunderforest.com/$tfStyleId/{z}/{x}/{y}.png?apikey=$tfKey"
    val tileSet = TileSet("2.1.0", tileUrl)
    val rasterSource = RasterSource("tf-source", tileSet, 256)
    val rasterLayer = RasterLayer("tf-layer", "tf-source")

    val minimalStyleJson = """
        {
          "version": 8,
          "sources": {},
          "layers": []
        }
    """.trimIndent()

    return Style.Builder()
        .fromJson(minimalStyleJson)
        .withSource(rasterSource)
        .withLayer(rasterLayer)
}

private fun addIconsToStyle(style: Style) {
    style.addImage("arrow-icon", createArrowBitmap())
    style.addImage("flag-start", createFlagBitmap(android.graphics.Color.GREEN))
    style.addImage("flag-finish", createFlagBitmap(android.graphics.Color.RED))
}

private fun createArrowBitmap(): Bitmap {
    val size = 48 // Leggermente più grande per contenere lo stroke
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    
    val path = Path()
    path.moveTo(size * 0.5f, size * 0.15f)
    path.lineTo(size * 0.15f, size * 0.85f)
    path.lineTo(size * 0.85f, size * 0.85f)
    path.close()

    // Stroke scuro per contrasto
    p.color = android.graphics.Color.BLACK
    p.style = Paint.Style.STROKE
    p.strokeWidth = 5f
    c.drawPath(path, p)

    // Riempimento bianco
    p.color = android.graphics.Color.WHITE
    p.style = Paint.Style.FILL
    c.drawPath(path, p)
    
    return b
}

private fun createFlagBitmap(color: Int): Bitmap {
    val size = 64
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    
    // Stick
    p.color = android.graphics.Color.BLACK
    c.drawRect(size * 0.48f, size * 0.2f, size * 0.52f, size * 0.9f, p)
    
    // Flag
    p.color = color
    val path = Path()
    path.moveTo(size * 0.52f, size * 0.2f)
    path.lineTo(size * 0.9f, size * 0.35f)
    path.lineTo(size * 0.52f, size * 0.5f)
    path.close()
    c.drawPath(path, p)
    
    return b
}

private fun ensureTrackLayer(style: Style) {
    if (style.getSource("track-source") == null) {
        style.addSource(GeoJsonSource("track-source", FeatureCollection.fromFeatures(arrayOf())))
    }
    if (style.getSource("track-arrows-source") == null) {
        style.addSource(GeoJsonSource("track-arrows-source", FeatureCollection.fromFeatures(arrayOf())))
    }
    
    if (style.getLayer("track-layer") == null) {
        val layer = LineLayer("track-layer", "track-source").withProperties(
            lineWidth(7f),
            lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineColor(
                step(
                    get("slope"),
                    color(Color(0xFF2563EB).toArgb()), // Default Blue
                    stop(-3.0, color(Color(0xFF3B82F6).toArgb())), // Discesa (Light Blue)
                    stop(1.5, color(Color(0xFF22C55E).toArgb())),  // Piano (Green)
                    stop(5.0, color(Color(0xFFFACC15).toArgb())),  // Pendenza media (Yellow)
                    stop(10.0, color(Color(0xFFEF4444).toArgb()))  // Salita ripida (Red)
                )
            )
        )
        style.addLayer(layer)
    }

    if (style.getLayer("track-arrows") == null) {
        val layer = SymbolLayer("track-arrows", "track-arrows-source").withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            symbolSpacing(120f), // Frecce un po' più frequenti
            iconImage("arrow-icon"),
            iconSize(0.55f), // Dimensione aumentata
            iconRotate(90f),
            iconAllowOverlap(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
        )
        style.addLayer(layer)
    }

    if (style.getSource("markers-source") == null) {
        style.addSource(GeoJsonSource("markers-source", FeatureCollection.fromFeatures(arrayOf())))
    }
    if (style.getLayer("markers-layer") == null) {
        val layer = SymbolLayer("markers-layer", "markers-source").withProperties(
            iconImage(get("icon")),
            iconSize(1.0f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconOffset(arrayOf(0f, -25f))
        )
        style.addLayer(layer)
    }

    if (style.getSource("progress-source") == null) {
        style.addSource(GeoJsonSource("progress-source", FeatureCollection.fromFeatures(arrayOf())))
    }
    if (style.getLayer("progress-layer") == null) {
        val layer = CircleLayer("progress-layer", "progress-source").withProperties(
            circleRadius(11f),
            circleColor(Color(0xFFFACC15).toArgb()),
            circleStrokeColor(Color.Black.toArgb()),
            circleStrokeWidth(2.5f)
        )
        style.addLayer(layer)
    }
}

private fun updateTrackWithSlopes(style: Style, points: List<LatLng>) {
    val src = style.getSourceAs<GeoJsonSource>("track-source") ?: return
    val arrowSrc = style.getSourceAs<GeoJsonSource>("track-arrows-source") ?: return
    
    if (points.size < 2) {
        src.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        arrowSrc.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        return
    }

    val features = mutableListOf<Feature>()
    for (i in 0 until points.size - 1) {
        val p1 = points[i]
        val p2 = points[i+1]
        
        val line = LineString.fromLngLats(listOf(
            Point.fromLngLat(p1.longitude, p1.latitude),
            Point.fromLngLat(p2.longitude, p2.latitude)
        ))
        
        val feature = Feature.fromGeometry(line)
        val slope = calculateSlope(p1, p2)
        feature.addNumberProperty("slope", slope)
        features.add(feature)
    }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
    
    val fullLine = LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })
    arrowSrc.setGeoJson(Feature.fromGeometry(fullLine))
}

private fun calculateSlope(p1: LatLng, p2: LatLng): Double {
    val res = FloatArray(1)
    android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
    val distance = res[0].toDouble()
    if (distance < 2.0) return 0.0
    val eleDiff = p2.altitude - p1.altitude
    return (eleDiff / distance) * 100.0
}

private fun updateMarkers(style: Style, 
                          start: LatLng?, 
                          finish: LatLng?, 
                          progress: LatLng?) {
    val markerSource = style.getSourceAs<GeoJsonSource>("markers-source")
    if (markerSource != null) {
        val features = mutableListOf<Feature>()
        start?.let {
            val f = Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
            f.addStringProperty("icon", "flag-start")
            features.add(f)
        }
        finish?.let {
            val f = Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))
            f.addStringProperty("icon", "flag-finish")
            features.add(f)
        }
        markerSource.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    val progressSource = style.getSourceAs<GeoJsonSource>("progress-source")
    if (progressSource != null) {
        if (progress != null) {
            val feature = Feature.fromGeometry(Point.fromLngLat(progress.longitude, progress.latitude))
            progressSource.setGeoJson(FeatureCollection.fromFeature(feature))
        } else {
            progressSource.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        }
    }
}

@Composable
private fun TfOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun AttributionBar(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {

            Text(
                text = "Maps © Thunderforest",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { openUrl("https://www.thunderforest.com/") }
            )

            Text(
                text = " • ",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )

            Text(
                text = "Data © OpenStreetMap contributors",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable {
                    openUrl("https://www.openstreetmap.org/copyright")
                }
            )
        }
    }
}
