from pathlib import Path

SERVICE = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
ACTIVITY = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")
MANIFEST = Path("android/poc-camera/src/main/AndroidManifest.xml")
GRADLE = Path("android/poc-camera/build.gradle.kts")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"Pattern not found for {label}: {old[:220]!r}")
    return text.replace(old, new, 1)


service = SERVICE.read_text(encoding="utf-8")
activity = ACTIVITY.read_text(encoding="utf-8")
manifest = MANIFEST.read_text(encoding="utf-8")
gradle = GRADLE.read_text(encoding="utf-8")

if "WEBSC_V010" not in service:
    service = replace_once(
        service,
        "import android.hardware.camera2.CameraCharacteristics\nimport android.net.Uri\n",
        "import android.hardware.camera2.CameraCharacteristics\nimport android.media.AudioManager\nimport android.media.ToneGenerator\nimport android.net.Uri\n",
        "tone imports",
    )
    service = replace_once(
        service,
        "import kotlin.math.abs\nimport kotlin.math.asin\n",
        "import kotlin.math.abs\nimport kotlin.math.acos\nimport kotlin.math.asin\n",
        "acos import",
    )

    service = replace_once(
        service,
        '''    private var lastGestureActionMs = 0L
    private var lastCameraElevationDeg: Float? = null
    private var upwardTurnAccumDeg = 0f
    private var upwardTurnStartMs = 0L
    @Volatile private var reloadGateUntilMs = 0L
''',
        '''    private var lastGestureActionMs = 0L
    private var lastCameraElevationDeg: Float? = null
    private var orientationCalibrationStage = ORIENTATION_CAL_NONE
    private val orientationCalibrationSamples = mutableListOf<FloatArray>()
    private var orientationCalibrationStartedMs = 0L
    private var orientationCalibrationLastVector: FloatArray? = null
    private var calibrationFlatVector: FloatArray? = null
    private var reloadOrientationArmed = false
    private var reloadOrientationMoveStartMs = 0L
    @Volatile private var reloadGateUntilMs = 0L
''',
        "orientation state fields",
    )

    service = replace_once(
        service,
        '''        val gestureNote = if (gestureEnabled && !hasGestureCalibration()) " · tir à calibrer" else ""
        val reloadNote = if (reloadGestureEnabled && !hasReloadGestureCalibration()) " · recharge à calibrer" else ""
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote$reloadNote.")
''',
        '''        val gestureNote = if (gestureEnabled && !hasGestureCalibration()) " · tir à calibrer" else ""
        val reloadNote = if (reloadGestureEnabled && !hasReloadGestureCalibration()) " · recharge à calibrer" else ""
        val orientationNote = if (reloadGestureEnabled && !hasOrientationCalibration()) " · orientation recharge à calibrer" else ""
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote$reloadNote$orientationNote.")
''',
        "settings orientation note",
    )

    service = replace_once(
        service,
        '''    fun reloadGestureCalibrationSummary(): String {
        val sensor = motionSensor ?: return "Recharge mouvement : aucun accéléromètre disponible"
        if (!hasReloadGestureCalibration()) return "Recharge mouvement : ${sensor.name} · geste non calibré"
        val threshold = prefs.getFloat(PREF_RELOAD_GESTURE_THRESHOLD, 0f)
        return "Recharge mouvement : calibrée · seuil ${String.format(Locale.US, "%.1f", threshold)} m/s²"
    }

    fun shutdown() {
''',
        '''    fun reloadGestureCalibrationSummary(): String {
        val sensor = motionSensor ?: return "Recharge mouvement : aucun accéléromètre disponible"
        if (!hasReloadGestureCalibration()) return "Recharge mouvement : ${sensor.name} · geste non calibré"
        val threshold = prefs.getFloat(PREF_RELOAD_GESTURE_THRESHOLD, 0f)
        return "Recharge mouvement : calibrée · seuil ${String.format(Locale.US, "%.1f", threshold)} m/s²"
    }

    fun orientationCalibrationSummary(): String {
        val sensor = orientationSensor ?: return "Orientation recharge : capteur de rotation indisponible"
        if (!hasOrientationCalibration()) return "Orientation recharge : non calibrée · ${sensor.name}"
        val angle = prefs.getFloat(PREF_ORIENTATION_LEARNED_ANGLE, 0f)
        val gate = prefs.getFloat(PREF_ORIENTATION_GATE_DEG, ORIENTATION_GATE_MIN_DEG)
        return "Orientation recharge : calibrée · course ${angle.roundToInt()}° · seuil ${gate.roundToInt()}°"
    }

    fun startOrientationCalibration() {
        if (orientationSensor == null) {
            listener?.onStatus("Calibration orientation impossible : capteur de rotation indisponible.")
            return
        }
        orientationCalibrationStage = ORIENTATION_CAL_FLAT
        orientationCalibrationSamples.clear()
        orientationCalibrationLastVector = null
        calibrationFlatVector = null
        orientationCalibrationStartedMs = SystemClock.elapsedRealtime()
        reloadGateUntilMs = 0L
        reloadOrientationArmed = false
        updateSensorRegistration(force = true)
        speak("pose le téléphone à plat, caméra vers le haut")
        listener?.onStatus("Orientation 1/2 · pose le téléphone à plat, caméra vers le haut, puis ne bouge plus. Attends le bip.")
    }

    fun shutdown() {
''',
        "orientation calibration public API",
    )

    old_orientation = '''    private fun handleOrientation(event: SensorEvent) {
        if (event.values.isEmpty()) return
        val rotation = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
        } catch (_: Exception) {
            return
        }
        // La caméra arrière regarde approximativement selon -Z du téléphone.
        // La composante verticale de ce vecteur donne une élévation indépendante du roulis.
        val forwardZ = (-rotation[8]).coerceIn(-1f, 1f)
        val elevation = Math.toDegrees(asin(forwardZ.toDouble())).toFloat()
        val now = SystemClock.elapsedRealtime()
        val previous = lastCameraElevationDeg
        lastCameraElevationDeg = elevation
        if (previous == null) return
        val delta = elevation - previous
        if (upwardTurnStartMs == 0L || now - upwardTurnStartMs > UPWARD_TURN_WINDOW_MS) {
            upwardTurnStartMs = now
            upwardTurnAccumDeg = 0f
        }
        if (delta > 0.4f) {
            upwardTurnAccumDeg += delta
        } else if (delta < -2.5f) {
            upwardTurnAccumDeg = 0f
            upwardTurnStartMs = now
        }
        if (upwardTurnAccumDeg >= UPWARD_TURN_MIN_DEG && now - upwardTurnStartMs <= UPWARD_TURN_WINDOW_MS) {
            reloadGateUntilMs = now + RELOAD_GATE_MS
            projectionImpulse = null
            reloadProjectionImpulse = null
            upwardTurnAccumDeg = 0f
            upwardTurnStartMs = now
            logGameEvent("reload_gate", latestResult, null)
            if (prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false)) {
                notifyStatus("Arme relevée · geste de recharge autorisé pendant 5 s.")
            }
        }
    }
'''
    new_orientation = '''    private fun handleOrientation(event: SensorEvent) {
        if (event.values.isEmpty()) return
        val rotation = FloatArray(9)
        try {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
        } catch (_: Exception) {
            return
        }

        // Vecteur avant de la caméra arrière dans le repère du monde.
        // Utiliser le vecteur 3D plutôt que portrait/paysage rend le geste indépendant de l'UI.
        val forward = normalize(-rotation[2], -rotation[5], -rotation[8]) ?: return
        lastCameraElevationDeg = Math.toDegrees(asin(forward[2].coerceIn(-1f, 1f).toDouble())).toFloat()
        val now = SystemClock.elapsedRealtime()

        if (orientationCalibrationStage != ORIENTATION_CAL_NONE) {
            handleOrientationCalibrationSample(now, forward)
            return
        }

        if (!prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) ||
            !hasReloadGestureCalibration() || !hasOrientationCalibration()) return

        handleReloadOrientationGate(now, forward)
    }

    private fun handleOrientationCalibrationSample(now: Long, current: FloatArray) {
        if (now - orientationCalibrationStartedMs < ORIENTATION_CAL_SETTLE_MS) return

        val last = orientationCalibrationLastVector
        if (last != null && angleDegrees(last, current) > ORIENTATION_STABLE_DELTA_DEG) {
            orientationCalibrationSamples.clear()
        }
        orientationCalibrationLastVector = current
        orientationCalibrationSamples.add(current.copyOf())
        if (orientationCalibrationSamples.size < ORIENTATION_CAL_SAMPLE_COUNT) return

        val stable = averageOrientationSamples() ?: return
        orientationCalibrationSamples.clear()
        orientationCalibrationLastVector = null

        if (orientationCalibrationStage == ORIENTATION_CAL_FLAT) {
            calibrationFlatVector = stable
            orientationCalibrationStage = ORIENTATION_CAL_READY
            orientationCalibrationStartedMs = now
            beepCalibration()
            speak("redresse le téléphone en position prête à tirer")
            notifyStatus("BIP · Orientation 2/2 · redresse maintenant le téléphone dans ta position normale, prêt à tirer, puis ne bouge plus.")
            return
        }

        val flat = calibrationFlatVector ?: run {
            orientationCalibrationStage = ORIENTATION_CAL_NONE
            updateSensorRegistration()
            return
        }
        val learnedAngle = angleDegrees(flat, stable)
        if (learnedAngle < ORIENTATION_CAL_MIN_SEPARATION_DEG) {
            orientationCalibrationStartedMs = now
            notifyStatus("Position de tir trop proche de la position à plat (${learnedAngle.roundToInt()}°). Redresse davantage le téléphone et stabilise-le.")
            return
        }

        val gate = (learnedAngle * 0.30f).coerceIn(ORIENTATION_GATE_MIN_DEG, ORIENTATION_GATE_MAX_DEG)
        prefs.edit()
            .putFloat(PREF_ORIENTATION_FLAT_X, flat[0])
            .putFloat(PREF_ORIENTATION_FLAT_Y, flat[1])
            .putFloat(PREF_ORIENTATION_FLAT_Z, flat[2])
            .putFloat(PREF_ORIENTATION_READY_X, stable[0])
            .putFloat(PREF_ORIENTATION_READY_Y, stable[1])
            .putFloat(PREF_ORIENTATION_READY_Z, stable[2])
            .putFloat(PREF_ORIENTATION_LEARNED_ANGLE, learnedAngle)
            .putFloat(PREF_ORIENTATION_GATE_DEG, gate)
            .apply()

        orientationCalibrationStage = ORIENTATION_CAL_NONE
        calibrationFlatVector = null
        reloadOrientationArmed = false
        reloadOrientationMoveStartMs = 0L
        beepCalibration(doubleBeep = true)
        speak("orientation calibrée")
        notifyStatus("Orientation calibrée · position à plat ↔ visée ${learnedAngle.roundToInt()}° · recharge armée après ${gate.roundToInt()}° vers le haut.")
        updateSensorRegistration()
    }

    private fun averageOrientationSamples(): FloatArray? {
        if (orientationCalibrationSamples.isEmpty()) return null
        var x = 0f
        var y = 0f
        var z = 0f
        orientationCalibrationSamples.forEach {
            x += it[0]
            y += it[1]
            z += it[2]
        }
        return normalize(x, y, z)
    }

    private fun handleReloadOrientationGate(now: Long, current: FloatArray) {
        val ready = loadOrientationVector(PREF_ORIENTATION_READY_X, PREF_ORIENTATION_READY_Y, PREF_ORIENTATION_READY_Z) ?: return
        val flat = loadOrientationVector(PREF_ORIENTATION_FLAT_X, PREF_ORIENTATION_FLAT_Y, PREF_ORIENTATION_FLAT_Z) ?: return
        val learnedAngle = prefs.getFloat(PREF_ORIENTATION_LEARNED_ANGLE, angleDegrees(ready, flat)).coerceAtLeast(ORIENTATION_CAL_MIN_SEPARATION_DEG)
        val gate = prefs.getFloat(PREF_ORIENTATION_GATE_DEG, (learnedAngle * 0.30f).coerceIn(ORIENTATION_GATE_MIN_DEG, ORIENTATION_GATE_MAX_DEG))
        val angleReady = angleDegrees(ready, current)
        val angleFlat = angleDegrees(flat, current)
        val readyZone = (gate * 0.55f).coerceIn(8f, 18f)

        // Tant que l'arme est en position de visée, une future rotation vers le haut est armée.
        if (angleReady <= readyZone) {
            reloadOrientationArmed = true
            reloadOrientationMoveStartMs = now
            return
        }

        if (!reloadOrientationArmed) return
        if (now - reloadOrientationMoveStartMs > RELOAD_ORIENTATION_TRANSITION_MS) {
            reloadOrientationArmed = false
            return
        }

        // On doit réellement se rapprocher de la référence "caméra vers le haut".
        val gainTowardFlat = learnedAngle - angleFlat
        if (gainTowardFlat >= gate && angleReady >= gate * 0.75f) {
            reloadGateUntilMs = now + RELOAD_GATE_MS
            projectionImpulse = null
            reloadProjectionImpulse = null
            reloadOrientationArmed = false
            reloadOrientationMoveStartMs = 0L
            logGameEvent("reload_gate", latestResult, null)
            notifyStatus("Arme relevée selon ta calibration · RECHARGE autorisée 5 s · tir neutralisé pendant cette fenêtre.")
        }
    }

    private fun loadOrientationVector(xKey: String, yKey: String, zKey: String): FloatArray? {
        if (!prefs.contains(xKey) || !prefs.contains(yKey) || !prefs.contains(zKey)) return null
        return normalize(prefs.getFloat(xKey, 0f), prefs.getFloat(yKey, 0f), prefs.getFloat(zKey, 0f))
    }

    private fun angleDegrees(a: FloatArray, b: FloatArray): Float {
        val d = dot(a[0], a[1], a[2], b[0], b[1], b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(d.toDouble())).toFloat()
    }

    private fun beepCalibration(doubleBeep: Boolean = false) {
        val tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 80) } catch (_: Exception) { null } ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 170)
        if (doubleBeep) {
            mainHandler.postDelayed({
                try { tone.startTone(ToneGenerator.TONE_PROP_BEEP, 170) } catch (_: Exception) { }
                mainHandler.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 220L)
            }, 260L)
        } else {
            mainHandler.postDelayed({ try { tone.release() } catch (_: Exception) { } }, 220L)
        }
    }
'''
    service = replace_once(service, old_orientation, new_orientation, "orientation detector")

    service = replace_once(
        service,
        '''    private fun updateSensorRegistration(force: Boolean = false) {
        val reloadEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) || calibratingReloadGesture
        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || reloadEnabled
''',
        '''    private fun updateSensorRegistration(force: Boolean = false) {
        val orientationCalibrationActive = orientationCalibrationStage != ORIENTATION_CAL_NONE
        val reloadEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) || calibratingReloadGesture || orientationCalibrationActive
        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || reloadEnabled || orientationCalibrationActive
''',
        "sensor registration orientation calibration",
    )

    service = replace_once(
        service,
        '''    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
    private fun hasReloadGestureCalibration(): Boolean = prefs.contains(PREF_RELOAD_GESTURE_THRESHOLD)
''',
        '''    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
    private fun hasReloadGestureCalibration(): Boolean = prefs.contains(PREF_RELOAD_GESTURE_THRESHOLD)
    private fun hasOrientationCalibration(): Boolean =
        prefs.contains(PREF_ORIENTATION_FLAT_X) && prefs.contains(PREF_ORIENTATION_READY_X) && prefs.contains(PREF_ORIENTATION_LEARNED_ANGLE)
''',
        "orientation calibration presence",
    )

    service = replace_once(
        service,
        '''        private const val WEBSC_V090 = "hot-stream-shot-classification-training-v1"
        private const val CSV_HEADER''',
        '''        private const val WEBSC_V090 = "hot-stream-shot-classification-training-v1"
        private const val WEBSC_V010 = "relative-orientation-calibration-v1"
        private const val CSV_HEADER''',
        "v010 marker",
    )

    service = replace_once(
        service,
        '''        private const val PREF_RELOAD_GESTURE_THRESHOLD = "reloadGesture.threshold"
        private const val CHANNEL_ID = "webcs_camera"
''',
        '''        private const val PREF_RELOAD_GESTURE_THRESHOLD = "reloadGesture.threshold"
        private const val PREF_ORIENTATION_FLAT_X = "orientation.flatX"
        private const val PREF_ORIENTATION_FLAT_Y = "orientation.flatY"
        private const val PREF_ORIENTATION_FLAT_Z = "orientation.flatZ"
        private const val PREF_ORIENTATION_READY_X = "orientation.readyX"
        private const val PREF_ORIENTATION_READY_Y = "orientation.readyY"
        private const val PREF_ORIENTATION_READY_Z = "orientation.readyZ"
        private const val PREF_ORIENTATION_LEARNED_ANGLE = "orientation.learnedAngle"
        private const val PREF_ORIENTATION_GATE_DEG = "orientation.gateDeg"
        private const val CHANNEL_ID = "webcs_camera"
''',
        "orientation prefs",
    )

    service = replace_once(
        service,
        '''        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
        private const val UPWARD_TURN_MIN_DEG = 20f
        private const val UPWARD_TURN_WINDOW_MS = 800L
        private const val RELOAD_GATE_MS = 5000L
''',
        '''        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
        private const val ORIENTATION_CAL_NONE = 0
        private const val ORIENTATION_CAL_FLAT = 1
        private const val ORIENTATION_CAL_READY = 2
        private const val ORIENTATION_CAL_SETTLE_MS = 900L
        private const val ORIENTATION_CAL_SAMPLE_COUNT = 12
        private const val ORIENTATION_STABLE_DELTA_DEG = 3.5f
        private const val ORIENTATION_CAL_MIN_SEPARATION_DEG = 30f
        private const val ORIENTATION_GATE_MIN_DEG = 14f
        private const val ORIENTATION_GATE_MAX_DEG = 32f
        private const val RELOAD_ORIENTATION_TRANSITION_MS = 1500L
        private const val RELOAD_GATE_MS = 5000L
''',
        "orientation constants",
    )

