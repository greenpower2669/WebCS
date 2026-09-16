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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
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

    inner class LocalBinder : Binder() { fun getService(): CameraForegroundService = this@CameraForegroundService }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var previewProvider: Preview.SurfaceProvider? = null
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
    private var sensorRegistered = false
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

    @Volatile private var latestResult: ScanResult? = null
    @Volatile private var calibrationRemaining = 0
    private var calibrationCount = 0
    private var calibrationSumR = 0.0
    private var calibrationSumG = 0.0
    private var calibrationSumB = 0.0
    private var calibrationSumL = 0.0
    private var calibrationSumL2 = 0.0

    var listener: Listener? = null

    private val prefs by lazy { getSharedPreferences("webcs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        score = prefs.getInt("score", 0)
        cameraExecutor = Executors.newSingleThreadExecutor()
        tts = TextToSpeech(this, this)
        sfx = SfxEngine().also {
            it.configure(prefs.getBoolean(PREF_SFX_ENABLED, true), prefs.getInt(PREF_SFX_VOLUME, 70))
        }
        ammo = magazineSize()
        sensorManager = getSystemService(SensorManager::class.java)
        motionSensor = chooseMotionSensor()
        updateSensorRegistration()
        createChannel()
        promoteToForeground(false)
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
        // Recrée une session unique Preview + Analysis + VideoCapture.
        // Sur certains Samsung, ajouter Preview dans une seconde liaison CameraX
        // laisse l'aperçu fonctionner mais peut affamer ImageAnalysis.
        if (cameraProvider != null && recording == null) {
            rebindCamera()
        } else {
            previewUseCase?.setSurfaceProvider(surfaceProvider)
        }
    }

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
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · vidéo ${s.videoQuality}$shotMotion$reloadMotion"
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
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}$gestureNote$reloadNote.")
        return true
    }

    fun startCalibration() {
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

    fun shutdown() {
        stopRecordingNow()
        stopSelf()
    }

    fun fire() {
        if (reloading) {
            listener?.onStatus("Rechargement en cours…")
            return
        }
        val result = latestResult
        if (result == null) {
            listener?.onStatus("Pas encore d'image exploitable.")
            return
        }
        if (ammo <= 0) {
            sfx.playEmpty()
            listener?.onStatus("Clic · chargeur vide. Recharge.")
            notifyAmmo()
            return
        }
        ammo--
        sfx.playShot()
        if (result.hit) {
            score++
            prefs.edit().putInt("score", score).apply()
        }
        val mode = if (result.total == 1) "centre" else "5 points"
        val details = "RGB ${result.r}/${result.g}/${result.b}   HSV ${result.h.roundToInt()}°/${(result.s * 100).roundToInt()}%/${(result.v * 100).roundToInt()}% · $mode · ${effectiveWidth}×${effectiveHeight} · munitions $ammo/${magazineSize()}"
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
                        notifyStatus("Analyse prête : ${image.width}×${image.height} · caméra $selectedId")
                    }
                    latestResult = analyse(image)
                    latestResult?.let { consumeCalibration(it) }
                } catch (e: Exception) {
                    latestResult = null
                    notifyStatus("Erreur analyse caméra : ${e.javaClass.simpleName} · ${e.message ?: "sans détail"}")
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
                    .also { it.setSurfaceProvider(surface) }
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

    private fun analyse(image: ImageProxy): ScanResult {
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
        val vector = motionVector(event) ?: return
        val now = SystemClock.elapsedRealtime()
        val x = vector[0]
        val y = vector[1]
        val z = vector[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        if (gestureCalibrationRemaining > 0) {
            handleGestureCalibration(now, x, y, z, magnitude)
        } else {
            if (prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) && hasReloadGestureCalibration()) {
                handleReloadGestureDetection(now, x, y, z)
            }
            if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, x, y, z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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

    private fun handleGestureDetection(now: Long, x: Float, y: Float, z: Float) {
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

    private fun chooseMotionSensor(): Sensor? {
        return sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    private fun updateSensorRegistration(force: Boolean = false) {
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

    private fun hasGestureCalibration(): Boolean = prefs.contains(PREF_GESTURE_THRESHOLD)
    private fun hasReloadGestureCalibration(): Boolean = prefs.contains(PREF_RELOAD_GESTURE_THRESHOLD)

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
        if (sensorRegistered) sensorManager.unregisterListener(this)
        sensorRegistered = false
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
        private const val CALIBRATION_MIN_ACCEL = 3.0f
        private const val RELOAD_DURATION_MS = 1250L
        private const val MIN_RECORDING_MS = 850L
        private const val MIN_VALID_RECORDING_NS = 350_000_000L
    }
}
