from pathlib import Path

service_path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
activity_path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")

service = service_path.read_text(encoding="utf-8")
activity = activity_path.read_text(encoding="utf-8")

if "WEBSC_V070" in service and "AimZoomView" in activity:
    print("WebCS v0.7.0 features already present")
    raise SystemExit(0)

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Pattern not found for {label}: {old[:180]!r}")
    return text.replace(old, new, 1)

service = replace_once(service,
'''import kotlin.math.abs
import kotlin.math.max
''',
'''import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
''', "asin import")

service = replace_once(service,
'''        fun onStatus(message: String)
        fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean)
''',
'''        fun onStatus(message: String)
        fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean)
        fun onAimPatch(size: Int, pixels: IntArray, calibrationRemaining: Int)
''', "listener aim patch")

service = replace_once(service,
'''    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private var sensorRegistered = false
''',
'''    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private var orientationSensor: Sensor? = null
    private var sensorRegistered = false
    private var orientationRegistered = false
''', "orientation fields")

service = replace_once(service,
'''    private var lastReloadGestureMs = 0L
    private var lastGestureActionMs = 0L

    @Volatile private var latestResult: ScanResult? = null
''',
'''    private var lastReloadGestureMs = 0L
    private var lastGestureActionMs = 0L
    private var lastCameraElevationDeg: Float? = null
    private var upwardTurnAccumDeg = 0f
    private var upwardTurnStartMs = 0L
    @Volatile private var reloadGateUntilMs = 0L

    @Volatile private var latestResult: ScanResult? = null
''', "reload gate fields")

service = replace_once(service,
'''    private var calibrationSumL2 = 0.0

    var listener: Listener? = null
''',
'''    private var calibrationSumL2 = 0.0
    private var targetCalibrationRemaining = 0
    private var targetCalibrationCount = 0
    private var targetSumR = 0.0
    private var targetSumG = 0.0
    private var targetSumB = 0.0
    private var targetSumR2 = 0.0
    private var targetSumG2 = 0.0
    private var targetSumB2 = 0.0
    private var patchFrameCounter = 0

    var listener: Listener? = null
''', "target fields")

service = replace_once(service,
'''        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = chooseMotionSensor()
        updateSensorRegistration()
''',
'''        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = chooseMotionSensor()
        orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        updateSensorRegistration()
''', "orientation sensor init")

old_calibration = '''    fun startCalibration() {
        if (latestResult == null) {
            listener?.onStatus("Attends que la caméra fournisse une image avant l'étalonnage.")
            return
        }
        calibrationCount = 0
        calibrationSumR = 0.0
        calibrationSumG = 0.0
        calibrationSumB = 0.0
        calibrationSumL = 0.0
        calibrationSumL2 = 0.0
        calibrationRemaining = 30
        listener?.onStatus("Étalonnage caméra : 30 images. Garde le téléphone stable sur la scène de référence.")
    }

    fun calibrationSummary(): String {
        val s = currentSettings()
        return prefs.getString("calibration.${s.profileId}", null)
            ?: "Profil ${s.profileId} : pas encore étalonné"
    }
'''
new_calibration = '''    fun startCalibration() {
        if (latestResult == null) {
            listener?.onStatus("Attends une image caméra avant de calibrer la cible.")
            return
        }
        targetCalibrationCount = 0
        targetSumR = 0.0
        targetSumG = 0.0
        targetSumB = 0.0
        targetSumR2 = 0.0
        targetSumG2 = 0.0
        targetSumB2 = 0.0
        targetCalibrationRemaining = TARGET_CALIBRATION_SHOTS
        speak("calibration cible")
        listener?.onStatus("Calibration couleur ennemi : vise la cible dans le zoom central puis tire 10 fois dessus.")
    }

    fun calibrationSummary(): String {
        val id = currentSettings().profileId
        if (!prefs.contains("target.$id.r")) return "Couleur ennemi $id : non calibrée · bleu historique utilisé"
        val r = prefs.getInt("target.$id.r", 0)
        val g = prefs.getInt("target.$id.g", 0)
        val b = prefs.getInt("target.$id.b", 0)
        val tolerance = prefs.getFloat("target.$id.tolerance", 42f)
        return "Couleur ennemi $id · RGB $r/$g/$b · tolérance ${tolerance.roundToInt()} · $TARGET_CALIBRATION_SHOTS tirs"
    }

    fun isTargetCalibrationActive(): Boolean = targetCalibrationRemaining > 0
'''
service = replace_once(service, old_calibration, new_calibration, "target calibration API")

