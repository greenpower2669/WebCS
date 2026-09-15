package online.tek4all.webcs.poc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.IBinder
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var decisionText: TextView
    private lateinit var scoreText: TextView
    private lateinit var ecoButton: Button
    private lateinit var recText: TextView

    private var cameraService: CameraForegroundService? = null
    private var isBound = false
    private var eco = false

    private val listener = object : CameraForegroundService.Listener {
        override fun onReady(score: Int, recording: Boolean) {
            scoreText.text = "Score $score"
            recText.visibility = if (recording) View.VISIBLE else View.GONE
            statusText.text = "Caméra de fond prête. Volume - : tir · Volume + : REC/STOP · écran éteint OK."
        }

        override fun onShot(hit: Boolean, votes: Int, score: Int, details: String) {
            decisionText.text = if (hit) "TOUCHÉ  $votes/5" else "RATÉ  $votes/5"
            decisionText.setTextColor(if (hit) Color.rgb(80, 220, 120) else Color.rgb(255, 120, 100))
            scoreText.text = "Score $score"
            statusText.text = details
            previewView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        override fun onRecording(active: Boolean, message: String) {
            recText.visibility = if (active) View.VISIBLE else View.GONE
            statusText.text = message
        }

        override fun onStatus(message: String) {
            statusText.text = message
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = binder as? CameraForegroundService.LocalBinder ?: return
            cameraService = local.getService().also {
                it.listener = listener
                it.attachPreview(previewView.surfaceProvider)
            }
            isBound = true
            listener.onReady(cameraService?.currentScore() ?: 0, cameraService?.isRecording() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            cameraService = null
            statusText.text = "Service caméra déconnecté."
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startServiceAndBind() else statusText.text = "Autorisation caméra refusée."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        enterImmersiveMode()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startServiceAndBind()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private fun startServiceAndBind() {
        val intent = Intent(this, CameraForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) bindService(intent, connection, Context.BIND_AUTO_CREATE)
        statusText.text = "Activation du service caméra de fond…"
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

        recText = textView("● REC", 18f).apply {
            setTextColor(Color.RED)
            visibility = View.GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.argb(150, 0, 0, 0))
        }
        val recParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
        recParams.setMargins(0, dp(12), dp(12), 0)
        root.addView(recText, recParams)
        ViewCompat.setOnApplyWindowInsetsListener(recText) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(12)
                rightMargin = bars.right + dp(12)
                view.layoutParams = this
            }
            insets
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(18))
            setBackgroundColor(Color.argb(185, 0, 0, 0))
        }
        decisionText = textView("PRÊT", 24f)
        scoreText = textView("Score 0", 20f)
        statusText = textView("Initialisation…", 15f)
        panel.addView(decisionText)
        panel.addView(scoreText)
        panel.addView(statusText)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fireButton = Button(this).apply {
            text = "TIR"
            setOnClickListener { fire() }
        }
        val recButton = Button(this).apply {
            text = "REC"
            setOnClickListener { toggleRecording() }
        }
        ecoButton = Button(this).apply {
            text = "MODE ÉCO"
            setOnClickListener { toggleEco() }
        }
        row.addView(fireButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(recButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ecoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(row)

        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(12), dp(16) + bars.right, dp(18) + bars.bottom)
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun textView(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun fire() {
        cameraService?.fire() ?: run { statusText.text = "Service caméra pas encore prêt." }
    }

    private fun toggleRecording() {
        cameraService?.toggleRecording() ?: run { statusText.text = "Service caméra pas encore prêt." }
    }

    private fun toggleEco() {
        eco = !eco
        previewView.alpha = if (eco) 0.03f else 1f
        ecoButton.text = if (eco) "APERÇU NORMAL" else "MODE ÉCO"
        statusText.text = if (eco) "Mode éco : aperçu presque noir. Le service caméra reste actif." else "Aperçu normal."
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.repeatCount == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    fire()
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    toggleRecording()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (isBound) {
            cameraService?.listener = null
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

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
