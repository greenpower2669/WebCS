from pathlib import Path

MAIN = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/MainActivity.kt")
BT = Path("android/poc-camera/src/main/java/online/tek4all/webcs/poc/BluetoothDataTransport.kt")
MANIFEST = Path("android/poc-camera/src/main/AndroidManifest.xml")
GRADLE = Path("android/poc-camera/build.gradle.kts")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"Pattern not found for {label}")


# ---- Bluetooth transport: nearby discovery + Android pairing -------------------
bt = BT.read_text(encoding="utf-8")
if "WEBSC_BT_PAIRING_V0111" not in bt:
    bt = bt.replace(
        "import android.bluetooth.BluetoothServerSocket\nimport android.content.Context\n",
        "import android.bluetooth.BluetoothServerSocket\nimport android.bluetooth.BluetoothDevice\nimport android.content.BroadcastReceiver\nimport android.content.Context\nimport android.content.Intent\nimport android.content.IntentFilter\nimport androidx.core.content.ContextCompat\n",
        1,
    )
    bt = bt.replace(
        "class BluetoothDataTransport(context: Context) {\n    data class PairedDevice(val name: String, val address: String)\n",
        "class BluetoothDataTransport(context: Context) {\n    // WEBSC_BT_PAIRING_V0111\n    data class PairedDevice(val name: String, val address: String)\n    data class NearbyDevice(val name: String, val address: String, val bonded: Boolean)\n",
        1,
    )
    bt = bt.replace(
        "    @Volatile private var serverSocket: BluetoothServerSocket? = null\n",
        "    @Volatile private var serverSocket: BluetoothServerSocket? = null\n    @Volatile private var discoveryReceiver: BroadcastReceiver? = null\n",
        1,
    )

    anchor = '''    @SuppressLint("MissingPermission")\n    fun send(\n'''
    insert = '''    @SuppressLint("MissingPermission")\n    fun startDiscovery(\n        onDevice: (NearbyDevice) -> Unit,\n        onStatus: (String) -> Unit,\n        onFinished: () -> Unit\n    ) {\n        stopDiscovery()\n        val bt = adapter() ?: run {\n            onStatus("Bluetooth indisponible sur cet appareil.")\n            onFinished()\n            return\n        }\n        val receiver = object : BroadcastReceiver() {\n            override fun onReceive(context: Context?, intent: Intent?) {\n                when (intent?.action) {\n                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {\n                        @Suppress("DEPRECATION")\n                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return\n                        try {\n                            onDevice(\n                                NearbyDevice(\n                                    name = device.name ?: "Téléphone / appareil Bluetooth",\n                                    address = device.address,\n                                    bonded = device.bondState == BluetoothDevice.BOND_BONDED\n                                )\n                            )\n                        } catch (_: SecurityException) { }\n                    }\n                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()\n                }\n            }\n        }\n        val filter = IntentFilter().apply {\n            addAction(BluetoothDevice.ACTION_FOUND)\n            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)\n            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)\n        }\n        discoveryReceiver = receiver\n        try {\n            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)\n            pairedDevices().forEach { onDevice(NearbyDevice(it.name, it.address, true)) }\n            bt.cancelDiscovery()\n            if (bt.startDiscovery()) onStatus("Bluetooth · recherche des téléphones proches…")\n            else {\n                onStatus("Bluetooth · impossible de démarrer la recherche.")\n                stopDiscovery()\n                onFinished()\n            }\n        } catch (e: Exception) {\n            stopDiscovery()\n            onStatus("Bluetooth · recherche impossible : ${e.message ?: e.javaClass.simpleName}.")\n            onFinished()\n        }\n    }\n\n    @SuppressLint("MissingPermission")\n    fun pairDevice(deviceAddress: String, onStatus: (String) -> Unit): Boolean {\n        val bt = adapter() ?: run {\n            onStatus("Bluetooth indisponible sur cet appareil.")\n            return false\n        }\n        return try {\n            val device = bt.getRemoteDevice(deviceAddress)\n            if (device.bondState == BluetoothDevice.BOND_BONDED) {\n                onStatus("${device.name ?: deviceAddress} est déjà appairé.")\n                true\n            } else {\n                val started = device.createBond()\n                onStatus(\n                    if (started) "Demande d'appairage envoyée · confirme le code Android sur les deux téléphones."\n                    else "Android n'a pas pu démarrer l'appairage."\n                )\n                started\n            }\n        } catch (e: Exception) {\n            onStatus("Appairage impossible : ${e.message ?: e.javaClass.simpleName}.")\n            false\n        }\n    }\n\n    @SuppressLint("MissingPermission")\n    fun stopDiscovery() {\n        try { adapter()?.cancelDiscovery() } catch (_: Exception) { }\n        val receiver = discoveryReceiver\n        discoveryReceiver = null\n        if (receiver != null) {\n            try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }\n        }\n    }\n\n    @SuppressLint("MissingPermission")\n    fun send(\n'''
    if anchor not in bt:
        raise SystemExit("Bluetooth send anchor not found")
    bt = bt.replace(anchor, insert, 1)
    bt = bt.replace(
        '''    fun close() {\n        stopReceiver()\n        executor.shutdownNow()\n    }''',
        '''    fun close() {\n        stopDiscovery()\n        stopReceiver()\n        executor.shutdownNow()\n    }''',
        1,
    )
    BT.write_text(bt, encoding="utf-8")


