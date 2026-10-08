package com.sonalika.chassisscan

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
    private lateinit var analysisExecutor: ExecutorService
    private var camera: Camera? = null
    private var torchOn = false

    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
            .build()
    )
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // State shared with the analysis thread
    @Volatile private var stampMode = false
    @Volatile private var qrScanning = true
    @Volatile private var stampFramesLeft = 0
    @Volatile private var viewW = 0
    @Volatile private var viewH = 0
    private val stampReads = mutableListOf<Chassis.OcrPick>()

    private var qrFields: List<Field> = emptyList()

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status("Camera permission is needed to scan. Allow it in Settings > Apps > Chassis Scan.", true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        analysisExecutor = Executors.newSingleThreadExecutor()

        b.previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> viewW = v.width; viewH = v.height }

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(id == b.btnModeStamp.id)
        }
        b.btnRead.setOnClickListener { readStampNow() }
        b.btnAgain.setOnClickListener { setMode(stampMode) }
        b.btnTorch.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            b.btnTorch.text = if (torchOn) "Light off" else "Light on"
        }
        b.previewView.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
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
        b.btnCopyQr.setOnClickListener { copy(Chassis.norm(b.qrVal.text.toString())) }
        b.btnCopySt.setOnClickListener { copy(Chassis.norm(b.stVal.text.toString())) }
        b.btnSave.setOnClickListener { saveRecord() }
        b.btnClear.setOnClickListener {
            b.qrVal.setText(""); b.stVal.setText(""); showFields(emptyList()); status("")
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
        analysisExecutor.shutdown()
        barcodeScanner.close()
        textRecognizer.close()
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
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
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
        val wantQr = !stampMode && qrScanning
        val wantStamp = stampMode && stampFramesLeft > 0
        if (!wantQr && !wantStamp) { proxy.close(); return }

        val bmp: Bitmap
        try {
            val raw = proxy.toBitmap()
            val rot = proxy.imageInfo.rotationDegrees
            bmp = if (rot == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
                Matrix().apply { postRotate(rot.toFloat()) }, true)
        } catch (e: Exception) {
            if (wantStamp) {
                stampFramesLeft -= 1
                if (stampFramesLeft <= 0) runOnUiThread { finishStampRead() }
            }
            return
        } finally {
            proxy.close()
        }

        if (wantQr) {
            try {
                val codes = Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bmp, 0)))
                val value = codes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                if (value != null && qrScanning && !stampMode) {
                    qrScanning = false
                    runOnUiThread { onQr(value) }
                }
            } catch (_: Exception) {}
        } else {
            try {
                val crop = cropToGuide(bmp)
                val text = Tasks.await(textRecognizer.process(InputImage.fromBitmap(crop, 0)))
                val lines = text.textBlocks.flatMap { blk -> blk.lines.map { it.text } }
                val pick = Chassis.pickFromOcr(lines)
                synchronized(stampReads) { if (pick != null) stampReads.add(pick) }
            } catch (_: Exception) {}
            stampFramesLeft -= 1
            if (stampFramesLeft <= 0) runOnUiThread { finishStampRead() }
        }
    }

    /** Crops the analysis frame to the yellow box the user sees (preview uses FILL_CENTER). */
    private fun cropToGuide(bmp: Bitmap): Bitmap {
        val vw = viewW.toFloat(); val vh = viewH.toFloat()
        if (vw <= 0f || vh <= 0f) return bmp
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val cw = (vw * GuideView.STAMP_W * 1.06f / scale).coerceAtMost(bmp.width.toFloat())
        val ch = (vh * GuideView.STAMP_H * 1.15f / scale).coerceAtMost(bmp.height.toFloat())
        val x = ((bmp.width - cw) / 2).toInt().coerceAtLeast(0)
        val y = ((bmp.height - ch) / 2).toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(bmp, x, y, cw.toInt().coerceAtMost(bmp.width - x), ch.toInt().coerceAtMost(bmp.height - y))
    }

    // ---------- modes ----------

    private fun setMode(stamp: Boolean) {
        stampMode = stamp
        b.guideView.stampMode = stamp
        b.btnAgain.visibility = View.GONE
        b.btnRead.visibility = if (stamp) View.VISIBLE else View.GONE
        b.btnRead.isEnabled = true
        if (stamp) {
            qrScanning = false
            status("Fit only the stamped number inside the box, light it from the side, then tap Read number.")
        } else {
            qrScanning = true
            status("Point at the QR label. It reads automatically.")
        }
    }

    // ---------- QR ----------

    private fun onQr(raw: String) {
        b.previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        b.btnAgain.visibility = View.VISIBLE
        qrFields = Chassis.parsePayload(raw)
        val picked = Chassis.pickFromQr(qrFields, raw)
        if (picked != null) {
            b.qrVal.setText(picked)
            status("QR read. Chassis number picked. Tap a field below if it chose the wrong one.")
        } else {
            b.qrVal.setText("")
            status("QR read, but no chassis field found. Tap the right field below.", true)
        }
        showFields(qrFields)
    }

    private fun showFields(fields: List<Field>) {
        b.qrFields.removeAllViews()
        b.qrFieldsLabel.visibility = if (fields.size > 1) View.VISIBLE else View.GONE
        if (fields.size <= 1) return
        val current = Chassis.norm(b.qrVal.text.toString())
        fields.forEach { f ->
            val chip = Chip(this).apply {
                text = if (f.key.isNotEmpty()) "${f.key}: ${f.value}" else f.value
                isCheckable = true
                isChecked = current.isNotEmpty() && Chassis.norm(f.value) == current
                setOnClickListener { b.qrVal.setText(Chassis.norm(f.value)) }
            }
            b.qrFields.addView(chip)
        }
    }

    // ---------- stamped number ----------

    private fun readStampNow() {
        synchronized(stampReads) { stampReads.clear() }
        b.btnRead.isEnabled = false
        status("Reading… hold steady.")
        stampFramesLeft = STAMP_FRAMES
    }

    /** Votes across several frames so one blurry frame does not decide the result. */
    private fun finishStampRead() {
        b.btnRead.isEnabled = true
        val reads = synchronized(stampReads) { stampReads.toList() }
        if (reads.isEmpty()) {
            status("No number found. Move closer, fill the box with the number, light it from the side, and try again. You can also type it.", true)
            return
        }
        val groups = reads.groupBy { it.value }
        val best = groups.entries.maxWith(compareBy<Map.Entry<String, List<Chassis.OcrPick>>> { it.value.size }
            .thenBy { e -> e.value.maxOf { it.score } }).key
        val agree = groups[best]!!.size
        b.stVal.setText(best)
        b.previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val sure = agree >= (STAMP_FRAMES + 1) / 2
        status("Read $agree of $STAMP_FRAMES frames the same. " +
                if (sure) "Check it against the frame." else "Unsure: check each character, or read again.", !sure)
    }

    // ---------- compare ----------

    private fun refresh() {
        val a = Chassis.norm(b.qrVal.text.toString())
        val s = Chassis.norm(b.stVal.text.toString())
        b.qrChecks.text = checksText(a)
        b.stChecks.text = checksText(s)
        if (a.isNotEmpty() && s.isNotEmpty()) {
            val same = a == s
            b.verdict.text = if (same) "Match: QR label and stamped number are the same." else "Mismatch: " + Chassis.diff(a, s)
            b.verdict.setTextColor(color(if (same) R.color.ok else R.color.bad))
        } else {
            b.verdict.text = "Scan both to compare."
            b.verdict.setTextColor(color(R.color.muted))
        }
    }

    private fun checksText(v: String): CharSequence {
        val sb = SpannableStringBuilder()
        Chassis.checks(v).forEachIndexed { i, (t, bad) ->
            if (i > 0) sb.append(" · ")
            val start = sb.length
            sb.append(t)
            val c = when (bad) { true -> R.color.bad; false -> R.color.ok; null -> R.color.muted }
            sb.setSpan(ForegroundColorSpan(color(c)), start, sb.length, 0)
        }
        return sb
    }

    // ---------- records ----------

    private fun prefs() = getSharedPreferences("records", MODE_PRIVATE)

    private fun loadRecords(): JSONArray =
        try { JSONArray(prefs().getString(KEY, "[]")) } catch (_: Exception) { JSONArray() }

    private fun saveRecord() {
        val qr = Chassis.norm(b.qrVal.text.toString())
        val st = Chassis.norm(b.stVal.text.toString())
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
        if (qr.isEmpty() || st.isEmpty()) "-" else if (qr == st) "MATCH" else "MISMATCH"

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

    private fun shareCsv() {
        val list = loadRecords()
        if (list.length() == 0) { status("No records to share.", true); return }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("time,qr_chassis,stamped_chassis,result\n")
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val qr = r.optString("qr"); val st = r.optString("st")
            sb.append(fmt.format(Date(r.optLong("t")))).append(',').append(qr).append(',').append(st)
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

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun status(t: String, err: Boolean = false) {
        b.status.text = t
        b.status.setTextColor(color(if (err) R.color.bad else R.color.muted))
    }

    companion object {
        private const val KEY = "list"
        private const val STAMP_FRAMES = 5
    }
}
