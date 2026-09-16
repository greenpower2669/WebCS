from pathlib import Path

SERVICE = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
ACTIVITY = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")

service = SERVICE.read_text(encoding="utf-8")
activity = ACTIVITY.read_text(encoding="utf-8")

if "WEBSC_V090" in service and "APERÇU OFF" in activity:
    print("WebCS v0.9.0 features already present")
    raise SystemExit(0)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"Pattern not found for {label}: {old[:220]!r}")
    return text.replace(old, new, 1)

# --- Service imports / structures -------------------------------------------------
service = replace_once(service,
'''import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
''',
'''import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
''', "imports")

service = replace_once(service,
'''    private data class ProjectionImpulse(val value: Float, val timeMs: Long)

    inner class LocalBinder : Binder() { fun getService(): CameraForegroundService = this@CameraForegroundService }
''',
'''    private data class ProjectionImpulse(val value: Float, val timeMs: Long)

    private data class AimFrame(
        val timestampNs: Long,
        val patchSize: Int,
        val patchPixels: IntArray,
        val decisionPoints: List<PointSample>
    )

    inner class LocalBinder : Binder() { fun getService(): CameraForegroundService = this@CameraForegroundService }
''', "AimFrame")

service = replace_once(service,
'''    private var previewUseCase: Preview? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    private lateinit var videoCapture: VideoCapture<Recorder>
''',
'''    private var previewUseCase: Preview? = null
    private var previewProvider: Preview.SurfaceProvider? = null
    @Volatile private var previewEnabled = true
    private lateinit var videoCapture: VideoCapture<Recorder>
''', "preview state")

service = replace_once(service,
'''    private var sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()

    var listener: Listener? = null
''',
'''    private var sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
    private val aimFrameLock = Any()
    private val aimFrames = ArrayDeque<AimFrame>(AIM_RING_SIZE)

    var listener: Listener? = null
''', "aim ring")

# --- Preview ON/OFF without camera unbind ---------------------------------------
old_attach = '''    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
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
'''
new_attach = '''    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
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
'''
service = replace_once(service, old_attach, new_attach, "preview controls")

service = replace_once(service,
'''        val reloadMotion = if (s.reloadGestureEnabled) " · recharge geste ON" else ""
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · vidéo ${s.videoQuality}$shotMotion$reloadMotion"
''',
'''        val reloadMotion = if (s.reloadGestureEnabled) " · recharge geste ON" else ""
        val preview = if (previewEnabled) " · aperçu ON" else " · aperçu OFF"
        return "Cam ${s.cameraId} · demandé ${s.requestedWidth}×${s.requestedHeight} · effectif $effective · $mode · analyse au tir · buffer ${AIM_RING_SIZE} frames · vidéo ${s.videoQuality}$shotMotion$reloadMotion$preview"
''', "summary")

# --- Calibration starts from hot frame buffer, not continuous recognition --------
service = replace_once(service,
'''    fun startCalibration() {
        if (latestResult == null) {
            listener?.onStatus("Attends une image caméra avant de calibrer la cible.")
            return
        }
''',
'''    fun startCalibration() {
        if (nearestAimFrame(SystemClock.elapsedRealtimeNanos()) == null) {
            listener?.onStatus("Attends une image caméra avant de calibrer la cible.")
            return
        }
''', "calibration readiness")

