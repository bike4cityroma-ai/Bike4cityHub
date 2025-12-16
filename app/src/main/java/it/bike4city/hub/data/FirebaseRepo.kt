package it.bike4city.hub.data

import android.net.Uri
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

object FirebaseRepo {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val storage: FirebaseStorage by lazy { FirebaseStorage.getInstance() }
    private val messaging: FirebaseMessaging by lazy { FirebaseMessaging.getInstance() }

    fun currentUser(): FirebaseUser? = auth.currentUser

    fun authState() = callbackFlow<FirebaseUser?> {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    suspend fun signUp(email: String, password: String, name: String) {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val uid = result.user?.uid ?: error("UID not found after signup")

        val profile = UserProfile(
            uid = uid,
            email = email,
            name = name,
            cognome = "", // Not asked in signup form
            attivo = true,
            privacy = true, // Assume privacy is accepted
            role = "user",
        )
        db.collection("users").document(uid).set(profile).await()
        runCatching { messaging.subscribeToTopic("users").await() }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password).await()
        runCatching { messaging.subscribeToTopic("users").await() }
    }

    fun signOut() = auth.signOut()

    fun observeBoardMessages() = callbackFlow<List<BoardMessage>> {
        val sub = db.collection("board")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { it.toObject(BoardMessage::class.java) }
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    fun observeUserProfile(uid: String) = callbackFlow<UserProfile?> {
        val sub = db.collection("users").document(uid)
            .addSnapshotListener { snap, err ->
                if (err != null) return@addSnapshotListener
                trySend(snap?.toObject(UserProfile::class.java))
            }
        awaitClose { sub.remove() }
    }

    suspend fun updateUserProfile(uid: String, profile: UserProfile) {
        db.collection("users").document(uid).set(profile).await()
    }

    suspend fun uploadMembershipCard(uid: String, imageUri: Uri): String {
        val ref = storage.reference.child("membershipCards/$uid/card.jpg")
        ref.putFile(imageUri).await()
        val url = ref.downloadUrl.await().toString()
        // aggiorno profilo
        val current = db.collection("users").document(uid).get().await()
            .toObject(UserProfile::class.java) ?: UserProfile()
        updateUserProfile(uid, current.copy(cardImageUrl = url))
        return url
    }

    fun observeOfficialRoutes() = callbackFlow<List<Route>> {
        val sub = db.collection("routes")
            .whereEqualTo("isOfficial", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w("FirebaseRepo", "Error observing official routes", err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                
                val list = snap.documents.mapNotNull { d ->
                    runCatching {
                        d.toObject(Route::class.java)?.also { it.id = d.id }
                    }.onFailure {
                        Log.w("FirebaseRepo", "Failed to parse route document ${d.id}", it)
                    }.getOrNull()
                }
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    fun observeMyRoutes(uid: String) = callbackFlow<List<Route>> {
        val sub = db.collection("routes")
            .whereEqualTo("ownerUid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(100)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w("FirebaseRepo", "Error observing my routes", err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener

                val list = snap.documents.mapNotNull { d ->
                    runCatching {
                        d.toObject(Route::class.java)?.also { it.id = d.id }
                    }.onFailure {
                        Log.w("FirebaseRepo", "Failed to parse route document ${d.id}", it)
                    }.getOrNull()
                }
                trySend(list)
            }
        awaitClose { sub.remove() }
    }

    suspend fun saveRoute(route: Route): String {
        val doc = db.collection("routes").add(route).await()
        return doc.id
    }

    suspend fun loadRoute(id: String): Route? {
        return runCatching {
            val d = db.collection("routes").document(id).get().await()
            d.toObject(Route::class.java)?.also { it.id = d.id }
        }.onFailure {
            Log.w("FirebaseRepo", "Failed to load route $id", it)
        }.getOrNull()
    }

    suspend fun deleteRoute(id: String) {
        db.collection("routes").document(id).delete().await()
    }

    suspend fun updateRoute(route: Route) {
        db.collection("routes").document(route.id).set(route).await()
    }
}
