package it.bike4city.hub

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import it.bike4city.hub.data.FirebaseRepo
import it.bike4city.hub.data.Route
import it.bike4city.hub.gpx.GpxParser
import it.bike4city.hub.location.LocationUpdates
import it.bike4city.hub.maps.ThunderforestMapLibre
import it.bike4city.hub.navigation.TrackNavigationEngine
import it.bike4city.hub.navigation.TtsCoach
import it.bike4city.hub.tracking.TrackRecorder
import it.bike4city.hub.tracking.TrackRecordingService
import it.bike4city.hub.ui.theme.Bike4CityHubTheme
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLngBounds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()

        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            System.currentTimeMillis() - startTime < 2000  // 2 secondi
        }
        MapLibre.getInstance(this)

        super.onCreate(savedInstanceState)

        setContent {
            Bike4CityHubTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var screen by remember { mutableStateOf("welcome") }

    // Auth state
    val user by FirebaseRepo.authState().collectAsState(initial = FirebaseRepo.currentUser())

    when (screen) {
        "welcome" -> WelcomeScreenV2(
            onLoginClick = { screen = if (user != null) "app" else "login" },
            onSignupClick = { screen = "signup" }
        )
        "login" -> LoginScreen(
            isSignup = false,
            onLogin = {
                    email, pass ->
                scope.launch {
                    try {
                        FirebaseRepo.signIn(email, pass)
                        screen = "app"
                    } catch (e: Exception) {
                        Log.w("AppRoot", "Sign-in failed", e)
                        Toast.makeText(context, "Login fallito: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onGoToSignup = { screen = "signup" }
        )
        "signup" -> LoginScreen(
            isSignup = true,
            onGoToLogin = { screen = "login" },
            onSignup = { name, email, pass ->
                scope.launch {
                    try {
                        FirebaseRepo.signUp(email, pass, name)
                        screen = "app"
                    } catch (e: Exception) {
                        Log.w("AppRoot", "Sign-up failed", e)
                        Toast.makeText(context, "Registrazione fallita: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        "app" -> {
            if (user == null) {
                // Should not happen, but as a fallback
                screen = "welcome"
                return
            }

            val uid = user!!.uid

            val mainTabs = listOf(
                BottomTab("home", "Bacheca", Icons.Outlined.Notifications),
                BottomTab("routes", "Percorsi", Icons.Outlined.Route),
                BottomTab("profile", "Profilo", Icons.Outlined.Person),
            )

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        val currentRoute = nav.currentBackStackEntryAsState().value?.destination?.route ?: ""
                        mainTabs.forEach { t ->
                            val isSelected = currentRoute.startsWith(t.route)
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { nav.navigate(t.route) { launchSingleTop = true; popUpTo(mainTabs.first().route) } },
                                icon = { Icon(t.icon, contentDescription = t.label) },
                                label = { Text(t.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.padding(padding)
                ) {
                    composable("home") { HomeScreen() }

                    // Nested navigation for routes
                    navigation(startDestination = "routes_map", route = "routes") {
                        composable("routes_map") { RoutesMapScreen(uid = uid, nav = nav) }
                        composable("my_routes_list") { MyRoutesListScreen(uid = uid, nav = nav) }
                        composable("official_routes_list") { OfficialRoutesListScreen(nav = nav) }
                        composable("edit_route/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                            val id = it.arguments?.getString("id") ?: return@composable
                            EditRouteScreen(routeId = id, nav = nav)
                        }
                    }

                    composable("profile") {
                        ProfileScreen(
                            uid = uid,
                            onLogout = {
                                FirebaseRepo.signOut()
                                screen = "welcome"
                            }
                        )
                    }
                    composable(
                        route = "routeDetail/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { back ->
                        val id = back.arguments?.getString("id") ?: return@composable
                        RouteDetailScreen(routeId = id)
                    }
                }
            }
        }
    }
}

private data class BottomTab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)


@Composable
private fun WelcomeScreenV2(
    onLoginClick: () -> Unit,
    onSignupClick: () -> Unit
) {
    val bg = Color(0xFF2E7D32) // green, più profondo
    val ivory = Color(0xFFFFF8E1)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo (mettilo in drawable come logoappb4c.png)
            Image(
                painter = painterResource(id = R.drawable.logoappb4c),
                contentDescription = "Bike4City APS",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Urban Cycling & Cycle Tourism",
                style = MaterialTheme.typography.titleMedium,
                color = ivory,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onLoginClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ivory, contentColor = bg)
            ) {
                Text("Login")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onSignupClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Registrati")
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "Pedala, scopri, condividi. Bike4City è la tua mappa green di città e avventure.",
                color = ivory.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(18.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "© 2025 Bike4City APS – Tutti i diritti riservati",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) }

        }
    }
}

@Composable
private fun LoginScreen(
    isSignup: Boolean = false,
    onLogin: ((String, String) -> Unit)? = null,
    onSignup: ((String, String, String) -> Unit)? = null,
    onGoToSignup: (() -> Unit)? = null,
    onGoToLogin: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Bike4City Hub", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text(if (isSignup) "Crea account socio" else "Accedi", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(20.dp))

            if (isSignup) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = {
                    if (isSignup) onSignup?.invoke(name.trim(), email.trim(), pass)
                    else onLogin?.invoke(email.trim(), pass)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSignup) "Registrati" else "Entra")
            }

            TextButton(onClick = {
                if (isSignup) onGoToLogin?.invoke()
                else onGoToSignup?.invoke()
            }) {
                Text(if (isSignup) "Hai già un account? Accedi" else "Non hai un account? Registrati")
            }
        }
    }
}
@Composable
private fun HomeScreen() {
    val pageBg = Color(0xFFFFF8E1) // bianco avorio
    Surface(Modifier.fillMaxSize(), color = pageBg) {
        val messages by FirebaseRepo.observeBoardMessages().collectAsState(initial = emptyList())
        val formatter = remember { SimpleDateFormat("dd/MM/yyyy 'alle' HH:mm", Locale.ITALY) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Bacheca", style = MaterialTheme.typography.headlineMedium)
            }
            if (messages.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Nessun messaggio ancora.")
                        }
                    }
                }
            } else {
                items(messages) { msg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(msg.title, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(msg.body)
                            Spacer(Modifier.height(8.dp))
                            val date = msg.createdAt?.let { formatter.format(it) } ?: ""
                            Text(
                                "Pubblicato da ${msg.authorName} il $date",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    uid: String, onLogout: () -> Unit) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    Surface(Modifier.fillMaxSize(), color = pageBg) {
        val scope = rememberCoroutineScope()
        val profile by FirebaseRepo.observeUserProfile(uid).collectAsState(initial = null)

        val picker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch { FirebaseRepo.uploadMembershipCard(uid, uri) }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Profilo e Tessera", style = MaterialTheme.typography.headlineMedium)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nome: ${profile?.name.orEmpty()} ${profile?.cognome.orEmpty()}")
                        Text("Email: ${profile?.email.orEmpty()}")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Numero tessera: ${profile?.tessera.orEmpty()}")
                        Text("Scadenza: ${profile?.tesseraScadenza.orEmpty()}")
                        Text("Stato: ${profile?.tesseraStato.orEmpty()}")
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Immagine tessera", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))

                        if (profile?.cardImageUrl?.isNotBlank() == true) {
                            AsyncImage(
                                model = profile!!.cardImageUrl,
                                contentDescription = "Tessera",
                                modifier = Modifier.fillMaxWidth().height(220.dp)
                            )
                        } else {
                            Text("Nessuna immagine caricata.")
                        }

                        Spacer(Modifier.height(12.dp))

                        Button(onClick = {
                            picker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) {
                            Text("Carica / aggiorna tessera")
                        }
                    }
                }
            }

            item {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Esci") }
            }
        }
    }
}

