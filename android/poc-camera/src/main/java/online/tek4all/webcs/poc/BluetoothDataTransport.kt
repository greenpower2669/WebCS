package online.tek4all.webcs.poc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.CRC32

class BluetoothDataTransport(context: Context) {
    data class PairedDevice(val name: String, val address: String)

    private val appContext = context.applicationContext
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var serverSocket: BluetoothServerSocket? = null

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
