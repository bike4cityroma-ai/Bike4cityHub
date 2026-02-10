package it.bike4city.hub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.bike4city.hub.data.FirebaseRepo
import it.bike4city.hub.data.Route
import it.bike4city.hub.data.UserProfileWeb
import it.bike4city.hub.gpx.GpxParser
import it.bike4city.hub.location.LocationUpdates
import it.bike4city.hub.maps.ThunderforestMapLibre
import it.bike4city.hub.navigation.TrackNavigationEngine
import it.bike4city.hub.navigation.TtsCoach
import it.bike4city.hub.tracking.TrackRecorder
import it.bike4city.hub.tracking.TrackRecordingService
import it.bike4city.hub.ui.route.ViewRouteScreen
import it.bike4city.hub.ui.theme.Bike4CityHubTheme
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ok */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        val splashScreen = installSplashScreen()

        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            System.currentTimeMillis() - startTime < 2000  // 2 secondi
        }
        MapLibre.getInstance(this)

        ensureNotificationPermission()

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
            onLoginClick = { screen = if (user != null) "app" else "login" }
        )
        "login" -> LoginScreen(
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
                    composable("home") { HomeScreen(nav = nav) }
                    composable("board_all") { BoardAllScreen(nav = nav) }
                    composable("info") { InfoScreen(nav = nav) }

                    // Nested navigation for routes
                    navigation(startDestination = "routes_map", route = "routes") {
                        composable("routes_map") { RoutesMapScreen(uid = uid, nav = nav) }
                        composable("my_routes_list") { MyRoutesListScreen(uid = uid, nav = nav) }
                        composable("official_routes_list") { OfficialRoutesListScreen(nav = nav) }
                        fragmentEditRoute(nav)
                    }

                    composable("profile") {
                        ProfileScreen(
                            uid = uid,
                            nav = nav,
                            onLogout = {
                                FirebaseRepo.signOut()
                                screen = "welcome"
                            }
                        )
                    }
                    composable("edit_profile") {
                        EditProfileScreen(uid = uid, nav = nav)
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

private fun androidx.navigation.NavGraphBuilder.fragmentEditRoute(nav: NavHostController) {
    composable("edit_route/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
        val id = it.arguments?.getString("id") ?: return@composable
        EditRouteScreen(routeId = id, nav = nav)
    }
}

private data class BottomTab(val route: String, val label: String, val icon: ImageVector)


@Composable
private fun WelcomeScreenV2(
    onLoginClick: () -> Unit
) {
    val bg = Color(0xFF2E7D32) // green, più profondo
    val ivory = Color(0xFFFFF8E1)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bg
    ) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> }
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
    onLogin: ((String, String) -> Unit)? = null,
) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    Surface(Modifier.fillMaxSize(), color = Color(0xFFF5F5F5)) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Bike4City Hub", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text("Accedi", style = MaterialTheme.typography.titleMedium)

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = { onLogin?.invoke(email.trim(), pass) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Entra")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Non hai un account? Registrati sul sito bike4city.org",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://bike4city.org/register"))
                    ctx.startActivity(i)
                },
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = "Password dimenticata? Recuperala dal sito",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://bike4city-social-hub.web.app/login.html"))
                    ctx.startActivity(i)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
