package it.bike4city.hub.trackerpro

internal class Smoother(private val cfg: SmoothingConfig) {
    private var has = false
    private var lat = 0.0
    private var lon = 0.0

    fun reset() { has = false }

    fun smooth(rawLat: Double, rawLon: Double, accuracyM: Float?): Pair<Double, Double> {
        if (!cfg.enabled) return rawLat to rawLon

        val alpha = when {
            accuracyM == null -> cfg.alphaMedium
            accuracyM.toDouble() <= cfg.goodAccM -> cfg.alphaGood
            accuracyM.toDouble() >= cfg.badAccM -> cfg.alphaBad
            else -> {
                val t = ((accuracyM.toDouble() - cfg.goodAccM) / (cfg.badAccM - cfg.goodAccM)).coerceIn(0.0, 1.0)
                cfg.alphaMedium + (cfg.alphaBad - cfg.alphaMedium) * t
            }
        }

        if (!has) {
            lat = rawLat
            lon = rawLon
            has = true
            return lat to lon
        }

        lat = (1 - alpha) * lat + alpha * rawLat
        lon = (1 - alpha) * lon + alpha * rawLon
        return lat to lon
    }
}