// --- Routes Section --- //

@Composable
private fun RoutesMapScreen(uid: String, nav: NavHostController) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    // --- Import GPX ---
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val gpxContent = ctx.contentResolver
                        .openInputStream(uri)
                        ?.bufferedReader()
                        .use { it?.readText() }

                    if (gpxContent.isNullOrBlank()) error("File GPX vuoto o non leggibile")

                    val parsed = GpxParser.parse(gpxContent)

                    val route = Route(
                        title = parsed.name ?: "GPX import ${SimpleDateFormat("dd/MM", Locale.ITALY).format(Date())}",
                        gpx = gpxContent,
                        distanceKm = parsed.distanceMeters / 1000.0,
                        isOfficial = false,
                        ownerUid = uid,
                        source = "imported"
                    )
                    FirebaseRepo.saveRoute(route)
                    Toast.makeText(ctx, "GPX importato!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Log.e("RoutesScreen", "GPX import failed", it)
                    Toast.makeText(ctx, "Errore GPX: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // --- Location permission (only to show dot / follow) ---
    var hasLocation by remember { mutableStateOf(false) }
    val requestPerms = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasLocation = isGranted
    }

    DisposableEffect(Unit) {
        requestPerms.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        onDispose { }
    }

    // --- FAB menu ---
    var fabOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = pageBg,

        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {

                if (fabOpen) {
                    MiniFabWithLabel(
                        label = "I miei percorsi",
                        onClick = {
                            fabOpen = false
                            nav.navigate("my_routes_list")
                        }
                    )
                    Spacer(Modifier.height(12.dp))

                    MiniFabWithLabel(
                        label = "Percorsi suggeriti",
                        onClick = {
                            fabOpen = false
                            nav.navigate("official_routes_list")
                        }
                    )
                    Spacer(Modifier.height(12.dp))

                    MiniFabWithLabel(
                        label = "Importa GPX",
                        onClick = {
                            fabOpen = false
                            importLauncher.launch(arrayOf("application/gpx+xml", "*/*"))
                        }
                    )
                    Spacer(Modifier.height(12.dp))

                    MiniFabWithLabel(
                        label = "Registra",
                        onClick = {
                            fabOpen = false
                            nav.navigate("routeDetail/_record")
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                FloatingActionButton(onClick = { fabOpen = !fabOpen }) {
                    Icon(
                        imageVector = if (fabOpen) Icons.Outlined.Close else Icons.Outlined.Add,
                        contentDescription = "Menu percorsi"
                    )
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ThunderforestMapLibre(
                modifier = Modifier.fillMaxSize(),
                showMyLocation = hasLocation,
                followMyLocation = true
            )

            // Tap on map closes the menu
            if (fabOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { fabOpen = false }
                )
            }
        }
    }
}

@Composable
private fun MiniFabWithLabel(
    label: String,
    onClick: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Spacer(Modifier.width(10.dp))
        FloatingActionButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = label)
        }
    }
}