# --- Persistent session/training storage ----------------------------------------
service = replace_once(service,
'''    fun sessionDataSummary(): String {
        val count = synchronized(eventLog) { eventLog.size }
        return "Session $sessionId · $count événements · joueur ${currentPlayerName()}"
    }

    fun startNewDataSession() {
        synchronized(eventLog) { eventLog.clear() }
        eventSequence = 0L
        sessionId = makeSessionId()
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()
        listener?.onStatus("Nouvelle session de données · $sessionId")
    }
''',
'''    fun sessionDataSummary(): String {
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

    private fun trainingDir(): File = File(filesDir, "webcs-training").apply { mkdirs() }

    private fun currentSessionFile(): File = File(trainingDir(), "WebCS-training-$sessionId.csv")

    private fun ensureSessionFile(): File {
        val file = currentSessionFile()
        if (!file.exists()) {
            file.writeText("\\uFEFF$CSV_HEADER\\n", Charsets.UTF_8)
        }
        return file
    }

    private fun persistSessionRow(row: String) {
        try {
            ensureSessionFile().appendText(row + "\\n", Charsets.UTF_8)
        } catch (e: Exception) {
            notifyStatus("Attention : sauvegarde data impossible · ${e.message ?: "erreur"}")
        }
    }
''', "persistent session")

old_export = '''        val rows = synchronized(eventLog) { eventLog.toList() }
        val fileName = "WebCS-arbitrage-$sessionId.csv"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/WebCS")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                if (writer == null) throw IllegalStateException("sortie CSV indisponible")
                writer.write("\\uFEFF")
                writer.appendLine(CSV_HEADER)
                rows.forEach { writer.appendLine(it) }
            }
            listener?.onStatus("CSV prêt · $fileName · ${rows.size} événements.")
            uri
'''
new_export = '''        val rows = synchronized(eventLog) { eventLog.toList() }
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
'''
service = replace_once(service, old_export, new_export, "export persistent file")

# --- Rich training/recognition log ----------------------------------------------
start_log = service.index("    private fun logGameEvent(")
end_log = service.index("\n    fun startGestureCalibration()", start_log)
old_log = service[start_log:end_log]
new_log = '''    private fun recognitionMode(): String {
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
            "2", sessionId, seq.toString(), type,
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
            ammo.toString(), magazineSize().toString(), score.toString(), currentSettings().profileId
        )
        val row = values.joinToString(",") { csv(it) }
        synchronized(eventLog) { eventLog.add(row) }
        persistSessionRow(row)
    }
'''
service = service[:start_log] + new_log + service[end_log:]

# Calibration event gets an explicit positive training label.
service = replace_once(service,
'''        logGameEvent("target_calibration", result, null)
''',
'''        logGameEvent("target_calibration", result, null, nearestAimFrame(SystemClock.elapsedRealtimeNanos()), SystemClock.elapsedRealtimeNanos(), "enemy_reference")
''', "calibration label")

# --- Fire: classify only a buffered frame at trigger time ------------------------
start_fire = service.index("    fun fire() {")
end_fire = service.index("\n    fun reloadMagazine()", start_fire)
old_fire = service[start_fire:end_fire]
new_fire = '''    fun fire() {
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
'''
service = service[:start_fire] + new_fire + service[end_fire:]

# recordTargetCalibrationShot receives the exact buffered frame used.
service = service.replace(
'''    private fun recordTargetCalibrationShot(result: ScanResult) {
        if (targetCalibrationRemaining <= 0) return
        targetCalibrationCount++
        logGameEvent("target_calibration", result, null, nearestAimFrame(SystemClock.elapsedRealtimeNanos()), SystemClock.elapsedRealtimeNanos(), "enemy_reference")
''',
'''    private fun recordTargetCalibrationShot(result: ScanResult, frame: AimFrame, triggerNs: Long) {
        if (targetCalibrationRemaining <= 0) return
        targetCalibrationCount++
        logGameEvent("target_calibration", result, null, frame, triggerNs, "enemy_reference")
''', 1)

# --- Camera analyzer: copy tiny hot patch only, no recognition per frame ----------
old_analyzer = '''            analysis.setAnalyzer(cameraExecutor) { image ->
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
'''
new_analyzer = '''            analysis.setAnalyzer(cameraExecutor) { image ->
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
'''
service = replace_once(service, old_analyzer, new_analyzer, "hot analyzer")

