package it.bike4city.hub.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import it.bike4city.hub.maps.signals.MapSignal
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.maplibre.android.geometry.LatLng
import java.util.UUID

data class SignalLite(
    val id: String,
    val lat: Double,
    val lng: Double,
    val kind: String?,
    val category: String?,
    val title: String?,
    val description: String?,
    val severity: Int?,
    val updatedAt: Long? = null
)

object FirebaseRepo {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val messaging: FirebaseMessaging by lazy { FirebaseMessaging.getInstance() }

    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    private const val COL_USERS = "users"
    private const val COL_BOARD = "board_posts"
    private const val COL_ROUTES_MEMBER = "routes_member"
    private const val COL_SUGGESTIONS = "routes_suggestions"
    private const val COL_SIGNALS = "map_signals"

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun authState() = callbackFlow<FirebaseUser?> {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signUp(email: String, password: String, name: String) {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val uid = result.user?.uid ?: error("UID not found after signup")

        val profile = UserProfileWeb(
            displayName = name,
            email = email,
            firstName = name,
            role = "member",
            status = "pending",
            source = "android-app"
        )
        db.collection(COL_USERS).document(uid).set(profile).await()
        runCatching { messaging.subscribeToTopic("users").await() }
        saveFcmToken()
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
        runCatching { messaging.subscribeToTopic("users").await() }
        saveFcmToken()
    }

    fun signOut() = auth.signOut()

    fun saveFcmTokenIfLogged() {
        val user = auth.currentUser ?: return
        messaging.token.addOnSuccessListener { token ->
            db.collection(COL_USERS).document(user.uid).set(
                mapOf(
                    "fcmToken" to token,
                    "notificationsEnabled" to true,
                    "tokenUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
        }
    }

    suspend fun saveFcmToken() {
        val uid = auth.currentUser?.uid ?: return
        try {
            val token = messaging.token.await()
            db.collection(COL_USERS).document(uid).set(
                mapOf(
                    "fcmToken" to token,
                    "notificationsEnabled" to true,
                    "tokenUpdatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "Failed to save FCM token", e)
        }
    }

    fun observeBoardMessages() = callbackFlow<List<BoardMessage>> {
        val q = db.collection(COL_BOARD)
            .whereEqualTo("status", "published")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)

        val sub = q.addSnapshotListener { snap, err ->
            if (err != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { d ->
                d.toObject(BoardMessage::class.java)?.also { it.id = d.id }
            } ?: emptyList()
            trySend(list)
        }
        awaitClose { sub.remove() }
    }

    fun observeUserProfile(uid: String) = callbackFlow<UserProfileWeb?> {
        val sub = db.collection(COL_USERS).document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                trySend(snap?.toObject(UserProfileWeb::class.java))
            }
        awaitClose { sub.remove() }
    }

    suspend fun updateUserProfileSafe(uid: String, profile: UserProfileWeb) {
        val updates = hashMapOf<String, Any>(
            "address" to profile.address.trim(),
            "city" to profile.city.trim(),
            "zip" to profile.zip.trim(),
            "newsletterOptIn" to profile.newsletterOptIn,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        db.collection(COL_USERS).document(uid).update(updates).await()
    }

    fun observeOfficialRoutes() = callbackFlow<List<Route>> {
        val q = db.collection(COL_ROUTES_MEMBER)
            .whereEqualTo("status", "public")
            .whereEqualTo("b4cCategory", "BIKE4CITY")
            .orderBy("updatedAt", Query.Direction.DESCENDING)

        val sub = q.addSnapshotListener { snap, err ->
            if (err != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { d ->
                d.toObject(Route::class.java)?.also { it.id = d.id }
            } ?: emptyList()
            trySend(list)
        }
        awaitClose { sub.remove() }
    }

    fun observeCommunityRoutes() = callbackFlow<List<Route>> {
        val q = db.collection(COL_ROUTES_MEMBER)
            .whereEqualTo("status", "public")
            .whereEqualTo("b4cCategory", "COMMUNITY")
            .orderBy("updatedAt", Query.Direction.DESCENDING)

        val sub = q.addSnapshotListener { snap, err ->
            if (err != null) {
                trySend(emptyList())
                return@addSnapshotListener
            }
            val list = snap?.documents?.mapNotNull { d ->
                d.toObject(Route::class.java)?.also { it.id = d.id }
            } ?: emptyList()
            trySend(list)
        }
        awaitClose { sub.remove() }
    }

    fun observeMyRoutes(uid: String) = callbackFlow<List<Route>> {
        val sub = db.collection(COL_ROUTES_MEMBER)
            .whereEqualTo("ownerUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { d ->
                    d.toObject(Route::class.java)?.also { it.id = d.id }
                } ?: emptyList()
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    suspend fun saveRoute(route: Route): String {
        val doc = db.collection(COL_ROUTES_MEMBER).add(route).await()
        return doc.id
    }

    suspend fun saveRouteWithPointsAndMatch(route: Route, points: List<LatLng>): String {
        val routeId = route.id.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val docRef = db.collection(COL_ROUTES_MEMBER).document(routeId)

        docRef.set(route, SetOptions.merge()).await()
        docRef.set(mapOf("id" to routeId), SetOptions.merge()).await()

        val pointsPayload = points.map { p -> mapOf("lat" to p.latitude, "lng" to p.longitude) }
        val simplified = points.smartThin(minDistanceMeters = 8.0)
        val pointsFilteredPayload = simplified.map { p -> mapOf("lat" to p.latitude, "lng" to p.longitude) }
        val polylineFiltered = encodePolyline(simplified.map { it.latitude to it.longitude })

        docRef.set(
            mapOf(
                "id" to routeId,
                "points" to pointsPayload,
                "pointsFiltered" to pointsFilteredPayload,
                "polylineFiltered" to polylineFiltered,
                "matchingStatus" to "pending",
                "matchingRequestedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()

        runCatching { matchRouteCallable(routeId) }
        return routeId
    }

    private suspend fun matchRouteCallable(routeId: String) {
        val user = auth.currentUser ?: return
        val data = hashMapOf("routeId" to routeId, "force" to false)
        functions.getHttpsCallable("matchRoute").call(data).await()
    }

    suspend fun loadRoute(id: String): Route? {
        return try {
            val d = db.collection(COL_ROUTES_MEMBER).document(id).get().await()
            if (d.exists()) {
                d.toObject(Route::class.java)?.also { r ->
                    r.id = d.id
                    val status = d.getString("matchingStatus")
                    val polyMatched = d.getString("polylineMatched")
                    if (status == "matched" && !polyMatched.isNullOrBlank()) {
                        val pts = decodePolyline(polyMatched).map { LatLng(it.first, it.second) }
                        r.gpxText = buildGpxFromLatLng(pts, creator = "Bike4City Planner (matched)")
                    }
                }
            } else {
                val d2 = db.collection(COL_SUGGESTIONS).document(id).get().await()
                d2.toObject(Route::class.java)?.also { it.id = d2.id }
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteRoute(id: String) {
        db.collection(COL_ROUTES_MEMBER).document(id).delete().await()
    }

    suspend fun updateRoute(route: Route) {
        db.collection(COL_ROUTES_MEMBER).document(route.id).set(route).await()
    }

    suspend fun saveSignal(signal: MapSignal): String {
        val uid = auth.currentUser?.uid ?: "anonymous"
        val gh7 = encodeGeohash(signal.lat, signal.lng, 7)
        val finalSignal = signal.copy(
            geohash = gh7,
            createdBy = uid,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val doc = db.collection(COL_SIGNALS).add(finalSignal).await()
        return doc.id
    }

    fun observePublicSignals() = callbackFlow<List<MapSignal>> {
        val sub = db.collection(COL_SIGNALS)
            .whereEqualTo("status", "active")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(MapSignal::class.java)?.copy(id = it.id) } ?: emptyList()
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    fun observeRouteSignals(routeId: String): Flow<List<MapSignal>> = callbackFlow {
        val reg = db.collection(COL_SIGNALS)
            .whereEqualTo("routeId", routeId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                val list = snap?.documents
                    ?.mapNotNull { it.toObject(MapSignal::class.java)?.copy(id = it.id) }
                    ?: emptyList()
                trySend(list).isSuccess
            }

        awaitClose { reg.remove() }
    }

    fun observePendingSignals() = callbackFlow<List<MapSignal>> {
        val sub = db.collection(COL_SIGNALS)
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val list = snap?.documents?.mapNotNull { it.toObject(MapSignal::class.java)?.copy(id = it.id) } ?: emptyList()
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    suspend fun approveSignal(id: String) {
        db.collection(COL_SIGNALS).document(id).update("status", "active", "updatedAt", System.currentTimeMillis()).await()
    }

    suspend fun loadActiveSignalsInBounds(south: Double, west: Double, north: Double, east: Double, limit: Long = 500): List<SignalLite> {
        val nowMs = System.currentTimeMillis()
        val snap = db.collection(COL_SIGNALS).whereEqualTo("status", "active").limit(limit).get().await()
        val out = ArrayList<SignalLite>()
        for (d in snap.documents) {
            val lat = d.getDouble("lat") ?: continue
            val lng = d.getDouble("lng") ?: continue
            if (lat < south || lat > north || lng < west || lng > east) continue
            val expiresOk = run {
                val expLong = d.getLong("expiresAt")
                if (expLong != null) return@run expLong > nowMs
                val ts = d.getTimestamp("expiresAt")
                if (ts != null) return@run ts.toDate().time > nowMs
                true
            }
            if (!expiresOk) continue
            out.add(SignalLite(
                id = d.id, lat = lat, lng = lng, kind = d.getString("kind"),
                category = d.getString("category"), title = d.getString("title"),
                description = d.getString("description"), severity = d.getLong("severity")?.toInt(),
                updatedAt = d.getLong("updatedAt")
            ))
        }
        return out.sortedByDescending { it.updatedAt ?: 0L }
    }

    // Helpers
    private fun List<LatLng>.smartThin(minDistanceMeters: Double): List<LatLng> {
        if (this.size <= 2) return this
        val out = ArrayList<LatLng>()
        out.add(this.first())
        var last = this.first()
        for (i in 1 until this.size - 1) {
            val p = this[i]
            val res = FloatArray(1)
            android.location.Location.distanceBetween(last.latitude, last.longitude, p.latitude, p.longitude, res)
            if (res[0] >= minDistanceMeters) {
                out.add(p)
                last = p
            }
        }
        out.add(this.last())
        return out
    }

    private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
        val poly = ArrayList<Pair<Double, Double>>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else (result shr 1)
            poly.add(lat / 1e5 to lng / 1e5)
        }
        return poly
    }

    private fun encodePolyline(points: List<Pair<Double, Double>>): String {
        var lastLat = 0
        var lastLng = 0
        val result = StringBuilder()
        for (p in points) {
            val lat = Math.round(p.first * 1e5).toInt()
            val lng = Math.round(p.second * 1e5).toInt()
            encodeSigned(lat - lastLat, result)
            encodeSigned(lng - lastLng, result)
            lastLat = lat
            lastLng = lng
        }
        return result.toString()
    }

    private fun encodeSigned(num: Int, out: StringBuilder) {
        var v = if (num < 0) (num shl 1).inv() else (num shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f)) + 63).toChar())
            v = v shr 5
        }
        out.append((v + 63).toChar())
    }

    private fun buildGpxFromLatLng(points: List<LatLng>, creator: String): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"$creator\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>Bike4City</name><trkseg>\n")
        for (p in points) sb.append("<trkpt lat=\"${p.latitude}\" lon=\"${p.longitude}\"></trkpt>\n")
        sb.append("</trkseg></trk>\n</gpx>")
        return sb.toString()
    }

    private fun encodeGeohash(lat: Double, lon: Double, precision: Int = 7): String {
        val base32 = "0123456789bcdefghjkmnpqrstuvwxyz"
        var latRange = doubleArrayOf(-90.0, 90.0); var lonRange = doubleArrayOf(-180.0, 180.0)
        val geohash = StringBuilder(); var isEven = true; var bit = 0; var ch = 0
        while (geohash.length < precision) {
            val mid: Double
            if (isEven) {
                mid = (lonRange[0] + lonRange[1]) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonRange[0] = mid } else lonRange[1] = mid
            } else {
                mid = (latRange[0] + latRange[1]) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latRange[0] = mid } else latRange[1] = mid
            }
            isEven = !isEven
            if (bit < 4) bit++ else { geohash.append(base32[ch]); bit = 0; ch = 0 }
        }
        return geohash.toString()
    }
}
