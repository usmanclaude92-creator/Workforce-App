package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import com.example.data.entity.ProjectEntity
import com.example.model.GeofenceStatus
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.tasks.await

data class DeviceLocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val isMock: Boolean,
    val isRealGps: Boolean,
    val elapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos(),
    val isStale: Boolean = false
)

data class GeofenceValidationResult(
    val isWithinRadius: Boolean,
    val distanceMeters: Float,
    val allowedRadiusMeters: Float,
    val isAccuracyAcceptable: Boolean,
    val accuracyMeters: Float,
    val isMockLocation: Boolean,
    val status: GeofenceStatus,
    val message: String
)

class LocationHelper(private val context: Context) {

    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): DeviceLocationSnapshot? {
        return try {
            val tokenSource = CancellationTokenSource()
            val location: Location? = fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                tokenSource.token
            ).await()

            if (location != null) {
                val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    location.isMock
                } else {
                    @Suppress("DEPRECATION")
                    location.isFromMockProvider
                }
                val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
                val isStale = ageMillis > 60_000L // Stale if captured more than 60 seconds ago

                DeviceLocationSnapshot(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    isMock = isMock,
                    isRealGps = !isMock,
                    elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                    isStale = isStale
                )
            } else {
                // Try last known location
                val lastLoc = fusedClient.lastLocation.await()
                if (lastLoc != null) {
                    val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        lastLoc.isMock
                    } else {
                        @Suppress("DEPRECATION")
                        lastLoc.isFromMockProvider
                    }
                    val ageMillis = (SystemClock.elapsedRealtimeNanos() - lastLoc.elapsedRealtimeNanos) / 1_000_000L
                    val isStale = ageMillis > 60_000L

                    DeviceLocationSnapshot(
                        latitude = lastLoc.latitude,
                        longitude = lastLoc.longitude,
                        accuracy = lastLoc.accuracy,
                        isMock = isMock,
                        isRealGps = !isMock,
                        elapsedRealtimeNanos = lastLoc.elapsedRealtimeNanos,
                        isStale = isStale
                    )
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Calculates distance between coordinates using Google Location Services (Location.distanceBetween).
     */
    fun calculateDistanceMeters(
        startLat: Double,
        startLng: Double,
        endLat: Double,
        endLng: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(startLat, startLng, endLat, endLng, results)
        return results[0]
    }

    /**
     * Checks if coordinates fall within the assigned project work site radius.
     */
    fun validateGeofence(
        location: DeviceLocationSnapshot?,
        project: ProjectEntity
    ): GeofenceValidationResult {
        if (location == null) {
            return GeofenceValidationResult(
                isWithinRadius = false,
                distanceMeters = -1f,
                allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                isAccuracyAcceptable = false,
                accuracyMeters = 0f,
                isMockLocation = false,
                status = GeofenceStatus.GPS_UNAVAILABLE,
                message = "GPS location is unavailable. Please enable location services to verify work site presence."
            )
        }

        if (location.isMock) {
            val dist = calculateDistanceMeters(location.latitude, location.longitude, project.latitude, project.longitude)
            return GeofenceValidationResult(
                isWithinRadius = false,
                distanceMeters = dist,
                allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                isAccuracyAcceptable = false,
                accuracyMeters = location.accuracy,
                isMockLocation = true,
                status = GeofenceStatus.MOCK_LOCATION_DETECTED,
                message = "Mock/Spoofed GPS location detected. Clock-in submission is blocked."
            )
        }

        if (location.isStale) {
            val dist = calculateDistanceMeters(location.latitude, location.longitude, project.latitude, project.longitude)
            return GeofenceValidationResult(
                isWithinRadius = false,
                distanceMeters = dist,
                allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                isAccuracyAcceptable = false,
                accuracyMeters = location.accuracy,
                isMockLocation = false,
                status = GeofenceStatus.ACCURACY_TOO_LOW,
                message = "GPS telemetry is stale (>60s old). Awaiting fresh satellite lock."
            )
        }

        val distance = calculateDistanceMeters(location.latitude, location.longitude, project.latitude, project.longitude)
        val isAccuracyAcceptable = location.accuracy <= project.maxGpsAccuracyMeters
        val isWithinRadius = distance <= project.geofenceRadiusMeters

        return when {
            !isWithinRadius -> {
                val excess = (distance - project.geofenceRadiusMeters).toInt()
                GeofenceValidationResult(
                    isWithinRadius = false,
                    distanceMeters = distance,
                    allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                    isAccuracyAcceptable = isAccuracyAcceptable,
                    accuracyMeters = location.accuracy,
                    isMockLocation = false,
                    status = GeofenceStatus.OUTSIDE_GEOFENCE,
                    message = "Outside work site perimeter by $excess m (Allowed radius: ${project.geofenceRadiusMeters.toInt()}m, Current distance: ${distance.toInt()}m)."
                )
            }
            !isAccuracyAcceptable -> {
                GeofenceValidationResult(
                    isWithinRadius = true,
                    distanceMeters = distance,
                    allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                    isAccuracyAcceptable = false,
                    accuracyMeters = location.accuracy,
                    isMockLocation = false,
                    status = GeofenceStatus.ACCURACY_TOO_LOW,
                    message = "GPS accuracy is below required threshold (${location.accuracy.toInt()}m > ${project.maxGpsAccuracyMeters.toInt()}m limit)."
                )
            }
            else -> {
                GeofenceValidationResult(
                    isWithinRadius = true,
                    distanceMeters = distance,
                    allowedRadiusMeters = project.geofenceRadiusMeters.toFloat(),
                    isAccuracyAcceptable = true,
                    accuracyMeters = location.accuracy,
                    isMockLocation = false,
                    status = GeofenceStatus.INSIDE_GEOFENCE,
                    message = "Verified on-site: ${distance.toInt()}m from ${project.projectName} center."
                )
            }
        }
    }
}
