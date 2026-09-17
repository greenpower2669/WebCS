package online.tek4all.webcs.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalCamera2Interop::class)
class CameraForegroundService : LifecycleService(), TextToSpeech.OnInitListener, SensorEventListener {
    interface Listener {
        fun onReady(score: Int, recording: Boolean)
        fun onShot(hit: Boolean, votes: Int, total: Int, score: Int, details: String)
        fun onRecording(active: Boolean, message: String)
        fun onStatus(message: String)
        fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean)
        fun onAimPatch(size: Int, pixels: IntArray, calibrationRemaining: Int)
    }

    data class CameraOption(val id: String, val label: String)
    data class ResolutionOption(val width: Int, val height: Int) {
        val label: String get() = "${width}×${height}"
    }
    data class QualityOption(val key: String, val label: String)
    data class SettingsSnapshot(
        val cameraId: String,
        val requestedWidth: Int,
        val requestedHeight: Int,
        val effectiveWidth: Int,
        val effectiveHeight: Int,
        val sampleMode: String,
        val videoQuality: String,
        val profileId: String,
        val sfxEnabled: Boolean,
        val sfxVolume: Int,
        val gestureEnabled: Boolean,
        val gestureSensitivity: Int,
        val magazineSize: Int,
        val gestureCalibrated: Boolean,
        val reloadGestureEnabled: Boolean,
        val reloadGestureCalibrated: Boolean
    )

    private data class MotionImpulse(
        val x: Float,
        val y: Float,
        val z: Float,
        val magnitude: Float,
        val timeMs: Long
    )

    private data class ProjectionImpulse(val value: Float, val timeMs: Long)

    private data class AimFrame(
        val timestampNs: Long,
        val patchSize: Int,
        val patchPixels: IntArray,
        val decisionPoints: List<PointSample>
    )

    inner class LocalBinder : Binder() { fun getService(): CameraForegroundService = this@CameraForegroundService }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    @Volatile private var previewEnabled = true
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    private var recordingStopping = false
    private var recordingHasData = false
    private var pendingStopAfterData = false
    private var recordingStartMs = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var gestureWakeLock: PowerManager.WakeLock? = null
    private lateinit var sfx: SfxEngine
    private var score = 0
    private var effectiveWidth = 0
    private var effectiveHeight = 0
    private var ammo = 0
    private var reloading = false

    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private var orientationSensor: Sensor? = null
    private var sensorRegistered = false
    private var orientationRegistered = false
    private val gravity = FloatArray(3)
    private var gravityReady = false
    private var calibrationImpulse: MotionImpulse? = null
    private val gestureCalibrationAxes = mutableListOf<FloatArray>()
    private val gestureCalibrationPeaks = mutableListOf<Float>()
    private var gestureCalibrationRemaining = 0
    private var lastCalibrationPairMs = 0L
    private var projectionImpulse: ProjectionImpulse? = null
    private var lastGestureShotMs = 0L
    private var calibratingReloadGesture = false
    private var reloadProjectionImpulse: ProjectionImpulse? = null
    private var lastReloadGestureMs = 0L
    private var lastGestureActionMs = 0L
    private var lastCameraElevationDeg: Float? = null
    private var orientationCalibrationStage = ORIENTATION_CAL_NONE
    private val orientationCalibrationSamples = mutableListOf<FloatArray>()
    private var orientationCalibrationStartedMs = 0L
    private var orientationCalibrationLastVector: FloatArray? = null
    private var calibrationFlatVector: FloatArray? = null
    private var reloadOrientationArmed = false
    private var reloadOrientationMoveStartMs = 0L
    @Volatile private var reloadGateUntilMs = 0L

    @Volatile private var latestResult: ScanResult? = null
    @Volatile private var calibrationRemaining = 0
    private var calibrationCount = 0
    private var calibrationSumR = 0.0
    private var calibrationSumG = 0.0
    private var calibrationSumB = 0.0
    private var calibrationSumL = 0.0
    private var calibrationSumL2 = 0.0
    private var targetCalibrationRemaining = 0
    private var targetCalibrationCount = 0
    private var targetSumR = 0.0
    private var targetSumG = 0.0
    private var targetSumB = 0.0
    private var targetSumR2 = 0.0
    private var targetSumG2 = 0.0
    private var targetSumB2 = 0.0
    private var patchFrameCounter = 0
    @Volatile private var latestRawPoints: List<PointSample> = emptyList()
    private val eventLog = mutableListOf<String>()
    private var eventSequence = 0L
    private var sessionId = makeSessionId()
    private var sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
    private val aimFrameLock = Any()
    private val aimFrames = ArrayDeque<AimFrame>(AIM_RING_SIZE)

    var listener: Listener? = null

    private val prefs by lazy { getSharedPreferences("webcs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        score = prefs.getInt("score", 0)
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
        sfx = SfxEngine(this).also {
            it.configure(prefs.getBoolean(PREF_SFX_ENABLED, true), prefs.getInt(PREF_SFX_VOLUME, 70))
        }
        ammo = magazineSize()
        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = chooseMotionSensor()
        orientationSensor = chooseOrientationSensor()
        updateSensorRegistration()
        createChannel()
        promoteToForeground(false)
        ensureSessionFile()
        startCameraCore()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_RECORDING -> requestStopRecording()
            ACTION_STOP_SERVICE -> {
                stopRecordingNow()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        previewProvider = surfaceProvider
        // Premier attachement seulement : si le service avait démarré sans UI,
        // on crée une session commune Preview + Analysis + VideoCapture.
        // Ensuite APERÇU ON/OFF ne rebinde JAMAIS la caméra.
        if (cameraProvider != null && previewUseCase == null && recording == null) {
            rebindCamera()
        } else if (previewEnabled) {
            previewUseCase?.setSurfaceProvider(surfaceProvider)
        }
    }

    fun setPreviewEnabled(enabled: Boolean, surfaceProvider: Preview.SurfaceProvider? = previewProvider) {
        previewEnabled = enabled
        if (surfaceProvider != null) previewProvider = surfaceProvider
        val preview = previewUseCase
        if (preview == null) {
            // Cas de tout premier attachement uniquement. Pas utilisé par le bouton ON/OFF normal.
            if (enabled && cameraProvider != null && recording == null && previewProvider != null) rebindCamera()
            return
        }
        // API CameraX officielle : null arrête la production de données pour Preview,
        // sans unbind et sans arrêter ImageAnalysis / la caméra chaude.
        preview.setSurfaceProvider(if (enabled) previewProvider else null)
        listener?.onStatus(
            if (enabled) "Aperçu ON · caméra et buffer de visée inchangés."
            else "Aperçu OFF · rendu vidéo coupé, caméra et buffer de visée restent actifs."
        )
    }

    fun isPreviewEnabled(): Boolean = previewEnabled

    fun isRecording(): Boolean = recording != null
    fun currentScore(): Int = score
    fun currentAmmo(): Int = ammo
    fun currentMagazineSize(): Int = magazineSize()
    fun isReloading(): Boolean = reloading

    fun currentSettings(): SettingsSnapshot {
        val cameraId = selectedCameraId()
        val w = prefs.getInt(PREF_WIDTH, 640)
        val h = prefs.getInt(PREF_HEIGHT, 480)
        val mode = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) ?: SAMPLE_FIVE
        val quality = prefs.getString(PREF_VIDEO_QUALITY, "AUTO") ?: "AUTO"
        return SettingsSnapshot(
            cameraId = cameraId,
            requestedWidth = w,
            requestedHeight = h,
            effectiveWidth = effectiveWidth,
            effectiveHeight = effectiveHeight,
            sampleMode = mode,
            videoQuality = quality,
            profileId = profileId(cameraId, w, h, mode),
            sfxEnabled = prefs.getBoolean(PREF_SFX_ENABLED, true),
            sfxVolume = prefs.getInt(PREF_SFX_VOLUME, 70),
            gestureEnabled = prefs.getBoolean(PREF_GESTURE_ENABLED, false),
            gestureSensitivity = prefs.getInt(PREF_GESTURE_SENSITIVITY, 60),
            magazineSize = magazineSize(),
            gestureCalibrated = hasGestureCalibration(),
            reloadGestureEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false),
            reloadGestureCalibrated = hasReloadGestureCalibration()
        )
    }

    fun configurationSummary(): String {
        val s = currentSettings()
        val effective = if (s.effectiveWidth > 0) "${s.effectiveWidth}×${s.effectiveHeight}" else "en attente"
        val mode = if (s.sampleMode == SAMPLE_CENTER) "centre" else "5 points"
        val shotMotion = if (s.gestureEnabled) " · tir geste ON" else ""
        val reloadMotion = if (s.reloadGestureEnabled) " · recharge geste ON" else ""
        val preview = if (previewEnabled) " · aperçu ON" else " · aperçu OFF"
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · analyse au tir · buffer ${AIM_RING_SIZE} frames · vidéo ${s.videoQuality}$shotMotion$reloadMotion$preview"
    }

    fun getCameraOptions(): List<CameraOption> {
        val provider = cameraProvider ?: return emptyList()
        return provider.availableCameraInfos.map { info ->
            val id = Camera2CameraInfo.from(info).cameraId
            val facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_FRONT -> "avant"
                CameraSelector.LENS_FACING_BACK -> "arrière"
                else -> "externe"
            }
            val focals = Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.joinToString("/") { String.format(Locale.US, "%.1f", it) }
                ?.takeIf { it.isNotBlank() }
            CameraOption(id, if (focals != null) "Caméra $id · $facing · ${focals} mm" else "Caméra $id · $facing")
        }.sortedBy { it.id }
    }

    fun getResolutionOptions(cameraId: String): List<ResolutionOption> {
        val info = findCameraInfo(cameraId) ?: return listOf(ResolutionOption(640, 480))
        val map = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        return sizes
            .map { ResolutionOption(it.width, it.height) }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height.toLong() }
            .ifEmpty { listOf(ResolutionOption(640, 480)) }
    }

    fun getVideoQualityOptions(cameraId: String): List<QualityOption> {
        val info = findCameraInfo(cameraId) ?: return listOf(QualityOption("AUTO", "Auto"))
        val supported = QualitySelector.getSupportedQualities(info)
        val result = mutableListOf(QualityOption("AUTO", "Auto (compatible analyse)"))
        supported.forEach { q -> qualityKey(q)?.let { key -> result.add(QualityOption(key, key)) } }
        return result.distinctBy { it.key }
    }

    fun applySettings(
        cameraId: String,
        width: Int,
        height: Int,
        sampleMode: String,
        videoQuality: String,
        sfxEnabled: Boolean,
        sfxVolume: Int,
        gestureEnabled: Boolean,
        gestureSensitivity: Int,
        reloadGestureEnabled: Boolean,
        magazineSize: Int
    ): Boolean {
        if (recording != null) {
            listener?.onStatus("Arrête le REC avant de changer les réglages.")
            return false
        }
        val oldCamera = selectedCameraId()
        val oldWidth = prefs.getInt(PREF_WIDTH, 640)
        val oldHeight = prefs.getInt(PREF_HEIGHT, 480)
        val oldQuality = prefs.getString(PREF_VIDEO_QUALITY, "AUTO") ?: "AUTO"
        val oldMag = magazineSize()
        prefs.edit()
            .putString(PREF_CAMERA_ID, cameraId)
            .putInt(PREF_WIDTH, width)
            .putInt(PREF_HEIGHT, height)
            .putString(PREF_SAMPLE_MODE, if (sampleMode == SAMPLE_CENTER) SAMPLE_CENTER else SAMPLE_FIVE)
            .putString(PREF_VIDEO_QUALITY, videoQuality)
            .putBoolean(PREF_SFX_ENABLED, sfxEnabled)
            .putInt(PREF_SFX_VOLUME, sfxVolume.coerceIn(0, 100))
            .putBoolean(PREF_GESTURE_ENABLED, gestureEnabled && hasGestureCalibration())
            .putInt(PREF_GESTURE_SENSITIVITY, gestureSensitivity.coerceIn(0, 100))
            .putBoolean(PREF_RELOAD_GESTURE_ENABLED, reloadGestureEnabled && hasReloadGestureCalibration())
            .putInt(PREF_MAGAZINE_SIZE, magazineSize.coerceIn(1, 99))
            .apply()
        sfx.configure(sfxEnabled, sfxVolume)
        if (oldMag != magazineSize()) ammo = magazineSize()
        updateSensorRegistration()
        notifyAmmo()
        val cameraChanged = oldCamera != cameraId || oldWidth != width || oldHeight != height || oldQuality != videoQuality
        if (cameraChanged) {
            effectiveWidth = 0
            effectiveHeight = 0
            latestResult = null
            rebindCamera()
        }
        val gestureNote = if (gestureEnabled && !hasGestureCalibration()) " · tir à calibrer" else ""
        val reloadNote = if (reloadGestureEnabled && !hasReloadGestureCalibration()) " · recharge à calibrer" else ""
        val orientationNote = if (reloadGestureEnabled && !hasOrientationCalibration()) " · orientation recharge à calibrer" else ""
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote$reloadNote$orientationNote.")
        return true
    }

    fun startCalibration() {
        if (nearestAimFrame(SystemClock.elapsedRealtimeNanos()) == null) {
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

    fun currentPlayerName(): String = prefs.getString(PREF_PLAYER_NAME, "Joueur") ?: "Joueur"

    fun setPlayerName(value: String) {
        val clean = value.trim().take(40).ifBlank { "Joueur" }
        prefs.edit().putString(PREF_PLAYER_NAME, clean).apply()
    }

    fun sessionDataSummary(): String {
        val count = synchronized(eventLog) { eventLog.size }
        return "Session $sessionId · $count événements · sauvegarde auto entraînement active · joueur ${currentPlayerName()}"
    }

    fun startNewDataSession() {
        synchronized(eventLog) { eventLog.clear() }
        eventSequence = 0L
        sessionId = makeSessionId()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        ensureSessionFile()
        listener?.onStatus("Nouvelle session de données · $sessionId · sauvegarde auto active")
    }

    fun resetGame() {
        // Fermer proprement la partie dans son CSV avant de créer la suivante.
        val previousScore = score
        val previousAmmo = ammo
        logGameEvent("game_end", latestResult, null, trainingLabel = "reset")

        score = 0
        prefs.edit().putInt("score", 0).apply()
        ammo = magazineSize()
        reloading = false
        reloadGateUntilMs = 0L
        reloadOrientationArmed = false
        reloadOrientationMoveStartMs = 0L
        projectionImpulse = null
        reloadProjectionImpulse = null
        lastGestureActionMs = 0L
        lastGestureShotMs = 0L
        lastReloadGestureMs = 0L

        startNewDataSession()
        logGameEvent("game_start", latestResult, null, trainingLabel = "reset")
        listener?.onReady(score, isRecording())
        notifyAmmo()
        notifyStatus("Nouvelle partie · score 0 · chargeur ${ammo}/${magazineSize()} · partie précédente conservée (score $previousScore, munitions $previousAmmo).")
    }

    fun recordImportedCalibrationSnapshot() {
        val id = currentSettings().profileId
        if (!prefs.contains("target.$id.r")) return
        val r = prefs.getInt("target.$id.r", 0)
        val g = prefs.getInt("target.$id.g", 0)
        val b = prefs.getInt("target.$id.b", 0)
        val hsv = FloatArray(3)
        Color.RGBToHSV(r, g, b, hsv)
        val snapshot = ScanResult(false, 0, 0, r, g, b, hsv[0], hsv[1], hsv[2])
        logGameEvent("target_calibration_snapshot", snapshot, null, trainingLabel = "enemy_reference_imported")
    }

    private fun trainingDir(): File = File(filesDir, "webcs-training").apply { mkdirs() }

    private fun currentSessionFile(): File = File(trainingDir(), "WebCS-training-$sessionId.csv")

    private fun ensureSessionFile(): File {
        val file = currentSessionFile()
        if (!file.exists()) {
            file.writeText("\uFEFF$CSV_HEADER\n", Charsets.UTF_8)
        }
        return file
    }

    private fun persistSessionRow(row: String) {
        try {
            ensureSessionFile().appendText(row + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            notifyStatus("Attention : sauvegarde data impossible · ${e.message ?: "erreur"}")
        }
    }

    fun exportSessionCsv(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener?.onStatus("Export CSV direct disponible à partir d'Android 10.")
            return null
        }
        val rows = synchronized(eventLog) { eventLog.toList() }
        val source = ensureSessionFile()
        val fileName = "WebCS-training-arbitrage-$sessionId.csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/WebCS")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) throw IllegalStateException("sortie CSV indisponible")
                source.inputStream().use { input -> input.copyTo(output) }
            }
            listener?.onStatus("Data entraînement/arbitrage prête · $fileName · ${rows.size} événements.")
            uri
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            listener?.onStatus("Erreur export CSV : ${e.message ?: "inconnue"}")
            null
        }
    }

    private fun makeSessionId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + "-" + (SystemClock.elapsedRealtime() % 100000L)

    private fun csv(value: Any?): String {
        val text = value?.toString() ?: ""
        return if (text.contains(',') || text.contains('"') || text.contains('\n')) {
            "\"" + text.replace("\"", "\"\"") + "\""
        } else text
    }

    private fun recognitionMode(): String {
        val id = currentSettings().profileId
        return if (prefs.contains("target.$id.r")) "calibrated_rgb_distance" else "legacy_blue_hsv"
    }

    private fun targetDistance(result: ScanResult?): Float? {
        result ?: return null
        val id = currentSettings().profileId
        if (!prefs.contains("target.$id.r")) return null
        val dr = result.r - prefs.getInt("target.$id.r", 0)
        val dg = result.g - prefs.getInt("target.$id.g", 0)
        val db = result.b - prefs.getInt("target.$id.b", 0)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    private fun patchHex(frame: AimFrame?): String {
        frame ?: return ""
        return frame.patchPixels.joinToString(";") { String.format(Locale.US, "%06X", it and 0xFFFFFF) }
    }

    private fun logGameEvent(
        type: String,
        result: ScanResult? = latestResult,
        hit: Boolean? = null,
        frame: AimFrame? = null,
        triggerNs: Long? = null,
        trainingLabel: String = ""
    ) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val chosenFrame = frame ?: nearestAimFrame(triggerNs ?: nowNs)
        val trigger = triggerNs ?: nowNs
        val frameNs = chosenFrame?.timestampNs
        val deltaUs = frameNs?.let { (it - trigger) / 1000L }
        val seq = ++eventSequence
        val points = chosenFrame?.decisionPoints?.take(5).orEmpty()
        val values = mutableListOf<String>()
        values += listOf(
            "3", sessionId, seq.toString(), type,
            System.currentTimeMillis().toString(), nowNs.toString(), (nowNs - sessionStartElapsedNs).toString(),
            trigger.toString(), frameNs?.toString() ?: "", deltaUs?.toString() ?: "",
            currentPlayerName(), hit?.toString() ?: "", trainingLabel,
            recognitionMode(), targetDistance(result)?.toString() ?: "",
            result?.r?.toString() ?: "", result?.g?.toString() ?: "", result?.b?.toString() ?: "",
            result?.h?.toString() ?: "", result?.s?.toString() ?: "", result?.v?.toString() ?: ""
        )
        for (i in 0 until 5) {
            val p = points.getOrNull(i)
            values += listOf(p?.r?.toString() ?: "", p?.g?.toString() ?: "", p?.b?.toString() ?: "")
        }
        values += listOf(
            chosenFrame?.patchSize?.toString() ?: "", patchHex(chosenFrame),
            lastCameraElevationDeg?.toString() ?: "",
            selectedCameraId(), effectiveWidth.toString(), effectiveHeight.toString(),
            prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) ?: SAMPLE_FIVE,
            ammo.toString(), magazineSize().toString(), score.toString(), currentSettings().profileId,
            if (prefs.contains("target.${currentSettings().profileId}.tolerance")) {
                prefs.getFloat("target.${currentSettings().profileId}.tolerance", 42f).toString()
            } else ""
        )
        val row = values.joinToString(",") { csv(it) }
        synchronized(eventLog) { eventLog.add(row) }
        persistSessionRow(row)
    }

    fun startGestureCalibration() {
        calibratingReloadGesture = false
        if (motionSensor == null) {
            listener?.onStatus("Aucun accéléromètre exploitable sur cet appareil.")
            return
        }
        gestureCalibrationAxes.clear()
        gestureCalibrationPeaks.clear()
        gestureCalibrationRemaining = GESTURE_CALIBRATION_COUNT
        calibrationImpulse = null
        projectionImpulse = null
        lastCalibrationPairMs = 0L
        updateSensorRegistration(force = true)
        speak("calibration mouvement")
        listener?.onStatus("Calibration tir : tiens le téléphone comme en jeu puis fais 5 à-coups rapides avant-arrière ou bas-haut.")
    }

    fun gestureCalibrationSummary(): String {
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

    fun orientationCalibrationSummary(): String {
        val sensor = orientationSensor ?: return "Orientation recharge : capteur de rotation indisponible"
        if (!hasOrientationCalibration()) return "Orientation recharge : non calibrée · ${sensor.name}"
        val readyElevation = prefs.getFloat(PREF_ORIENTATION_READY_ELEVATION, Float.NaN)
        val flatElevation = prefs.getFloat(PREF_ORIENTATION_FLAT_ELEVATION, Float.NaN)
        val course = if (readyElevation.isFinite() && flatElevation.isFinite()) {
            abs(flatElevation - readyElevation)
        } else {
            prefs.getFloat(PREF_ORIENTATION_LEARNED_ANGLE, 0f)
        }
        val gate = prefs.getFloat(PREF_ORIENTATION_GATE_DEG, ORIENTATION_GATE_MIN_DEG)
        val wake = if (sensor.isWakeUpSensor) "wake-up" else "service + wake lock"
        return "Orientation recharge : calibrée · élévation ${course.roundToInt()}° · seuil ${gate.roundToInt()}° · azimut libre 360° · ${sensor.name} · $wake"
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
        stopRecordingNow()
        stopSelf()
    }

    fun fire() {
        fireAt(SystemClock.elapsedRealtimeNanos())
    }

    private fun fireAt(triggerNs: Long) {
        if (reloading) {
            listener?.onStatus("Rechargement en cours…")
            return
        }
        val frame = nearestAimFrame(triggerNs)
        if (frame == null) {
            listener?.onStatus("Pas encore de frame de visée exploitable.")
            return
        }
        val result = classifyFrame(frame)
        latestResult = result
        latestRawPoints = frame.decisionPoints
        val deltaUs = (frame.timestampNs - triggerNs) / 1000L
        if (targetCalibrationRemaining > 0) {
            sfx.playShot()
            recordTargetCalibrationShot(result, frame, triggerNs)
            return
        }
        if (ammo <= 0) {
            sfx.playEmpty()
            logGameEvent("empty", result, null, frame, triggerNs)
            listener?.onStatus("Clic · chargeur vide. Recharge.")
            notifyAmmo()
            return
        }
        ammo--
        sfx.playShot()
        if (result.hit) {
            score++
            prefs.edit().putInt("score", score).apply()
            sfx.playHitReward()
        }
        logGameEvent("shot", result, result.hit, frame, triggerNs)
        val mode = if (result.total == 1) "centre" else "5 points"
        val details = "RGB ${result.r}/${result.g}/${result.b}   HSV ${result.h.roundToInt()}°/${(result.s * 100).roundToInt()}%/${(result.v * 100).roundToInt()}% · $mode · frame Δ ${deltaUs} µs · ${effectiveWidth}×${effectiveHeight} · munitions $ammo/${magazineSize()}"
        listener?.onShot(result.hit, result.blueVotes, result.total, score, details)
        notifyAmmo()
        if (ammo == 0) {
            mainHandler.postDelayed({
                sfx.playEndOfMagazine()
                listener?.onStatus("Chargeur vide · RECHARGER.")
            }, 120L)
        }
    }

    fun reloadMagazine() {
        if (reloading) return
        val capacity = magazineSize()
        if (ammo >= capacity) {
            listener?.onStatus("Chargeur déjà plein · $ammo/$capacity.")
            return
        }
        reloading = true
        logGameEvent("reload", latestResult, null)
        sfx.playReload()
        notifyAmmo()
        listener?.onStatus("Rechargement…")
        mainHandler.postDelayed({
            if (!reloading) return@postDelayed
            ammo = magazineSize()
            reloading = false
            notifyAmmo()
            listener?.onStatus("Rechargé · $ammo/${magazineSize()}.")
        }, RELOAD_DURATION_MS)
    }

    fun toggleRecording() {
        if (!::videoCapture.isInitialized) {
            listener?.onStatus("Caméra vidéo pas encore prête.")
            return
        }
        if (recording != null) {
            requestStopRecording()
            return
        }
        // L'enregistrement vidéo ne doit pas dépendre d'ImageAnalysis.
        // Le Preview peut être valide même si l'analyse est momentanément en reprise.
        if (cameraProvider == null) {
            listener?.onStatus("Caméra pas encore prête pour REC.")
            return
        }

        val fileName = "WebCS-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/WebCS")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()

        recordingStopping = false
        recordingHasData = false
        pendingStopAfterData = false
        recordingStartMs = SystemClock.elapsedRealtime()
        listener?.onStatus("Préparation REC…")
        recording = videoCapture.output
            .prepareRecording(this, output)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        acquireRecordingWakeLock()
                        promoteToForeground(true)
                        speak("rec")
                        listener?.onRecording(true, "REC actif · attends au moins une seconde avant STOP.")
                    }
                    is VideoRecordEvent.Status -> {
                        if (event.recordingStats.recordedDurationNanos >= MIN_VALID_RECORDING_NS) {
                            recordingHasData = true
                            if (pendingStopAfterData) stopRecordingNow()
                        }
                    }
                    is VideoRecordEvent.Finalize -> handleRecordingFinalize(event)
                }
            }
    }

    private fun requestStopRecording() {
        val active = recording ?: return
        if (recordingStopping) return
        val elapsed = SystemClock.elapsedRealtime() - recordingStartMs
        if (recordingHasData && elapsed >= MIN_RECORDING_MS) {
            stopRecordingNow()
        } else {
            pendingStopAfterData = true
            listener?.onStatus("STOP demandé · finalisation dès la première trame vidéo valide…")
            val wait = (MIN_RECORDING_MS - elapsed).coerceAtLeast(250L)
            mainHandler.postDelayed({
                if (recording === active && pendingStopAfterData && !recordingStopping) stopRecordingNow()
            }, wait + 450L)
        }
    }

    private fun stopRecordingNow() {
        val active = recording ?: return
        if (!recordingStopping) {
            recordingStopping = true
            pendingStopAfterData = false
            active.stop()
            speak("stop")
            listener?.onStatus("Arrêt et sauvegarde de la vidéo…")
        }
    }

    private fun handleRecordingFinalize(event: VideoRecordEvent.Finalize) {
        releaseRecordingWakeLock()
        recording?.close()
        recording = null
        recordingStopping = false
        recordingHasData = false
        pendingStopAfterData = false
        promoteToForeground(false)
        if (!event.hasError()) {
            listener?.onRecording(false, "Vidéo sauvegardée dans Films/WebCS.")
            return
        }

        val uri = event.outputResults.outputUri
        if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
            event.error == VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED ||
            event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR) {
            deleteInvalidRecording(uri)
        }
        val message = recordingErrorMessage(event.error)
        listener?.onRecording(false, message)
        if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA ||
            event.error == VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR ||
            event.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
            mainHandler.postDelayed({
                if (recording == null) rebindCamera()
            }, 350L)
        }
    }

    private fun recordingErrorMessage(error: Int): String = when (error) {
        VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "REC annulé : aucune trame vidéo valide. Caméra réinitialisée, réessaie après une seconde."
        VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE -> "REC interrompu : la source caméra s'est arrêtée. Caméra réinitialisée."
        VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "REC impossible : stockage insuffisant."
        VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "REC impossible : erreur d'encodage vidéo. Fichier invalide supprimé."
        VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "REC impossible : encodeur à réinitialiser. Caméra réinitialisée."
        VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "REC impossible : destination vidéo invalide."
        else -> "REC interrompu (code $error)."
    }

    private fun deleteInvalidRecording(uri: Uri) {
        if (uri == Uri.EMPTY) return
        try { contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }

    private fun startCameraCore() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                ensureValidCameraPreference()
                rebindCamera()
            } catch (e: Exception) {
                listener?.onStatus("Erreur caméra de fond : ${e.message ?: "inconnue"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        val provider = cameraProvider ?: return
        if (recording != null) return
        try {
            provider.unbindAll()
            previewUseCase = null
            val info = findCameraInfo(selectedCameraId()) ?: provider.availableCameraInfos.firstOrNull()
            if (info == null) {
                listener?.onStatus("Aucune caméra CameraX disponible.")
                return
            }
            val selectedId = Camera2CameraInfo.from(info).cameraId
            if (selectedId != prefs.getString(PREF_CAMERA_ID, "")) prefs.edit().putString(PREF_CAMERA_ID, selectedId).apply()
            val selector = selectorForId(selectedId)
            val requested = Size(prefs.getInt(PREF_WIDTH, 640), prefs.getInt(PREF_HEIGHT, 480))

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(requested)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (effectiveWidth != image.width || effectiveHeight != image.height) {
                        effectiveWidth = image.width
                        effectiveHeight = image.height
                        notifyStatus("Flux de visée prêt : ${image.width}×${image.height} · caméra $selectedId · reconnaissance seulement au tir")
                    }
                    val frame = captureAimFrame(image)
                    synchronized(aimFrameLock) {
                        while (aimFrames.size >= AIM_RING_SIZE) aimFrames.removeFirst()
                        aimFrames.addLast(frame)
                    }
                    maybePublishAimPatch(frame)
                } catch (e: Exception) {
                    notifyStatus("Erreur buffer caméra : ${e.javaClass.simpleName} · ${e.message ?: "sans détail"}")
                } finally {
                    image.close()
                }
            }

            val quality = selectedQuality(info)
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
            videoCapture = VideoCapture.withOutput(recorder)

            // Une seule liaison CameraX pour éviter le cas où Preview fonctionne
            // alors qu'ImageAnalysis ne reçoit aucune trame.
            val preview = previewProvider?.let { surface ->
                Preview.Builder()
                    .setTargetResolution(requested)
                    .build()
                    .also { it.setSurfaceProvider(if (previewEnabled) surface else null) }
            }
            previewUseCase = preview
            if (preview != null) {
                provider.bindToLifecycle(this, selector, preview, analysis, videoCapture)
            } else {
                provider.bindToLifecycle(this, selector, analysis, videoCapture)
            }
            listener?.onReady(score, isRecording())
            notifyAmmo()
        } catch (e: Exception) {
            listener?.onStatus("Erreur réglage caméra : ${e.message ?: "inconnue"}")
        }
    }

    private fun bindPreviewOnly() {
        val provider = cameraProvider ?: return
        val surface = previewProvider ?: return
        try {
            val requested = Size(prefs.getInt(PREF_WIDTH, 640), prefs.getInt(PREF_HEIGHT, 480))
            previewUseCase = Preview.Builder()
                .setTargetResolution(requested)
                .build()
                .also {
                    it.setSurfaceProvider(surface)
                    provider.bindToLifecycle(this, selectorForId(selectedCameraId()), it)
                }
        } catch (e: Exception) {
            listener?.onStatus("Erreur aperçu : ${e.message ?: "inconnue"}")
        }
    }

    private fun captureAimFrame(image: ImageProxy): AimFrame {
        val cx = image.width / 2
        val cy = image.height / 2
        val half = AIM_PATCH_SIZE / 2
        val patch = IntArray(AIM_PATCH_SIZE * AIM_PATCH_SIZE)
        var index = 0
        for (dy in -half..half) {
            for (dx in -half..half) {
                val p = sample(image, cx + dx, cy + dy)
                patch[index++] = Color.rgb(p.r, p.g, p.b)
            }
        }
        val gap = max(2, (min(image.width, image.height) * 0.008f).roundToInt())
        val offsets = arrayOf(0 to 0, -gap to -gap, gap to -gap, -gap to gap, gap to gap)
        val decision = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        return AimFrame(image.imageInfo.timestamp, AIM_PATCH_SIZE, patch, decision)
    }

    private fun nearestAimFrame(triggerNs: Long): AimFrame? = synchronized(aimFrameLock) {
        aimFrames.minByOrNull { frame -> kotlin.math.abs(frame.timestampNs - triggerNs) }
    }

    private fun classifyFrame(frame: AimFrame): ScanResult {
        val points = frame.decisionPoints
        val centerOnly = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER
        val used = if (centerOnly) listOf(points.first()) else points
        val targetVotes = used.count { matchesTarget(it.r, it.g, it.b) }
        val avgR = used.sumOf { it.r } / used.size
        val avgG = used.sumOf { it.g } / used.size
        val avgB = used.sumOf { it.b } / used.size
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        val hit = if (centerOnly) targetVotes == 1 else targetVotes >= 3
        return ScanResult(hit, targetVotes, used.size, avgR, avgG, avgB, hsv[0], hsv[1], hsv[2])
    }

    private fun sample(image: ImageProxy, x0: Int, y0: Int): PointSample {
        val x = x0.coerceIn(0, image.width - 1)
        val y = y0.coerceIn(0, image.height - 1)
        val yp = image.planes[0]
        val up = image.planes[1]
        val vp = image.planes[2]
        val yy = yp.buffer.get(y * yp.rowStride + x * yp.pixelStride).toInt() and 0xff
        val uvX = x / 2
        val uvY = y / 2
        val uu = up.buffer.get(uvY * up.rowStride + uvX * up.pixelStride).toInt() and 0xff
        val vv = vp.buffer.get(uvY * vp.rowStride + uvX * vp.pixelStride).toInt() and 0xff
        val c = (yy - 16).coerceAtLeast(0)
        val d = uu - 128
        val e = vv - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        val hsv = FloatArray(3)
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

    private fun recordTargetCalibrationShot(result: ScanResult, frame: AimFrame, triggerNs: Long) {
        if (targetCalibrationRemaining <= 0) return
        targetCalibrationCount++
        logGameEvent("target_calibration", result, null, frame, triggerNs, "enemy_reference")
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

    private fun maybePublishAimPatch(frame: AimFrame) {
        if (targetCalibrationRemaining <= 0) return
        patchFrameCounter++
        if (patchFrameCounter % 4 != 0) return
        val pixels = frame.patchPixels.copyOf()
        val remaining = targetCalibrationRemaining
        ContextCompat.getMainExecutor(this).execute {
            listener?.onAimPatch(frame.patchSize, pixels, remaining)
        }
    }

    private fun consumeCalibration(result: ScanResult) {
        if (calibrationRemaining <= 0) return
        calibrationCount++
        calibrationSumR += result.r
        calibrationSumG += result.g
        calibrationSumB += result.b
        val luma = 0.2126 * result.r + 0.7152 * result.g + 0.0722 * result.b
        calibrationSumL += luma
        calibrationSumL2 += luma * luma
        calibrationRemaining--
        if (calibrationRemaining == 0 && calibrationCount > 0) {
            val n = calibrationCount.toDouble()
            val meanL = calibrationSumL / n
            val variance = (calibrationSumL2 / n - meanL * meanL).coerceAtLeast(0.0)
            val text = "Profil ${currentSettings().profileId} · RGB moyen ${Math.round(calibrationSumR / n)}/${Math.round(calibrationSumG / n)}/${Math.round(calibrationSumB / n)} · luminance ${String.format(Locale.US, "%.1f", meanL)} · bruit σ ${String.format(Locale.US, "%.2f", sqrt(variance))} · 30 images"
            prefs.edit().putString("calibration.${currentSettings().profileId}", text).apply()
            notifyStatus("Étalonnage terminé · $text")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            handleOrientation(event)
            return
        }
        val vector = motionVector(event) ?: return
        val now = SystemClock.elapsedRealtime()
        val x = vector[0]
        val y = vector[1]
        val z = vector[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        if (gestureCalibrationRemaining > 0) {
            handleGestureCalibration(now, x, y, z, magnitude)
        } else {
            val reloadReady = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) && hasReloadGestureCalibration()
            val reloadContext = reloadReady && now <= reloadGateUntilMs
            if (reloadReady) {
                handleReloadGestureDetection(now, x, y, z)
            }
            if (reloadContext) {
                // L'orientation donne le sens du geste : dans cette fenêtre, aucun tir accidentel.
                projectionImpulse = null
            } else if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, event.timestamp, x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun handleOrientation(event: SensorEvent) {
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
        val flatElevation = elevationDegrees(flat)
        val readyElevation = elevationDegrees(stable)
        val learnedAngle = abs(flatElevation - readyElevation)
        if (learnedAngle < ORIENTATION_CAL_MIN_SEPARATION_DEG) {
            orientationCalibrationStartedMs = now
            notifyStatus("Position de tir trop proche de la position à plat en élévation (${learnedAngle.roundToInt()}°). Redresse davantage le téléphone et stabilise-le.")
            return
        }

        val gate = (learnedAngle * 0.30f).coerceIn(ORIENTATION_GATE_MIN_DEG, ORIENTATION_GATE_MAX_DEG)
        prefs.edit()
            // Vecteurs conservés pour compatibilité avec les calibrations v0.10/v0.11.
            .putFloat(PREF_ORIENTATION_FLAT_X, flat[0])
            .putFloat(PREF_ORIENTATION_FLAT_Y, flat[1])
            .putFloat(PREF_ORIENTATION_FLAT_Z, flat[2])
            .putFloat(PREF_ORIENTATION_READY_X, stable[0])
            .putFloat(PREF_ORIENTATION_READY_Y, stable[1])
            .putFloat(PREF_ORIENTATION_READY_Z, stable[2])
            // La décision de recharge ne dépend plus du cap : seule l'élévation caméra est apprise.
            .putFloat(PREF_ORIENTATION_FLAT_ELEVATION, flatElevation)
            .putFloat(PREF_ORIENTATION_READY_ELEVATION, readyElevation)
            .putFloat(PREF_ORIENTATION_LEARNED_ANGLE, learnedAngle)
            .putFloat(PREF_ORIENTATION_GATE_DEG, gate)
            .apply()

        orientationCalibrationStage = ORIENTATION_CAL_NONE
        calibrationFlatVector = null
        reloadOrientationArmed = false
        reloadOrientationMoveStartMs = 0L
        beepCalibration(doubleBeep = true)
        speak("orientation calibrée")
        notifyStatus("Orientation calibrée · course verticale ${learnedAngle.roundToInt()}° · seuil ${gate.roundToInt()}° · cap/azimut totalement libre sur 360°.")
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
        // Une fenêtre déjà ouverte reste valide 5 s, quelle que soit l'orientation ensuite.
        if (now <= reloadGateUntilMs) return

        val readyVector = loadOrientationVector(PREF_ORIENTATION_READY_X, PREF_ORIENTATION_READY_Y, PREF_ORIENTATION_READY_Z)
        val flatVector = loadOrientationVector(PREF_ORIENTATION_FLAT_X, PREF_ORIENTATION_FLAT_Y, PREF_ORIENTATION_FLAT_Z)
        val readyElevation = if (prefs.contains(PREF_ORIENTATION_READY_ELEVATION)) {
            prefs.getFloat(PREF_ORIENTATION_READY_ELEVATION, 0f)
        } else {
            readyVector?.let { elevationDegrees(it) } ?: return
        }
        val flatElevation = if (prefs.contains(PREF_ORIENTATION_FLAT_ELEVATION)) {
            prefs.getFloat(PREF_ORIENTATION_FLAT_ELEVATION, 90f)
        } else {
            flatVector?.let { elevationDegrees(it) } ?: return
        }

        val course = flatElevation - readyElevation
        val learnedAngle = abs(course).coerceAtLeast(ORIENTATION_CAL_MIN_SEPARATION_DEG)
        val gate = prefs.getFloat(
            PREF_ORIENTATION_GATE_DEG,
            (learnedAngle * 0.30f).coerceIn(ORIENTATION_GATE_MIN_DEG, ORIENTATION_GATE_MAX_DEG)
        )
        val currentElevation = elevationDegrees(current)
        val direction = if (course >= 0f) 1f else -1f
        val progressUp = (currentElevation - readyElevation) * direction
        val readyDistance = abs(currentElevation - readyElevation)
        val readyZone = (gate * 0.55f).coerceIn(8f, 18f)

        // Le cap géographique n'intervient jamais : nord/sud/est/ouest et rotation 360° sont équivalents.
        if (readyDistance <= readyZone) {
            if (!reloadOrientationArmed) {
                logGameEvent("reload_ready", latestResult, null, trainingLabel = "elevation_only")
            }
            reloadOrientationArmed = true
            reloadOrientationMoveStartMs = now
            return
        }

        if (!reloadOrientationArmed) return
        if (now - reloadOrientationMoveStartMs > RELOAD_ORIENTATION_TRANSITION_MS) {
            reloadOrientationArmed = false
            reloadOrientationMoveStartMs = 0L
            logGameEvent("reload_orientation_timeout", latestResult, null, trainingLabel = "elevation_only")
            return
        }

        if (progressUp >= gate) {
            reloadGateUntilMs = now + RELOAD_GATE_MS
            projectionImpulse = null
            reloadProjectionImpulse = null
            reloadOrientationArmed = false
            reloadOrientationMoveStartMs = 0L
            logGameEvent("reload_gate", latestResult, null, trainingLabel = "elevation_only")
            notifyStatus("Arme relevée · RECHARGE autorisée 5 s · azimut libre 360° · tir neutralisé pendant cette fenêtre.")
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

    private fun elevationDegrees(vector: FloatArray): Float =
        Math.toDegrees(asin(vector[2].coerceIn(-1f, 1f).toDouble())).toFloat()

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

    private fun motionVector(event: SensorEvent): FloatArray? {
        if (event.values.size < 3) return null
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            return floatArrayOf(event.values[0], event.values[1], event.values[2])
        }
        if (!gravityReady) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            gravityReady = true
            return null
        }
        val alpha = 0.82f
        for (i in 0..2) gravity[i] = alpha * gravity[i] + (1f - alpha) * event.values[i]
        return floatArrayOf(event.values[0] - gravity[0], event.values[1] - gravity[1], event.values[2] - gravity[2])
    }

    private fun handleGestureCalibration(now: Long, x: Float, y: Float, z: Float, magnitude: Float) {
        if (now - lastCalibrationPairMs < 550L || magnitude < CALIBRATION_MIN_ACCEL) return
        val current = MotionImpulse(x, y, z, magnitude, now)
        val first = calibrationImpulse
        if (first == null || now - first.timeMs > GESTURE_PAIR_WINDOW_MS) {
            calibrationImpulse = current
            return
        }
        val cosine = dot(first.x, first.y, first.z, x, y, z) / (first.magnitude * magnitude).coerceAtLeast(0.001f)
        if (cosine <= -0.18f) {
            val axis = normalize(first.x - x, first.y - y, first.z - z)
            if (axis != null) {
                val aligned = alignAxis(axis)
                gestureCalibrationAxes.add(aligned)
                gestureCalibrationPeaks.add((first.magnitude + magnitude) * 0.5f)
                gestureCalibrationRemaining--
                lastCalibrationPairMs = now
                calibrationImpulse = null
                val done = GESTURE_CALIBRATION_COUNT - gestureCalibrationRemaining
                val action = if (calibratingReloadGesture) "Recharge" else "Tir"
                val message = "$action $done/$GESTURE_CALIBRATION_COUNT enregistré."
                notifyStatus(message)
                speak(done.toString())
                if (gestureCalibrationRemaining == 0) finishGestureCalibration()
                return
            }
        }
        if (cosine > 0.55f && magnitude > first.magnitude) calibrationImpulse = current
    }

    private fun alignAxis(axis: FloatArray): FloatArray {
        val reference = gestureCalibrationAxes.firstOrNull() ?: return axis
        return if (dot(reference[0], reference[1], reference[2], axis[0], axis[1], axis[2]) < 0f) {
            floatArrayOf(-axis[0], -axis[1], -axis[2])
        } else axis
    }

    private fun finishGestureCalibration() {
        if (gestureCalibrationAxes.isEmpty()) return
        val ax = gestureCalibrationAxes.sumOf { it[0].toDouble() }.toFloat()
        val ay = gestureCalibrationAxes.sumOf { it[1].toDouble() }.toFloat()
        val az = gestureCalibrationAxes.sumOf { it[2].toDouble() }.toFloat()
        val axis = normalize(ax, ay, az) ?: return
        val averagePeak = gestureCalibrationPeaks.average().toFloat()
        val threshold = max(2.0f, averagePeak * 0.45f)

        if (calibratingReloadGesture) {
            // Le geste peut ressembler au tir : la transition vers le haut ouvre seule le contexte recharge.
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
            notifyStatus("Calibration recharge terminée · geste activé. Il ne rechargera qu'après une rotation vers le haut (fenêtre 5 s).")
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

    private fun handleGestureDetection(now: Long, triggerNs: Long, x: Float, y: Float, z: Float) {
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
            mainHandler.post { fireAt(triggerNs) }
        } else if ((first.value >= 0f) == (projection >= 0f) && abs(projection) > abs(first.value)) {
            projectionImpulse = ProjectionImpulse(projection, now)
        }
    }

    private fun handleReloadGestureDetection(now: Long, x: Float, y: Float, z: Float) {
        if (now > reloadGateUntilMs) {
            if (reloadGateUntilMs > 0L) {
                logGameEvent("reload_gate_timeout", latestResult, null)
                reloadGateUntilMs = 0L
            }
            reloadProjectionImpulse = null
            return
        }
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
            logGameEvent("reload_impulse_1", latestResult, null)
            return
        }
        if (dt >= RELOAD_GESTURE_MIN_PAIR_MS && first.value * projection < 0f && abs(projection) >= threshold * 0.55f) {
            reloadProjectionImpulse = null
            logGameEvent("reload_impulse_2", latestResult, null)
            reloadGateUntilMs = 0L
            lastReloadGestureMs = now
            lastGestureActionMs = now
            mainHandler.post { reloadMagazine() }
        } else if ((first.value >= 0f) == (projection >= 0f) && abs(projection) > abs(first.value)) {
            reloadProjectionImpulse = ProjectionImpulse(projection, now)
        }
    }

    private fun chooseMotionSensor(): Sensor? {
        return sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun chooseOrientationSensor(): Sensor? {
        // WebCS n'a pas besoin du nord magnétique : privilégier le vecteur de jeu réduit
        // les perturbations de cap. Variante wake-up prioritaire pour l'usage écran éteint.
        return sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    }

    private fun updateSensorRegistration(force: Boolean = false) {
        val orientationCalibrationActive = orientationCalibrationStage != ORIENTATION_CAL_NONE
        val reloadEnabled = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) || calibratingReloadGesture || orientationCalibrationActive
        val shouldRun = force || gestureCalibrationRemaining > 0 || prefs.getBoolean(PREF_GESTURE_ENABLED, false) || reloadEnabled || orientationCalibrationActive
        if (shouldRun && !sensorRegistered) {
            motionSensor?.let {
                sensorRegistered = sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
        }

        // Un accéléromètre wake-up ne suffit pas si le capteur d'orientation, lui, ne l'est pas.
        // Maintenir le CPU éveillé tant qu'un capteur non-wake-up nécessaire au geste est enregistré.
        val needsWakeLock = shouldRun && (
            (sensorRegistered && motionSensor?.isWakeUpSensor != true) ||
            (orientationRegistered && orientationSensor?.isWakeUpSensor != true)
        )
        if (needsWakeLock) acquireGestureWakeLock() else releaseGestureWakeLock()
    }

    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
    private fun hasReloadGestureCalibration(): Boolean = prefs.contains(PREF_RELOAD_GESTURE_THRESHOLD)
    private fun hasOrientationCalibration(): Boolean =
        (prefs.contains(PREF_ORIENTATION_FLAT_ELEVATION) && prefs.contains(PREF_ORIENTATION_READY_ELEVATION) && prefs.contains(PREF_ORIENTATION_LEARNED_ANGLE)) ||
            (prefs.contains(PREF_ORIENTATION_FLAT_X) && prefs.contains(PREF_ORIENTATION_READY_X) && prefs.contains(PREF_ORIENTATION_LEARNED_ANGLE))

    private fun dot(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float = ax * bx + ay * by + az * bz

    private fun normalize(x: Float, y: Float, z: Float): FloatArray? {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 0.001f) return null
        return floatArrayOf(x / n, y / n, z / n)
    }

    private fun ensureValidCameraPreference() {
        val options = getCameraOptions()
        if (options.isEmpty()) return
        val current = prefs.getString(PREF_CAMERA_ID, "") ?: ""
        if (options.none { it.id == current }) {
            val back = cameraProvider?.availableCameraInfos?.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
            val id = back?.let { Camera2CameraInfo.from(it).cameraId } ?: options.first().id
            prefs.edit().putString(PREF_CAMERA_ID, id).apply()
        }
    }

    private fun selectedCameraId(): String {
        val stored = prefs.getString(PREF_CAMERA_ID, "") ?: ""
        if (stored.isNotBlank()) return stored
        val provider = cameraProvider ?: return "?"
        val back = provider.availableCameraInfos.firstOrNull { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        return back?.let { Camera2CameraInfo.from(it).cameraId }
            ?: provider.availableCameraInfos.firstOrNull()?.let { Camera2CameraInfo.from(it).cameraId }
            ?: "?"
    }

    private fun findCameraInfo(id: String): CameraInfo? = cameraProvider?.availableCameraInfos?.firstOrNull {
        Camera2CameraInfo.from(it).cameraId == id
    }

    private fun selectorForId(id: String): CameraSelector = CameraSelector.Builder()
        .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
        .build()

    private fun selectedQuality(info: CameraInfo): Quality {
        val supported = QualitySelector.getSupportedQualities(info)
        if (supported.isEmpty()) return Quality.SD
        val key = prefs.getString(PREF_VIDEO_QUALITY, "AUTO") ?: "AUTO"
        val desired = when (key) {
            "UHD" -> Quality.UHD
            "FHD" -> Quality.FHD
            "HD" -> Quality.HD
            "SD" -> Quality.SD
            else -> null
        }
        if (desired != null && supported.contains(desired)) return desired
        // AUTO privilégie la stabilité de Preview + ImageAnalysis + VideoCapture.
        // L'utilisateur peut toujours forcer FHD/UHD dans les réglages.
        return when {
            supported.contains(Quality.HD) -> Quality.HD
            supported.contains(Quality.SD) -> Quality.SD
            else -> supported.last()
        }
    }

    private fun qualityKey(q: Quality): String? = when (q) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> null
    }

    private fun profileId(cameraId: String, width: Int, height: Int, mode: String): String =
        "cam${cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")}-${width}x${height}-$mode"

    private fun magazineSize(): Int = prefs.getInt(PREF_MAGAZINE_SIZE, 12).coerceIn(1, 99)

    private fun notifyAmmo() {
        val capacity = magazineSize()
        ContextCompat.getMainExecutor(this).execute { listener?.onAmmo(ammo, capacity, reloading) }
    }

    private fun notifyStatus(message: String) {
        ContextCompat.getMainExecutor(this).execute { listener?.onStatus(message) }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "WebCS caméra", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun promoteToForeground(rec: Boolean) {
        val openIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopAction = PendingIntent.getService(this, 1, Intent(this, CameraForegroundService::class.java).setAction(if (rec) ACTION_STOP_RECORDING else ACTION_STOP_SERVICE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (rec) "WebCS · REC" else "WebCS · caméra active")
            .setContentText(if (rec) "Enregistrement continue écran éteint" else "Caméra et déclencheur de jeu actifs")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, if (rec) "STOP REC" else "ARRÊTER", stopAction)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        recordingWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebCS:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.let { if (it.isHeld) it.release() }
        recordingWakeLock = null
    }

    private fun acquireGestureWakeLock() {
        if (gestureWakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        gestureWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebCS:gesture").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseGestureWakeLock() {
        gestureWakeLock?.let { if (it.isHeld) it.release() }
        gestureWakeLock = null
    }

    private fun speak(message: String) {
        if (ttsReady) tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "webcs-$message")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            tts?.language = Locale.FRENCH
        }
    }

    override fun onDestroy() {
        listener = null
        reloading = false
        stopRecordingNow()
        if (sensorRegistered || orientationRegistered) sensorManager.unregisterListener(this)
        sensorRegistered = false
        orientationRegistered = false
        releaseRecordingWakeLock()
        releaseGestureWakeLock()
        cameraProvider?.unbindAll()
        tts?.stop()
        tts?.shutdown()
        sfx.release()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    data class PointSample(val r: Int, val g: Int, val b: Int, val isBlue: Boolean)
    data class ScanResult(val hit: Boolean, val blueVotes: Int, val total: Int, val r: Int, val g: Int, val b: Int, val h: Float, val s: Float, val v: Float)

    companion object {
        private const val WEBSC_V070 = true
        private const val WEBSC_V080 = "context-reload-data-v1"
        private const val WEBSC_V090 = "hot-stream-shot-classification-training-v1"
        private const val WEBSC_V010 = "relative-orientation-calibration-v1"
        private const val CSV_HEADER = "schema_version,session_id,seq,event,wall_time_ms,elapsed_realtime_ns,session_elapsed_ns,trigger_time_ns,camera_frame_ns,frame_delta_us,player,recognized_hit,training_label,recognition_mode,target_distance,r,g,b,h,s,v,p0_r,p0_g,p0_b,p1_r,p1_g,p1_b,p2_r,p2_g,p2_b,p3_r,p3_g,p3_b,p4_r,p4_g,p4_b,patch_size,patch_rgb_hex,elevation_deg,camera_id,width,height,sample_mode,ammo,capacity,score,target_profile,target_tolerance"
        const val SAMPLE_CENTER = "center"
        const val SAMPLE_FIVE = "five"
        private const val PREF_CAMERA_ID = "camera.id"
        private const val PREF_WIDTH = "camera.width"
        private const val PREF_HEIGHT = "camera.height"
        private const val PREF_SAMPLE_MODE = "camera.sampleMode"
        private const val PREF_VIDEO_QUALITY = "camera.videoQuality"
        private const val PREF_SFX_ENABLED = "game.sfxEnabled"
        private const val PREF_SFX_VOLUME = "game.sfxVolume"
        private const val PREF_MAGAZINE_SIZE = "game.magazineSize"
        private const val PREF_PLAYER_NAME = "game.playerName"
        private const val PREF_GESTURE_ENABLED = "game.gestureEnabled"
        private const val PREF_GESTURE_SENSITIVITY = "game.gestureSensitivity"
        private const val PREF_GESTURE_AXIS_X = "gesture.axisX"
        private const val PREF_GESTURE_AXIS_Y = "gesture.axisY"
        private const val PREF_GESTURE_AXIS_Z = "gesture.axisZ"
        private const val PREF_GESTURE_THRESHOLD = "gesture.threshold"
        private const val PREF_RELOAD_GESTURE_ENABLED = "game.reloadGestureEnabled"
        private const val PREF_RELOAD_GESTURE_AXIS_X = "reloadGesture.axisX"
        private const val PREF_RELOAD_GESTURE_AXIS_Y = "reloadGesture.axisY"
        private const val PREF_RELOAD_GESTURE_AXIS_Z = "reloadGesture.axisZ"
        private const val PREF_RELOAD_GESTURE_THRESHOLD = "reloadGesture.threshold"
        private const val PREF_ORIENTATION_FLAT_X = "orientation.flatX"
        private const val PREF_ORIENTATION_FLAT_Y = "orientation.flatY"
        private const val PREF_ORIENTATION_FLAT_Z = "orientation.flatZ"
        private const val PREF_ORIENTATION_READY_X = "orientation.readyX"
        private const val PREF_ORIENTATION_READY_Y = "orientation.readyY"
        private const val PREF_ORIENTATION_READY_Z = "orientation.readyZ"
        private const val PREF_ORIENTATION_FLAT_ELEVATION = "orientation.flatElevation"
        private const val PREF_ORIENTATION_READY_ELEVATION = "orientation.readyElevation"
        private const val PREF_ORIENTATION_LEARNED_ANGLE = "orientation.learnedAngle"
        private const val PREF_ORIENTATION_GATE_DEG = "orientation.gateDeg"
        private const val CHANNEL_ID = "webcs_camera"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP_RECORDING = "online.tek4all.webcs.STOP_RECORDING"
        private const val ACTION_STOP_SERVICE = "online.tek4all.webcs.STOP_SERVICE"
        private const val GESTURE_CALIBRATION_COUNT = 5
        private const val GESTURE_PAIR_WINDOW_MS = 330L
        private const val GESTURE_COOLDOWN_MS = 520L
        private const val GESTURE_ACTION_GUARD_MS = 360L
        private const val RELOAD_GESTURE_MIN_PAIR_MS = 90L
        private const val RELOAD_GESTURE_MAX_PAIR_MS = 620L
        private const val RELOAD_GESTURE_COOLDOWN_MS = 1200L
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
        private const val TARGET_CALIBRATION_SHOTS = 10
        private const val AIM_PATCH_SIZE = 11
        private const val AIM_RING_SIZE = 3
        private const val CALIBRATION_MIN_ACCEL = 3.0f
        private const val RELOAD_DURATION_MS = 1250L
        private const val MIN_RECORDING_MS = 850L
        private const val MIN_VALID_RECORDING_NS = 350_000_000L
    }
}
