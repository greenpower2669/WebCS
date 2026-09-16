from pathlib import Path

service_path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/CameraForegroundService.kt")
activity_path = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")
service = service_path.read_text(encoding="utf-8")
activity = activity_path.read_text(encoding="utf-8")

if "WEBSC_V080" in service and "PARTAGER CSV D'ARBITRAGE" in activity:
    print("WebCS v0.8.0 features already present")
    raise SystemExit(0)

def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"Pattern not found for {label}: {old[:220]!r}")
    return text.replace(old, new, 1)

# --- Service: local session/event data ---
service = replace_once(service,
'''    private var patchFrameCounter = 0

    var listener: Listener? = null
''',
'''    private var patchFrameCounter = 0
    @Volatile private var latestRawPoints: List<PointSample> = emptyList()
    private val eventLog = mutableListOf<String>()
    private var eventSequence = 0L
    private var sessionId = makeSessionId()
    private var sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()

    var listener: Listener? = null
''', "session fields")

service = replace_once(service,
'''    fun isTargetCalibrationActive(): Boolean = targetCalibrationRemaining > 0

    fun startGestureCalibration() {
''',
'''    fun isTargetCalibrationActive(): Boolean = targetCalibrationRemaining > 0

    fun currentPlayerName(): String = prefs.getString(PREF_PLAYER_NAME, "Joueur") ?: "Joueur"

    fun setPlayerName(value: String) {
        val clean = value.trim().take(40).ifBlank { "Joueur" }
        prefs.edit().putString(PREF_PLAYER_NAME, clean).apply()
    }

    fun sessionDataSummary(): String {
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

    fun exportSessionCsv(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            listener?.onStatus("Export CSV direct disponible à partir d'Android 10.")
            return null
        }
        val rows = synchronized(eventLog) { eventLog.toList() }
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
        return if (text.contains(',') || text.contains('"') || text.contains('\\n')) {
            "\\\"" + text.replace("\\\"", "\\\"\\\"") + "\\\""
        } else text
    }

    private fun logGameEvent(type: String, result: ScanResult? = latestResult, hit: Boolean? = null) {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val seq = ++eventSequence
        val points = latestRawPoints.take(5)
        val values = mutableListOf<String>()
        values += listOf(
            "1", sessionId, seq.toString(), type,
            System.currentTimeMillis().toString(), nowNs.toString(), (nowNs - sessionStartElapsedNs).toString(),
            currentPlayerName(), hit?.toString() ?: "",
            result?.r?.toString() ?: "", result?.g?.toString() ?: "", result?.b?.toString() ?: "",
            result?.h?.toString() ?: "", result?.s?.toString() ?: "", result?.v?.toString() ?: ""
        )
        for (i in 0 until 5) {
            val p = points.getOrNull(i)
            values += listOf(p?.r?.toString() ?: "", p?.g?.toString() ?: "", p?.b?.toString() ?: "")
        }
        values += listOf(
            lastCameraElevationDeg?.toString() ?: "",
            selectedCameraId(), effectiveWidth.toString(), effectiveHeight.toString(),
            ammo.toString(), magazineSize().toString(), score.toString(), currentSettings().profileId
        )
        val row = values.joinToString(",") { csv(it) }
        synchronized(eventLog) { eventLog.add(row) }
    }

    fun startGestureCalibration() {
''', "session API")

# Calibration shots become training events too.
service = replace_once(service,
'''        targetCalibrationCount++
        targetSumR += result.r
''',
'''        targetCalibrationCount++
        logGameEvent("target_calibration", result, null)
        targetSumR += result.r
''', "log target calibration")

# Normal shots are logged after score calculation so score/ammo are final for the event.
service = replace_once(service,
'''        if (result.hit) {
            score++
            prefs.edit().putInt("score", score).apply()
        }
        val mode = if (result.total == 1) "centre" else "5 points"
''',
'''        if (result.hit) {
            score++
            prefs.edit().putInt("score", score).apply()
        }
        logGameEvent("shot", result, result.hit)
        val mode = if (result.total == 1) "centre" else "5 points"
''', "log shot")

# Reload button/gesture shares the same event stream.
service = replace_once(service,
'''        reloading = true
        sfx.playReload()
''',
'''        reloading = true
        logGameEvent("reload", latestResult, null)
        sfx.playReload()
''', "log reload")

# Keep the raw five aiming samples, independent of decision mode.
service = replace_once(service,
'''        val points = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        val centerOnly = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER
''',
'''        val points = offsets.map { (dx, dy) -> sample(image, cx + dx, cy + dy) }
        latestRawPoints = points
        val centerOnly = prefs.getString(PREF_SAMPLE_MODE, SAMPLE_FIVE) == SAMPLE_CENTER
''', "raw five samples")