# ---- Manifest permissions for discovery / pairing -------------------------------
manifest = MANIFEST.read_text(encoding="utf-8")
manifest = replace_once(
    manifest,
    '    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />\n',
    '    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />\n'
    '    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" android:usesPermissionFlags="neverForLocation" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />\n',
    "Bluetooth scan permissions",
)
MANIFEST.write_text(manifest, encoding="utf-8")


# ---- MainActivity: pairing UI + scan permissions + rescans ----------------------
main = MAIN.read_text(encoding="utf-8")
if "WEBSC_BT_PAIRING_V0111" not in main:
    main = main.replace(
        "    // WEBSC_BT_DATA_V011\n",
        "    // WEBSC_BT_DATA_V011\n    // WEBSC_BT_PAIRING_V0111\n",
        1,
    )
    main = main.replace(
        "    private var pendingBluetoothAction: (() -> Unit)? = null\n",
        "    private var pendingBluetoothAction: (() -> Unit)? = null\n    private var pendingBluetoothScanAction: (() -> Unit)? = null\n",
        1,
    )

    old = '''    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->\n        val action = pendingBluetoothAction\n        pendingBluetoothAction = null\n        if (granted) action?.invoke() else statusText.text = "Autorisation Bluetooth refusée."\n    }\n'''
    new = old + '''\n    private val bluetoothScanPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->\n        val action = pendingBluetoothScanAction\n        pendingBluetoothScanAction = null\n        if (grants.values.all { it }) action?.invoke()\n        else statusText.text = "Autorisation de recherche Bluetooth refusée."\n    }\n'''
    if old not in main:
        raise SystemExit("Bluetooth permission launcher anchor not found")
    main = main.replace(old, new, 1)

    old = '''        val bluetoothReceiveButton = Button(this).apply {\n            text = "RECEVOIR DATA WEB CS PAR BLUETOOTH"\n            contentDescription = "Attendre et importer un dataset envoyé par un autre téléphone WebCS appairé"\n        }\n        content.addView(bluetoothReceiveButton)\n'''
    new = old + '''        val bluetoothPairButton = Button(this).apply {\n            text = "APPAIRER UN TÉLÉPHONE"\n            contentDescription = "Rechercher un téléphone proche et lancer l'appairage Bluetooth Android"\n        }\n        content.addView(bluetoothPairButton)\n'''
    if old not in main:
        raise SystemExit("Bluetooth receive UI anchor not found")
    main = main.replace(old, new, 1)

    old = '''            bluetoothReceiveButton.setOnClickListener {\n                withBluetoothPermission {\n                    dialog.dismiss()\n                    startBluetoothReceive()\n                }\n            }\n'''
    new = old + '''\n            bluetoothPairButton.setOnClickListener {\n                withBluetoothScanPermission {\n                    dialog.dismiss()\n                    showBluetoothPairingDialog()\n                }\n            }\n'''
    if old not in main:
        raise SystemExit("Bluetooth receive listener anchor not found")
    main = main.replace(old, new, 1)

    old = '''    private fun ensureBluetoothReady(): Boolean {\n'''
    new = '''    private fun withBluetoothScanPermission(action: () -> Unit) {\n        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {\n            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)\n        } else {\n            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)\n        }\n        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }\n        if (missing.isEmpty()) {\n            action()\n            return\n        }\n        pendingBluetoothScanAction = action\n        bluetoothScanPermissions.launch(missing.toTypedArray())\n    }\n\n    private fun ensureBluetoothReady(): Boolean {\n'''
    if old not in main:
        raise SystemExit("Bluetooth ready helper anchor not found")
    main = main.replace(old, new, 1)

    old = '''    private fun showBluetoothSendPicker() {\n        if (!ensureBluetoothReady()) return\n        val payload = dataExchange.currentSessionPayload()\n        if (payload == null) {\n            statusText.text = "Aucune session WebCS locale à envoyer."\n            return\n        }\n        val devices = bluetoothTransport.pairedDevices()\n        if (devices.isEmpty()) {\n            statusText.text = "Aucun appareil Bluetooth appairé. Appaire d'abord les deux téléphones dans Android."\n            return\n        }\n        val labels = devices.map { "${it.name} · ${it.address}" }.toTypedArray()\n        AlertDialog.Builder(this)\n            .setTitle("Envoyer la session WebCS")\n            .setItems(labels) { _, which ->\n                val device = devices[which]\n                bluetoothTransport.send(\n                    device.address,\n                    payload.fileName,\n                    payload.bytes,\n                    onStatus = { message -> runOnUiThread { statusText.text = message } },\n                    onComplete = { _, message -> runOnUiThread { statusText.text = message } }\n                )\n            }\n            .setNegativeButton("ANNULER", null)\n            .show()\n    }\n'''
    new = '''    private fun showBluetoothSendPicker() {\n        if (!ensureBluetoothReady()) return\n        val payload = dataExchange.currentSessionPayload()\n        if (payload == null) {\n            statusText.text = "Aucune session WebCS locale à envoyer."\n            return\n        }\n        val devices = bluetoothTransport.pairedDevices()\n        if (devices.isEmpty()) {\n            statusText.text = "Aucun appareil Bluetooth appairé · utilise APPAIRER UN TÉLÉPHONE."\n            withBluetoothScanPermission { showBluetoothPairingDialog() }\n            return\n        }\n        val labels = devices.map { "${it.name} · ${it.address}" }.toTypedArray()\n        val picker = AlertDialog.Builder(this)\n            .setTitle("Envoyer la session WebCS")\n            .setItems(labels) { _, which ->\n                val device = devices[which]\n                bluetoothTransport.send(\n                    device.address,\n                    payload.fileName,\n                    payload.bytes,\n                    onStatus = { message -> runOnUiThread { statusText.text = message } },\n                    onComplete = { _, message -> runOnUiThread { statusText.text = message } }\n                )\n            }\n            .setNeutralButton("RESCANNER", null)\n            .setNegativeButton("ANNULER", null)\n            .create()\n        picker.setOnShowListener {\n            picker.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {\n                picker.dismiss()\n                showBluetoothSendPicker()\n            }\n        }\n        picker.show()\n    }\n\n    private fun showBluetoothPairingDialog() {\n        if (!ensureBluetoothReady()) return\n        val devices = linkedMapOf<String, BluetoothDataTransport.NearbyDevice>()\n        val labels = mutableListOf<String>()\n        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)\n        lateinit var pairingDialog: AlertDialog\n\n        fun refreshList() {\n            labels.clear()\n            labels.addAll(devices.values.map { device ->\n                val state = if (device.bonded) "APPARIÉ" else "NOUVEAU"\n                "${device.name} · $state · ${device.address}"\n            })\n            adapter.notifyDataSetChanged()\n        }\n\n        fun scan() {\n            devices.clear()\n            bluetoothTransport.pairedDevices().forEach {\n                devices[it.address] = BluetoothDataTransport.NearbyDevice(it.name, it.address, true)\n            }\n            refreshList()\n            statusText.text = "Bluetooth · scan en cours…"\n            bluetoothTransport.startDiscovery(\n                onDevice = { device -> runOnUiThread {\n                    devices[device.address] = device\n                    refreshList()\n                    if (device.bonded) statusText.text = "Bluetooth · ${device.name} appairé."\n                } },\n                onStatus = { message -> runOnUiThread { statusText.text = message } },\n                onFinished = { runOnUiThread {\n                    statusText.text = "Bluetooth · scan terminé · ${devices.size} appareil(s) visible(s)."\n                } }\n            )\n        }\n\n        pairingDialog = AlertDialog.Builder(this)\n            .setTitle("Appairer un téléphone WebCS")\n            .setAdapter(adapter, null)\n            .setPositiveButton("RESCANNER", null)\n            .setNegativeButton("FERMER", null)\n            .create()\n        pairingDialog.setOnShowListener {\n            pairingDialog.listView.setOnItemClickListener { _, _, which, _ ->\n                val device = devices.values.getOrNull(which) ?: return@setOnItemClickListener\n                if (device.bonded) {\n                    statusText.text = "${device.name} est déjà appairé et prêt pour WebCS."\n                } else {\n                    bluetoothTransport.pairDevice(device.address) { message ->\n                        runOnUiThread { statusText.text = message }\n                    }\n                }\n            }\n            pairingDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { scan() }\n            scan()\n        }\n        pairingDialog.setOnDismissListener { bluetoothTransport.stopDiscovery() }\n        pairingDialog.show()\n    }\n'''
    if old not in main:
        raise SystemExit("Bluetooth send picker anchor not found")
    main = main.replace(old, new, 1)
    MAIN.write_text(main, encoding="utf-8")


# ---- Version --------------------------------------------------------------------
gradle = GRADLE.read_text(encoding="utf-8")
gradle = replace_once(gradle, "versionCode = 13", "versionCode = 14", "versionCode 14")
gradle = replace_once(gradle, 'versionName = "0.11.0"', 'versionName = "0.11.1"', "versionName 0.11.1")
GRADLE.write_text(gradle, encoding="utf-8")

print("WebCS v0.11.1 Bluetooth pairing/discovery/rescan applied")
