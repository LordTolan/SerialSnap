package com.accutek.serialsnap

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.accutek.serialsnap.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var photoUri: Uri? = null
    private val serials = linkedSetOf<String>()

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(::scanImage) else binding.status.text = "Picture canceled."
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera() else Toast.makeText(this, "Camera permission is required.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loadSerials()
        binding.scanButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
            else permission.launch(Manifest.permission.CAMERA)
        }
        binding.clearButton.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Clear the list?").setMessage("This removes every saved serial number.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ -> serials.clear(); saveSerials(); render() }.show()
        }
    }

    private fun openCamera() {
        val dir = File(cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "serial_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        photoUri = uri
        takePicture.launch(uri)
    }

    private fun scanImage(uri: Uri) {
        binding.status.text = "Reading text…"
        val image = InputImage.fromFilePath(this, uri)
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image)
            .addOnSuccessListener { result ->
                val candidates = extractSerials(result.text)
                if (candidates.isEmpty()) {
                    binding.status.text = "No likely serial number found. Try closer, with better light."
                    showEditor("")
                } else showEditor(candidates.joinToString("\n"))
            }
            .addOnFailureListener { binding.status.text = "Could not read that image. Please try again." }
    }

    private fun extractSerials(text: String): List<String> {
        val labeled = Regex("(?i)(?:S[/\\\\]?N|SERIAL(?:\\s*(?:NO|NUMBER))?)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9._/-]{3,})")
            .findAll(text).map { clean(it.groupValues[1]) }.toList()
        if (labeled.isNotEmpty()) return labeled.distinct()
        return text.lines().flatMap { line -> Regex("[A-Z0-9][A-Z0-9._/-]{5,}", RegexOption.IGNORE_CASE).findAll(line).map { clean(it.value) } }
            .filter { token -> token.any(Char::isLetter) && token.any(Char::isDigit) && token.length in 6..40 }
            .distinct().take(8)
    }

    private fun clean(value: String) = value.trim('.', ':', '-', '#').uppercase(Locale.US)

    private fun showEditor(found: String) {
        val input = EditText(this).apply { setText(found); hint = "One serial number per line"; minLines = 4 }
        AlertDialog.Builder(this).setTitle("Verify serial numbers").setMessage("Correct the results, then tap Add.")
            .setView(input).setNegativeButton("Cancel") { _, _ -> binding.status.text = "Nothing added." }
            .setPositiveButton("Add") { _, _ ->
                val values = input.text.lines().map(::clean).filter { it.isNotBlank() }
                val before = serials.size; serials.addAll(values); saveSerials(); render()
                binding.status.text = "Added ${serials.size - before}; skipped ${values.size - (serials.size - before)} duplicate(s)."
                openCamera()
            }.show()
    }

    private fun loadSerials() {
        serials.addAll(getSharedPreferences("serials", MODE_PRIVATE).getStringSet("items", emptySet())!!.sorted())
        render()
    }

    private fun saveSerials() = getSharedPreferences("serials", MODE_PRIVATE).edit().putStringSet("items", serials).apply()

    private fun render() {
        binding.count.text = "${serials.size} serial number${if (serials.size == 1) "" else "s"}"
        binding.serialList.text = if (serials.isEmpty()) "No serial numbers yet." else serials.mapIndexed { i, s -> "${i + 1}.  $s" }.joinToString("\n")
    }
}