# Context state machine: while reload is armed, shot recognition is suppressed.
service = replace_once(service,
'''            if (prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) && hasReloadGestureCalibration()) {
                handleReloadGestureDetection(now, x, y, z)
            }
            if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, x, y, z)
            }
''',
'''            val reloadReady = prefs.getBoolean(PREF_RELOAD_GESTURE_ENABLED, false) && hasReloadGestureCalibration()
            val reloadContext = reloadReady && now <= reloadGateUntilMs
            if (reloadReady) {
                handleReloadGestureDetection(now, x, y, z)
            }
            if (reloadContext) {
                // L'orientation donne le sens du geste : dans cette fenêtre, aucun tir accidentel.
                projectionImpulse = null
            } else if (prefs.getBoolean(PREF_GESTURE_ENABLED, false) && hasGestureCalibration()) {
                handleGestureDetection(now, x, y, z)
            }
''', "reload context suppresses shot")

# Opening the gate resets any half-recognized shot and is itself logged for later tuning.
service = replace_once(service,
'''            reloadGateUntilMs = now + RELOAD_GATE_MS
            upwardTurnAccumDeg = 0f
            upwardTurnStartMs = now
''',
'''            reloadGateUntilMs = now + RELOAD_GATE_MS
            projectionImpulse = null
            reloadProjectionImpulse = null
            upwardTurnAccumDeg = 0f
            upwardTurnStartMs = now
            logGameEvent("reload_gate", latestResult, null)
''', "gate state")

# A reload gesture is allowed to be identical to a shot gesture: orientation disambiguates it.
similarity_block = '''            if (hasGestureCalibration()) {
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
'''
if similarity_block not in service:
    raise SystemExit("Similarity guard block not found")
service = service.replace(similarity_block, '''            // Le geste peut ressembler au tir : la transition vers le haut ouvre seule le contexte recharge.
''', 1)

service = replace_once(service,
'''            notifyStatus("Calibration recharge terminée · geste activé. Le FX de rechargement jouera à chaque recharge valide.")
''',
'''            notifyStatus("Calibration recharge terminée · geste activé. Il ne rechargera qu'après une rotation vers le haut (fenêtre 5 s).")
''', "reload calibration message")

# Marker + CSV schema constants.
service = replace_once(service,
'''    companion object {
        const val SAMPLE_CENTER = "center"
''',
'''    companion object {
        private const val WEBSC_V080 = "context-reload-data-v1"
        private const val CSV_HEADER = "schema_version,session_id,seq,event,wall_time_ms,elapsed_realtime_ns,session_elapsed_ns,player,hit,r,g,b,h,s,v,p0_r,p0_g,p0_b,p1_r,p1_g,p1_b,p2_r,p2_g,p2_b,p3_r,p3_g,p3_b,p4_r,p4_g,p4_b,elevation_deg,camera_id,width,height,ammo,capacity,score,target_profile"
        const val SAMPLE_CENTER = "center"
''', "version marker")

service = replace_once(service,
'''        private const val PREF_MAGAZINE_SIZE = "game.magazineSize"
''',
'''        private const val PREF_MAGAZINE_SIZE = "game.magazineSize"
        private const val PREF_PLAYER_NAME = "game.playerName"
''', "player pref")

# --- Activity: player identity + session reset + Android Sharesheet ---
activity = replace_once(activity,
'''import android.widget.CheckBox
import android.widget.FrameLayout
''',
'''import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
''', "EditText import")

activity = replace_once(activity,
'''        content.addView(magazineSpinner)

        content.addView(label("Déclenchement par mouvement"))
''',
'''        content.addView(magazineSpinner)

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
        val shareDataButton = Button(this).apply {
            text = "PARTAGER CSV D'ARBITRAGE"
            contentDescription = "Exporter et partager les tirs et valeurs de visée"
        }
        content.addView(shareDataButton)

        content.addView(label("Déclenchement par mouvement"))
''', "data UI")

activity = replace_once(activity,
'''                val magazineSize = magazineValues[magazineSpinner.selectedItemPosition]
                if (service.applySettings(
''',
'''                val magazineSize = magazineValues[magazineSpinner.selectedItemPosition]
                service.setPlayerName(playerEdit.text.toString())
                if (service.applySettings(
''', "save player")

activity = replace_once(activity,
'''            gestureCalibrateButton.setOnClickListener {
                service.startGestureCalibration()
                dialog.dismiss()
            }
''',
'''            newSessionButton.setOnClickListener {
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
                    startActivity(Intent.createChooser(share, "Partager les données WebCS"))
                    dataSummary.text = service.sessionDataSummary()
                }
            }

            gestureCalibrateButton.setOnClickListener {
                service.startGestureCalibration()
                dialog.dismiss()
            }
''', "data actions")

service_path.write_text(service, encoding="utf-8")
activity_path.write_text(activity, encoding="utf-8")
print("WebCS v0.8.0 contextual reload + arbitration CSV applied")
