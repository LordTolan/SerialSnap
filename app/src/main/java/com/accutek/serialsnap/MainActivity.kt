package com.accutek.serialsnap

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.accutek.serialsnap.databinding.ActivityMainBinding
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceRecord(
    var serial: String,
    var site: String,
    var type: String,
    var location: String,
    val capturedAt: Long,
    var replacesSerial: String = ""
)

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var photoUri: Uri? = null
    private var pendingNewSerial: String? = null
    private val records = mutableListOf<DeviceRecord>()
    private val deviceTypes = listOf("SolarEdge Power Optimizer", "SolarEdge Inverter", "Enphase Microinverter", "Other Solar Device")

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::scanImage) else binding.status.text = "Picture canceled."
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else toast("Camera permission is required.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.deviceType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, deviceTypes)
        loadRecords(); loadLastContext(); render()
        binding.scanButton.setOnClickListener { requestCamera() }
        binding.manualButton.setOnClickListener { showDetectedEditor(emptyList()) }
        binding.replacementMode.setOnCheckedChangeListener { _, enabled ->
            pendingNewSerial = null
            updateReplacementUi(enabled)
        }
        binding.exportButton.setOnClickListener { exportCsv() }
        binding.clearButton.setOnClickListener { confirmClear() }
        binding.serialList.setOnItemClickListener { _, _, position, _ -> editRecord(position) }
        binding.serialList.setOnItemLongClickListener { _, _, position, _ -> confirmDelete(position); true }
    }

    private fun requestCamera() {
        saveLastContext()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun openCamera() {
        val file = File(File(cacheDir, "photos").apply { mkdirs() }, "device_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        photoUri = uri
        takePicture.launch(uri)
    }

    private fun scanImage(uri: Uri) {
        binding.status.text = "Reading barcode and printed text…"
        val image = InputImage.fromFilePath(this, uri)
        BarcodeScanning.getClient().process(image).addOnSuccessListener { barcodes ->
            val codes = barcodes.mapNotNull { it.rawValue }.map(::clean).filter(::likelySerial)
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
                .addOnSuccessListener {
                    val candidates = (codes + extractSerials(it.text)).distinct()
                    binding.status.text = if (candidates.isEmpty()) "No serial found—enter it manually." else "Found ${candidates.size} possible serial(s)."
                    showDetectedEditor(candidates)
                }.addOnFailureListener { showDetectedEditor(codes) }
        }.addOnFailureListener { runTextOnly(image) }
    }

    private fun runTextOnly(image: InputImage) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { showDetectedEditor(extractSerials(it.text)) }
            .addOnFailureListener { binding.status.text = "Could not read the label. Try closer with even lighting." }
    }

    private fun extractSerials(text: String): List<String> {
        val labeled = Regex("(?i)(?:S[/\\\\]?N|SERIAL(?:\\s*(?:NO|NUMBER))?)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9._/-]{3,})")
            .findAll(text).map { clean(it.groupValues[1]) }.toList()
        val tokens = text.lines().flatMap { line ->
            Regex("[A-Z0-9][A-Z0-9._/-]{5,}", RegexOption.IGNORE_CASE).findAll(line).map { clean(it.value) }
        }.filter(::likelySerial)
        return (labeled + tokens).distinct().take(12)
    }

    private fun likelySerial(value: String): Boolean {
        if (value.length !in 6..40 || value.contains("HTTP")) return false
        return value.count(Char::isDigit) >= 4 && value.all { it.isLetterOrDigit() || it in "-_/." }
    }
    private fun clean(value: String) = value.trim().trim('.', ':', '-', '#').replace(" ", "").uppercase(Locale.US)

    private fun showDetectedEditor(found: List<String>) {
        val input = EditText(this).apply { setText(found.joinToString("\n")); hint = "One serial number per line"; minLines = 4 }
        val replacement = binding.replacementMode.isChecked
        val scanningOld = replacement && pendingNewSerial != null
        val title = when {
            scanningOld -> "Verify OLD serial"
            replacement -> "Verify NEW serial"
            else -> "Verify serial numbers"
        }
        val message = if (replacement) "Keep exactly one serial number for this device." else "Remove wrong results or correct characters before saving."
        AlertDialog.Builder(this).setTitle(title)
            .setMessage(message)
            .setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton(when { scanningOld -> "Save Replacement"; replacement -> "Continue"; else -> "Save" }) { _, _ ->
                if (replacement) handleReplacement(input.text.lines()) else addSerials(input.text.lines())
            }.show()
    }

    private fun handleReplacement(raw: List<String>) {
        val values = raw.map(::clean).filter { it.isNotBlank() }.distinct()
        if (values.size != 1) {
            binding.status.text = "Replacement mode requires exactly one serial per scan. Please try again."
            return
        }
        val serial = values.first()
        if (pendingNewSerial == null) {
            if (records.any { it.serial == serial }) {
                binding.status.text = "That new serial is already in the list."
                return
            }
            pendingNewSerial = serial
            updateReplacementUi(true)
            binding.status.text = "New device: $serial. Now scan the OLD device being replaced."
            requestCamera()
        } else {
            val newSerial = pendingNewSerial!!
            if (newSerial == serial) {
                binding.status.text = "The old and new serial numbers cannot be the same."
                return
            }
            records.add(DeviceRecord(
                newSerial,
                binding.siteName.text.toString().trim(),
                binding.deviceType.selectedItem.toString(),
                binding.locationNote.text.toString().trim(),
                System.currentTimeMillis(),
                serial
            ))
            pendingNewSerial = null
            saveRecords(); saveLastContext(); render(); updateReplacementUi(true)
            binding.status.text = "Replacement saved: NEW $newSerial replaces OLD $serial."
        }
    }

    private fun updateReplacementUi(enabled: Boolean) {
        binding.scanButton.text = when {
            !enabled -> "SCAN NEXT DEVICE"
            pendingNewSerial == null -> "SCAN NEW REPLACEMENT"
            else -> "SCAN OLD DEVICE"
        }
        if (enabled && pendingNewSerial == null) binding.status.text = "Replacement mode: scan the NEW device first."
        if (!enabled) binding.status.text = "Scan the barcode or printed serial on each device label."
    }

    private fun addSerials(raw: List<String>) {
        val values = raw.map(::clean).filter { it.isNotBlank() }.distinct()
        val existing = records.map { it.serial }.toSet()
        val newValues = values.filterNot(existing::contains)
        val site = binding.siteName.text.toString().trim()
        val type = binding.deviceType.selectedItem.toString()
        val location = binding.locationNote.text.toString().trim()
        newValues.forEach { records.add(DeviceRecord(it, site, type, location, System.currentTimeMillis())) }
        saveRecords(); saveLastContext(); render()
        binding.status.text = "Saved ${newValues.size}; skipped ${values.size - newValues.size} duplicate(s)."
        if (newValues.isNotEmpty()) requestCamera()
    }

    private fun editRecord(position: Int) {
        val record = records[position]
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 0, 36, 0) }
        val serial = EditText(this).apply { hint = "Serial"; setText(record.serial) }
        val site = EditText(this).apply { hint = "Site"; setText(record.site) }
        val type = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, deviceTypes)
            setSelection(deviceTypes.indexOf(record.type).coerceAtLeast(0))
        }
        val location = EditText(this).apply { hint = "Location"; setText(record.location) }
        val replaces = EditText(this).apply { hint = "Old serial replaced (optional)"; setText(record.replacesSerial) }
        box.addView(serial); box.addView(site); box.addView(type); box.addView(location); box.addView(replaces)
        AlertDialog.Builder(this).setTitle("Edit device").setView(box).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                record.serial = clean(serial.text.toString()); record.site = site.text.toString().trim()
                record.type = type.selectedItem.toString(); record.location = location.text.toString().trim()
                record.replacesSerial = clean(replaces.text.toString())
                saveRecords(); render()
            }.show()
    }

    private fun confirmDelete(position: Int) {
        AlertDialog.Builder(this).setTitle("Delete ${records[position].serial}?")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> records.removeAt(position); saveRecords(); render() }.show()
    }
    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Clear every device?").setMessage("Export first if you need a backup.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> records.clear(); saveRecords(); render() }.show()
    }

    private fun exportCsv() {
        if (records.isEmpty()) return toast("There are no records to export.")
        val safeSite = binding.siteName.text.toString().ifBlank { "all-sites" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(cacheDir, "SerialSnap_${safeSite}_${dateStamp()}.csv")
        val rows = mutableListOf("New/Installed Serial,Replaces Old Serial,Replacement,Device Type,Site,Location,Captured At")
        records.forEach { r -> rows += listOf(r.serial, r.replacesSerial, if (r.replacesSerial.isBlank()) "No" else "Yes", r.type, r.site, r.location, dateTime(r.capturedAt)).joinToString(",") { csv(it) } }
        file.writeText(rows.joinToString("\n"))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share serial-number list"))
    }

    private fun render() {
        binding.count.text = "${records.size} device${if (records.size == 1) "" else "s"} — tap to edit, hold to delete"
        binding.serialList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_2, android.R.id.text1,
            records.map { "${it.serial}${if (it.replacesSerial.isBlank()) "" else "  ← replaces ${it.replacesSerial}"}\n${it.type} • ${it.site.ifBlank { "No site" }}${if (it.location.isBlank()) "" else " • ${it.location}"}" })
    }

    private fun saveRecords() {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("serial", r.serial).put("site", r.site).put("type", r.type).put("location", r.location).put("capturedAt", r.capturedAt).put("replacesSerial", r.replacesSerial)) }
        prefs().edit().putString("records_v2", array.toString()).apply()
    }
    private fun loadRecords() {
        val saved = prefs().getString("records_v2", null)
        saved?.let { json -> runCatching {
            val array = JSONArray(json)
            for (i in 0 until array.length()) array.getJSONObject(i).let { o ->
                records += DeviceRecord(o.getString("serial"), o.optString("site"), o.optString("type", "Other Solar Device"), o.optString("location"), o.optLong("capturedAt"), o.optString("replacesSerial"))
            }
        } }
        if (saved == null) {
            val oldSerials = getSharedPreferences("serials", MODE_PRIVATE).getStringSet("items", emptySet()).orEmpty()
            oldSerials.sorted().forEach { records += DeviceRecord(it, "", "Other Solar Device", "", System.currentTimeMillis()) }
            if (oldSerials.isNotEmpty()) saveRecords()
        }
    }

    private fun saveLastContext() = prefs().edit().putString("site", binding.siteName.text.toString()).putInt("type", binding.deviceType.selectedItemPosition).putString("location", binding.locationNote.text.toString()).apply()
    private fun loadLastContext() { binding.siteName.setText(prefs().getString("site", "")); binding.deviceType.setSelection(prefs().getInt("type", 0)); binding.locationNote.setText(prefs().getString("location", "")) }
    private fun prefs() = getSharedPreferences("serialsnap", MODE_PRIVATE)
    private fun dateTime(time: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
    private fun dateStamp() = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
    private fun csv(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
