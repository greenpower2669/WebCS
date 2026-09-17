from pathlib import Path

p = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "WEBSC_BT_DATA_V011" in s:
    raise SystemExit(0)

s = s.replace(
    "import android.os.Bundle\n",
    "import android.os.Build\nimport android.os.Bundle\n",
    1,
)

s = s.replace(
    "class MainActivity : ComponentActivity() {\n",
    "class MainActivity : ComponentActivity() {\n    // WEBSC_BT_DATA_V011\n",
    1,
)

old = """    private lateinit var aimZoomView: AimZoomView

    private var cameraService: CameraForegroundService? = null
"""
new = """    private lateinit var aimZoomView: AimZoomView
    private lateinit var dataExchange: WebCsDataExchange
    private lateinit var bluetoothTransport: BluetoothDataTransport
    private var pendingBluetoothAction: (() -> Unit)? = null

    private var cameraService: CameraForegroundService? = null
"""
if old not in s:
    raise SystemExit("field anchor not found")
s = s.replace(old, new, 1)

old = """    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startServiceAndBind() else statusText.text = "Autorisation caméra refusée."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
"""
new = """    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startServiceAndBind() else statusText.text = "Autorisation caméra refusée."
    }

    private val importDataPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && ::dataExchange.isInitialized) {
            val result = dataExchange.importCsv(uri)
            statusText.text = result.message
        }
    }

    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingBluetoothAction
        pendingBluetoothAction = null
        if (granted) action?.invoke() else statusText.text = "Autorisation Bluetooth refusée."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataExchange = WebCsDataExchange(this)
        bluetoothTransport = BluetoothDataTransport(this)
        buildUi()
"""
if old not in s:
    raise SystemExit("permission/onCreate anchor not found")
s = s.replace(old, new, 1)

old = """        content.addView(shareDataButton)

        content.addView(label("Déclenchement par mouvement"))
"""
new = """        content.addView(shareDataButton)
        val importedDataSummary = label(dataExchange.importedSummary())
        content.addView(importedDataSummary)
        val importDataButton = Button(this).apply {
            text = "IMPORTER / RÉUTILISER UN CSV WEB CS"
            contentDescription = "Importer un ancien dataset WebCS depuis un fichier"
        }
        content.addView(importDataButton)
        val reuseCalibrationButton = Button(this).apply {
            text = "RÉUTILISER CALIBRATION DU DERNIER IMPORT"
            contentDescription = "Reconstruire la calibration de couleur avec les références cible du dernier dataset importé"
        }
        content.addView(reuseCalibrationButton)
        content.addView(label("Bluetooth direct WebCS ↔ WebCS : les deux téléphones doivent être appairés. Sur le téléphone destinataire, lance RECEVOIR, puis ENVOYER sur l'autre."))
        val bluetoothSendButton = Button(this).apply {
            text = "ENVOYER DATA À WEB CS PAR BLUETOOTH"
            contentDescription = "Envoyer la session courante directement à un autre téléphone WebCS appairé"
        }
        content.addView(bluetoothSendButton)
        val bluetoothReceiveButton = Button(this).apply {
            text = "RECEVOIR DATA WEB CS PAR BLUETOOTH"
            contentDescription = "Attendre et importer un dataset envoyé par un autre téléphone WebCS appairé"
        }
        content.addView(bluetoothReceiveButton)

        content.addView(label("Déclenchement par mouvement"))
"""
if old not in s:
    raise SystemExit("data UI anchor not found")
s = s.replace(old, new, 1)

old = """            shareDataButton.setOnClickListener {
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
"""
new = """            shareDataButton.setOnClickListener {
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

            importDataButton.setOnClickListener {
                importDataPicker.launch(arrayOf("text/*", "application/octet-stream", "application/vnd.ms-excel"))
            }

            reuseCalibrationButton.setOnClickListener {
                val message = dataExchange.reuseLatestImportedCalibration(service.currentSettings().profileId)
                importedDataSummary.text = dataExchange.importedSummary()
                profileText.text = service.calibrationSummary()
                statusText.text = message
            }

            bluetoothSendButton.setOnClickListener {
                service.setPlayerName(playerEdit.text.toString())
                withBluetoothPermission {
                    dialog.dismiss()
                    showBluetoothSendPicker()
                }
            }

            bluetoothReceiveButton.setOnClickListener {
                withBluetoothPermission {
                    dialog.dismiss()
                    startBluetoothReceive()
                }
            }

            gestureCalibrateButton.setOnClickListener {
"""
if old not in s:
    raise SystemExit("data listeners anchor not found")
s = s.replace(old, new, 1)

old = """    private fun simpleSeekListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
"""
new = """    private fun withBluetoothPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingBluetoothAction = action
        bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun ensureBluetoothReady(): Boolean {
        if (!bluetoothTransport.isAvailable()) {
            statusText.text = "Bluetooth indisponible sur cet appareil."
            return false
        }
        if (!bluetoothTransport.isEnabled()) {
            statusText.text = "Active le Bluetooth puis relance l'envoi ou la réception WebCS."
            try { startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { }
            return false
        }
        return true
    }

    private fun showBluetoothSendPicker() {
        if (!ensureBluetoothReady()) return
        val payload = dataExchange.currentSessionPayload()
        if (payload == null) {
            statusText.text = "Aucune session WebCS locale à envoyer."
            return
        }
        val devices = bluetoothTransport.pairedDevices()
        if (devices.isEmpty()) {
            statusText.text = "Aucun appareil Bluetooth appairé. Appaire d'abord les deux téléphones dans Android."
            return
        }
        val labels = devices.map { "${it.name} · ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Envoyer la session WebCS")
            .setItems(labels) { _, which ->
                val device = devices[which]
                bluetoothTransport.send(
                    device.address,
                    payload.fileName,
                    payload.bytes,
                    onStatus = { message -> runOnUiThread { statusText.text = message } },
                    onComplete = { _, message -> runOnUiThread { statusText.text = message } }
                )
            }
            .setNegativeButton("ANNULER", null)
            .show()
    }

    private fun startBluetoothReceive() {
        if (!ensureBluetoothReady()) return
        bluetoothTransport.startReceiver(
            onStatus = { message -> runOnUiThread { statusText.text = message } },
            onReceived = { fileName, bytes ->
                val result = dataExchange.importCsvBytes(fileName, bytes)
                runOnUiThread {
                    statusText.text = if (result.ok) "Bluetooth · ${result.message}" else result.message
                }
            },
            onError = { message -> runOnUiThread { statusText.text = message } }
        )
    }

    private fun simpleSeekListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
"""
if old not in s:
    raise SystemExit("helper anchor not found")
s = s.replace(old, new, 1)

old = """    override fun onDestroy() {
        if (isBound) {
"""
new = """    override fun onDestroy() {
        if (::bluetoothTransport.isInitialized) bluetoothTransport.close()
        if (isBound) {
"""
if old not in s:
    raise SystemExit("onDestroy anchor not found")
s = s.replace(old, new, 1)

p.write_text(s, encoding="utf-8")
