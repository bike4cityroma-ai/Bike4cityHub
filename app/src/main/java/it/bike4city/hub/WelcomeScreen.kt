package it.bike4city.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(
    onLoginClick: () -> Unit,
    onSignupClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(primary, secondary)
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.DirectionsBike,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Bike4City Hub",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    "Benvenuto! Qui trovi comunicazioni dell’associazione, tessera digitale e tracce bici.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
                )

                Spacer(Modifier.height(22.dp))

                // “Menu” iniziale: 4 card
                WelcomeMenuGrid()
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Accedi")
                }
                TextButton(
                    onClick = onSignupClick
                ) {
                    Text("Non hai un account? Registrati", color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

@Composable
private fun WelcomeMenuGrid() {
    val items = listOf(
        Triple("Bacheca", "Avvisi e news", Icons.Outlined.Notifications),
        Triple("Tracce", "Percorsi ufficiali", Icons.Outlined.Route),
        Triple("Tessera", "Sempre con te", Icons.Outlined.Badge),
        Triple("Profilo", "Account e preferenze", Icons.Outlined.Person)
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (title, subtitle, icon) ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(110.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.height(8.dp))
                            Text(title, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium)
                            Text(subtitle, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}