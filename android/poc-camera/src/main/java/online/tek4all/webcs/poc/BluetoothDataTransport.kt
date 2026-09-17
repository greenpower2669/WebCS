package online.tek4all.webcs.poc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.CRC32

class BluetoothDataTransport(context: Context) {
    // WEBSC_BT_PAIRING_V0111
    data class PairedDevice(val name: String, val address: String)
    data class NearbyDevice(val name: String, val address: String, val bonded: Boolean)

    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var discoveryReceiver: BroadcastReceiver? = null

    private fun adapter(): BluetoothAdapter? = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    fun isAvailable(): Boolean = adapter() != null

    @SuppressLint("MissingPermission")
    fun isEnabled(): Boolean = try { adapter()?.isEnabled == true } catch (_: SecurityException) { false }

    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedDevice> {
        val bt = adapter() ?: return emptyList()
        return try {
            bt.bondedDevices
                .map { PairedDevice(it.name ?: "Appareil Bluetooth", it.address) }
                .sortedBy { it.name.lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(
        onDevice: (NearbyDevice) -> Unit,
        onStatus: (String) -> Unit,
        onFinished: () -> Unit
    ) {
        stopDiscovery()
        val bt = adapter() ?: run {
            onStatus("Bluetooth indisponible sur cet appareil.")
            onFinished()
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        try {
                            onDevice(
                                NearbyDevice(
                                    name = device.name ?: "Téléphone / appareil Bluetooth",
                                    address = device.address,
                                    bonded = device.bondState == BluetoothDevice.BOND_BONDED
                                )
                            )
                        } catch (_: SecurityException) { }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        discoveryReceiver = receiver
        try {
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            pairedDevices().forEach { onDevice(NearbyDevice(it.name, it.address, true)) }
            bt.cancelDiscovery()
            if (bt.startDiscovery()) onStatus("Bluetooth · recherche des téléphones proches…")
            else {
                onStatus("Bluetooth · impossible de démarrer la recherche.")
                stopDiscovery()
                onFinished()
            }
        } catch (e: Exception) {
            stopDiscovery()
            onStatus("Bluetooth · recherche impossible : ${e.message ?: e.javaClass.simpleName}.")
            onFinished()
        }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(deviceAddress: String, onStatus: (String) -> Unit): Boolean {
        val bt = adapter() ?: run {
            onStatus("Bluetooth indisponible sur cet appareil.")
            return false
        }
        return try {
            val device = bt.getRemoteDevice(deviceAddress)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                onStatus("${device.name ?: deviceAddress} est déjà appairé.")
                true
            } else {
                val started = device.createBond()
                onStatus(
                    if (started) "Demande d'appairage envoyée · confirme le code Android sur les deux téléphones."
                    else "Android n'a pas pu démarrer l'appairage."
                )
                started
            }
        } catch (e: Exception) {
            onStatus("Appairage impossible : ${e.message ?: e.javaClass.simpleName}.")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try { adapter()?.cancelDiscovery() } catch (_: Exception) { }
        val receiver = discoveryReceiver
        discoveryReceiver = null
        if (receiver != null) {
            try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }
        }
    }

    @SuppressLint("MissingPermission")
    fun send(
        deviceAddress: String,
        fileName: String,
        payload: ByteArray,
        onStatus: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        if (payload.isEmpty()) {
            onComplete(false, "Aucune donnée à envoyer.")
            return
        }
        if (payload.size > MAX_PAYLOAD_BYTES) {
            onComplete(false, "Envoi refusé : dataset supérieur à 20 Mo.")
            return
        }
        executor.execute {
            val bt = adapter()
            if (bt == null) {
                onComplete(false, "Bluetooth indisponible sur cet appareil.")
                return@execute
            }
            var socket: android.bluetooth.BluetoothSocket? = null
            try {
                bt.cancelDiscovery()
                val device = bt.getRemoteDevice(deviceAddress)
                onStatus("Bluetooth · connexion à ${device.name ?: deviceAddress}…")
                socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                val crc = CRC32().apply { update(payload) }.value
                DataOutputStream(socket.outputStream).use { out ->
                    out.writeUTF(MAGIC)
                    out.writeUTF(fileName.take(120))
                    out.writeLong(payload.size.toLong())
                    out.writeLong(crc)
                    out.write(payload)
                    out.flush()
                }
                onComplete(true, "Bluetooth · dataset envoyé · ${payload.size / 1024} Ko.")
            } catch (e: Exception) {
                onComplete(false, "Bluetooth · envoi impossible : ${e.message ?: e.javaClass.simpleName}.")
            } finally {
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startReceiver(
        onStatus: (String) -> Unit,
        onReceived: (String, ByteArray) -> Unit,
        onError: (String) -> Unit
    ) {
        stopReceiver()
        executor.execute {
            val bt = adapter()
            if (bt == null) {
                onError("Bluetooth indisponible sur cet appareil.")
                return@execute
            }
            var accepted: android.bluetooth.BluetoothSocket? = null
            try {
                val server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = server
                onStatus("Bluetooth WebCS · attente d'un autre téléphone appairé…")
                accepted = server.accept()
                try { server.close() } catch (_: Exception) { }
                serverSocket = null

                val remote = accepted.remoteDevice?.name ?: "autre téléphone"
                onStatus("Bluetooth · réception depuis $remote…")
                DataInputStream(accepted.inputStream).use { input ->
                    val magic = input.readUTF()
                    if (magic != MAGIC) throw IllegalArgumentException("protocole WebCS incompatible")
                    val fileName = input.readUTF().take(120)
                    val size = input.readLong()
                    val expectedCrc = input.readLong()
                    if (size <= 0L || size > MAX_PAYLOAD_BYTES) throw IllegalArgumentException("taille de dataset invalide")
                    val bytes = ByteArray(size.toInt())
                    input.readFully(bytes)
                    val actualCrc = CRC32().apply { update(bytes) }.value
                    if (actualCrc != expectedCrc) throw IllegalStateException("contrôle CRC invalide")
                    onReceived(fileName.ifBlank { "WebCS-Bluetooth.csv" }, bytes)
                }
            } catch (e: Exception) {
                if (serverSocket != null) onError("Bluetooth · réception impossible : ${e.message ?: e.javaClass.simpleName}.")
            } finally {
                try { accepted?.close() } catch (_: Exception) { }
                try { serverSocket?.close() } catch (_: Exception) { }
                serverSocket = null
            }
        }
    }

    fun stopReceiver() {
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
    }

    fun close() {
        stopDiscovery()
        stopReceiver()
        executor.shutdownNow()
    }

    companion object {
        private const val SERVICE_NAME = "WebCS Data"
        private const val MAGIC = "WEBCS_DATA_V1"
        private const val MAX_PAYLOAD_BYTES = 20 * 1024 * 1024L
        private val SERVICE_UUID: UUID = UUID.fromString("6a5df81a-0c2f-4d6c-a4b7-8f7db0ef4c51")
    }
}