old_fire_head = '''        val result = latestResult
        if (result == null) {
            listener?.onStatus("Pas encore d'image exploitable.")
            return
        }
        if (ammo <= 0) {
'''
new_fire_head = '''        val result = latestResult
        if (result == null) {
            listener?.onStatus("Pas encore d'image exploitable.")
            return
        }
        if (targetCalibrationRemaining > 0) {
            sfx.playShot()
            recordTargetCalibrationShot(result)
            return
        }
        if (ammo <= 0) {
'''
service = replace_once(service, old_fire_head, new_fire_head, "calibration shots in fire")

old_analyse = '''    private fun analyse(image: ImageProxy): ScanResult {
        val cx = image.width / 2
        val cy = image.height / 2
        val gap = max(2, (min(image.width, image.height) * 0.008f).roundToInt())
        val offsets = arrayOf(0 to 0, -gap to -gap, gap to -gap, -gap to gap, gap to gap)
        val points = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        val centerOnly = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER
        val used = if (centerOnly) listOf(points.first()) else points
        val blueVotes = used.count { it.isBlue }
        val avgR = used.sumOf { it.r } / used.size
        val avgG = used.sumOf { it.g } / used.size
        val avgB = used.sumOf { it.b } / used.size
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hit = if (centerOnly) blueVotes == 1 else blueVotes >= 3
        return ScanResult(hit, blueVotes, used.size, avgR, avgG, avgB, hsv[0], hsv[1], hsv[2])
    }
'''
new_analyse = '''    private fun analyse(image: ImageProxy): ScanResult {
        val cx = image.width / 2
        val cy = image.height / 2
        val gap = max(2, (min(image.width, image.height) * 0.008f).roundToInt())
        val offsets = arrayOf(0 to 0, -gap to -gap, gap to -gap, -gap to gap, gap to gap)
        val points = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        val centerOnly = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER
        val used = if (centerOnly) listOf(points.first()) else points
        val targetVotes = used.count { matchesTarget(it.r, it.g, it.b) }
        val avgR = used.sumOf { it.r } / used.size
        val avgG = used.sumOf { it.g } / used.size
        val avgB = used.sumOf { it.b } / used.size
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hit = if (centerOnly) targetVotes == 1 else targetVotes >= 3
        maybePublishAimPatch(image, cx, cy)
        return ScanResult(hit, targetVotes, used.size, avgR, avgG, avgB, hsv[0], hsv[1], hsv[2])
    }
'''
service = replace_once(service, old_analyse, new_analyse, "target matching analyse")