private fun HomeScreen(nav: NavHostController) {
    val pageBg = Color(0xFFFFF8E1) // bianco avorio
    Surface(Modifier.fillMaxSize(), color = pageBg) {

        val messages by FirebaseRepo.observeBoardMessages().collectAsState(initial = emptyList())
        val formatter = remember { SimpleDateFormat("dd/MM/yyyy 'alle' HH:mm", Locale.ITALY) }

        val latest5 = remember(messages) { messages.take(5) }

        val ctx = LocalContext.current
        var selected by remember { mutableStateOf<it.bike4city.hub.data.BoardMessage?>(null) }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Bacheca", style = MaterialTheme.typography.headlineMedium)
                    TextButton(onClick = { nav.navigate("board_all") }) { Text("Vedi tutti") }
                }
            }

            if (messages.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { Text("Nessun messaggio ancora.") }
                    }
                }
            } else {

                items(latest5) { msg ->
                    val fullText = boardMessageText(msg)
                    val preview = fullText.replace(Regex("\\s+"), " ").trim().let {
                        if (it.length > 180) it.take(180) + "…" else it
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = msg }
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(msg.title, style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.height(6.dp))
                            Text(preview, maxLines = 4, overflow = TextOverflow.Ellipsis)
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

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tutti i messaggi", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { nav.navigate("board_all") }) { Text("Apri") }
                        }
                    }
                }
            }
        }

        selected?.let { msg ->
            val fullText = boardMessageText(msg)
            val annotated = remember(fullText) { buildLinkifiedText(fullText) }
            val date = msg.createdAt?.let { formatter.format(it) } ?: ""

            AlertDialog(
                onDismissRequest = { selected = null },
                title = {
                    Column {
                        Text(msg.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "di ${msg.authorName}${if (date.isNotBlank()) " • $date" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                text = {
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        onClick = { offset ->
                            annotated.getStringAnnotations("LINK", offset, offset)
                                .firstOrNull()?.let { ann -> openLink(ctx, ann.item) }
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { selected = null }) { Text("Chiudi") }
                }
            )
        }
    }
}


private fun boardMessageText(msg: it.bike4city.hub.data.BoardMessage): String {
    val t = msg.contentPlain.trim()
    return if (t.isNotBlank()) t else msg.body.trim()
}

