package com.sonalika.chassisscan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.sonalika.chassisscan.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var worker: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false
    private var tone: ToneGenerator? = null

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // State shared with the worker thread
    @Volatile private var stampMode = false
    @Volatile private var vertical = false
    @Volatile private var qrScanning = true
    @Volatile private var stampFramesLeft = 0
    @Volatile private var photoBusy = false
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    private val attempts = mutableListOf<List<String>>()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status("Camera permission is needed to scan. You can still use From photo.", true)
    }
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) readPhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        worker = Executors.newSingleThreadExecutor()

        b.previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> viewW = v.width; viewH = v.height }

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(id == b.btnModeStamp.id)
        }
        b.btnRead.setOnClickListener { readStampNow() }
        b.btnAgain.setOnClickListener { setMode(stampMode) }
        b.btnOrient.setOnClickListener {
            vertical = !vertical
            b.guideView.vertical = vertical
            b.btnOrient.text = if (vertical) "Box: horizontal" else "Box: vertical"
        }
        b.btnPhoto.setOnClickListener { pickPhoto.launch("image/*") }
        b.btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            b.btnTorch.text = if (torchOn) "Light off" else "Light on"
        }

        // Pinch to zoom, tap to focus
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val cam = camera ?: return true
                val z = cam.cameraInfo.zoomState.value ?: return true
                cam.cameraControl.setZoomRatio((z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio))
                return true
            }
        })
        b.previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scale.isInProgress && e.pointerCount == 1) {
                val pt = b.previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
                v.performClick()
            }
            true
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        }
        b.qrVal.addTextChangedListener(watcher)
        b.stVal.addTextChangedListener(watcher)
        b.btnCopyQr.setOnClickListener { copy(b.qrVal.text.toString().trim()) }
        b.btnCopySt.setOnClickListener { copy(b.stVal.text.toString().trim()) }
        b.btnSave.setOnClickListener { saveRecord() }
        b.btnClear.setOnClickListener {
            b.qrVal.setText(""); b.stVal.setText("")
            showChips(b.qrFields, b.qrFieldsLabel, emptyList(), b.qrVal)
            showChips(b.stFields, b.stFieldsLabel, emptyList(), b.stVal)
            status("")
        }
        b.btnShare.setOnClickListener { shareCsv() }
        b.btnDeleteAll.setOnClickListener {
            AlertDialog.Builder(this).setMessage("Delete all saved records?")
                .setPositiveButton("Delete") { _, _ -> prefs().edit().remove(KEY).apply(); renderRecords() }
                .setNegativeButton("Cancel", null).show()
        }

        setMode(false)
        refresh()
        renderRecords()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
        barcodeScanner.close()
        textRecognizer.close()
        tone?.release()
    }

    // ---------- camera ----------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(worker) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                b.btnTorch.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                status("Could not start the camera: ${e.message}", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val wantQr = !stampMode && qrScanning && !photoBusy
        val wantStamp = stampMode && stampFramesLeft > 0 && !photoBusy
        if (!wantQr && !wantStamp) { proxy.close(); return }

        val bmp: Bitmap? = try {
            ImageTools.rotate(proxy.toBitmap(), proxy.imageInfo.rotationDegrees)
        } catch (_: Exception) { null } finally { proxy.close() }

        if (wantQr) {
            if (bmp == null) return
            val value = decodeQr(bmp)
            if (value != null && qrScanning && !stampMode) {
                qrScanning = false
                runOnUiThread { onQr(value) }
            }
        } else {
            if (bmp != null) {
                val rotations = if (vertical) listOf(90, 270) else listOf(0)
                val got = recognize(cropToGuide(bmp), rotations)
                synchronized(attempts) { attempts.addAll(got) }
            }
            stampFramesLeft -= 1
            if (stampFramesLeft <= 0) runOnUiThread { finishStampRead("camera") }
        }
    }

    private fun decodeQr(bmp: Bitmap): String? = try {
        Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bmp, 0)))
            .firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
    } catch (_: Exception) { null }

    /**
     * Runs text recognition on each rotation, on both the plain and the contrast-enhanced image.
     * Returns one list of lines per pass.
     */
    private fun recognize(src: Bitmap, rotations: List<Int>): List<List<String>> {
        val out = mutableListOf<List<String>>()
        val sized = ImageTools.sizeForOcr(src)
        val versions = listOf(sized, ImageTools.enhance(sized))
        for (rot in rotations) {
            for (v in versions) {
                try {
                    val img = ImageTools.rotate(v, rot)
                    val text = Tasks.await(textRecognizer.process(InputImage.fromBitmap(img, 0)))
                    out.add(text.textBlocks.flatMap { blk -> blk.lines.map { it.text } })
                } catch (_: Exception) {}
            }
        }
        return out
    }

    /** Crops the analysis frame to the yellow box the user sees (preview uses FILL_CENTER). */
    private fun cropToGuide(bmp: Bitmap): Bitmap {
        val vw = viewW.toFloat(); val vh = viewH.toFloat()
        if (vw <= 0f || vh <= 0f) return bmp
        val (fw, fh) = b.guideView.bandFractions()
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val cw = (vw * fw * 1.08f / scale).coerceAtMost(bmp.width.toFloat())
        val ch = (vh * fh * 1.08f / scale).coerceAtMost(bmp.height.toFloat())
        val x = ((bmp.width - cw) / 2).toInt().coerceAtLeast(0)
        val y = ((bmp.height - ch) / 2).toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(bmp, x, y, cw.toInt().coerceIn(1, bmp.width - x), ch.toInt().coerceIn(1, bmp.height - y))
    }

    // ---------- modes ----------

    private fun setMode(stamp: Boolean) {
        stampMode = stamp
        b.guideView.stampMode = stamp
        b.btnAgain.visibility = View.GONE
        b.btnRead.visibility = if (stamp) View.VISIBLE else View.GONE
        b.btnOrient.visibility = if (stamp) View.VISIBLE else View.GONE
        b.btnRead.isEnabled = true
        if (stamp) {
            qrScanning = false
            status("Fit only the number inside the box. Pinch to zoom, tap to focus, light from the side, then tap Read number.")
        } else {
            qrScanning = true
            status("Point at the QR label. It reads automatically.")
        }
    }

    // ---------- QR ----------

    private fun onQr(raw: String) {
        beep()
        b.btnAgain.visibility = View.VISIBLE
        val fields = Chassis.parsePayload(raw)
        val picked = Chassis.pickFromQr(fields, raw)
        if (picked != null) {
            b.qrVal.setText(picked)
            status(if (fields.size > 1) "QR read. Tap a field below if it chose the wrong one." else "QR read.")
        } else {
            b.qrVal.setText("")
            status("QR read. Tap the field that is the chassis number.", true)
        }
        showChips(b.qrFields, b.qrFieldsLabel,
            if (fields.size > 1) fields.map { (if (it.key.isNotEmpty()) "${it.key}: " else "") + it.value to it.value.trim() } else emptyList(),
            b.qrVal)
    }

    /** Shows options as chips; tapping one puts its value in the target box. Items are (label, value). */
    private fun showChips(group: ChipGroup, label: View, items: List<Pair<String, String>>, target: android.widget.EditText) {
        group.removeAllViews()
        label.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        val current = target.text.toString().trim()
        items.forEach { (text, value) ->
            group.addView(Chip(this).apply {
                this.text = text
                isCheckable = true
                isChecked = value == current
                setOnClickListener { target.setText(value) }
            })
        }
    }

    // ---------- stamped number ----------

    private fun readStampNow() {
        synchronized(attempts) { attempts.clear() }
        b.btnRead.isEnabled = false
        status("Reading… hold steady.")
        stampFramesLeft = STAMP_FRAMES
    }

    private fun readPhoto(uri: Uri) {
        if (photoBusy) return
        photoBusy = true
        b.btnPhoto.isEnabled = false
        status("Reading the photo…")
        val qr = !stampMode
        worker.execute {
            val bmp = ImageTools.load(this, uri)
            if (bmp == null) {
                runOnUiThread { photoBusy = false; b.btnPhoto.isEnabled = true; status("Could not open that photo.", true) }
                return@execute
            }
            if (qr) {
                val v = decodeQr(bmp)
                runOnUiThread {
                    photoBusy = false; b.btnPhoto.isEnabled = true
                    if (v != null) { qrScanning = false; onQr(v) } else status("No QR code found in that photo.", true)
                }
            } else {
                // Photo direction is unknown, so try all four.
                val got = recognize(bmp, listOf(0, 90, 180, 270))
                synchronized(attempts) { attempts.clear(); attempts.addAll(got) }
                runOnUiThread { photoBusy = false; b.btnPhoto.isEnabled = true; finishStampRead("photo") }
            }
        }
    }

    /** Picks the reading most passes agreed on; the rest are offered as chips. */
    private fun finishStampRead(source: String) {
        b.btnRead.isEnabled = true
        val ranked = synchronized(attempts) { Chassis.rank(attempts.toList()) to attempts.size }
        val list = ranked.first; val passes = ranked.second
        if (list.isEmpty()) {
            showChips(b.stFields, b.stFieldsLabel, emptyList(), b.stVal)
            status(if (source == "photo") "No number found in that photo. Try a closer, sharper photo, or type it."
                   else "No number found. Zoom in, fill the box with the number, light it from the side, and try again. You can also type it.", true)
            return
        }
        val best = list[0]
        b.stVal.setText(best.first)
        showChips(b.stFields, b.stFieldsLabel, list.take(8).map { "${it.first}  (${it.second})" to it.first }, b.stVal)
        beep()
        val strong = best.second >= 2
        status("Read \"${best.first}\" in ${best.second} of $passes passes. " +
                if (strong) "Check it against the part." else "Unsure: check it, tap another reading below, or read again.", !strong)
    }

    // ---------- compare ----------

    private fun refresh() {
        val qrRaw = b.qrVal.text.toString().trim(); val stRaw = b.stVal.text.toString().trim()
        b.qrChecks.text = if (qrRaw.isEmpty()) "" else "${qrRaw.length} characters"
        b.stChecks.text = if (stRaw.isEmpty()) "" else "${stRaw.length} characters"
        val a = Chassis.norm(qrRaw); val s = Chassis.norm(stRaw)
        if (a.isNotEmpty() && s.isNotEmpty()) {
            val same = a == s
            b.verdict.text = if (same) "Match: QR label and stamped number are the same." else "Mismatch: " + Chassis.diff(a, s)
            b.verdict.setTextColor(color(if (same) R.color.ok else R.color.bad))
        } else {
            b.verdict.text = "Scan both to compare."
            b.verdict.setTextColor(color(R.color.muted))
        }
    }

    // ---------- records ----------

    private fun prefs() = getSharedPreferences("records", MODE_PRIVATE)

    private fun loadRecords(): JSONArray =
        try { JSONArray(prefs().getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun saveRecord() {
        val qr = b.qrVal.text.toString().trim()
        val st = b.stVal.text.toString().trim()
        if (qr.isEmpty() && st.isEmpty()) { status("Nothing to save yet.", true); return }
        val old = loadRecords()
        val list = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("qr", qr).put("st", st))
        for (i in 0 until old.length()) list.put(old.get(i))
        prefs().edit().putString(KEY, list.toString()).apply()
        renderRecords()
        status("Record saved.")
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
    }

    private fun result(qr: String, st: String) =
        if (qr.isEmpty() || st.isEmpty()) "-" else if (Chassis.norm(qr) == Chassis.norm(st)) "MATCH" else "MISMATCH"

    private fun renderRecords() {
        val list = loadRecords()
        if (list.length() == 0) { b.records.text = "No records yet."; return }
        val fmt = SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val qr = r.optString("qr"); val st = r.optString("st")
            sb.append(fmt.format(Date(r.optLong("t")))).append("  ").append(result(qr, st)).append('\n')
            sb.append("  QR: ").append(qr.ifEmpty { "-" }).append('\n')
            sb.append("  ST: ").append(st.ifEmpty { "-" }).append('\n')
        }
        b.records.text = sb.trimEnd()
    }

    private fun csv(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun shareCsv() {
        val list = loadRecords()
        if (list.length() == 0) { status("No records to share.", true); return }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("time,qr_chassis,stamped_chassis,result\n")
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val qr = r.optString("qr"); val st = r.optString("st")
            sb.append(fmt.format(Date(r.optLong("t")))).append(',').append(csv(qr)).append(',').append(csv(st))
                .append(',').append(result(qr, st).lowercase()).append('\n')
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Chassis scan records")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }, "Share records"))
    }

    // ---------- helpers ----------

    private fun copy(v: String) {
        if (v.isEmpty()) return
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("chassis", v))
        Toast.makeText(this, "Copied $v", Toast.LENGTH_SHORT).show()
    }

    /** Scanner beep plus a short vibration when a value has been read. */
    private fun beep() {
        b.previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (_: Exception) {}
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun status(t: String, err: Boolean = false) {
        b.status.text = t
        b.status.setTextColor(color(if (err) R.color.bad else R.color.muted))
    }

    companion object {
        private const val KEY = "list"
        private const val STAMP_FRAMES = 3
    }
}