old_sample_tail = '''        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val blue = hsv[0] in 185f..255f && hsv[1] >= 0.22f && hsv[2] >= 0.10f
        return PointSample(r, g, b, blue)
    }

    private fun consumeCalibration(result: ScanResult) {
'''
new_sample_tail = '''        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val blue = hsv[0] in 185f..255f && hsv[1] >= 0.22f && hsv[2] >= 0.10f
        return PointSample(r, g, b, blue)
    }

    private fun matchesTarget(r: Int, g: Int, b: Int): Boolean {
        val id = currentSettings().profileId
        if (!prefs.contains("target.$id.r")) {
            val hsv = FloatArray(3)
            Color.RGBToHSV(r, g, b, hsv)
            return hsv[0] in 185f..255f && hsv[1] >= 0.22f && hsv[2] >= 0.10f
        }
        val tr = prefs.getInt("target.$id.r", 0)
        val tg = prefs.getInt("target.$id.g", 0)
        val tb = prefs.getInt("target.$id.b", 0)
        val tolerance = prefs.getFloat("target.$id.tolerance", 42f)
        val dr = (r - tr).toFloat()
        val dg = (g - tg).toFloat()
        val db = (b - tb).toFloat()
        return sqrt(dr * dr + dg * dg + db * db) <= tolerance
    }

    private fun recordTargetCalibrationShot(result: ScanResult) {
        if (targetCalibrationRemaining <= 0) return
        targetCalibrationCount++
        targetSumR += result.r
        targetSumG += result.g
        targetSumB += result.b
        targetSumR2 += result.r.toDouble() * result.r
        targetSumG2 += result.g.toDouble() * result.g
        targetSumB2 += result.b.toDouble() * result.b
        targetCalibrationRemaining--
        val done = TARGET_CALIBRATION_SHOTS - targetCalibrationRemaining
        if (targetCalibrationRemaining > 0) {
            listener?.onStatus("Couleur ennemi : tir $done/$TARGET_CALIBRATION_SHOTS enregistré · garde le centre sur la cible.")
            speak(done.toString())
            return
        }
        val n = targetCalibrationCount.toDouble().coerceAtLeast(1.0)
        val mr = targetSumR / n
        val mg = targetSumG / n
        val mb = targetSumB / n
        val sr = sqrt((targetSumR2 / n - mr * mr).coerceAtLeast(0.0))
        val sg = sqrt((targetSumG2 / n - mg * mg).coerceAtLeast(0.0))
        val sb = sqrt((targetSumB2 / n - mb * mb).coerceAtLeast(0.0))
        val tolerance = max(32.0, sqrt(sr * sr + sg * sg + sb * sb) * 3.0).coerceAtMost(120.0).toFloat()
        val id = currentSettings().profileId
        prefs.edit()
            .putInt("target.$id.r", mr.roundToInt())
            .putInt("target.$id.g", mg.roundToInt())
            .putInt("target.$id.b", mb.roundToInt())
            .putFloat("target.$id.tolerance", tolerance)
            .apply()
        speak("couleur ennemi calibrée")
        listener?.onStatus("Couleur ennemi calibrée · RGB ${mr.roundToInt()}/${mg.roundToInt()}/${mb.roundToInt()} · tolérance ${tolerance.roundToInt()}.")
    }

    private fun maybePublishAimPatch(image: ImageProxy, cx: Int, cy: Int) {
        patchFrameCounter++
        if (patchFrameCounter % 4 != 0) return
        val half = AIM_PATCH_SIZE / 2
        val pixels = IntArray(AIM_PATCH_SIZE * AIM_PATCH_SIZE)
        var index = 0
        for (dy in -half..half) {
            for (dx in -half..half) {
                val p = sample(image, cx + dx, cy + dy)
                pixels[index++] = Color.rgb(p.r, p.g, p.b)
            }
        }
        val remaining = targetCalibrationRemaining
        ContextCompat.getMainExecutor(this).execute {
            listener?.onAimPatch(AIM_PATCH_SIZE, pixels, remaining)
        }
    }

    private fun consumeCalibration(result: ScanResult) {
'''
service = replace_once(service, old_sample_tail, new_sample_tail, "target helpers")

old_sensor = '''    override fun onSensorChanged(event: SensorEvent) {
        val vector = motionVector(event) ?: return
        val now = SystemClock.elapsedRealtime()
'''
new_sensor = '''    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            handleOrientation(event)
            return
        }
        val vector = motionVector(event) ?: return
        val now = SystemClock.elapsedRealtime()
'''
service = replace_once(service, old_sensor, new_sensor, "route orientation")

insert_before_motion = '''    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun motionVector(event: SensorEvent): FloatArray? {
'''
orientation_code = '''    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleOrientation(event: SensorEvent) {
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
            upwardTurnAccumDeg = 0f
            upwardTurnStartMs = now
            if (prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false)) {
                notifyStatus("Arme relevée · geste de recharge autorisé pendant 5 s.")
            }
        }
    }

    private fun motionVector(event: SensorEvent): FloatArray? {
'''
service = replace_once(service, insert_before_motion, orientation_code, "orientation handler")

