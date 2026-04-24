package com.isnaturereserve

import android.Manifest
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.isnaturereserve.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * UI states. We enumerate them up-front so the view logic is a single `render(state)`
 * call rather than scattered if/else chains.
 */
sealed class UiState {
    object LoadingData : UiState()
    data class LoadFailed(val message: String) : UiState()
    object NeedsPermission : UiState()
    object PermissionPermanentlyDenied : UiState()
    object LocationDisabled : UiState()
    object WaitingForFix : UiState()
    data class FixFailed(val message: String) : UiState()
    data class HasFix(
        val matches: List<ReserveIndex.Match>,
        val lat: Double,
        val lon: Double,
        val accuracyMeters: Float,
        val exitPoint: ReserveIndex.ExitPoint? = null,
        val nearbyReserve: ReserveIndex.NearbyReserve? = null,
    ) : UiState()
}

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var locationHelper: LocationHelper
    private lateinit var sensorManager: SensorManager

    private var index: ReserveIndex? = null
    private var state: UiState = UiState.LoadingData
    private var bearingToExit: Float? = null
    private var deviceHeading = 0f
    private var locationJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val anyGranted = grants.values.any { it }
        if (anyGranted) {
            startLocationUpdates()
        } else {
            val showRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            render(if (showRationale) UiState.NeedsPermission else UiState.PermissionPermanentlyDenied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationHelper = LocationHelper(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        render(UiState.LoadingData)
        loadIndex()
    }

    private fun loadIndex() {
        render(UiState.LoadingData)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReserveIndex.loadFromAssets(this@MainActivity) }
            }
            result.onSuccess {
                index = it
                start()
            }.onFailure { t ->
                render(UiState.LoadFailed(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotation != null) {
            sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
        }
        if (index != null && state is UiState.PermissionPermanentlyDenied &&
            locationHelper.hasAnyLocationPermission()
        ) {
            start()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotMat = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotMat, orientation)
        deviceHeading = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val b = bearingToExit ?: return
        binding.exitCompass.setDirection(b, deviceHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun start() {
        if (!locationHelper.hasAnyLocationPermission()) {
            render(UiState.NeedsPermission)
            return
        }
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        locationJob?.cancel()
        render(UiState.WaitingForFix)
        locationJob = lifecycleScope.launch {
            locationHelper.locationUpdates().collect { result ->
                val newState: UiState = when (result) {
                    is LocationResult.PermissionMissing -> UiState.NeedsPermission
                    is LocationResult.LocationDisabled -> UiState.LocationDisabled
                    is LocationResult.Error -> UiState.FixFailed(result.message)
                    is LocationResult.Ok -> {
                        val loc = result.location
                        val idx = index
                        val matches = idx?.query(loc.longitude, loc.latitude).orEmpty()
                        val exit = if (matches.isNotEmpty()) idx?.nearestExit(loc.longitude, loc.latitude) else null
                        val nearby = if (matches.isEmpty()) idx?.nearestReserve(loc.longitude, loc.latitude) else null
                        UiState.HasFix(matches, loc.latitude, loc.longitude, loc.accuracy, exit, nearby)
                    }
                }
                render(newState)
            }
        }
    }

    private fun render(newState: UiState) {
        state = newState
        val v = binding
        v.spinner.visibility = View.GONE
        v.primaryButton.visibility = View.GONE
        v.caveat.visibility = View.GONE
        v.exitCompass.visibility = View.GONE
        v.coords.text = ""
        bearingToExit = null

        when (newState) {
            is UiState.LoadingData -> {
                v.verdict.text = ""
                v.detail.text = getString(R.string.loading_data)
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.LoadFailed -> {
                v.verdict.text = ""
                v.detail.text = "Couldn't load reserve data: ${newState.message}"
                v.primaryButton.text = getString(R.string.retry)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener { loadIndex() }
            }
            is UiState.NeedsPermission -> {
                v.verdict.text = ""
                v.detail.text = getString(R.string.permission_rationale)
                v.primaryButton.text = getString(R.string.grant_permission)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ))
                }
            }
            is UiState.PermissionPermanentlyDenied -> {
                v.verdict.text = ""
                v.detail.text = getString(R.string.permission_denied)
                v.primaryButton.text = getString(R.string.open_settings)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                    startActivity(intent)
                }
            }
            is UiState.LocationDisabled -> {
                v.verdict.text = ""
                v.detail.text = getString(R.string.location_disabled)
                v.primaryButton.text = getString(R.string.open_settings)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            is UiState.WaitingForFix -> {
                v.verdict.text = ""
                v.detail.text = getString(R.string.waiting_for_fix)
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.FixFailed -> {
                v.verdict.text = ""
                v.detail.text = "${getString(R.string.fix_timeout)}\n(${newState.message})"
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.HasFix -> renderFix(newState)
        }
    }

    private fun renderFix(s: UiState.HasFix) {
        val v = binding
        if (s.matches.isEmpty()) {
            v.verdict.text = getString(R.string.no_not_in_reserve)
            v.verdict.setTextColor(ContextCompat.getColor(this, R.color.red_no))

            s.nearbyReserve?.let { nr ->
                val label = if (nr.type == ReserveIndex.Type.PARK) "National park" else "Nature reserve"
                val displayName = when {
                    nr.nameEn.isNotBlank() -> nr.nameEn
                    nr.name.isNotBlank() -> nr.name
                    else -> "(unnamed)"
                }
                val dist = nr.distanceMeters
                val formatted = if (dist >= 1000f) {
                    String.format(Locale.US, "%.1f km", dist / 1000f)
                } else {
                    String.format(Locale.US, "%d m", dist.toInt())
                }
                v.detail.text = getString(R.string.nearest_reserve, formatted, label, displayName)

                val results = FloatArray(2)
                Location.distanceBetween(s.lat, s.lon, nr.lat, nr.lon, results)
                bearingToExit = results[1]
                v.exitCompass.setDirection(results[1], deviceHeading)
                v.exitCompass.visibility = View.VISIBLE
            } ?: run {
                v.detail.text = getString(R.string.not_in_any_reserve)
            }
        } else {
            v.verdict.text = getString(R.string.yes_in_reserve)
            v.verdict.setTextColor(ContextCompat.getColor(this, R.color.green_yes))
            val lines = s.matches.map { m ->
                val label = if (m.type == ReserveIndex.Type.PARK) "National park" else "Nature reserve"
                val displayName = when {
                    m.nameEn.isNotBlank() -> m.nameEn
                    m.name.isNotBlank() -> m.name
                    else -> "(unnamed)"
                }
                "$label: $displayName"
            }
            val exitLine = s.exitPoint?.let { ep ->
                val dist = ep.distanceMeters
                val formatted = if (dist >= 1000f) {
                    String.format(Locale.US, "%.1f km", dist / 1000f)
                } else {
                    String.format(Locale.US, "%d m", dist.toInt())
                }
                getString(R.string.nearest_exit, formatted)
            }
            v.detail.text = if (exitLine != null) {
                (lines + "" + exitLine).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }

            s.exitPoint?.let { ep ->
                val results = FloatArray(2)
                Location.distanceBetween(s.lat, s.lon, ep.lat, ep.lon, results)
                bearingToExit = results[1]
                v.exitCompass.setDirection(results[1], deviceHeading)
                v.exitCompass.visibility = View.VISIBLE
            }
        }

        val accInt = s.accuracyMeters.toInt()
        v.coords.text = String.format(
            Locale.US,
            "%.5f, %.5f  (±%dm)",
            s.lat, s.lon, accInt,
        )

        if (s.accuracyMeters > 30f) {
            v.caveat.text = getString(R.string.accuracy_caveat)
            v.caveat.visibility = View.VISIBLE
        }
    }
}