# Preview is always bound once; provider null means no preview frames/raster output.
service = replace_once(service,
'''            val preview = previewProvider?.let { surface ->
                Preview.Builder()
                    .setTargetResolution(requested)
                    .build()
                    .also { it.setSurfaceProvider(surface) }
            }
            previewUseCase = preview
''',
'''            val preview = previewProvider?.let { surface ->
                Preview.Builder()
                    .setTargetResolution(requested)
                    .build()
                    .also { it.setSurfaceProvider(if (previewEnabled) surface else null) }
            }
            previewUseCase = preview
''', "preview provider gate")

# Replace continuous analyse() with capture + classify from buffered tiny patch.
start_analyse = service.index("    private fun analyse(image: ImageProxy): ScanResult {")
end_analyse = service.index("\n    private fun sample(image: ImageProxy", start_analyse)
old_analyse = service[start_analyse:end_analyse]
new_analyse = '''    private fun captureAimFrame(image: ImageProxy): AimFrame {
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
'''
service = service[:start_analyse] + new_analyse + service[end_analyse:]

# Aim zoom publishes already-buffered patch, and only during target calibration.
start_publish = service.index("    private fun maybePublishAimPatch(")
end_publish = service.index("\n    private fun consumeCalibration", start_publish)
old_publish = service[start_publish:end_publish]
new_publish = '''    private fun maybePublishAimPatch(frame: AimFrame) {
        if (targetCalibrationRemaining <= 0) return
        patchFrameCounter++
        if (patchFrameCounter % 4 != 0) return
        val pixels = frame.patchPixels.copyOf()
        val remaining = targetCalibrationRemaining
        ContextCompat.getMainExecutor(this).execute {
            listener?.onAimPatch(frame.patchSize, pixels, remaining)
        }
    }
'''
service = service[:start_publish] + new_publish + service[end_publish:]

# Gesture shot uses the sensor's monotonic timestamp to choose the closest camera frame.
service = replace_once(service,
'''            if (reloadContext) {
                // L'orientation donne le sens du geste : dans cette fenêtre, aucun tir accidentel.
                projectionImpulse = null
            } else if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, x, y, z)
            }
''',
'''            if (reloadContext) {
                // L'orientation donne le sens du geste : dans cette fenêtre, aucun tir accidentel.
                projectionImpulse = null
            } else if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, event.timestamp, x, y, z)
            }
''', "sensor timestamp dispatch")

service = replace_once(service,
'''    private fun handleGestureDetection(now: Long, x: Float, y: Float, z: Float) {
''',
'''    private fun handleGestureDetection(now: Long, triggerNs: Long, x: Float, y: Float, z: Float) {
''', "gesture signature")

service = replace_once(service,
'''            lastGestureShotMs = now
            lastGestureActionMs = now
            mainHandler.post { fire() }
''',
'''            lastGestureShotMs = now
            lastGestureActionMs = now
            mainHandler.post { fireAt(triggerNs) }
''', "gesture fireAt")

# Session file exists immediately after service start.
service = replace_once(service,
'''        promoteToForeground(false)
        startCameraCore()
''',
'''        promoteToForeground(false)
        ensureSessionFile()
        startCameraCore()
''', "session init")

# Marker + schema v2 + ring size.
service = replace_once(service,
'''        private const val WEBSC_V080 = "context-reload-data-v1"
        private const val CSV_HEADER = "schema_version,session_id,seq,event,wall_time_ms,elapsed_realtime_ns,session_elapsed_ns,player,hit,r,g,b,h,s,v,p0_r,p0_g,p0_b,p1_r,p1_g,p1_b,p2_r,p2_g,p2_b,p3_r,p3_g,p3_b,p4_r,p4_g,p4_b,elevation_deg,camera_id,width,height,ammo,capacity,score,target_profile"
''',
'''        private const val WEBSC_V080 = "context-reload-data-v1"
        private const val WEBSC_V090 = "hot-stream-shot-classification-training-v1"
        private const val CSV_HEADER = "schema_version,session_id,seq,event,wall_time_ms,elapsed_realtime_ns,session_elapsed_ns,trigger_time_ns,camera_frame_ns,frame_delta_us,player,recognized_hit,training_label,recognition_mode,target_distance,r,g,b,h,s,v,p0_r,p0_g,p0_b,p1_r,p1_g,p1_b,p2_r,p2_g,p2_b,p3_r,p3_g,p3_b,p4_r,p4_g,p4_b,patch_size,patch_rgb_hex,elevation_deg,camera_id,width,height,sample_mode,ammo,capacity,score,target_profile"
''', "schema marker")

