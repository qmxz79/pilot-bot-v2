package com.qmxz.pilotbotv2.nav

/** One maneuver step of a driving route. Distances in metres. */
data class RouteStep(
    /** Spoken instruction, e.g. "沿江滨二路继续行驶 800 米". */
    val instruction: String,
    /** Length of this step, in metres. */
    val distanceMeters: Float,
    /** Road this step runs on, when the provider knows it. */
    val roadName: String? = null,
    /**
     * Shape of this step as GCJ-02 (lat, lng) points, when the provider
     * knows it. NavigationSession uses it for off-route detection; an
     * empty list means that step cannot be checked (detection is skipped
     * for it, never failed).
     */
    val polyline: List<Pair<Double, Double>> = emptyList()
)

/** A planned driving route. Durations in seconds, distances in metres. */
data class PlannedRoute(
    val destinationName: String,
    val totalDistanceMeters: Float,
    val totalDurationSeconds: Long,
    val steps: List<RouteStep>
)

/** Route-planning outcome: a route, or a human-readable reason it failed. */
sealed class RouteResult {
    data class Ok(val route: PlannedRoute) : RouteResult()
    data class Failed(val message: String) : RouteResult()
}

/** Geocoding outcome. Coordinates are GCJ-02, like [NavLocation]. */
sealed class GeocodeResult {
    data class Ok(
        val latGcj02: Double,
        val lngGcj02: Double,
        val address: String?
    ) : GeocodeResult()

    data class Failed(val message: String) : GeocodeResult()
}

/**
 * Destination lookup + route planning. Implementations must never throw
 * from these methods: every failure comes back as Failed with a message
 * the driver can understand, exactly like [NavigationProvider].
 *
 * Results are delivered on the main thread, so ViewModel code can use
 * them directly.
 */
interface RouteProvider {
    val providerName: String

    /** Resolve a place name to coordinates. [city] narrows the search. */
    fun geocode(query: String, city: String?, onResult: (GeocodeResult) -> Unit)

    /** Plan a driving route between two GCJ-02 points. */
    fun planRoute(
        fromLatGcj02: Double,
        fromLngGcj02: Double,
        toLatGcj02: Double,
        toLngGcj02: Double,
        destinationName: String,
        onResult: (RouteResult) -> Unit
    )
}
