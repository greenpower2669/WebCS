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
import android.widget.EditText
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
    private lateinit var previewButton: Button
    private lateinit var recText: TextView
    private lateinit var aimZoomView: AimZoomView

    private var cameraService: CameraForegroundService? = null
    private var isBound = false
    private var previewVisible = true

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

        override fun onAimPatch(size: Int, pixels: IntArray, calibrationRemaining: Int) {
            aimZoomView.setPatch(size, pixels, calibrationRemaining)
            aimZoomView.visibility = if (calibrationRemaining > 0) View.VISIBLE else View.GONE
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

        aimZoomView = AimZoomView(this).apply {
            visibility = View.GONE
            contentDescription = "Zoom des pixels centraux pour calibrer la couleur ennemi"
        }
        val zoomParams = FrameLayout.LayoutParams(dp(176), dp(176), Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        zoomParams.setMargins(0, dp(76), 0, 0)
        root.addView(aimZoomView, zoomParams)

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
        previewButton = Button(this).apply {
            text = "APERÇU OFF"
            contentDescription = "Couper uniquement l'affichage vidéo sans arrêter la caméra"
            setOnClickListener { togglePreview() }
        }
        row.addView(fireButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(recButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(previewButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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

        content.addView(label("Données / arbitrage"))
        val playerEdit = EditText(this).apply {
            hint = "Nom du joueur"
            setText(service.currentPlayerName())
            contentDescription = "Nom du joueur dans les données WebCS"
        }
        content.addView(playerEdit)
        val dataSummary = label(service.sessionDataSummary())
        content.addView(dataSummary)
        val newSessionButton = Button(this).apply {
            text = "NOUVELLE SESSION DATA"
            contentDescription = "Effacer les événements de test et démarrer une nouvelle session"
        }
        content.addView(newSessionButton)
        content.addView(label("Chaque tir est sauvegardé automatiquement avec la frame caméra la plus proche, les pixels centraux, le résultat de reconnaissance et les timestamps. Le CSV sert à l'entraînement et à l'arbitrage."))
        val shareDataButton = Button(this).apply {
            text = "PARTAGER DATA ENTRAÎNEMENT / ARBITRAGE"
            contentDescription = "Exporter et partager les données de tirs, reconnaissance et entraînement"
        }
        content.addView(shareDataButton)

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
            text = "Recharger par geste après avoir relevé l'arme (fenêtre 5 s)"
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

        val orientationProfileText = label(service.orientationCalibrationSummary())
        content.addView(orientationProfileText)
        content.addView(label("Orientation recharge : 1) téléphone à plat caméra vers le haut, 2) au bip redresse-le en position prête à tirer. Le mode RECHARGE dure ensuite 5 s, puis retour automatique au TIR."))
        val orientationCalibrateButton = Button(this).apply {
            text = "CALIBRER ORIENTATION RECHARGE"
            contentDescription = "Calibrer la position à plat et la position prête à tirer"
        }
        content.addView(orientationCalibrateButton)

        val effectiveText = label("Résolution effective actuelle : ${if (snapshot.effectiveWidth > 0) "${snapshot.effectiveWidth}×${snapshot.effectiveHeight}" else "en attente"}")
        content.addView(effectiveText)

        val profileText = label(service.calibrationSummary())
        content.addView(profileText)
        content.addView(label("Calibration optionnelle : vise la couleur ennemi dans le zoom central et déclenche 10 tirs. Elle remplace le bleu historique pour ce profil caméra/résolution."))

        val calibrateButton = Button(this).apply { text = "CALIBRER COULEUR ENNEMI · 10 TIRS" }
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
                service.setPlayerName(playerEdit.text.toString())
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

            newSessionButton.setOnClickListener {
                service.setPlayerName(playerEdit.text.toString())
                service.startNewDataSession()
                dataSummary.text = service.sessionDataSummary()
            }

            shareDataButton.setOnClickListener {
                service.setPlayerName(playerEdit.text.toString())
                val uri = service.exportSessionCsv()
                if (uri != null) {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, service.sessionDataSummary())
                        clipData = android.content.ClipData.newRawUri("WebCS arbitrage", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Partager les données entraînement / arbitrage WebCS"))
                    dataSummary.text = service.sessionDataSummary()
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

            orientationCalibrateButton.setOnClickListener {
                service.startOrientationCalibration()
                dialog.dismiss()
            }

            calibrateButton.setOnClickListener {
                if (!previewVisible) {
                    previewVisible = true
                    previewView.visibility = View.VISIBLE
                    service.setPreviewEnabled(true, previewView.surfaceProvider)
                    previewButton.text = "APERÇU OFF"
                }
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

    private fun togglePreview() {
        previewVisible = !previewVisible
        if (previewVisible) {
            previewView.visibility = View.VISIBLE
            cameraService?.setPreviewEnabled(true, previewView.surfaceProvider)
            previewButton.text = "APERÇU OFF"
            statusText.text = "Aperçu ON · affichage vidéo réactivé sans redémarrer la caméra."
        } else {
            cameraService?.setPreviewEnabled(false)
            previewView.visibility = View.INVISIBLE
            previewButton.text = "APERÇU ON"
            statusText.text = "Aperçu OFF · la caméra reste chaude et le buffer de visée continue."
        }
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

    class AimZoomView(context: Context) : View(context) {
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
