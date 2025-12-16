package it.bike4city.hub.location

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Flow di posizione: semplice, riutilizzabile sia per registrazione che per "segui traccia".
 */
object LocationUpdates {

    private const val ACCURACY_THRESHOLD_METERS = 25f

    @SuppressLint("MissingPermission")
    fun flow(
        ctx: Context,
        intervalMs: Long = 1500L,
        minDistanceM: Float = 3f
    ): Flow<LatLng> = callbackFlow {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateDistanceMeters(minDistanceM)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                // Ignora gli aggiornamenti con scarsa accuratezza per evitare "zigzag"
                if (loc.accuracy > ACCURACY_THRESHOLD_METERS) return

                trySend(LatLng(loc.latitude, loc.longitude))
            }
        }

        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(cb) }
    }
}