old_reload_detect = '''    private fun handleReloadGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now - lastReloadGestureMs < RELOAD_GESTURE_COOLDOWN_MS || now - lastGestureActionMs < GESTURE_ACTION_GUARD_MS) return
'''
new_reload_detect = '''    private fun handleReloadGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now > reloadGateUntilMs) {
            reloadProjectionImpulse = null
            return
        }
        if (now - lastReloadGestureMs < RELOAD_GESTURE_COOLDOWN_MS || now - lastGestureActionMs < GESTURE_ACTION_GUARD_MS) return
'''
service = replace_once(service, old_reload_detect, new_reload_detect, "reload gate check")

service = replace_once(service,
'''            reloadProjectionImpulse = null
            lastReloadGestureMs = now
            lastGestureActionMs = now
            mainHandler.post { reloadMagazine() }
''',
'''            reloadProjectionImpulse = null
            reloadGateUntilMs = 0L
            lastReloadGestureMs = now
            lastGestureActionMs = now
            mainHandler.post { reloadMagazine() }
''', "close reload gate")

old_registration = '''    private fun updateSensorRegistration(force: Boolean = false) {
        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false)
        if (shouldRun && !sensorRegistered) {
            motionSensor?.let {
                sensorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                if (sensorRegistered && !it.isWakeUpSensor) acquireGestureWakeLock()
            }
        } else if (!shouldRun && sensorRegistered) {
            sensorManager.unregisterListener(this)
            sensorRegistered = false
            releaseGestureWakeLock()
        }
    }
'''
new_registration = '''    private fun updateSensorRegistration(force: Boolean = false) {
        val reloadEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) || calibratingReloadGesture
        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || reloadEnabled
        if (shouldRun && !sensorRegistered) {
            motionSensor?.let {
                sensorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                if (sensorRegistered && !it.isWakeUpSensor) acquireGestureWakeLock()
            }
        }
        if (shouldRun && reloadEnabled && !orientationRegistered) {
            orientationSensor?.let {
                orientationRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        } else if ((!reloadEnabled || !shouldRun) && orientationRegistered) {
            orientationSensor?.let { sensorManager.unregisterListener(this, it) }
            orientationRegistered = false
        }
        if (!shouldRun && sensorRegistered) {
            motionSensor?.let { sensorManager.unregisterListener(this, it) }
            sensorRegistered = false
            releaseGestureWakeLock()
        }
    }
'''
service = replace_once(service, old_registration, new_registration, "orientation registration")

service = replace_once(service,
'''        if (sensorRegistered) sensorManager.unregisterListener(this)
        sensorRegistered = false
''',
'''        if (sensorRegistered || orientationRegistered) sensorManager.unregisterListener(this)
        sensorRegistered = false
        orientationRegistered = false
''', "sensor destroy")

service = replace_once(service,
'''    companion object {
        const val SAMPLE_CENTER = "center"
''',
'''    companion object {
        private const val WEBSC_V070 = true
        const val SAMPLE_CENTER = "center"
''', "version marker")

service = replace_once(service,
'''        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
        private const val CALIBRATION_MIN_ACCEL = 3.0f
''',
'''        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
        private const val UPWARD_TURN_MIN_DEG = 20f
        private const val UPWARD_TURN_WINDOW_MS = 800L
        private const val RELOAD_GATE_MS = 5000L
        private const val TARGET_CALIBRATION_SHOTS = 10
        private const val AIM_PATCH_SIZE = 11
        private const val CALIBRATION_MIN_ACCEL = 3.0f
''', "v070 constants")

# UI: zoom window + enemy-color wording.
activity = replace_once(activity,
'''    private lateinit var recText: TextView
''',
'''    private lateinit var recText: TextView
    private lateinit var aimZoomView: AimZoomView
''', "zoom field")