service = replace_once(service,
'''        private const val AIM_PATCH_SIZE = 11
''',
'''        private const val AIM_PATCH_SIZE = 11
        private const val AIM_RING_SIZE = 3
''', "ring constant")

# --- Activity: real Preview ON/OFF + richer data wording -------------------------
activity = replace_once(activity,
'''    private lateinit var ecoButton: Button
''',
'''    private lateinit var previewButton: Button
''', "preview button field")
activity = replace_once(activity,
'''    private var eco = false
''',
'''    private var previewVisible = true
''', "preview state activity")

activity = replace_once(activity,
'''        ecoButton = Button(this).apply {
            text = "MODE ÉCO"
            setOnClickListener { toggleEco() }
        }
''',
'''        previewButton = Button(this).apply {
            text = "APERÇU OFF"
            contentDescription = "Couper uniquement l'affichage vidéo sans arrêter la caméra"
            setOnClickListener { togglePreview() }
        }
''', "preview button")
activity = activity.replace("row.addView(ecoButton,", "row.addView(previewButton,", 1)

activity = replace_once(activity,
'''        val shareDataButton = Button(this).apply {
            text = "PARTAGER CSV D'ARBITRAGE"
            contentDescription = "Exporter et partager les tirs et valeurs de visée"
        }
        content.addView(shareDataButton)
''',
'''        content.addView(label("Chaque tir est sauvegardé automatiquement avec la frame caméra la plus proche, les pixels centraux, le résultat de reconnaissance et les timestamps. Le CSV sert à l'entraînement et à l'arbitrage."))
        val shareDataButton = Button(this).apply {
            text = "PARTAGER DATA ENTRAÎNEMENT / ARBITRAGE"
            contentDescription = "Exporter et partager les données de tirs, reconnaissance et entraînement"
        }
        content.addView(shareDataButton)
''', "data wording")

activity = replace_once(activity,
'''                    startActivity(Intent.createChooser(share, "Partager les données WebCS"))
''',
'''                    startActivity(Intent.createChooser(share, "Partager les données entraînement / arbitrage WebCS"))
''', "share chooser")

# Calibration must turn preview back on so the user can aim; no camera restart.
activity = replace_once(activity,
'''            calibrateButton.setOnClickListener {
                service.startCalibration()
                dialog.dismiss()
            }
''',
'''            calibrateButton.setOnClickListener {
                if (!previewVisible) {
                    previewVisible = true
                    previewView.visibility = View.VISIBLE
                    service.setPreviewEnabled(true, previewView.surfaceProvider)
                    previewButton.text = "APERÇU OFF"
                }
                service.startCalibration()
                dialog.dismiss()
            }
''', "calibration preview")

old_toggle = '''    private fun toggleEco() {
        eco = !eco
        previewView.alpha = if (eco) 0.03f else 1f
        ecoButton.text = if (eco) "APERÇU NORMAL" else "MODE ÉCO"
        statusText.text = if (eco) "Mode éco : aperçu presque noir. Le service caméra reste actif." else "Aperçu normal."
    }
'''
new_toggle = '''    private fun togglePreview() {
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
'''
activity = replace_once(activity, old_toggle, new_toggle, "toggle preview")

SERVICE.write_text(service, encoding="utf-8")
ACTIVITY.write_text(activity, encoding="utf-8")
print("WebCS v0.9.0 hot stream + shot-only recognition + persistent training data applied")