private fun buildLinkifiedText(text: String): AnnotatedString {
    val urlRegex = Regex("""\b(https?://[^\s]+|www\.[^\s]+)\b""", RegexOption.IGNORE_CASE)
    val emailRegex = Regex("""\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""", RegexOption.IGNORE_CASE)

    return buildAnnotatedString {
        var i = 0
        val matches = (urlRegex.findAll(text).map { it.range to "url" } +
                emailRegex.findAll(text).map { it.range to "email" })
            .sortedBy { it.first.first }

        for ((range, kind) in matches) {
            if (range.first < i) continue
            if (range.first > i) append(text.substring(i, range.first))

            val raw = text.substring(range.first, range.last + 1)
            val target = when (kind) {
                "email" -> "mailto:$raw"
                else -> if (raw.startsWith("http", true)) raw else "https://$raw"
            }

            pushStringAnnotation(tag = "LINK", annotation = target)
            withStyle(
                SpanStyle(
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(raw)
            }
            pop()

            i = range.last + 1
        }

        if (i < text.length) append(text.substring(i))
    }
}

private fun openLink(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Impossibile aprire il link", Toast.LENGTH_SHORT).show()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardAllScreen(nav: NavHostController) {
    val pageBg = Color(0xFFFFF8E1)
    val ctx = LocalContext.current

    val messages by FirebaseRepo.observeBoardMessages().collectAsState(initial = emptyList())
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy 'alle' HH:mm", Locale.ITALY) }

    var q by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<it.bike4city.hub.data.BoardMessage?>(null) }

    val filtered = remember(messages, q) {
        val term = q.trim().lowercase(Locale.ITALY)
        if (term.isBlank()) messages
        else messages.filter {
            it.title.lowercase(Locale.ITALY).contains(term) ||
                    boardMessageText(it).lowercase(Locale.ITALY).contains(term)
        }
    }

    Scaffold(
        containerColor = pageBg,
        topBar = {
            TopAppBar(
                title = { Text("Tutti i messaggi") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = q,
                onValueChange = { q = it },
                label = { Text("Cerca") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { Text("Nessun messaggio.") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered) { msg ->
                        val fullText = boardMessageText(msg)
                        val preview = fullText.replace(Regex("\\s+"), " ").trim().let {
                            if (it.length > 180) it.take(180) + "…" else it
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = msg }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(msg.title, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(preview, maxLines = 4, overflow = TextOverflow.Ellipsis)
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

        selected?.let { msg ->
            val fullText = boardMessageText(msg)
            val annotated = remember(fullText) { buildLinkifiedText(fullText) }
            val date = msg.createdAt?.let { formatter.format(it) } ?: ""

            AlertDialog(
                onDismissRequest = { selected = null },
                title = {
                    Column {
                        Text(msg.title, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "di ${msg.authorName}${if (date.isNotBlank()) " • $date" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                text = {
                    ClickableText(
                        text = annotated,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                        onClick = { offset ->
                            annotated.getStringAnnotations("LINK", offset, offset)
                                .firstOrNull()?.let { ann -> openLink(ctx, ann.item) }
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = { selected = null }) { Text("Chiudi") }
                }
            )
        }
    }
}


@Composable
private fun ProfileScreen(
    uid: String,
    nav: NavHostController,
    onLogout: () -> Unit
) {
    val pageBg = Color(0xFFF5F5F5) // grigio chiaro
    Surface(Modifier.fillMaxSize(), color = pageBg) {
        val profile by FirebaseRepo.observeUserProfile(uid).collectAsState(initial = null)

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
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Info & Privacy", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Copyright, trattamento dei dati, riconoscimenti e contatti.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { nav.navigate("info") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Apri")
                        }
                    }
                }
            }

            if (profile != null) {
                item {
                    MembershipCard(profile!!)
                }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val nome = listOf(profile!!.firstName, profile!!.lastName).filter { !it.isNullOrBlank() }.joinToString(" ")
                            val fallback = profile!!.displayName.orEmpty()

                            Text("Nome: ${if (nome.isNotBlank()) nome else fallback}")
                            Text("Email: ${profile!!.email.orEmpty()}")
                            if (!profile!!.phone.isNullOrBlank()) Text("Telefono: ${profile!!.phone}")
                            if (!profile!!.city.isNullOrBlank()) Text("Città: ${profile!!.city}")
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            item {
                Button(
                    onClick = { nav.navigate("edit_profile") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Modifica Profilo")
                }
            }

            item {
                OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Esci") }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "© 2025 Bike4City APS – Tutti i diritti riservati",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MembershipCard(profile: UserProfileWeb) {
    val m = profile.membership
    val formatter = remember { SimpleDateFormat("dd/MM/yyyy", Locale.ITALY) }

    val numero = m.number.ifBlank { profile.membershipNumber.ifBlank { "—" } }
    val validUntil = when {
        m.validUntilTs != null -> formatter.format(m.validUntilTs)
        m.validUntil.isNotBlank() -> m.validUntil
        profile.membershipValidUntilTs != null -> formatter.format(profile.membershipValidUntilTs)
        profile.membershipValidUntil.isNotBlank() -> profile.membershipValidUntil
        else -> "—"
    }

    val fullName = listOf(profile.firstName, profile.lastName)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .ifBlank { profile.displayName.ifBlank { "Socio Bike4City" } }

    val statoRaw = m.status.ifBlank { profile.status }
    val stato = when (statoRaw.lowercase(Locale.ITALY)) {
        "active", "attiva" -> "Attiva"
        "pending", "in_attesa" -> "In attesa"
        "expired", "scaduta" -> "Scaduta"
        else -> statoRaw.ifBlank { "—" }
    }

    val pagamentoRaw = m.paymentStatus
    val pagamento = when (pagamentoRaw.lowercase(Locale.ITALY)) {
        "paid", "pagato" -> "Pagato"
        "unpaid", "non_pagato" -> "Non pagato"
        else -> pagamentoRaw.ifBlank { "—" }
    }

    // Colore scuro ufficiale Bike4City
    val textColor = Color(0xFF1B5E20)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = R.drawable.card_bg),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "TESSERA SOCIO",
                    style = MaterialTheme.typography.labelMedium,
                    color = textColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    fullName.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = textColor,
                    fontWeight = FontWeight.ExtraBold
                )

                Text(
                    "N. tessera: $numero",
                    style = MaterialTheme.typography.titleMedium,
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Validità: fino al $validUntil",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    InfoChipDark("Stato: $stato")
                    InfoChipDark("Pagamento: $pagamento")
                }
            }
        }
    }
}

/**
 * Chip con testo scuro per sfondi chiari
 */
@Composable
private fun InfoChipDark(text: String) {
    Surface(
        color = Color(0xFF1B5E20).copy(alpha = 0.1f), // leggero velo verde scuro
        shape = CircleShape,
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF1B5E20),
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditProfileScreen(uid: String, nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val profile by FirebaseRepo.observeUserProfile(uid).collectAsState(initial = null)

    var city by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var zip by remember { mutableStateOf("") }
    var newsletterOptIn by remember { mutableStateOf(false) }

    LaunchedEffect(profile) {
        profile?.let {
            city = it.city
            address = it.address
            zip = it.zip
            newsletterOptIn = it.newsletterOptIn
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Modifica Profilo") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->
        if (profile == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Info a sola lettura (perché protette dalle rules)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Dati anagrafici (non editabili)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                        Text("Nome: ${profile!!.firstName} ${profile!!.lastName}", style = MaterialTheme.typography.bodyMedium)
                        Text("Email: ${profile!!.email}", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Dati editabili", style = MaterialTheme.typography.titleSmall)

                OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("Città") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Indirizzo") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zip, onValueChange = { zip = it }, label = { Text("CAP") }, modifier = Modifier.fillMaxWidth())

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Switch(checked = newsletterOptIn, onCheckedChange = { newsletterOptIn = it })
                    Spacer(Modifier.width(12.dp))
                    Text("Iscriviti alla newsletter")
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val updated = profile!!.copy(
                                city = city,
                                address = address,
                                zip = zip,
                                newsletterOptIn = newsletterOptIn
                            )
                            try {
                                FirebaseRepo.updateUserProfileSafe(uid, updated)
                                Toast.makeText(ctx, "Profilo aggiornato!", Toast.LENGTH_SHORT).show()
                                nav.popBackStack()
                            } catch (e: Exception) {
                                Log.e("EditProfile", "Update failed", e)
                                Toast.makeText(ctx, "Errore: Permessi insufficienti o rete assente.", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salva")
                }
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
                        gpxText = gpxContent,
                        distanceKm = parsed.distanceMeters / 1000.0,
                        isOfficial = false,
                        ownerUid = uid,
                        createdByUid = uid,
                        createdAt = Date(),
                        status = "recorded",
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
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { res ->
        hasLocation = (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) || (res[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
    }

    DisposableEffect(Unit) {
        requestPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        onDispose { }
    }

    // --- FAB menu ---
    var fabOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = pageBg,

        floatingActionButton = {
            @Suppress("DEPRECATION")
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
                        label = "Percorsi da Bike4city",
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
                @Suppress("DEPRECATION")
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
    val pageBg = Color(0xFFF5F5F5)
    var selectedTab by remember { mutableIntStateOf(0) }

    val official by FirebaseRepo.observeOfficialRoutes().collectAsState(initial = emptyList())
    val community by FirebaseRepo.observeCommunityRoutes().collectAsState(initial = emptyList())

    Scaffold(
        containerColor = pageBg,
        topBar = {
            @Suppress("DEPRECATION")
            Column {
                TopAppBar(
                    title = { Text("Percorsi da Bike4city") },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Ufficiali") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Community") }
                    )
                }
            }
        }
    ) { padding ->
        val currentList = if (selectedTab == 0) official else community

        if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (selectedTab == 0) "Nessun percorso ufficiale disponibile." else "Nessun percorso della community approvato.",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(currentList) { r ->
                    RouteListItem(route = r, onClick = { nav.navigate("routeDetail/${r.id}") })
                }
            }
        }
    }
}

@Composable
private fun RouteListItem(route: Route, onClick: () -> Unit, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = route.title.ifBlank { "(senza nome)" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Badge categoria
                    if (route.b4cCategory != null) {
                        Spacer(Modifier.width(8.dp))
                        CategoryBadge(route.b4cCategory!!)
                    }
                }

                Spacer(Modifier.height(4.dp))
                val km = ((route.distanceKm ?: 0.0) * 10).roundToInt() / 10.0
                Text(
                    text = "Dist: $km km • Diff: ${prettyDifficulty(route.difficulty)} • Disl: ${prettyMeters(route.ascentM)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun CategoryBadge(category: String) {
    val (color, icon, label) = when (category.uppercase()) {
        "BIKE4CITY" -> Triple(Color(0xFF2E7D32), Icons.Default.Verified, "Ufficiale")
        "COMMUNITY" -> Triple(Color(0xFF0288D1), Icons.Default.Group, "Community")
        else -> Triple(Color.Gray, Icons.Outlined.Route, category)
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
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
    ) { padding ->
        if (route == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            @Suppress("DEPRECATION")
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            gpxText = gpx,
                            distanceKm = rec.distanceMeters / 1000.0,
                            isOfficial = false,
                            ownerUid = uid,
                            createdByUid = uid,
                            createdAt = Date(now),
                            status = "recorded",
                            source = "recorded"
                        )
                        val routeId = FirebaseRepo.saveRouteWithPointsAndMatch(route, rec.points)
                        
                        // ✅ Salva anche i segnali raccolti
                        rec.signals.forEach { s ->
                            FirebaseRepo.saveSignal(s.copy(routeId = routeId))
                        }

                        TrackRecorder.reset()

                        Toast.makeText(ctx, "Percorso e segnalazioni salvati!", Toast.LENGTH_SHORT).show()
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
                points = rec.points, // ✅ Non serve più la conversione, i tipi corrispondono!
                signals = remember(rec.signals) { rec.signals.map { it.copy(status = "active") } }, 
                showMyLocation = hasLocation,
                followMyLocation = rec.isRecording
            )

            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)
            ) {
                @Suppress("DEPRECATION")
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
            
            // ✅ PULSANTE SEGNALA CRITICITÀ
            if (rec.isRecording || rec.isPaused) {
                var showSignalMenu by remember { mutableStateOf(false) }
                
                FloatingActionButton(
                    onClick = { showSignalMenu = true },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp),
                    containerColor = Color(0xFFFFD600), // Giallo evidenziatore
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = "Segnala")
                }
                
                if (showSignalMenu) {
                    SignalSelectionMenu(
                        onDismiss = { showSignalMenu = false },
                        onSelected = { kind, cat, title ->
                            // TODO: Chiedere descrizione minima
                            TrackRecorder.addSignal(kind, cat, title)
                            showSignalMenu = false
                            Toast.makeText(ctx, "Segnalazione aggiunta!", Toast.LENGTH_SHORT).show()
                        }
                    )
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
                            onClick = { 
                                Log.d("RecordRouteScreen", "Click su AVVIA")
                                TrackRecordingService.start(ctx) 
                            },
                            modifier = Modifier.fillMaxWidth()
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
                            onClick = { 
                                TrackRecorder.resume(System.currentTimeMillis())
                                TrackRecordingService.resume(ctx) 
                            },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignalSelectionMenu(onDismiss: () -> Unit, onSelected: (String, String, String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Segnala Criticità", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            
            SignalCategory("🚨 Infrastrutturali", listOf(
                "buche" to "Buche",
                "asfalto_dissestato" to "Asfalto dissestato",
                "binari_tram" to "Binari del tram",
                "cordoli_killer" to "Cordoli killer",
                "ciclabili_interrotte" to "Ciclabili interrotte"
            ), "critical", onSelected)
            
            Spacer(Modifier.height(16.dp))
            
            SignalCategory("🚗 Comportamentali", listOf(
                "parche parking_selvaggio" to "Parcheggio selvaggio",
                "doppie_file" to "Doppie file croniche",
                "incroci_pericolosi" to "Attraversamenti pericolosi",
                "semafori_antibici" to "Semafori “anti-bici”"
            ), "critical", onSelected)
            
            Spacer(Modifier.height(16.dp))
            
            SignalCategory("⚠️ Temporanee", listOf(
                "cantieri" to "Cantieri",
                "lavori_infiniti" to "Lavori infiniti",
                "deviazioni" to "Deviazioni non segnalate",
                "transenne" to "Transenne creative"
            ), "critical", onSelected)
            
            Spacer(Modifier.height(16.dp))
            
            SignalCategory("📍 Punti di Interesse", listOf(
                "fontanella" to "Fontanella",
                "rastrelliera" to "Rastrelliera",
                "officina" to "Ciclo-officina"
            ), "poi", onSelected)
            
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SignalCategory(title: String, items: List<Pair<String, String>>, kind: String, onSelected: (String, String, String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.height(if (items.size > 2) 120.dp else 60.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(items) { (cat, label) ->
                Card(
                    onClick = { onSelected(kind, cat, label) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    }
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

    val mapLibrePoints = remember(route!!.gpxText) {
        runCatching { GpxParser.parse(route!!.gpxText).points }.getOrElse { emptyList() }
    }

    val ctx = LocalContext.current

    // engine & TTS: li ricreiamo quando cambia il percorso
    val engine = remember(mapLibrePoints) { TrackNavigationEngine(points = mapLibrePoints) }
    val tts = remember { TtsCoach(ctx) }
    LaunchedEffect(muted) { tts.muted = muted }

    DisposableEffect(Unit) {
        onDispose {
            tts.shutdown()
        }
    }

    // loop di navigazione: quando “following” è attivo, ascolta la posizione e aggiorna UI + voce
    LaunchedEffect(following, hasLocation, mapLibrePoints) {
        if (!following || !hasLocation || mapLibrePoints.size < 2) return@LaunchedEffect
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
            if (mapLibrePoints.isNotEmpty()) {
                val boundsBuilder = LatLngBounds.Builder()
                mapLibrePoints.forEach { boundsBuilder.include(it) }
                ThunderforestMapLibre(
                    modifier = Modifier.fillMaxSize(),
                    points = mapLibrePoints,
                    initialBounds = boundsBuilder.build(),
                    showMyLocation = hasLocation,
                    // ✅ di default centra il percorso; quando attivi “Segui”, allora segue te
                    followMyLocation = following
                )
            }

            if (following && mapLibrePoints.isNotEmpty()) {
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        route!!.title.ifBlank { "Percorso" },
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    if (route!!.b4cCategory != null) {
                        CategoryBadge(route!!.b4cCategory!!)
                    }
                }

                val km = ((route!!.distanceKm ?: 0.0) * 10).roundToInt() / 10.0
                val diffTxt = prettyDifficulty(route!!.difficulty)
                val ascentTxt = prettyMeters(route!!.ascentM)
                Text("Distanza: $km km")
                Text("Difficoltà: $diffTxt")
                Text("Dislivello: $ascentTxt")

                if (route!!.b4cCategory == "BIKE4CITY") {
                    Text("Percorso ufficiale dell'associazione", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                } else if (route!!.b4cCategory == "COMMUNITY") {
                    Text("Percorso approvato dalla Community", color = Color(0xFF0288D1), fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { if (hasLocation) following = true },
                        modifier = Modifier.weight(1f),
                        enabled = hasLocation && mapLibrePoints.size >= 2
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoScreen(nav: NavHostController) {
    val scroll = rememberScrollState()
    val pageBg = Color(0xFFF5F5F5)
    val ctx = LocalContext.current

    Scaffold(
        containerColor = pageBg,
        topBar = {
            TopAppBar(
                title = { Text("Info & Privacy") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            Text("Bike4City Hub", style = MaterialTheme.typography.headlineMedium)
            Text(
                "© 2025 Bike4City APS – Tutti i diritti riservati",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            InfoCard("Chi siamo") {
                Text(
                    "Bike4City Hub è un project indipendente promosso da Bike4City APS.\n" +
                            "Nasce per sostenere una mobilità più giusta, sicura e accessibile, mettendo al centro la bicicletta come strumento di cambiamento urbano, culturale e sociale.\n\n" +
                            "Crediamo che la città si capisca meglio a pedali.\n" +
                            "E quando la capisci davvero, non puoi fare a meno di pretenderla migliore:\n" +
                            "più sicura, più vivibile, meno inquinata, più umana.\n\n" +
                            "Bike4City Hub è uno spazio condiviso dove soci e cittadini possono:\n\n" +
                            "• esplorare e condividere percorsi ciclabili urbani e cicloturistici\n" +
                            "• segnalare criticità e buone pratiche sul territorio\n" +
                            "• contribuire a una mappa collettiva della città reale, vissuta, pedalata\n\n" +
                            "Non è solo un’app: è uno strumento di partecipazione attiva e di cittadinanza consapevole."
                )
            }

            InfoCard("Dati personali e privacy") {
                Text(
                    "L’app utilizza la posizione GPS solo quando necessario per mostrare la tua posizione o registrare un percorso.\n\n" +
                            "Non vendiamo dati, non facciamo profilazione pubblicitaria e non tracciamo gli utenti a fini commerciali.\n\n" +
                            "Se attive funzioni di salvataggio/sincronizzazione, alcuni dati possono essere archiviati su servizi cloud (es. Firebase)."
                )
            }

            InfoCard("I tuoi diritti") {
                Text(
                    "Puoi chiedere accesso, cancellazione ed esportazione dei tuoi dati (dove applicabile).\n\n" +
                            "Scrivici: rispondiamo in modo umano, non burocratico."
                )
            }

            InfoCard("Tracce e responsabilità") {
                Text(
                    "Le tracce che crei restano tue.\n\n" +
                            "L’app è un supporto: non sostituisce il Codice della Strada, il buon senso e la valutazione dei rischi."
                )
            }

            InfoCard("Riconoscimenti") {
                val recognitions = remember {
                    buildAnnotatedString {
                        append("• ")
                        pushStringAnnotation(tag = "LINK", annotation = "https://www.openstreetmap.org/copyright")
                        withStyle(SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline)) {
                            append("OpenStreetMap")
                        }
                        pop()
                        append(" e contributori\n• ")
                        
                        pushStringAnnotation(tag = "LINK", annotation = "https://maplibre.org/")
                        withStyle(SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline)) {
                            append("MapLibre")
                        }
                        pop()
                        append("\n• ")

                        pushStringAnnotation(tag = "LINK", annotation = "https://firebase.google.com/")
                        withStyle(SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline)) {
                            append("Firebase")
                        }
                        pop()
                        append("\n• Provider mappe (es. ")

                        pushStringAnnotation(tag = "LINK", annotation = "https://www.thunderforest.com/")
                        withStyle(SpanStyle(color = Color(0xFF1976D2), textDecoration = TextDecoration.Underline)) {
                            append("Thunderforest")
                        }
                        pop()
                        append(")\n\nMarchi e loghi appartengono ai rispettivi proprietari.")
                    }
                }

                ClickableText(
                    text = recognitions,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    onClick = { offset ->
                        recognitions.getStringAnnotations("LINK", offset, offset)
                            .firstOrNull()?.let { ann -> openLink(ctx, ann.item) }
                    }
                )
            }

            InfoCard("Contatti") {
                val email = "admin.hub@bike4city.it"
                Text(
                    text = "Email: $email",
                    modifier = Modifier.clickable {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:$email"))
                        ctx.startActivity(i)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )

                val site = "https://www.bike4city.it"
                Text(
                    text = "Sito: www.bike4city.it",
                    modifier = Modifier.clickable {
                        val i = Intent(Intent.ACTION_VIEW, Uri.parse(site))
                        ctx.startActivity(i)
                    },
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
            }


            Spacer(Modifier.height(12.dp))
            Text(
                "Ultimo aggiornamento: 12-2025",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

fun emptyStateList(): List<Route> = emptyList()
