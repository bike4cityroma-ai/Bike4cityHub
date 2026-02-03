@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package it.bike4city.hub.maps

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import it.bike4city.hub.maps.signals.MapSignal
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
import org.maplibre.android.style.expressions.Expression.*
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
import java.util.concurrent.atomic.AtomicReference

enum class TfStyle(val id: String, val label: String) {
    CYCLE("cycle", "Ciclabili"),
    OUTDOORS("outdoors", "Outdoors"),
    ATLAS("atlas", "Mappa Città")
}

private const val SIGNALS_SOURCE_ID = "signals-source"
private const val SIGNALS_POI_LAYER_ID = "signals-poi-layer"
private const val SIGNALS_CRITICAL_LAYER_ID = "signals-critical-layer"

@Composable
fun ThunderforestMapLibre(
    modifier: Modifier = Modifier,
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
    val ctx = LocalContext.current
    val tfKey = stringResource(R.string.thunderforest_api_key)
    val userPrefs = remember { UserPrefs(ctx) }
    val styleState by userPrefs.mapStyle.collectAsState(initial = TfStyle.CYCLE)

    val scope = rememberCoroutineScope()

    val signalsByIdRef = remember { AtomicReference<Map<String, MapSignal>>(emptyMap()) }
    var selectedSignal by remember { mutableStateOf<MapSignal?>(null) }
    var clickListenerAdded by remember { mutableStateOf(false) }

    LaunchedEffect(signals) {
        signalsByIdRef.set(signals.associateBy { it.id })
    }

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
                        
                        if (!clickListenerAdded) {
                            clickListenerAdded = true
                            map.addOnMapClickListener { latLng ->
                                val p: PointF = map.projection.toScreenLocation(latLng)
                                val tol = 24f
                                val rect = android.graphics.RectF(p.x - tol, p.y - tol, p.x + tol, p.y + tol)

                                val features = map.queryRenderedFeatures(
                                    rect,
                                    SIGNALS_CRITICAL_LAYER_ID,
                                    SIGNALS_POI_LAYER_ID
                                )

                                if (features.isNotEmpty()) {
                                    val f = features.first()
                                    val id = if (f.hasProperty("id")) f.getStringProperty("id") else ""
                                    val signal = signalsByIdRef.get()[id]
                                    if (signal != null) {
                                        selectedSignal = signal
                                        true
                                    } else false
                                } else false
                            }
                        }

                        map.setStyle(buildThunderforestStyle(tfKey, styleState.id)) { loadedStyle ->
                            addIconsToStyle(loadedStyle)
                            ensureTrackLayer(loadedStyle)
                            ensureSignalsLayers(loadedStyle)
                            updateTrackWithSlopes(loadedStyle, points)
                            updateMarkers(loadedStyle, startPoint, finishPoint, progressPoint)
                            updateSignals(loadedStyle, signals)
                            
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

        AttributionBar(modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))

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
                    TfStyle.values().forEach { s ->
                        TfOption(
                            title = s.label,
                            desc = when(s) {
                                TfStyle.ATLAS -> "Ideale per orientarsi in città, con POI"
                                TfStyle.CYCLE -> "Evidenzia le piste ciclabili e i percorsi"
                                TfStyle.OUTDOORS -> "Più adatto a escursioni e sentieri"
                            },
                            selected = styleState == s
                        ) {
                            scope.launch {
                                userPrefs.setMapStyle(s)
                                sheetOpen = false
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        if (selectedSignal != null) {
            ModalBottomSheet(onDismissRequest = { selectedSignal = null }) {
                val s = selectedSignal!!
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(text = s.title.ifBlank { s.category }, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = (if (s.kind == "critical") "⚠️ Criticità" else "📍 POI") + " • " + s.category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(text = s.description.ifBlank { "Nessuna descrizione" }, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(14.dp))
                    
                    if (s.link.isNotBlank()) {
                        Text(
                            text = "Sito Web / Info",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.link))) }
                                }
                                .padding(vertical = 10.dp)
                        )
                    }

                    Text(
                        text = "Apri in navigazione",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                val uri = Uri.parse("geo:${s.lat},${s.lng}?q=${s.lat},${s.lng}(${Uri.encode(s.title.ifBlank { s.category })})")
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            .padding(vertical = 10.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    LaunchedEffect(signals) {
        mapView.getMapAsync { map ->
            map.style?.let { updateSignals(it, signals) }
        }
    }

    LaunchedEffect(mapView, showMyLocation) {
        if (!showMyLocation) return@LaunchedEffect
        mapView.getMapAsync { map ->
            map.style?.let { style ->
                activateLocation(ctx, map, style)
            }
        }
    }

    LaunchedEffect(mapView, followMyLocation) {
        mapView.getMapAsync { map ->
            if (map.style?.isFullyLoaded == true && map.locationComponent.isLocationComponentActivated) {
                if (followMyLocation) {
                    val cameraUpdate = org.maplibre.android.camera.CameraPosition.Builder()
                        .zoom(17.5)
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

    LaunchedEffect(styleState) {
        reloadStyle(mapView, tfKey, styleState, points, signals, startPoint, finishPoint, progressPoint)
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

@SuppressLint("MissingPermission")
private fun activateLocation(ctx: Context, map: org.maplibre.android.maps.MapLibreMap, style: Style) {
    val locationComponent = map.locationComponent
    locationComponent.activateLocationComponent(
        LocationComponentActivationOptions.builder(ctx, style).build()
    )
    locationComponent.isLocationComponentEnabled = true
    locationComponent.renderMode = RenderMode.COMPASS
}

@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { id = R.id.map_view } }
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

private fun reloadStyle(mapView: MapView, tfKey: String, style: TfStyle, points: List<LatLng>, signals: List<MapSignal>, startPoint: LatLng?, finishPoint: LatLng?, progressPoint: LatLng?) {
    mapView.getMapAsync { map ->
        map.setStyle(buildThunderforestStyle(tfKey, style.id), Style.OnStyleLoaded {
            addIconsToStyle(it)
            ensureTrackLayer(it)
            ensureSignalsLayers(it)
            updateTrackWithSlopes(it, points)
            updateMarkers(it, startPoint, finishPoint, progressPoint)
            updateSignals(it, signals)
        })
    }
}

private fun buildThunderforestStyle(tfKey: String, tfStyleId: String): Style.Builder {
    val tileUrl = "https://tile.thunderforest.com/$tfStyleId/{z}/{x}/{y}.png?apikey=$tfKey"
    val rasterSource = RasterSource("tf-source", TileSet("2.1.0", tileUrl), 256)
    return Style.Builder().fromJson("""{"version": 8, "sources": {}, "layers": []}""").withSource(rasterSource).withLayer(RasterLayer("tf-layer", "tf-source"))
}

private fun addIconsToStyle(style: Style) {
    style.addImage("arrow-icon", createArrowBitmap())
    style.addImage("flag-start", createFlagBitmap(android.graphics.Color.GREEN))
    style.addImage("flag-finish", createFlagBitmap(android.graphics.Color.RED))
    style.addImage("sig-poi", createDotBitmap(android.graphics.Color.parseColor("#1E88E5")))
    style.addImage("sig-critical", createDotBitmap(android.graphics.Color.parseColor("#E53935")))
}

private fun createArrowBitmap(): Bitmap {
    val size = 48
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(b)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 5f 
    }
    val path = Path().apply {
        moveTo(size * 0.5f, size * 0.15f)
        lineTo(size * 0.15f, size * 0.85f)
        lineTo(size * 0.85f, size * 0.85f)
        close()
    }
    canvas.drawPath(path, paint)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.FILL
    canvas.drawPath(path, paint)
    return b
}

private fun createFlagBitmap(fillColor: Int): Bitmap {
    val size = 64
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(b)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
    canvas.drawRect(size * 0.48f, size * 0.2f, size * 0.52f, size * 0.9f, paint)
    paint.color = fillColor
    val path = Path().apply {
        moveTo(size * 0.52f, size * 0.2f)
        lineTo(size * 0.9f, size * 0.35f)
        lineTo(size * 0.52f, size * 0.5f)
        close()
    }
    canvas.drawPath(path, paint)
    return b
}

private fun createDotBitmap(dotColor: Int): Bitmap {
    val size = 48
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(b)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dotColor }
    canvas.drawCircle(size / 2f, size / 2f, 14f, paint)
    paint.color = android.graphics.Color.WHITE
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 6f
    canvas.drawCircle(size / 2f, size / 2f, 14f, paint)
    return b
}

private fun ensureTrackLayer(style: Style) {
    if (style.getSource("track-source") == null) style.addSource(GeoJsonSource("track-source", FeatureCollection.fromFeatures(arrayOf())))
    if (style.getSource("track-arrows-source") == null) style.addSource(GeoJsonSource("track-arrows-source", FeatureCollection.fromFeatures(arrayOf())))
    
    if (style.getLayer("track-layer") == null) {
        val layer = LineLayer("track-layer", "track-source").withProperties(
            lineWidth(7f),
            lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineColor(
                step(
                    get("slope"),
                    literal("#2563EB"),
                    stop(-3.0, literal("#3B82F6")),
                    stop(1.5, literal("#22C55E")),
                    stop(5.0, literal("#FACC15")),
                    stop(10.0, literal("#EF4444"))
                )
            )
        )
        style.addLayer(layer)
    }
    if (style.getLayer("track-arrows") == null) {
        style.addLayer(SymbolLayer("track-arrows", "track-arrows-source").withProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            symbolSpacing(120f),
            iconImage("arrow-icon"),
            iconSize(0.55f),
            iconRotate(90f),
            iconAllowOverlap(true),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
        ))
    }
    if (style.getSource("markers-source") == null) style.addSource(GeoJsonSource("markers-source", FeatureCollection.fromFeatures(arrayOf())))
    if (style.getLayer("markers-layer") == null) {
        style.addLayer(SymbolLayer("markers-layer", "markers-source").withProperties(
            iconImage(get("icon")),
            iconSize(1.0f),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconOffset(arrayOf(0f, -25f))
        ))
    }
    if (style.getSource("progress-source") == null) style.addSource(GeoJsonSource("progress-source", FeatureCollection.fromFeatures(arrayOf())))
    if (style.getLayer("progress-layer") == null) {
        style.addLayer(CircleLayer("progress-layer", "progress-source").withProperties(
            circleRadius(11f),
            circleColor(literal("#FACC15")),
            circleStrokeColor(literal("#000000")),
            circleStrokeWidth(2.5f)
        ))
    }
}

private fun ensureSignalsLayers(style: Style) {
    if (style.getSource(SIGNALS_SOURCE_ID) == null) style.addSource(GeoJsonSource(SIGNALS_SOURCE_ID, FeatureCollection.fromFeatures(arrayOf())))
    if (style.getLayer(SIGNALS_POI_LAYER_ID) == null) {
        val layer = SymbolLayer(SIGNALS_POI_LAYER_ID, SIGNALS_SOURCE_ID).withProperties(iconImage("sig-poi"), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(0.9f))
        layer.setFilter(all(all(eq(get("kind"), literal("poi")), eq(get("status"), literal("active")))))
        if (style.getLayer("track-arrows") != null) style.addLayerAbove(layer, "track-arrows") else style.addLayer(layer)
    }
    if (style.getLayer(SIGNALS_CRITICAL_LAYER_ID) == null) {
        val layer = SymbolLayer(SIGNALS_CRITICAL_LAYER_ID, SIGNALS_SOURCE_ID).withProperties(iconImage("sig-critical"), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(1.0f))
        layer.setFilter(all(all(eq(get("kind"), literal("critical")), eq(get("status"), literal("active")))))
        if (style.getLayer("track-arrows") != null) style.addLayerAbove(layer, "track-arrows") else style.addLayer(layer)
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
    val features = (0 until points.size - 1).map { i ->
        val p1 = points[i]
        val p2 = points[i+1]
        Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(p1.longitude, p1.latitude), Point.fromLngLat(p2.longitude, p2.latitude)))).apply { 
            addNumberProperty("slope", calculateSlope(p1, p2)) 
        }
    }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
    arrowSrc.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.longitude, it.latitude) })))
}

