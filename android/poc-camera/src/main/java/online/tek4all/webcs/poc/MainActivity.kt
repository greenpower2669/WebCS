package online.tek4all.webcs.poc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var decisionText: TextView
    private lateinit var scoreText: TextView
    private lateinit var ecoButton: Button
    private lateinit var cameraExecutor: ExecutorService

    @Volatile private var latestResult: ScanResult? = null
    private var score = 0
    private var eco = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else statusText.text = "Autorisation caméra refusée."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        score = getSharedPreferences("webcs", MODE_PRIVATE).getInt("score", 0)
        buildUi()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(CrosshairView(this), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
            setBackgroundColor(Color.argb(185, 0, 0, 0))
        }
        decisionText = textView("PRÊT", 24f)
        scoreText = textView("Score $score", 20f)
        statusText = textView("Initialisation caméra…", 15f)
        panel.addView(decisionText)
        panel.addView(scoreText)
        panel.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fireButton = Button(this).apply {
            text = "TIR"
            setOnClickListener { fire() }
        }
        ecoButton = Button(this).apply {
            text = "Mode éco"
            setOnClickListener { toggleEco() }
        }
        row.addView(fireButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ecoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)

        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun textView(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun startCamera() {
        statusText.text = "Ouverture de la caméra…"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder()
                    .setTargetResolution(Size(640, 480))
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { image ->
                    latestResult = analyse(image)
                    image.close()
                }

                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                statusText.text = "Caméra prête. TIR ou Volume - pour analyser le centre."
            } catch (e: Exception) {
                statusText.text = "Erreur caméra : ${e.message ?: "inconnue"}"
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
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yy = yPlane.buffer.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xff
        val uvX = x / 2
        val uvY = y / 2
        val uu = uPlane.buffer.get(uvY * uPlane.rowStride + uvX * uPlane.pixelStride).toInt() and 0xff
        val vv = vPlane.buffer.get(uvY * vPlane.rowStride + uvX * vPlane.pixelStride).toInt() and 0xff

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

    private fun fire() {
        val result = latestResult
        if (result == null) {
            statusText.text = "Pas encore d'image exploitable."
            return
        }
        if (result.hit) {
            score++
            getSharedPreferences("webcs", MODE_PRIVATE).edit().putInt("score", score).apply()
            decisionText.text = "TOUCHÉ  ${result.blueVotes}/5"
            decisionText.setTextColor(Color.rgb(80, 220, 120))
        } else {
            decisionText.text = "RATÉ  ${result.blueVotes}/5"
            decisionText.setTextColor(Color.rgb(255, 120, 100))
        }
        scoreText.text = "Score $score"
        statusText.text = "RGB ${result.r}/${result.g}/${result.b}   HSV ${result.h.roundToInt()}°/${(result.s * 100).roundToInt()}%/${(result.v * 100).roundToInt()}%"
        previewView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun toggleEco() {
        eco = !eco
        previewView.alpha = if (eco) 0.03f else 1f
        ecoButton.text = if (eco) "Aperçu normal" else "Mode éco"
        statusText.text = if (eco) "Mode éco : aperçu presque noir, analyse caméra toujours active." else "Aperçu normal."
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            fire()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    data class PointSample(val r: Int, val g: Int, val b: Int, val isBlue: Boolean)
    data class ScanResult(
        val hit: Boolean,
        val blueVotes: Int,
        val r: Int,
        val g: Int,
        val b: Int,
        val h: Float,
        val s: Float,
        val v: Float
    )

    class CrosshairView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, 28f, paint)
            canvas.drawLine(cx - 60f, cy, cx - 14f, cy, paint)
            canvas.drawLine(cx + 14f, cy, cx + 60f, cy, paint)
            canvas.drawLine(cx, cy - 60f, cx, cy - 14f, paint)
            canvas.drawLine(cx, cy + 14f, cx, cy + 60f, paint)
        }
    }
}