// --- Routes lists / detail --- //
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyRoutesListScreen(uid: String, nav: NavHostController) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    val scope = rememberCoroutineScope()
    val mine by FirebaseRepo.observeMyRoutes(uid).collectAsState(initial = emptyList())
    var routeToDelete by remember { mutableStateOf<Route?>(null) }

    if (routeToDelete != null) {
        AlertDialog(
            onDismissRequest = { routeToDelete = null },
            title = { Text("Conferma eliminazione") },
            text = { Text("Sei sicuro di voler eliminare il percorso \"${routeToDelete!!.title}\"? L'azione è irreversibile.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching { FirebaseRepo.deleteRoute(routeToDelete!!.id) }
                            routeToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Elimina") }
            },
            dismissButton = {
                TextButton(onClick = { routeToDelete = null }) { Text("Annulla") }
            }
        )
    }

    Scaffold(
        containerColor = pageBg,

        topBar = {
            TopAppBar(title = { Text("I miei percorsi") }, navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                }
            })
        }
    ) { padding ->
        if (mine.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nessun percorso creato. Inizia a registrarne uno!", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mine) { r ->
                    RouteListItem(
                        route = r,
                        onClick = { nav.navigate("routeDetail/${r.id}") },
                        onEdit = { nav.navigate("edit_route/${r.id}") },
                        onDelete = { routeToDelete = r }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OfficialRoutesListScreen(nav: NavHostController) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    val official by FirebaseRepo.observeOfficialRoutes().collectAsState(initial = emptyList())

    Scaffold(
        containerColor = pageBg,

        topBar = {
            TopAppBar(title = { Text("Percorsi suggeriti") }, navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                }
            })
        }
    ) { padding ->
        if (official.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nessun percorso suggerito, per ora.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(official) { r ->
                    RouteListItem(route = r, onClick = { nav.navigate("routeDetail/${r.id}") })
                }
            }
        }
    }
}

@Composable
private fun RouteListItem(route: Route, onClick: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = route.title.ifBlank { "(senza nome)" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                val km = ((route.distanceKm ?: 0.0) * 10).roundToInt() / 10.0
                Text(
                    text = "Distanza: $km km • Difficoltà: ${prettyDifficulty(route.difficulty)} • Dislivello: ${prettyMeters(route.ascent)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (route.isOfficial) {
                Icon(
                    Icons.Outlined.Verified,
                    contentDescription = "Ufficiale",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            if (onEdit != null) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Modifica")
                }
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRouteScreen(routeId: String, nav: NavHostController) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var route by remember { mutableStateOf<Route?>(null) }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf("") }

    LaunchedEffect(routeId) {
        route = FirebaseRepo.loadRoute(routeId)
        route?.let {
            title = it.title
            description = it.description
            difficulty = (it.difficulty ?: "")
        }
    }

    Scaffold(
        containerColor = pageBg,

        topBar = {
            TopAppBar(title = { Text("Modifica percorso") }, navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                }
            })
        }
    ) {
        if (route == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(Modifier.fillMaxSize().padding(it).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titolo") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Descrizione") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5
                )
                OutlinedTextField(
                    value = difficulty,
                    onValueChange = { difficulty = it },
                    label = { Text("Difficoltà (es. Facile, Medio, Difficile)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        scope.launch {
                            val updatedRoute = route!!.copy(
                                title = title,
                                description = description,
                                difficulty = difficulty
                            )
                            runCatching { FirebaseRepo.updateRoute(updatedRoute) }.onSuccess {
                                Toast.makeText(ctx, "Percorso aggiornato!", Toast.LENGTH_SHORT).show()
                                nav.popBackStack()
                            }.onFailure {
                                Toast.makeText(ctx, "Errore: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salva modifiche")
                }
            }
        }
    }
}