if "CALIBRER ORIENTATION RECHARGE" not in activity:
    activity = replace_once(
        activity,
        '''        val reloadGestureCalibrateButton = Button(this).apply {
            text = "CALIBRER MON GESTE DE RECHARGE · 5 FOIS"
            contentDescription = "Calibrer le rechargement par mouvement"
        }
        content.addView(reloadGestureCalibrateButton)

        val effectiveText = label''',
        '''        val reloadGestureCalibrateButton = Button(this).apply {
            text = "CALIBRER MON GESTE DE RECHARGE · 5 FOIS"
            contentDescription = "Calibrer le rechargement par mouvement"
        }
        content.addView(reloadGestureCalibrateButton)

        val orientationProfileText = label(service.orientationCalibrationSummary())
        content.addView(orientationProfileText)
        content.addView(label("Orientation recharge : 1) téléphone à plat caméra vers le haut, 2) au bip redresse-le en position prête à tirer. Le mode RECHARGE dure ensuite 5 s, puis retour automatique au TIR."))
        val orientationCalibrateButton = Button(this).apply {
            text = "CALIBRER ORIENTATION RECHARGE"
            contentDescription = "Calibrer la position à plat et la position prête à tirer"
        }
        content.addView(orientationCalibrateButton)

        val effectiveText = label''',
        "orientation calibration UI",
    )

    activity = replace_once(
        activity,
        '''            reloadGestureCalibrateButton.setOnClickListener {
                service.startReloadGestureCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
''',
        '''            reloadGestureCalibrateButton.setOnClickListener {
                service.startReloadGestureCalibration()
                dialog.dismiss()
            }

            orientationCalibrateButton.setOnClickListener {
                service.startOrientationCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
''',
        "orientation calibration action",
    )

if 'android:screenOrientation="landscape"' not in manifest:
    manifest = replace_once(
        manifest,
        '''        <activity
            android:name=".MainActivity"
            android:exported="true">''',
        '''        <activity
            android:name=".MainActivity"
            android:screenOrientation="landscape"
            android:exported="true">''',
        "landscape lock",
    )

if 'versionName = "0.10.0"' not in gradle:
    gradle = replace_once(gradle, 'versionCode = 10\n        versionName = "0.9.0"', 'versionCode = 11\n        versionName = "0.10.0"', "version bump")

SERVICE.write_text(service, encoding="utf-8")
ACTIVITY.write_text(activity, encoding="utf-8")
MANIFEST.write_text(manifest, encoding="utf-8")
GRADLE.write_text(gradle, encoding="utf-8")
print("WebCS v0.10.0 relative orientation calibration applied")
