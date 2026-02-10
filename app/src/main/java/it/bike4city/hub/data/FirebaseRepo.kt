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
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.maplibre.android.geometry.LatLng

object FirebaseRepo {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val messaging: FirebaseMessaging by lazy { FirebaseMessaging.getInstance() }

    // ✅ Callable Functions
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
            db.collection(COL_USERS)
                .document(user.uid)
                .set(
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
            Log.d("FirebaseRepo", "FCM Token saved for user $uid")
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
                Log.e("FirebaseRepo", "observeBoardMessages error: ${err.message}", err)
                trySend(emptyList())
                return@addSnapshotListener
            }
            if (snap == null) {
                trySend(emptyList())
                return@addSnapshotListener
            }

            val list = snap.documents.mapNotNull { d ->
                d.toObject(BoardMessage::class.java)?.also { it.id = d.id }
            }
            trySend(list)
        }

        awaitClose { sub.remove() }
    }

    fun observeUserProfile(uid: String) = callbackFlow<UserProfileWeb?> {
        val sub = db.collection(COL_USERS).document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("FirebaseRepo", "observeUserProfile error: ${err.message}")
                    return@addSnapshotListener
                }
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
                Log.e("FirebaseRepo", "observeOfficialRoutes ERROR: ${err.message}", err)
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
                Log.e("FirebaseRepo", "observeCommunityRoutes ERROR: ${err.message}", err)
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
                    Log.e("FirebaseRepo", "observeMyRoutes ERROR: ${err.message}", err)
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

    /**
     * ✅ NUOVO: salva route + points (lat/lng) in routes_member e poi chiama matchRoute callable.
     */
    suspend fun saveRouteWithPointsAndMatch(route: Route, points: List<LatLng>): String {
        val docRef = db.collection(COL_ROUTES_MEMBER).add(route).await()
        val routeId = docRef.id

        val pointsPayload = points.map { p ->
            mapOf("lat" to p.latitude, "lng" to p.longitude)
        }

        docRef.set(
            mapOf(
                "points" to pointsPayload,
                "matchingStatus" to "pending",
                "matchingRequestedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        ).await()

        runCatching { matchRouteCallable(routeId) }
            .onFailure { e ->
                Log.e("FirebaseRepo", "matchRouteCallable failed for $routeId: ${e.message}", e)
                runCatching {
                    docRef.set(
                        mapOf(
                            "matchingStatus" to "failed",
                            "matchingError" to (e.message ?: "callable_failed"),
                            "matchedAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).await()
                }
            }

        return routeId
    }

    private suspend fun matchRouteCallable(routeId: String) {
        auth.currentUser?.uid ?: error("Utente non loggato")
        val data = hashMapOf("routeId" to routeId, "force" to false)
        functions.getHttpsCallable("matchRoute").call(data).await()
    }

    suspend fun loadRoute(id: String): Route? {
        return try {
            val d = db.collection(COL_ROUTES_MEMBER).document(id).get().await()
            if (d.exists()) {
                d.toObject(Route::class.java)?.also { it.id = d.id }
            } else {
                val d2 = db.collection(COL_SUGGESTIONS).document(id).get().await()
                d2.toObject(Route::class.java)?.also { it.id = d2.id }
            }
        } catch (e: Exception) {
            Log.e("FirebaseRepo", "loadRoute error for id $id: ${e.message}")
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
        val finalSignal = signal.copy(
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

    fun observeRouteSignals(routeId: String) = callbackFlow<List<MapSignal>> {
        val sub = db.collection(COL_SIGNALS)
            .whereEqualTo("routeId", routeId)
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

    fun observePendingSignals() = callbackFlow<List<MapSignal>> {
        val sub = db.collection(COL_SIGNALS)
            .whereEqualTo("status", "pending")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.e("FirebaseRepo", "observePendingSignals error: ${err.message}")
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
}
