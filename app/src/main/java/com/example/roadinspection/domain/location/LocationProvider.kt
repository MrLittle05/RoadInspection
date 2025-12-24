package com.example.roadinspection.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback as GmsLocationCallback
import com.google.android.gms.location.LocationRequest as GmsLocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.LocationServices as GmsLocationServices
import com.google.android.gms.location.Priority as GmsPriority
import com.huawei.hms.api.HuaweiApiAvailability
import com.huawei.hms.location.LocationCallback as HmsLocationCallback
import com.huawei.hms.location.LocationRequest as HmsLocationRequest
import com.huawei.hms.location.LocationResult as HmsLocationResult
import com.huawei.hms.location.LocationServices as HmsLocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A reusable location service provider that abstracts away the underlying implementation (GMS or HMS).
 * It provides continuous location updates and GPS signal level via Flow.
 * @param context The application context.
 */
class LocationProvider(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _locationState = MutableStateFlow<Location?>(null)
    val locationFlow: StateFlow<Location?> = _locationState

    private val _gpsLevelState = MutableStateFlow(0)
    val gpsLevelFlow: StateFlow<Int> = _gpsLevelState

    private val locationUpdateProvider: LocationUpdateProvider

    init {
        locationUpdateProvider = if (isGmsAvailable()) {
            GmsLocationProvider(context, _locationState)
        } else if (isHmsAvailable()) {
            HmsLocationProvider(context, _locationState)
        } else {
            // Fallback or error handling if neither is available
            // For simplicity, we'''ll use a no-op implementation
            object : LocationUpdateProvider {
                override fun startLocationUpdates() {}
                override fun stopLocationUpdates() {}
            }
        }
    }

    private fun isGmsAvailable(): Boolean {
        return GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }

    private fun isHmsAvailable(): Boolean {
        return HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) == com.huawei.hms.api.ConnectionResult.SUCCESS
    }

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            super.onSatelliteStatusChanged(status)
            var satellitesWithGoodSignal = 0
            for (i in 0 until status.satelliteCount) {
                if (status.getCn0DbHz(i) > 30) {
                    satellitesWithGoodSignal++
                }
            }
            _gpsLevelState.value = when {
                satellitesWithGoodSignal >= 10 -> 4
                satellitesWithGoodSignal >= 7 -> 3
                satellitesWithGoodSignal >= 4 -> 2
                satellitesWithGoodSignal > 0 -> 1
                else -> 0
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        locationUpdateProvider.startLocationUpdates()
        // Register GNSS status callback for signal strength
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), gnssStatusCallback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(gnssStatusCallback)
        }
    }

    fun stopLocationUpdates() {
        locationUpdateProvider.stopLocationUpdates()
        locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
    }
}

/**
 * Interface for location update providers (GMS/HMS).
 */
private interface LocationUpdateProvider {
    fun startLocationUpdates()
    fun stopLocationUpdates()
}

/**
 * GMS implementation of LocationUpdateProvider.
 */
private class GmsLocationProvider(
    context: Context,
    private val locationState: MutableStateFlow<Location?>
) : LocationUpdateProvider {
    private val fusedLocationClient = GmsLocationServices.getFusedLocationProviderClient(context)

    private val locationCallback = object : GmsLocationCallback() {
        override fun onLocationResult(locationResult: GmsLocationResult) {
            locationState.value = locationResult.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        val locationRequest = GmsLocationRequest.Builder(GmsPriority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .setMaxUpdateDelayMillis(1000)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

/**
 * HMS implementation of LocationUpdateProvider.
 */
private class HmsLocationProvider(
    context: Context,
    private val locationState: MutableStateFlow<Location?>
) : LocationUpdateProvider {
    private val fusedLocationClient = HmsLocationServices.getFusedLocationProviderClient(context)

    private val locationCallback = object : HmsLocationCallback() {
        override fun onLocationResult(locationResult: HmsLocationResult) {
            locationState.value = locationResult.lastLocation
        }
    }

    @SuppressLint("MissingPermission")
    override fun startLocationUpdates() {
        val locationRequest = HmsLocationRequest.create().apply {
            priority = HmsLocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            fastestInterval = 500
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    override fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}
