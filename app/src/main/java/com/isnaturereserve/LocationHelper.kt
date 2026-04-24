package com.isnaturereserve

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult as GmsLocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed class LocationResult {
    data class Ok(val location: Location) : LocationResult()
    object PermissionMissing : LocationResult()
    object LocationDisabled : LocationResult()
    data class Error(val message: String) : LocationResult()
}

class LocationHelper(private val ctx: Context) {

    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasAnyLocationPermission(): Boolean =
        hasFinePermission() ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private fun playServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) ==
            ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): LocationResult {
        if (!hasAnyLocationPermission()) return LocationResult.PermissionMissing
        if (!isLocationEnabled()) return LocationResult.LocationDisabled

        return try {
            if (playServicesAvailable()) fusedCurrent() else managerCurrent()
        } catch (t: Throwable) {
            LocationResult.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun fusedCurrent(): LocationResult {
        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val cts = CancellationTokenSource()
        val loc: Location? = try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token).await()
        } catch (t: Throwable) {
            cts.cancel()
            return LocationResult.Error(t.message ?: "location request failed")
        }
        return if (loc != null) LocationResult.Ok(loc)
        else LocationResult.Error("no fix")
    }

    @SuppressLint("MissingPermission")
    private suspend fun managerCurrent(): LocationResult = suspendCancellableCoroutine { cont ->
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true).filter {
            it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER
        }
        if (providers.isEmpty()) {
            cont.resume(LocationResult.Error("no location providers"))
            return@suspendCancellableCoroutine
        }
        val best = providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best != null) {
            cont.resume(LocationResult.Ok(best))
            return@suspendCancellableCoroutine
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: Location) {
                lm.removeUpdates(this)
                if (cont.isActive) cont.resume(LocationResult.Ok(location))
            }
            @Deprecated("required by interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            val provider = if (providers.contains(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else providers.first()
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(provider, listener, ctx.mainLooper)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resumeWithException(t)
        }
        cont.invokeOnCancellation { lm.removeUpdates(listener) }
    }

    @SuppressLint("MissingPermission")
    fun locationUpdates(intervalMs: Long = 5_000L): Flow<LocationResult> = callbackFlow {
        if (!hasAnyLocationPermission()) {
            trySend(LocationResult.PermissionMissing)
            close()
            return@callbackFlow
        }
        if (!isLocationEnabled()) {
            trySend(LocationResult.LocationDisabled)
            close()
            return@callbackFlow
        }
        if (!playServicesAvailable()) {
            val oneShot = managerCurrent()
            trySend(oneShot)
            close()
            return@callbackFlow
        }

        val client = LocationServices.getFusedLocationProviderClient(ctx)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: GmsLocationResult) {
                val loc = result.lastLocation
                if (loc != null) trySend(LocationResult.Ok(loc))
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }
}