/**
 * Route detail:
 * - se id = "_record" entra in modalità registrazione
 * - altrimenti carica da Firestore e mostra + follow
 */
@Composable
private fun RouteDetailScreen(routeId: String) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    if (routeId == "_record") {
        RecordRouteScreen()
    } else {
        ViewRouteScreen(routeId = routeId)
    }
}

@Composable
private fun RecordRouteScreen() {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val rec by TrackRecorder.state.collectAsState()

    var hasLocation by remember { mutableStateOf(false) }
    val requestPerms = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasLocation = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) || (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    fun requestPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPerms.launch(permissionsToRequest)
    }

    DisposableEffect(Unit) {
        requestPermissions()

        onDispose {
            TrackRecorder.reset()
        }
    }

    if (rec.phase == TrackRecorder.Phase.STOPPED && rec.points.size >= 2) {
        val durationSec = ((rec.stoppedAt - rec.startedAt) / 1000L).coerceAtLeast(0L) - rec.pausedTotalSec

        AlertDialog(
            onDismissRequest = { /* non chiudiamo a tap fuori, così obblighi scelta */ },
            title = { Text("Registrazione fermata") },
            text = {
                val km = (rec.distanceMeters / 1000.0 * 10).toInt() / 10.0
                Text("Distanza: $km km\nDurata: ${durationSec / 60} min\n\nVuoi salvare il percorso o scartarlo?")
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val uid = FirebaseRepo.currentUser()?.uid ?: return@launch
                        val now = System.currentTimeMillis()
                        val gpx = GpxParser.createGpx(rec.points, "Recorded track")

                        val route = Route(
                            title = "Percorso del ${SimpleDateFormat("dd/MM", Locale.ITALY).format(Date(now))}",
                            gpx = gpx,
                            distanceKm = rec.distanceMeters / 1000.0,
                            isOfficial = false,
                            ownerUid = uid,
                            source = "recorded"
                        )
                        FirebaseRepo.saveRoute(route)

                        TrackRecorder.reset()

                        Toast.makeText(ctx, "Percorso salvato!", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Salva") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    TrackRecorder.reset()
                    Toast.makeText(ctx, "Percorso scartato", Toast.LENGTH_SHORT).show()
                }) { Text("Scarta") }
            }
        )
    }


    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            ThunderforestMapLibre(
                modifier = Modifier.fillMaxSize(),
                points = rec.points,
                showMyLocation = hasLocation,
                followMyLocation = rec.isRecording
            )

            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    val km = (rec.distanceMeters / 1000.0 * 10).roundToInt() / 10.0
                    val status = when {
                        rec.isRecording -> "REC ●"
                        rec.isPaused -> "In pausa"
                        else -> "Pronto"
                    }
                    Text(status, style = MaterialTheme.typography.titleMedium)
                    Text("Punti: ${rec.points.size} • Distanza: $km km")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasLocation) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Per registrare un percorso e vedere la tua posizione, sono necessari i permessi di localizzazione.",
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { requestPermissions() }) {
                        Text("Concedi permessi")
                    }
                }
            } else {
                when (rec.phase) {
                    TrackRecorder.Phase.IDLE -> {
                        Button(
                            onClick = { TrackRecordingService.start(ctx) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = hasLocation
                        ) { Text("Avvia") }
                    }

                    TrackRecorder.Phase.RECORDING -> {
                        Button(
                            onClick = { TrackRecordingService.pause(ctx) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Pausa") }
                        OutlinedButton(
                            onClick = { TrackRecordingService.stop(ctx) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }
                    TrackRecorder.Phase.PAUSED -> {
                        Button(
                            onClick = { TrackRecordingService.resume(ctx) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Riprendi") }
                        OutlinedButton(
                            onClick = { TrackRecordingService.stop(ctx) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop") }
                    }
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun ViewRouteScreen(routeId: String) {
    var route by remember { mutableStateOf<Route?>(null) }

    // modalità “Segui percorso”
    var following by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var navUpdate by remember {
        mutableStateOf(
            Triple("", Double.POSITIVE_INFINITY, true) // instruction, meters, onRoute
        )
    }

    var hasLocation by remember { mutableStateOf(false) }
    val requestPerms = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasLocation = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) || (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    DisposableEffect(Unit) {
        requestPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        onDispose { }
    }


    LaunchedEffect(routeId) {
        route = FirebaseRepo.loadRoute(routeId)
    }

    if (route == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val points = remember(route!!.gpx) {
        runCatching { GpxParser.parse(route!!.gpx).points }.getOrElse { emptyList() }
    }

    val ctx = LocalContext.current

    // engine & TTS: li ricreiamo quando cambia il percorso
    val engine = remember(points) { TrackNavigationEngine(points = points) }
    val tts = remember { TtsCoach(ctx) }
    LaunchedEffect(muted) { tts.muted = muted }

    DisposableEffect(Unit) {
        onDispose {
            tts.shutdown()
        }
    }

    // loop di navigazione: quando “following” è attivo, ascolta la posizione e aggiorna UI + voce
    LaunchedEffect(following, hasLocation, points) {
        if (!following || !hasLocation || points.size < 2) return@LaunchedEffect
        engine.reset()
        LocationUpdates.flow(ctx).collect { pos ->
            val up = engine.update(pos)
            navUpdate = Triple(up.nextInstruction, up.distanceToInstructionMeters, up.onRoute)

            // voce: prossima manovra
            if (engine.shouldSpeakManeuver(up)) {
                val d = up.distanceToInstructionMeters.toInt().coerceAtLeast(1)
                tts.speak("Tra $d metri, ${up.nextInstruction}")
                engine.markManeuverSpoken()
            }

            // voce: fuori traccia
            val now = System.currentTimeMillis()
            if (engine.shouldSpeakOffRoute(now, up)) {
                val d = up.distanceToRouteMeters.toInt().coerceAtLeast(1)
                tts.speak("Sei fuori traccia di circa $d metri. Torna sul percorso.")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (points.isNotEmpty()) {
                val boundsBuilder = LatLngBounds.Builder()
                points.forEach { boundsBuilder.include(org.maplibre.android.geometry.LatLng(it.latitude, it.longitude)) }
                ThunderforestMapLibre(
                    modifier = Modifier.fillMaxSize(),
                    points = points,
                    initialBounds = boundsBuilder.build(),
                    showMyLocation = hasLocation,
                    // ✅ di default centra il percorso; quando attivi “Segui”, allora segue te
                    followMyLocation = following
                )
            }

            if (following && points.isNotEmpty()) {
                val (instr, meters, onRoute) = navUpdate
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (onRoute) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val m = if (meters.isFinite()) meters.roundToInt() else 0
                        Text(
                            if (onRoute) "Segui percorso" else "Fuori percorso",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (instr.isNotBlank()) {
                            Text("Tra $m m: $instr", textAlign = TextAlign.Center)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { muted = !muted }) {
                                Text(if (muted) "Voce OFF" else "Voce ON")
                            }
                            Button(onClick = { following = false }) {
                                Text("Stop")
                            }
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(route!!.title.ifBlank { "Percorso" }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                val km = ((route!!.distanceKm ?: 0.0) * 10).roundToInt() / 10.0
                Text("Distanza: $km km")
                Text("Difficoltà: ${route!!.difficulty}")
                if (route!!.isOfficial) Text("Percorso ufficiale dell’associazione")

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (hasLocation) following = true },
                        modifier = Modifier.weight(1f),
                        enabled = hasLocation && points.size >= 2
                    ) {
                        Text("Segui")
                    }
                    OutlinedButton(
                        onClick = { following = false },
                        modifier = Modifier.weight(1f),
                        enabled = following
                    ) {
                        Text("Esci")
                    }
                }

                if (!hasLocation) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Per seguire il percorso servono i permessi di localizzazione.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// --- Helpers: route metadata formatting --- //

private fun prettyDifficulty(raw: String?): String {
    val v = raw?.trim()?.lowercase(Locale.ITALY).orEmpty()
    return when {
        v.isBlank() -> "—"
        v in setOf("easy", "facile") -> "Facile"
        v in setOf("medium", "media", "medio") -> "Medio"
        v in setOf("hard", "difficile") -> "Difficile"
        else -> raw!!.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALY) else it.toString() }
    }
}

private fun prettyMeters(meters: Double?): String {
    if (meters == null) return "—"
    val m = meters.toInt()
    return "${m} m"
}

@Composable
private fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
