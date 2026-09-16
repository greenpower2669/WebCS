package online.tek4all.webcs.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

@OptIn(ExperimentalCamera2Interop::class)
class CameraForegroundService : LifecycleService(), TextToSpeech.OnInitListener {
    interface Listener {
        fun onReady(score: Int, recording: Boolean)
        fun onShot(hit: Boolean, votes: Int, total: Int, score: Int, details: String)
        fun onRecording(active: Boolean, message: String)
        fun onStatus(message: String)
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
        val profileId: String
    )

    inner class LocalBinder : Binder() { fun getService(): CameraForegroundService = this@CameraForegroundService }

    private val binder = LocalBinder()
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var recording: Recording? = null
    private var recordingStopping = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var score = 0
    private var effectiveWidth = 0
    private var effectiveHeight = 0

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
            ACTION_STOP_RECORDING -> stopRecording()
            ACTION_STOP_SERVICE -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        previewProvider = surfaceProvider
        val existing = previewUseCase
        if (existing != null) {
            existing.setSurfaceProvider(surfaceProvider)
            return
        }
        bindPreviewOnly()
    }

    fun isRecording(): Boolean = recording != null
    fun currentScore(): Int = score

    fun currentSettings(): SettingsSnapshot {
        val cameraId = selectedCameraId()
        val w = prefs.getInt(PREF_WIDTH, 640)
        val h = prefs.getInt(PREF_HEIGHT, 480)
        val mode = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) ?: SAMPLE_FIVE
        val quality = prefs.getString(PREF_VIDEO_QUALITY, "AUTO") ?: "AUTO"
        return SettingsSnapshot(cameraId, w, h, effectiveWidth, effectiveHeight, mode, quality, profileId(cameraId, w, h, mode))
    }

    fun configurationSummary(): String {
        val s = currentSettings()
        val effective = if (s.effectiveWidth > 0) "${s.effectiveWidth}×${s.effectiveHeight}" else "en attente"
        val mode = if (s.sampleMode == SAMPLE_CENTER) "centre" else "5 points"
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · vidéo ${s.videoQuality}"
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
        val result = mutableListOf(QualityOption("AUTO", "Auto (meilleure disponible)"))
        supported.forEach { q ->
            qualityKey(q)?.let { key -> result.add(QualityOption(key, key)) }
        }
        return result.distinctBy { it.key }
    }

    fun applySettings(cameraId: String, width: Int, height: Int, sampleMode: String, videoQuality: String): Boolean {
        if (recording != null) {
            listener?.onStatus("Arrête le REC avant de changer de caméra ou de résolution.")
            return false
        }
        prefs.edit()
            .putString(PREF_CAMERA_ID, cameraId)
            .putInt(PREF_WIDTH, width)
            .putInt(PREF_HEIGHT, height)
            .putString(PREF_SAMPLE_MODE, if (sampleMode == SAMPLE_CENTER) SAMPLE_CENTER else SAMPLE_FIVE)
            .putString(PREF_VIDEO_QUALITY, videoQuality)
            .apply()
        effectiveWidth = 0
        effectiveHeight = 0
        latestResult = null
        rebindCamera()
        listener?.onStatus("Réglages appliqués · $cameraId · ${width}×${height} · ${if (sampleMode == SAMPLE_CENTER) "centre" else "5 points"}.")
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
        listener?.onStatus("Étalonnage : 30 images en cours. Garde le téléphone stable sur la scène de référence.")
    }

    fun calibrationSummary(): String {
        val s = currentSettings()
        return prefs.getString("calibration.${s.profileId}", null)
            ?: "Profil ${s.profileId} : pas encore étalonné"
    }

    fun shutdown() {
        stopRecording()
        stopSelf()
    }

    fun fire() {
        val result = latestResult
        if (result == null) {
            listener?.onStatus("Pas encore d'image exploitable.")
            return
        }
        if (result.hit) {
            score++
            prefs.edit().putInt("score", score).apply()
        }
        val mode = if (result.total == 1) "centre" else "5 points"
        val details = "RGB ${result.r}/${result.g}/${result.b}   HSV ${result.h.roundToInt()}°/${(result.s * 100).roundToInt()}%/${(result.v * 100).roundToInt()}% · $mode · ${effectiveWidth}×${effectiveHeight}"
        listener?.onShot(result.hit, result.blueVotes, result.total, score, details)
    }

    fun toggleRecording() {
        if (!::videoCapture.isInitialized) {
            listener?.onStatus("Caméra vidéo pas encore prête.")
            return
        }
        if (recording != null) {
            stopRecording()
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
        recording = videoCapture.output
            .prepareRecording(this, output)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        acquireWakeLock()
                        promoteToForeground(true)
                        speak("rec")
                        listener?.onRecording(true, "Enregistrement MP4 actif · écran éteint autorisé.")
                    }
                    is VideoRecordEvent.Finalize -> {
                        releaseWakeLock()
                        recording?.close()
                        recording = null
                        recordingStopping = false
                        promoteToForeground(false)
                        val message = if (event.hasError()) "Erreur enregistrement : ${event.error}" else "Vidéo sauvegardée dans Films/WebCS."
                        listener?.onRecording(false, message)
                    }
                }
            }
    }

    private fun stopRecording() {
        val active = recording ?: return
        if (!recordingStopping) {
            recordingStopping = true
            active.stop()
            speak("stop")
            listener?.onStatus("Arrêt et sauvegarde de la vidéo…")
        }
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
                if (effectiveWidth != image.width || effectiveHeight != image.height) {
                    effectiveWidth = image.width
                    effectiveHeight = image.height
                    notifyStatus("Résolution d'analyse effective : ${image.width}×${image.height} · caméra $selectedId")
                }
                latestResult = analyse(image)
                latestResult?.let { consumeCalibration(it) }
                image.close()
            }

            val quality = selectedQuality(info)
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
            videoCapture = VideoCapture.withOutput(recorder)

            provider.bindToLifecycle(this, selector, analysis, videoCapture)
            bindPreviewOnly()
            listener?.onReady(score, isRecording())
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
        val centerOnly = (prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER)
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
        return if (desired != null && supported.contains(desired)) desired else supported.first()
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
            .setContentText(if (rec) "Enregistrement continue écran éteint" else "Service caméra prêt pour écran éteint")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, if (rec) "STOP REC" else "ARRÊTER", stopAction)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WebCS:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        stopRecording()
        releaseWakeLock()
        cameraProvider?.unbindAll()
        tts?.stop()
        tts?.shutdown()
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
        private const val CHANNEL_ID = "webcs_camera"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP_RECORDING = "online.tek4all.webcs.STOP_RECORDING"
        private const val ACTION_STOP_SERVICE = "online.tek4all.webcs.STOP_SERVICE"
    }
}
