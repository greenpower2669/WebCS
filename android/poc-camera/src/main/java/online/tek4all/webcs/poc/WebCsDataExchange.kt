package online.tek4all.webcs.poc

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.sqrt

class WebCsDataExchange(context: Context) {
    data class ImportResult(val ok: Boolean, val message: String, val eventCount: Int = 0)
    data class TransferPayload(val fileName: String, val bytes: ByteArray)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("webcs", Context.MODE_PRIVATE)

    private fun trainingDir(): File = File(appContext.filesDir, "webcs-training").apply { mkdirs() }
    private fun importsDir(): File = File(appContext.filesDir, "webcs-imports").apply { mkdirs() }

    fun importCsv(uri: Uri): ImportResult {
        return try {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { readBounded(it) }
                ?: return ImportResult(false, "Import impossible : fichier illisible.")
            importCsvBytes(uri.lastPathSegment ?: "WebCS-import.csv", bytes)
        } catch (e: Exception) {
            ImportResult(false, "Import impossible : ${e.message ?: "erreur inconnue"}.")
        }
    }

    fun importCsvBytes(displayName: String, bytes: ByteArray): ImportResult {
        if (bytes.isEmpty()) return ImportResult(false, "Import refusé : fichier vide.")
        if (bytes.size > MAX_IMPORT_BYTES) return ImportResult(false, "Import refusé : fichier supérieur à 20 Mo.")

        val text = bytes.toString(Charsets.UTF_8)
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return ImportResult(false, "Import refusé : CSV vide.")

        val header = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val required = listOf("schema_version", "session_id", "seq", "event", "r", "g", "b", "target_profile")
        val missing = required.filterNot { it in header }
        if (missing.isNotEmpty()) {
            return ImportResult(false, "Import refusé : ce n'est pas un CSV WebCS compatible (${missing.joinToString()}).")
        }

        val eventCount = (lines.size - 1).coerceAtLeast(0)
        val safe = sanitizeName(displayName).ifBlank { "WebCS-import.csv" }
        val finalName = if (safe.lowercase().endsWith(".csv")) safe else "$safe.csv"
        val target = File(importsDir(), "${System.currentTimeMillis()}-$finalName")
        return try {
            target.writeBytes(bytes)
            prefs.edit().putString(PREF_LATEST_IMPORT, target.absolutePath).apply()
            ImportResult(true, "Import WebCS OK · $eventCount événements · ${target.name}", eventCount)
        } catch (e: Exception) {
            ImportResult(false, "Import impossible à sauvegarder : ${e.message ?: "erreur"}.")
        }
    }

    fun currentSessionPayload(): TransferPayload? {
        val file = trainingDir().listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return try {
            TransferPayload(file.name, file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    fun importedSummary(): String {
        val files = importsDir().listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (files.isEmpty()) return "Imports : aucun dataset réimporté"
        return "Imports : ${files.size} dataset(s) · dernier ${files.first().name}"
    }

    fun reuseLatestImportedCalibration(targetProfile: String): String {
        val latestPath = prefs.getString(PREF_LATEST_IMPORT, null)
            ?: return "Aucun dataset importé à réutiliser."
        val file = File(latestPath)
        val root = importsDir().canonicalFile
        val safeFile = try { file.canonicalFile } catch (_: Exception) { return "Dataset importé introuvable." }
        if (!safeFile.path.startsWith(root.path + File.separator) || !safeFile.isFile) {
            return "Dataset importé introuvable."
        }

        val lines = try {
            safeFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        } catch (e: Exception) {
            return "Lecture import impossible : ${e.message ?: "erreur"}."
        }
        if (lines.size < 2) return "Dataset importé sans événements exploitables."

        val header = parseCsvLine(lines.first().removePrefix("\uFEFF"))
        val eventIndex = header.indexOf("event")
        val labelIndex = header.indexOf("training_label")
        val rIndex = header.indexOf("r")
        val gIndex = header.indexOf("g")
        val bIndex = header.indexOf("b")
        if (eventIndex < 0 || rIndex < 0 || gIndex < 0 || bIndex < 0) {
            return "Dataset incompatible avec la réutilisation de calibration."
        }

        val refs = mutableListOf<Triple<Double, Double, Double>>()
        for (line in lines.drop(1)) {
            val cols = parseCsvLine(line)
            if (cols.size <= maxOf(eventIndex, rIndex, gIndex, bIndex)) continue
            val event = cols[eventIndex]
            val label = if (labelIndex >= 0 && labelIndex < cols.size) cols[labelIndex] else ""
            if (event != "target_calibration" && label != "enemy_reference") continue
            val r = cols[rIndex].toDoubleOrNull() ?: continue
            val g = cols[gIndex].toDoubleOrNull() ?: continue
            val b = cols[bIndex].toDoubleOrNull() ?: continue
            refs += Triple(r, g, b)
        }

        if (refs.size < 3) {
            return "Import conservé, mais pas assez de références cible pour reconstruire la calibration (${refs.size}/3 minimum)."
        }

        val meanR = refs.map { it.first }.average()
        val meanG = refs.map { it.second }.average()
        val meanB = refs.map { it.third }.average()
        val sr = stdDev(refs.map { it.first }, meanR)
        val sg = stdDev(refs.map { it.second }, meanG)
        val sb = stdDev(refs.map { it.third }, meanB)
        val tolerance = max(32.0, 3.0 * sqrt(sr * sr + sg * sg + sb * sb)).coerceAtMost(120.0)

        prefs.edit()
            .putInt("target.$targetProfile.r", meanR.toInt().coerceIn(0, 255))
            .putInt("target.$targetProfile.g", meanG.toInt().coerceIn(0, 255))
            .putInt("target.$targetProfile.b", meanB.toInt().coerceIn(0, 255))
            .putFloat("target.$targetProfile.tolerance", tolerance.toFloat())
            .apply()

        return "Calibration réutilisée depuis l'import · ${refs.size} références · RGB ${meanR.toInt()}/${meanG.toInt()}/${meanB.toInt()} · tolérance ${tolerance.toInt()}."
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_IMPORT_BYTES) throw IllegalArgumentException("fichier supérieur à 20 Mo")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun sanitizeName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)

    private fun stdDev(values: List<Double>, mean: Double): Double {
        if (values.isEmpty()) return 0.0
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    quoted = !quoted
                }
            } else if (c == ',' && !quoted) {
                result += current.toString()
                current.setLength(0)
            } else {
                current.append(c)
            }
            i++
        }
        result += current.toString()
        return result
    }

    companion object {
        private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
        private const val PREF_LATEST_IMPORT = "data.latestImport"
    }
}
