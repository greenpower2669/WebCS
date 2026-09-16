package online.tek4all.webcs.poc

import android.Manifest
import android.app.AlertDialog
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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
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
    private lateinit var ammoText: TextView
    private lateinit var ecoButton: Button
    private lateinit var recText: TextView

    private var cameraService: CameraForegroundService? = null
    private var isBound = false
    private var eco = false

    private val listener = object : CameraForegroundService.Listener {
        override fun onReady(score: Int, recording: Boolean) {
            scoreText.text = "Score $score"
            recText.visibility = if (recording) View.VISIBLE else View.GONE
            val service = cameraService
            if (service != null) {
                onAmmo(service.currentAmmo(), service.currentMagazineSize(), service.isReloading())
            }
            statusText.text = service?.configurationSummary()
                ?: "Caméra de fond prête. Volume - : tir · Volume + : REC/STOP."
        }

        override fun onShot(hit: Boolean, votes: Int, total: Int, score: Int, details: String) {
            decisionText.text = if (hit) "TOUCHÉ  $votes/$total" else "RATÉ  $votes/$total"
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

        override fun onAmmo(ammo: Int, capacity: Int, reloading: Boolean) {
            ammoText.text = if (reloading) "Chargeur $ammo/$capacity · rechargement…" else "Chargeur $ammo/$capacity"
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
            val service = cameraService
            listener.onReady(service?.currentScore() ?: 0, service?.isRecording() == true)
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

        val settingsButton = Button(this).apply {
            text = "⚙"
            textSize = 24f
            contentDescription = "Réglages"
            setOnClickListener { showSettings() }
        }
        val settingsParams = FrameLayout.LayoutParams(dp(64), dp(56), Gravity.TOP or Gravity.START)
        settingsParams.setMargins(dp(8), dp(8), 0, 0)
        root.addView(settingsButton, settingsParams)
        ViewCompat.setOnApplyWindowInsetsListener(settingsButton) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            (view.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(8)
                leftMargin = bars.left + dp(8)
                view.layoutParams = this
            }
            insets
        }

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
        ammoText = textView("Chargeur --/--", 17f)
        statusText = textView("Initialisation…", 15f)
        panel.addView(decisionText)
        panel.addView(scoreText)
        panel.addView(ammoText)
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

        val reloadButton = Button(this).apply {
            text = "RECHARGER"
            contentDescription = "Recharger le chargeur"
            setOnClickListener { cameraService?.reloadMagazine() }
        }
        panel.addView(reloadButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(12), dp(16) + bars.right, dp(18) + bars.bottom)
            insets
        }

        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun showSettings() {
        val service = cameraService
        if (service == null) {
            statusText.text = "Service caméra pas encore prêt."
            return
        }
        if (service.isRecording()) {
            statusText.text = "Arrête le REC avant d'ouvrir les réglages."
            return
        }

        val snapshot = service.currentSettings()
        val cameras = service.getCameraOptions()
        if (cameras.isEmpty()) {
            statusText.text = "Liste des caméras pas encore disponible."
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(content) }

        fun label(text: String): TextView = textView(text, 16f).apply {
            setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, dp(4))
        }

        content.addView(label("Caméra physique / logique"))
        val cameraSpinner = Spinner(this)
        cameraSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, cameras.map { it.label }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val currentCameraIndex = cameras.indexOfFirst { it.id == snapshot.cameraId }.coerceAtLeast(0)
        cameraSpinner.setSelection(currentCameraIndex)
        content.addView(cameraSpinner)

        content.addView(label("Résolution d'analyse"))
        val resolutionSpinner = Spinner(this)
        content.addView(resolutionSpinner)

        content.addView(label("Mesure pour la décision"))
        val sampleLabels = listOf("Centre seul", "Centre + 4 points")
        val sampleSpinner = Spinner(this)
        sampleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sampleLabels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        sampleSpinner.setSelection(if (snapshot.sampleMode == CameraForegroundService.SAMPLE_CENTER) 0 else 1)
        content.addView(sampleSpinner)

        content.addView(label("Qualité vidéo"))
        val qualitySpinner = Spinner(this)
        content.addView(qualitySpinner)

        content.addView(label("Effets sonores"))
        val sfxCheck = CheckBox(this).apply {
            text = "Activer tir, chargeur vide et rechargement"
            isChecked = snapshot.sfxEnabled
        }
        content.addView(sfxCheck)

        val volumeLabel = label("Volume effets : ${snapshot.sfxVolume}%")
        content.addView(volumeLabel)
        val volumeSeek = SeekBar(this).apply {
            max = 100
            progress = snapshot.sfxVolume
        }
        content.addView(volumeSeek)
        volumeSeek.setOnSeekBarChangeListener(simpleSeekListener { value -> volumeLabel.text = "Volume effets : $value%" })

        content.addView(label("Chargeur"))
        val magazineValues = listOf(6, 12, 20, 30)
        val magazineSpinner = Spinner(this)
        magazineSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, magazineValues.map { "$it coups" }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val magIndex = magazineValues.indexOf(snapshot.magazineSize).let { if (it >= 0) it else 1 }
        magazineSpinner.setSelection(magIndex)
        content.addView(magazineSpinner)

        content.addView(label("Déclenchement par mouvement"))
        val gestureCheck = CheckBox(this).apply {
            text = "Tirer avec un à-coup rapide calibré"
            isChecked = snapshot.gestureEnabled
        }
        content.addView(gestureCheck)

        val sensitivityLabel = label("Sensibilité mouvement : ${snapshot.gestureSensitivity}%")
        content.addView(sensitivityLabel)
        val sensitivitySeek = SeekBar(this).apply {
            max = 100
            progress = snapshot.gestureSensitivity
        }
        content.addView(sensitivitySeek)
        sensitivitySeek.setOnSeekBarChangeListener(simpleSeekListener { value -> sensitivityLabel.text = "Sensibilité mouvement : $value%" })

        val gestureProfileText = label(service.gestureCalibrationSummary())
        content.addView(gestureProfileText)
        val gestureCalibrateButton = Button(this).apply {
            text = "CALIBRER MON GESTE DE TIR · 5 FOIS"
            contentDescription = "Calibrer le tir par mouvement"
        }
        content.addView(gestureCalibrateButton)

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
        content.addView(effectiveText)

        val profileText = label(service.calibrationSummary())
        content.addView(profileText)

        val calibrateButton = Button(this).apply { text = "ÉTALONNER CE PROFIL CAMÉRA" }
        content.addView(calibrateButton)

        val quitButton = Button(this).apply {
            text = "QUITTER WEB CS"
            setTextColor(Color.rgb(180, 30, 30))
        }
        content.addView(quitButton)

        var resolutionOptions = service.getResolutionOptions(cameras[currentCameraIndex].id)
        var qualityOptions = service.getVideoQualityOptions(cameras[currentCameraIndex].id)

        fun refreshResolution(cameraId: String) {
            resolutionOptions = service.getResolutionOptions(cameraId)
            resolutionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, resolutionOptions.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val wanted = if (cameraId == snapshot.cameraId) {
                resolutionOptions.indexOfFirst { it.width == snapshot.requestedWidth && it.height == snapshot.requestedHeight }
            } else {
                resolutionOptions.indexOfFirst { it.width == 640 && it.height == 480 }
            }
            resolutionSpinner.setSelection(if (wanted >= 0) wanted else 0)
        }

        fun refreshQuality(cameraId: String) {
            qualityOptions = service.getVideoQualityOptions(cameraId)
            qualitySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, qualityOptions.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val wanted = if (cameraId == snapshot.cameraId) qualityOptions.indexOfFirst { it.key == snapshot.videoQuality } else 0
            qualitySpinner.setSelection(if (wanted >= 0) wanted else 0)
        }

        refreshResolution(cameras[currentCameraIndex].id)
        refreshQuality(cameras[currentCameraIndex].id)

        cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val cameraId = cameras[position].id
                refreshResolution(cameraId)
                refreshQuality(cameraId)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("WebCS · réglages")
            .setView(scroll)
            .setPositiveButton("APPLIQUER", null)
            .setNegativeButton("FERMER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val camera = cameras[cameraSpinner.selectedItemPosition]
                val resolution = resolutionOptions[resolutionSpinner.selectedItemPosition]
                val sampleMode = if (sampleSpinner.selectedItemPosition == 0) CameraForegroundService.SAMPLE_CENTER else CameraForegroundService.SAMPLE_FIVE
                val quality = qualityOptions[qualitySpinner.selectedItemPosition]
                val magazineSize = magazineValues[magazineSpinner.selectedItemPosition]
                if (service.applySettings(
                        camera.id,
                        resolution.width,
                        resolution.height,
                        sampleMode,
                        quality.key,
                        sfxCheck.isChecked,
                        volumeSeek.progress,
                        gestureCheck.isChecked,
                        sensitivitySeek.progress,
                        reloadGestureCheck.isChecked,
                        magazineSize
                    )) {
                    dialog.dismiss()
                }
            }

            gestureCalibrateButton.setOnClickListener {
                service.startGestureCalibration()
                dialog.dismiss()
            }

            reloadGestureCalibrateButton.setOnClickListener {
                service.startReloadGestureCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
                service.startCalibration()
                dialog.dismiss()
            }

            quitButton.setOnClickListener {
                dialog.dismiss()
                shutdownApp()
            }
        }
        dialog.show()
    }

    private fun simpleSeekListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = onProgress(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun shutdownApp() {
        cameraService?.shutdown()
        stopService(Intent(this, CameraForegroundService::class.java))
        finishAndRemoveTask()
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