private fun calculateSlope(p1: LatLng, p2: LatLng): Double {
    val res = FloatArray(1)
    android.location.Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, res)
    val distance = res[0].toDouble()
    if (distance < 2.0) return 0.0
    return ((p2.altitude - p1.altitude) / distance) * 100.0
}

private fun updateMarkers(style: Style, start: LatLng?, finish: LatLng?, progress: LatLng?) {
    val src = style.getSourceAs<GeoJsonSource>("markers-source") ?: return
    val features = mutableListOf<Feature>()
    start?.let { features.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("icon", "flag-start") }) }
    finish?.let { features.add(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)).apply { addStringProperty("icon", "flag-finish") }) }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
    
    val pSrc = style.getSourceAs<GeoJsonSource>("progress-source") ?: return
    if (progress != null) {
        pSrc.setGeoJson(Feature.fromGeometry(Point.fromLngLat(progress.longitude, progress.latitude)))
    } else {
        pSrc.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
    }
}

private fun updateSignals(style: Style, signals: List<MapSignal>) {
    val src = style.getSourceAs<GeoJsonSource>(SIGNALS_SOURCE_ID) ?: return
    val features = signals.map { s ->
        Feature.fromGeometry(Point.fromLngLat(s.lng, s.lat)).apply {
            addStringProperty("id", s.id)
            addStringProperty("kind", s.kind)
            addStringProperty("category", s.category)
            addStringProperty("title", s.title)
            addStringProperty("description", s.description)
            addStringProperty("status", s.status)
            addStringProperty("link", s.link)
        }
    }
    src.setGeoJson(FeatureCollection.fromFeatures(features))
}

@Composable
private fun TfOption(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick, 
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), 
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Maps © Thunderforest", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.thunderforest.com/"))) } })
            Text(text = " • ", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(text = "Data © OpenStreetMap contributors", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } })
        }
    }
}
