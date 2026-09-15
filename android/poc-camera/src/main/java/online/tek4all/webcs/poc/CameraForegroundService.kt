package online.tek4all.webcs.poc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.util.Size
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

class CameraForegroundService : LifecycleService(), TextToSpeech.OnInitListener {
    interface Listener {
        fun onReady(score: Int, recording: Boolean)
        fun onShot(hit: Boolean, votes: Int, score: Int, details: String)
        fun onRecording(active: Boolean, message: String)
        fun onStatus(message: String)
    }

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

    @Volatile private var latestResult: ScanResult? = null
    var listener: Listener? = null

    override fun onCreate() {
        super.onCreate()
        score = getSharedPreferences("webcs", MODE_PRIVATE).getInt("score", 0)
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
        val provider = cameraProvider ?: return
        try {
            previewUseCase = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    it.setSurfaceProvider(surfaceProvider)
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, it)
                }
        } catch (e: Exception) {
            listener?.onStatus("Erreur aperçu : ${e.message ?: "inconnue"}")
        }
    }

    fun isRecording(): Boolean = recording != null
    fun currentScore(): Int = score

    fun fire() {
        val result = latestResult
        if (result == null) {
            listener?.onStatus("Pas encore d'image exploitable.")
            return
        }
        if (result.hit) {
            score++
            getSharedPreferences("webcs", MODE_PRIVATE).edit().putInt("score", score).apply()
        }
        val details = "RGB ${result.r}/${result.g}/${result.b}   HSV ${result.h.roundToInt()}°/${(result.s * 100).roundToInt()}%/${(result.v * 100).roundToInt()}%"
        listener?.onShot(result.hit, result.blueVotes, score, details)
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
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    latestResult = analyse(image)
                    image.close()
                }

                val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build()
                videoCapture = VideoCapture.withOutput(recorder)

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis, videoCapture)
                previewProvider?.let { attachPreview(it) }
                listener?.onReady(score, isRecording())
            } catch (e: Exception) {
                listener?.onStatus("Erreur caméra de fond : ${e.message ?: "inconnue"}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyse(image: ImageProxy): ScanResult {
        val cx = image.width / 2
        val cy = image.height / 2
        val gap = max(2, (min(image.width, image.height) * 0.008f).roundToInt())
        val offsets = arrayOf(0 to 0, -gap to -gap, gap to -gap, -gap to gap, gap to gap)
        val points = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        val blueVotes = points.count { it.isBlue }
        val avgR = points.sumOf { it.r } / points.size
        val avgG = points.sumOf { it.g } / points.size
        val avgB = points.sumOf { it.b } / points.size
        val hsv = FloatArray(3)
        Color.RGBToHSV(avgR, avgG, avgB, hsv)
        return ScanResult(blueVotes >= 3, blueVotes, avgR, avgG, avgB, hsv[0], hsv[1], hsv[2])
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
    data class ScanResult(val hit: Boolean, val blueVotes: Int, val r: Int, val g: Int, val b: Int, val h: Float, val s: Float, val v: Float)

    companion object {
        private const val CHANNEL_ID = "webcs_camera"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_STOP_RECORDING = "online.tek4all.webcs.STOP_RECORDING"
        private const val ACTION_STOP_SERVICE = "online.tek4all.webcs.STOP_SERVICE"
    }
}
