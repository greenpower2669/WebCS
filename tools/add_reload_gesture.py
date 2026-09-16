from pathlib import Path

SERVICE = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
ACTIVITY = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")


def apply_replacements(path: Path, replacements):
    text = path.read_text(encoding="utf-8")
    original = text
    for name, old, new in replacements:
        if new in text:
            continue
        if old not in text:
            raise SystemExit(f"{path}: pattern '{name}' not found and replacement absent")
        text = text.replace(old, new, 1)
    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"{path} patched")
    else:
        print(f"{path} already patched")


service_replacements = [
    (
        "settings snapshot reload fields",
'''        val gestureSensitivity: Int,
        val magazineSize: Int,
        val gestureCalibrated: Boolean
''',
'''        val gestureSensitivity: Int,
        val magazineSize: Int,
        val gestureCalibrated: Boolean,
        val reloadGestureEnabled: Boolean,
        val reloadGestureCalibrated: Boolean
'''
    ),
    (
        "reload gesture state fields",
'''    private var projectionImpulse: ProjectionImpulse? = null
    private var lastGestureShotMs = 0L
''',
'''    private var projectionImpulse: ProjectionImpulse? = null
    private var lastGestureShotMs = 0L
    private var calibratingReloadGesture = false
    private var reloadProjectionImpulse: ProjectionImpulse? = null
    private var lastReloadGestureMs = 0L
    private var lastGestureActionMs = 0L
'''
    ),
    (
        "current settings reload values",
'''            gestureSensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60),
            magazineSize = magazineSize(),
            gestureCalibrated = hasGestureCalibration()
''',
'''            gestureSensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60),
            magazineSize = magazineSize(),
            gestureCalibrated = hasGestureCalibration(),
            reloadGestureEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false),
            reloadGestureCalibrated = hasReloadGestureCalibration()
'''
    ),
    (
        "configuration summary reload",
'''        val motion = if (s.gestureEnabled) " · geste ON" else ""
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · vidéo ${s.videoQuality}$motion"
''',
'''        val shotMotion = if (s.gestureEnabled) " · tir geste ON" else ""
        val reloadMotion = if (s.reloadGestureEnabled) " · recharge geste ON" else ""
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · vidéo ${s.videoQuality}$shotMotion$reloadMotion"
'''
    ),
    (
        "apply settings signature reload",
'''        gestureEnabled: Boolean,
        gestureSensitivity: Int,
        magazineSize: Int
''',
'''        gestureEnabled: Boolean,
        gestureSensitivity: Int,
        reloadGestureEnabled: Boolean,
        magazineSize: Int
'''
    ),
    (
        "save reload setting",
'''            .putBoolean(PREF_GESTURE_ENABLED, gestureEnabled && hasGestureCalibration())
            .putInt(PREF_GESTURE_SENSITIVITY, gestureSensitivity.coerceIn(0, 100))
            .putInt(PREF_MAGAZINE_SIZE, magazineSize.coerceIn(1, 99))
''',
'''            .putBoolean(PREF_GESTURE_ENABLED, gestureEnabled && hasGestureCalibration())
            .putInt(PREF_GESTURE_SENSITIVITY, gestureSensitivity.coerceIn(0, 100))
            .putBoolean(PREF_RELOAD_GESTURE_ENABLED, reloadGestureEnabled && hasReloadGestureCalibration())
            .putInt(PREF_MAGAZINE_SIZE, magazineSize.coerceIn(1, 99))
'''
    ),
    (
        "settings note reload",
'''        val gestureNote = if (gestureEnabled && !hasGestureCalibration()) " · geste à calibrer" else ""
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote.")
''',
'''        val gestureNote = if (gestureEnabled && !hasGestureCalibration()) " · tir à calibrer" else ""
        val reloadNote = if (reloadGestureEnabled && !hasReloadGestureCalibration()) " · recharge à calibrer" else ""
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote$reloadNote.")
'''
    ),
    (
        "shot calibration mode flag",
'''    fun startGestureCalibration() {
        if (motionSensor == null) {
''',
'''    fun startGestureCalibration() {
        calibratingReloadGesture = false
        if (motionSensor == null) {
'''
    ),
    (
        "reload calibration API",
'''    fun gestureCalibrationSummary(): String {
        val sensor = motionSensor ?: return "Mouvement : aucun accéléromètre disponible"
        if (!hasGestureCalibration()) return "Mouvement : ${sensor.name} · geste non calibré"
        val threshold = prefs.getFloat(PREF_GESTURE_THRESHOLD, 0f)
        val wake = if (sensor.isWakeUpSensor) "wake-up" else "service actif"
        return "Mouvement : calibré · seuil ${String.format(Locale.US, "%.1f", threshold)} m/s² · capteur $wake"
    }
''',
'''    fun gestureCalibrationSummary(): String {
        val sensor = motionSensor ?: return "Mouvement : aucun accéléromètre disponible"
        if (!hasGestureCalibration()) return "Tir mouvement : ${sensor.name} · geste non calibré"
        val threshold = prefs.getFloat(PREF_GESTURE_THRESHOLD, 0f)
        val wake = if (sensor.isWakeUpSensor) "wake-up" else "service actif"
        return "Tir mouvement : calibré · seuil ${String.format(Locale.US, "%.1f", threshold)} m/s² · capteur $wake"
    }

    fun startReloadGestureCalibration() {
        if (motionSensor == null) {
            listener?.onStatus("Aucun accéléromètre exploitable sur cet appareil.")
            return
        }
        calibratingReloadGesture = true
        gestureCalibrationAxes.clear()
        gestureCalibrationPeaks.clear()
        gestureCalibrationRemaining = GESTURE_CALIBRATION_COUNT
        calibrationImpulse = null
        reloadProjectionImpulse = null
        lastCalibrationPairMs = 0L
        updateSensorRegistration(force = true)
        speak("calibration rechargement")
        listener?.onStatus("Calibration recharge : fais 5 fois ton geste de rechargement, par exemple un à-coup bas-haut distinct du recul de tir.")
    }

    fun reloadGestureCalibrationSummary(): String {
        val sensor = motionSensor ?: return "Recharge mouvement : aucun accéléromètre disponible"
        if (!hasReloadGestureCalibration()) return "Recharge mouvement : ${sensor.name} · geste non calibré"
        val threshold = prefs.getFloat(PREF_RELOAD_GESTURE_THRESHOLD, 0f)
        return "Recharge mouvement : calibrée · seuil ${String.format(Locale.US, "%.1f", threshold)} m/s²"
    }
'''
    ),
    (
        "sensor dispatch reload",
'''        if (gestureCalibrationRemaining > 0) {
            handleGestureCalibration(now, x, y, z, magnitude)
        } else if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
            handleGestureDetection(now, x, y, z)
        }
''',
'''        if (gestureCalibrationRemaining > 0) {
            handleGestureCalibration(now, x, y, z, magnitude)
        } else {
            if (prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) && hasReloadGestureCalibration()) {
                handleReloadGestureDetection(now, x, y, z)
            }
            if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, x, y, z)
            }
        }
'''
    ),
    (
        "calibration progress label",
'''                val message = "Geste $done/$GESTURE_CALIBRATION_COUNT enregistré."
                notifyStatus(message)
                speak(done.toString())
''',
'''                val action = if (calibratingReloadGesture) "Recharge" else "Tir"
                val message = "$action $done/$GESTURE_CALIBRATION_COUNT enregistré."
                notifyStatus(message)
                speak(done.toString())
'''
    ),
    (
        "finish calibration routing",
'''    private fun finishGestureCalibration() {
        if (gestureCalibrationAxes.isEmpty()) return
        val ax = gestureCalibrationAxes.sumOf { it[0].toDouble() }.toFloat()
        val ay = gestureCalibrationAxes.sumOf { it[1].toDouble() }.toFloat()
        val az = gestureCalibrationAxes.sumOf { it[2].toDouble() }.toFloat()
        val axis = normalize(ax, ay, az) ?: return
        val averagePeak = gestureCalibrationPeaks.average().toFloat()
        val threshold = max(2.0f, averagePeak * 0.45f)
        prefs.edit()
            .putFloat(PREF_GESTURE_AXIS_X, axis[0])
            .putFloat(PREF_GESTURE_AXIS_Y, axis[1])
            .putFloat(PREF_GESTURE_AXIS_Z, axis[2])
            .putFloat(PREF_GESTURE_THRESHOLD, threshold)
            .putBoolean(PREF_GESTURE_ENABLED, true)
            .apply()
        projectionImpulse = null
        updateSensorRegistration()
        speak("calibration terminée")
        notifyStatus("Calibration mouvement terminée · tir par à-coup activé. Seuil ${String.format(Locale.US, "%.1f", threshold)} m/s².")
    }
''',
'''    private fun finishGestureCalibration() {
        if (gestureCalibrationAxes.isEmpty()) return
        val ax = gestureCalibrationAxes.sumOf { it[0].toDouble() }.toFloat()
        val ay = gestureCalibrationAxes.sumOf { it[1].toDouble() }.toFloat()
        val az = gestureCalibrationAxes.sumOf { it[2].toDouble() }.toFloat()
        val axis = normalize(ax, ay, az) ?: return
        val averagePeak = gestureCalibrationPeaks.average().toFloat()
        val threshold = max(2.0f, averagePeak * 0.45f)

        if (calibratingReloadGesture) {
            if (hasGestureCalibration()) {
                val sx = prefs.getFloat(PREF_GESTURE_AXIS_X, 0f)
                val sy = prefs.getFloat(PREF_GESTURE_AXIS_Y, 0f)
                val sz = prefs.getFloat(PREF_GESTURE_AXIS_Z, 0f)
                val similarity = abs(dot(sx, sy, sz, axis[0], axis[1], axis[2]))
                if (similarity > 0.82f) {
                    calibratingReloadGesture = false
                    gestureCalibrationRemaining = 0
                    speak("geste trop proche du tir")
                    notifyStatus("Recharge non enregistrée : geste trop proche du tir. Recalibre avec une direction différente, par exemple bas-haut.")
                    updateSensorRegistration()
                    return
                }
            }
            prefs.edit()
                .putFloat(PREF_RELOAD_GESTURE_AXIS_X, axis[0])
                .putFloat(PREF_RELOAD_GESTURE_AXIS_Y, axis[1])
                .putFloat(PREF_RELOAD_GESTURE_AXIS_Z, axis[2])
                .putFloat(PREF_RELOAD_GESTURE_THRESHOLD, threshold)
                .putBoolean(PREF_RELOAD_GESTURE_ENABLED, true)
                .apply()
            reloadProjectionImpulse = null
            calibratingReloadGesture = false
            updateSensorRegistration()
            speak("rechargement calibré")
            notifyStatus("Calibration recharge terminée · geste activé. Le FX de rechargement jouera à chaque recharge valide.")
        } else {
            prefs.edit()
                .putFloat(PREF_GESTURE_AXIS_X, axis[0])
                .putFloat(PREF_GESTURE_AXIS_Y, axis[1])
                .putFloat(PREF_GESTURE_AXIS_Z, axis[2])
                .putFloat(PREF_GESTURE_THRESHOLD, threshold)
                .putBoolean(PREF_GESTURE_ENABLED, true)
                .apply()
            projectionImpulse = null
            updateSensorRegistration()
            speak("calibration terminée")
            notifyStatus("Calibration mouvement terminée · tir par à-coup activé. Seuil ${String.format(Locale.US, "%.1f", threshold)} m/s².")
        }
    }
'''
    ),
    (
        "gesture detectors",
'''    private fun handleGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now - lastGestureShotMs < GESTURE_COOLDOWN_MS) return
        val ax = prefs.getFloat(PREF_GESTURE_AXIS_X, 0f)
        val ay = prefs.getFloat(PREF_GESTURE_AXIS_Y, 0f)
        val az = prefs.getFloat(PREF_GESTURE_AXIS_Z, 0f)
        val base = prefs.getFloat(PREF_GESTURE_THRESHOLD, 4f)
        val sensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60).coerceIn(0, 100)
        val multiplier = 1.35f - sensitivity * 0.0075f
        val threshold = max(1.8f, base * multiplier)
        val projection = x * ax + y * ay + z * az
        if (abs(projection) < threshold) return

        val first = projectionImpulse
        if (first == null || now - first.timeMs > GESTURE_PAIR_WINDOW_MS) {
            projectionImpulse = ProjectionImpulse(projection, now)
            return
        }
        if (first.value * projection < 0f && abs(projection) >= threshold * 0.58f) {
            projectionImpulse = null
            lastGestureShotMs = now
            mainHandler.post { fire() }
        } else if ((first.value >= 0f) == (projection >= 0f) && abs(projection) > abs(first.value)) {
            projectionImpulse = ProjectionImpulse(projection, now)
        }
    }
''',
'''    private fun handleGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now - lastGestureShotMs < GESTURE_COOLDOWN_MS || now - lastGestureActionMs < GESTURE_ACTION_GUARD_MS) return
        val ax = prefs.getFloat(PREF_GESTURE_AXIS_X, 0f)
        val ay = prefs.getFloat(PREF_GESTURE_AXIS_Y, 0f)
        val az = prefs.getFloat(PREF_GESTURE_AXIS_Z, 0f)
        val base = prefs.getFloat(PREF_GESTURE_THRESHOLD, 4f)
        val sensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60).coerceIn(0, 100)
        val multiplier = 1.35f - sensitivity * 0.0075f
        val threshold = max(1.8f, base * multiplier)
        val projection = x * ax + y * ay + z * az
        if (abs(projection) < threshold) return

        val first = projectionImpulse
        if (first == null || now - first.timeMs > GESTURE_PAIR_WINDOW_MS) {
            projectionImpulse = ProjectionImpulse(projection, now)
            return
        }
        if (first.value * projection < 0f && abs(projection) >= threshold * 0.58f) {
            projectionImpulse = null
            lastGestureShotMs = now
            lastGestureActionMs = now
            mainHandler.post { fire() }
        } else if ((first.value >= 0f) == (projection >= 0f) && abs(projection) > abs(first.value)) {
            projectionImpulse = ProjectionImpulse(projection, now)
        }
    }

    private fun handleReloadGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now - lastReloadGestureMs < RELOAD_GESTURE_COOLDOWN_MS || now - lastGestureActionMs < GESTURE_ACTION_GUARD_MS) return
        val ax = prefs.getFloat(PREF_RELOAD_GESTURE_AXIS_X, 0f)
        val ay = prefs.getFloat(PREF_RELOAD_GESTURE_AXIS_Y, 0f)
        val az = prefs.getFloat(PREF_RELOAD_GESTURE_AXIS_Z, 0f)
        val base = prefs.getFloat(PREF_RELOAD_GESTURE_THRESHOLD, 4f)
        val sensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60).coerceIn(0, 100)
        val multiplier = 1.42f - sensitivity * 0.0065f
        val threshold = max(2.0f, base * multiplier)
        val projection = x * ax + y * ay + z * az
        if (abs(projection) < threshold) return

        val first = reloadProjectionImpulse
        val dt = if (first == null) Long.MAX_VALUE else now - first.timeMs
        if (first == null || dt > RELOAD_GESTURE_MAX_PAIR_MS) {
            reloadProjectionImpulse = ProjectionImpulse(projection, now)
            return
        }
        if (dt >= RELOAD_GESTURE_MIN_PAIR_MS && first.value * projection < 0f && abs(projection) >= threshold * 0.55f) {
            reloadProjectionImpulse = null
            lastReloadGestureMs = now
            lastGestureActionMs = now
            mainHandler.post { reloadMagazine() }
        } else if ((first.value >= 0f) == (projection >= 0f) && abs(projection) > abs(first.value)) {
            reloadProjectionImpulse = ProjectionImpulse(projection, now)
        }
    }
'''
    ),
    (
        "sensor registration reload",
'''        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false)
''',
'''        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false)
'''
    ),
    (
        "reload calibration presence",
'''    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
''',
'''    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
    private fun hasReloadGestureCalibration(): Boolean = prefs.contains(PREF_RELOAD_GESTURE_THRESHOLD)
'''
    ),
    (
        "reload constants",
'''        private const val PREF_GESTURE_AXIS_Z = "gesture.axisZ"
        private const val PREF_GESTURE_THRESHOLD = "gesture.threshold"
        private const val CHANNEL_ID = "webcs_camera"
''',
'''        private const val PREF_GESTURE_AXIS_Z = "gesture.axisZ"
        private const val PREF_GESTURE_THRESHOLD = "gesture.threshold"
        private const val PREF_RELOAD_GESTURE_ENABLED = "game.reloadGestureEnabled"
        private const val PREF_RELOAD_GESTURE_AXIS_X = "reloadGesture.axisX"
        private const val PREF_RELOAD_GESTURE_AXIS_Y = "reloadGesture.axisY"
        private const val PREF_RELOAD_GESTURE_AXIS_Z = "reloadGesture.axisZ"
        private const val PREF_RELOAD_GESTURE_THRESHOLD = "reloadGesture.threshold"
        private const val CHANNEL_ID = "webcs_camera"
'''
    ),
    (
        "reload timing constants",
'''        private const val GESTURE_PAIR_WINDOW_MS = 330L
        private const val GESTURE_COOLDOWN_MS = 520L
        private const val CALIBRATION_MIN_ACCEL = 3.0f
''',
'''        private const val GESTURE_PAIR_WINDOW_MS = 330L
        private const val GESTURE_COOLDOWN_MS = 520L
        private const val GESTURE_ACTION_GUARD_MS = 360L
        private const val RELOAD_GESTURE_MIN_PAIR_MS = 90L
        private const val RELOAD_GESTURE_MAX_PAIR_MS = 620L
        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
        private const val CALIBRATION_MIN_ACCEL = 3.0f
'''
    ),
]

