package com.reserveboundary

import android.Manifest
import android.content.Intent
import android.hardware.GeomagneticField
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.reserveboundary.databinding.ActivityMainBinding
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
    /** Magnetic bearing (degrees) toward the displayed target. The rotation-vector sensor
     *  reports magnetic-north heading, so the compass arithmetic is consistent only if
     *  this value is also magnetic — see [trueToMagnetic]. */
    private var bearingToExit: Float? = null
    private var deviceHeading = 0f
    private var declination = 0f
    private var locationJob: Job? = null
    private var syncingLanguagePicker = false

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
        binding.root.layoutDirection = if (AppLanguage.isHebrew()) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }

        locationHelper = LocationHelper(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        setupLanguagePicker()

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
        // Re-enter the flow when the user returns from system settings (granted permission
        // or enabled location). Without this, the screen stays stuck on the previous error.
        if (index != null && (state is UiState.PermissionPermanentlyDenied ||
                state is UiState.LocationDisabled)) {
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
        // flowWithLifecycle(STARTED) cancels and re-collects the upstream flow on every
        // background/foreground transition, which stops the fused location callback while
        // the activity isn't visible. lifecycleScope alone would keep it firing until
        // DESTROYED — wasted battery and a privacy footgun.
        locationJob = lifecycleScope.launch {
            locationHelper.locationUpdates()
                .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
                .collect { result ->
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
        bindStaticText()
        v.spinner.visibility = View.GONE
        v.primaryButton.visibility = View.GONE
        v.caveat.visibility = View.GONE
        v.exitCompass.visibility = View.GONE
        v.coords.text = ""
        bearingToExit = null

        when (newState) {
            is UiState.LoadingData -> {
                v.verdict.text = ""
                v.detail.text = tr(R.string.loading_data)
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.LoadFailed -> {
                v.verdict.text = ""
                v.detail.text = tr(R.string.load_failed, newState.message)
                v.primaryButton.text = tr(R.string.retry)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener { loadIndex() }
            }
            is UiState.NeedsPermission -> {
                v.verdict.text = ""
                v.detail.text = tr(R.string.permission_rationale)
                v.primaryButton.text = tr(R.string.grant_permission)
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
                v.detail.text = tr(R.string.permission_denied)
                v.primaryButton.text = tr(R.string.open_settings)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.fromParts("package", packageName, null))
                    startActivity(intent)
                }
            }
            is UiState.LocationDisabled -> {
                v.verdict.text = ""
                v.detail.text = tr(R.string.location_disabled)
                v.primaryButton.text = tr(R.string.open_settings)
                v.primaryButton.visibility = View.VISIBLE
                v.primaryButton.setOnClickListener {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            is UiState.WaitingForFix -> {
                v.verdict.text = ""
                v.detail.text = tr(R.string.waiting_for_fix)
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.FixFailed -> {
                v.verdict.text = ""
                v.detail.text = tr(
                    R.string.fix_timeout_with_reason,
                    tr(R.string.fix_timeout),
                    newState.message,
                )
                v.spinner.visibility = View.VISIBLE
            }
            is UiState.HasFix -> renderFix(newState)
        }
    }

    private fun renderFix(s: UiState.HasFix) {
        val v = binding
        // Compute local magnetic declination once per fix; used to convert any true bearing
        // we display in the compass into the magnetic frame the rotation sensor reports in.
        declination = GeomagneticField(
            s.lat.toFloat(), s.lon.toFloat(), 0f, System.currentTimeMillis()
        ).declination

        if (s.matches.isEmpty()) {
            v.verdict.text = tr(R.string.no_not_in_reserve)
            v.verdict.setTextColor(ContextCompat.getColor(this, R.color.red_no))

            s.nearbyReserve?.let { nr ->
                val label = featureTypeLabel(nr.type)
                val displayName = reserveDisplayName(nr.name, nr.nameEn)
                val formatted = formatDistance(nr.distanceMeters)
                v.detail.text = tr(R.string.nearest_reserve, formatted, label, displayName)

                val results = FloatArray(2)
                Location.distanceBetween(s.lat, s.lon, nr.lat, nr.lon, results)
                val mag = trueToMagnetic(results[1])
                bearingToExit = mag
                v.exitCompass.setDirection(mag, deviceHeading)
                v.exitCompass.visibility = View.VISIBLE
            } ?: run {
                v.detail.text = tr(R.string.not_in_any_reserve)
            }
        } else {
            v.verdict.text = tr(R.string.yes_in_reserve)
            v.verdict.setTextColor(ContextCompat.getColor(this, R.color.green_yes))
            val lines = s.matches.map { m ->
                val label = featureTypeLabel(m.type)
                val displayName = reserveDisplayName(m.name, m.nameEn)
                "$label: $displayName"
            }
            val exitLine = s.exitPoint?.let { ep ->
                val formatted = formatDistance(ep.distanceMeters)
                tr(R.string.nearest_exit, formatted)
            }
            v.detail.text = if (exitLine != null) {
                (lines + "" + exitLine).joinToString("\n")
            } else {
                lines.joinToString("\n")
            }

            s.exitPoint?.let { ep ->
                val results = FloatArray(2)
                Location.distanceBetween(s.lat, s.lon, ep.lat, ep.lon, results)
                val mag = trueToMagnetic(results[1])
                bearingToExit = mag
                v.exitCompass.setDirection(mag, deviceHeading)
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
            v.caveat.text = tr(R.string.accuracy_caveat)
            v.caveat.visibility = View.VISIBLE
        }
    }

    /** Location.distanceBetween returns degrees east of TRUE north; the rotation-vector
     *  sensor reports heading relative to MAGNETIC north. Subtracting the local
     *  declination converts true bearing to magnetic so the two are comparable. */
    private fun trueToMagnetic(trueBearingDeg: Float): Float = trueBearingDeg - declination

    private fun featureTypeLabel(type: ReserveIndex.Type): String =
        tr(
            if (type == ReserveIndex.Type.PARK) {
                R.string.feature_type_national_park
            } else {
                R.string.feature_type_nature_reserve
            }
        )

    private fun setupLanguagePicker() {
        syncingLanguagePicker = true
        binding.languageOptions.check(
            if (AppLanguage.isHebrew()) R.id.languageHebrew else R.id.languageEnglish
        )
        syncingLanguagePicker = false

        binding.languageOptions.setOnCheckedChangeListener { _, checkedId ->
            if (syncingLanguagePicker || checkedId == View.NO_ID) return@setOnCheckedChangeListener
            val languageTag = if (checkedId == R.id.languageHebrew) {
                AppLanguage.HEBREW
            } else {
                AppLanguage.ENGLISH
            }
            if (AppLanguage.setLanguage(languageTag)) {
                recreate()
            }
        }
    }

    private fun bindStaticText() {
        binding.languageLabel.text = tr(R.string.language_label)
        binding.languageEnglish.text = tr(R.string.language_english)
        binding.languageHebrew.text = tr(R.string.language_hebrew)
        binding.question.text = tr(R.string.header_question)
        binding.vintage.text = tr(R.string.data_vintage_note)
    }

    private fun reserveDisplayName(nameHe: String, nameEn: String): String {
        val primary = if (AppLanguage.isHebrew()) nameHe else nameEn
        val fallback = if (AppLanguage.isHebrew()) nameEn else nameHe
        return primary.ifBlank { fallback }.ifBlank { tr(R.string.unnamed_reserve) }
    }

    private fun formatDistance(distanceMeters: Float): String =
        if (distanceMeters >= 1000f) {
            tr(R.string.distance_kilometers, distanceMeters / 1000f)
        } else {
            tr(R.string.distance_meters, distanceMeters.toInt())
        }

    private fun tr(resId: Int, vararg args: Any): String =
        ContextCompat.getContextForLanguage(this).let { localized ->
            if (args.isEmpty()) {
                ContextCompat.getString(localized, resId)
            } else {
                localized.getString(resId, *args)
            }
        }
}