activity = replace_once(activity,
'''        override fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean) {
            ammoText.text = if (reloading) "Chargeur $ammo/$capacity · rechargement…" else "Chargeur $ammo/$capacity"
        }
''',
'''        override fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean) {
            ammoText.text = if (reloading) "Chargeur $ammo/$capacity · rechargement…" else "Chargeur $ammo/$capacity"
        }

        override fun onAimPatch(size: Int, pixels: IntArray, calibrationRemaining: Int) {
            aimZoomView.setPatch(size, pixels, calibrationRemaining)
            aimZoomView.visibility = if (calibrationRemaining > 0) View.VISIBLE else View.GONE
        }
''', "zoom listener")

activity = replace_once(activity,
'''        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(CrosshairView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val settingsButton = Button(this).apply {
''',
'''        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(CrosshairView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        aimZoomView = AimZoomView(this).apply {
            visibility = View.GONE
            contentDescription = "Zoom des pixels centraux pour calibrer la couleur ennemi"
        }
        val zoomParams = FrameLayout.LayoutParams(dp(176), dp(176), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        zoomParams.setMargins(0, dp(76), 0, 0)
        root.addView(aimZoomView, zoomParams)

        val settingsButton = Button(this).apply {
''', "zoom view")

activity = replace_once(activity,
'''        val profileText = label(service.calibrationSummary())
        content.addView(profileText)

        val calibrateButton = Button(this).apply { text = "ÉTALONNER CE PROFIL CAMÉRA" }
        content.addView(calibrateButton)
''',
'''        val profileText = label(service.calibrationSummary())
        content.addView(profileText)
        content.addView(label("Calibration optionnelle : vise la couleur ennemi dans le zoom central et déclenche 10 tirs. Elle remplace le bleu historique pour ce profil caméra/résolution."))

        val calibrateButton = Button(this).apply { text = "CALIBRER COULEUR ENNEMI · 10 TIRS" }
        content.addView(calibrateButton)
''', "target calibration UI")

activity = replace_once(activity,
'''        val reloadGestureCheck = CheckBox(this).apply {
            text = "Recharger avec un geste calibré"
''',
'''        val reloadGestureCheck = CheckBox(this).apply {
            text = "Recharger par geste après avoir relevé l'arme (fenêtre 5 s)"
''', "reload gating UI")

activity = replace_once(activity,
'''    class CrosshairView(context: Context) : View(context) {
''',
'''    class AimZoomView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
        }
        private var patchSize = 0
        private var pixels = IntArray(0)
        private var remaining = 0

        fun setPatch(size: Int, values: IntArray, calibrationRemaining: Int) {
            patchSize = size
            pixels = values.copyOf()
            remaining = calibrationRemaining
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.argb(210, 0, 0, 0))
            if (patchSize > 0 && pixels.size >= patchSize * patchSize) {
                val cellW = width.toFloat() / patchSize
                val cellH = height.toFloat() / patchSize
                var i = 0
                for (y in 0 until patchSize) {
                    for (x in 0 until patchSize) {
                        paint.color = pixels[i++]
                        paint.style = Paint.Style.FILL
                        canvas.drawRect(x * cellW, y * cellH, (x + 1) * cellW + 1f, (y + 1) * cellH + 1f, paint)
                    }
                }
            }
            canvas.drawRect(1f, 1f, width - 1f, height - 1f, border)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawLine(cx - 26f, cy, cx + 26f, cy, cross)
            canvas.drawLine(cx, cy - 26f, cx, cy + 26f, cross)
            if (remaining > 0) canvas.drawText("CIBLE ${11 - remaining}/10", 8f, 30f, textPaint)
        }
    }

    class CrosshairView(context: Context) : View(context) {
''', "aim zoom class")

service_path.write_text(service, encoding="utf-8")
activity_path.write_text(activity, encoding="utf-8")
print("WebCS v0.7.0 orientation gate + target calibration patched")