activity_replacements = [
    (
        "reload gesture controls",
'''        content.addView(gestureCalibrateButton)

        val effectiveText = label("Résolution effective actuelle : ${if (snapshot.effectiveWidth > 0) "${snapshot.effectiveWidth}×${snapshot.effectiveHeight}" else "en attente"}")
''',
'''        content.addView(gestureCalibrateButton)

        val reloadGestureCheck = CheckBox(this).apply {
            text = "Recharger avec un geste calibré"
            isChecked = snapshot.reloadGestureEnabled
        }
        content.addView(reloadGestureCheck)
        val reloadGestureProfileText = label(service.reloadGestureCalibrationSummary())
        content.addView(reloadGestureProfileText)
        val reloadGestureCalibrateButton = Button(this).apply {
            text = "CALIBRER MON GESTE DE RECHARGE · 5 FOIS"
            contentDescription = "Calibrer le rechargement par mouvement"
        }
        content.addView(reloadGestureCalibrateButton)

        val effectiveText = label("Résolution effective actuelle : ${if (snapshot.effectiveWidth > 0) "${snapshot.effectiveWidth}×${snapshot.effectiveHeight}" else "en attente"}")
'''
    ),
    (
        "apply reload gesture checkbox",
'''                        gestureCheck.isChecked,
                        sensitivitySeek.progress,
                        magazineSize
''',
'''                        gestureCheck.isChecked,
                        sensitivitySeek.progress,
                        reloadGestureCheck.isChecked,
                        magazineSize
'''
    ),
    (
        "reload calibration button action",
'''            gestureCalibrateButton.setOnClickListener {
                service.startGestureCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
''',
'''            gestureCalibrateButton.setOnClickListener {
                service.startGestureCalibration()
                dialog.dismiss()
            }

            reloadGestureCalibrateButton.setOnClickListener {
                service.startReloadGestureCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
'''
    ),
]

apply_replacements(SERVICE, service_replacements)
apply_replacements(ACTIVITY, activity_replacements)
