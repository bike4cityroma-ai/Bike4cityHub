package it.bike4city.hub.ui.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import it.bike4city.hub.maps.engine.BikeMap
import it.bike4city.hub.maps.engine.MapEngine

@Composable
fun MapEnginesTestScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        BikeMap(
            modifier = Modifier.fillMaxSize(),
            engine = MapEngine.OSMAND
        )
    }
}
