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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var worker: ExecutorService
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var captureExec: ExecutorService
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
    @Volatile private var cloudGrab = false
    @Volatile private var photoBusy = false
    // Line mode: after a QR scan the app reads the stamp from the live camera by itself
    @Volatile private var expected: String? = null
    @Volatile private var autoUntil = 0L
    @Volatile private var autoFirstText = 0L
    @Volatile private var autoCount = 0
    private val autoReads = mutableListOf<List<String>>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
        captureExec = Executors.newSingleThreadExecutor()

        b.previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> viewW = v.width; viewH = v.height }

        b.modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) setMode(id == b.btnModeStamp.id)
        }
        b.btnRead.setOnClickListener { readStampNow() }
        b.btnAgain.setOnClickListener { if (stampMode) nextPart() else setMode(false) }
        b.btnOrient.setOnClickListener {
            setVertical(!vertical)
            prefs().edit().putBoolean("vertical", vertical).apply()
        }
        setVertical(prefs().getBoolean("vertical", false))
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
        captureExec.shutdown()
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
            // QR reading does not need big frames; keeping them small lets the phone also run full-quality photo capture.
            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            // Still photos for stamped numbers: real camera photo (sharper, less noise than a video frame).
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(3264, 2448), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(analysisSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(worker) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                try {
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                    imageCapture = capture
                } catch (_: Exception) {
                    // Older phones that can't run all three: fall back to reading video frames.
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    imageCapture = null
                }
                b.btnTorch.visibility = if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                status("Could not start the camera: ${e.message}", true)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(proxy: ImageProxy) {
        val wantQr = !stampMode && qrScanning && !photoBusy
        val wantStamp = stampMode && (stampFramesLeft > 0 || cloudGrab || autoUntil > 0L) && !photoBusy
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
        } else if (autoUntil > 0L) {
            if (bmp != null) autoFrame(bmp)
        } else if (cloudGrab) {
            cloudGrab = false
            if (bmp == null) { runOnUiThread { b.btnRead.isEnabled = true; status("Could not grab the picture. Try again.", true) }; return }
            readCloud(listOf(cropToGuide(bmp, 1.3f)), if (vertical) "vertical" else "horizontal", "camera", if (vertical) listOf(90, 270) else listOf(0))
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
    private fun cropToGuide(bmp: Bitmap, margin: Float = 1.08f): Bitmap {
        val vw = viewW.toFloat(); val vh = viewH.toFloat()
        if (vw <= 0f || vh <= 0f) return bmp
        val (fw, fh) = b.guideView.bandFractions()
        val scale = maxOf(vw / bmp.width, vh / bmp.height)
        val cw = (vw * fw * margin / scale).coerceAtMost(bmp.width.toFloat())
        val ch = (vh * fh * margin / scale).coerceAtMost(bmp.height.toFloat())
        val x = ((bmp.width - cw) / 2).toInt().coerceAtLeast(0)
        val y = ((bmp.height - ch) / 2).toInt().coerceAtLeast(0)
        return Bitmap.createBitmap(bmp, x, y, cw.toInt().coerceIn(1, bmp.width - x), ch.toInt().coerceIn(1, bmp.height - y))
    }

    // ---------- modes ----------

    private fun setMode(stamp: Boolean) {
        stampMode = stamp
        b.guideView.stampMode = stamp
        b.btnAgain.visibility = View.GONE
        b.shot.visibility = View.GONE
        b.btnRead.visibility = if (stamp) View.VISIBLE else View.GONE
        b.btnOrient.visibility = if (stamp) View.VISIBLE else View.GONE
        b.btnRead.isEnabled = true
        b.btnRead.text = if (expected != null) "Read again" else "Take photo"
        if (stamp) {
            qrScanning = false
            status("Fit the number inside the box. Pinch to zoom, hold steady, then tap Take photo.")
        } else {
            autoUntil = 0L
            b.btnAgain.text = "Scan again"
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
            expected = picked
            showChips(b.qrFields, b.qrFieldsLabel,
                if (fields.size > 1) fields.map { (if (it.key.isNotEmpty()) "${it.key}: " else "") + it.value to it.value.trim() } else emptyList(),
                b.qrVal)
            // Line mode: go straight to reading the stamp, no tap needed.
            b.modeGroup.check(b.btnModeStamp.id)
            setMode(true)
            startAuto()
            return
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
        if (expected != null) { startAuto(); return }
        synchronized(attempts) { attempts.clear() }
        b.btnRead.isEnabled = false
        if (imageCapture != null) {
            status("Taking photo… hold steady.")
            worker.execute { captureAndRead() }
        } else if (Cloud.enabled) {
            status("Reading… hold steady.")
            cloudGrab = true
        } else {
            status("Reading on the phone… hold steady.")
            stampFramesLeft = STAMP_FRAMES
        }
    }

    /**
     * Runs on the worker thread. Asks the cloud reader first; if there is no internet or the
     * server fails, falls back to the on-phone reader with the same picture.
     */
    private fun readCloud(imgs: List<Bitmap>, orientation: String, source: String, localRotations: List<Int>) {
        if (Cloud.enabled) {
            try {
                val r = Cloud.read(imgs, orientation)
                runOnUiThread { showCloudResult(r, source) }
                return
            } catch (e: Exception) {
                runOnUiThread { status("Cloud reader not reachable (${e.message ?: "error"}). Reading on the phone instead…", true) }
            }
        }
        val got = imgs.flatMap { recognize(it, localRotations) }
        synchronized(attempts) { attempts.clear(); attempts.addAll(got) }
        runOnUiThread { finishStampRead(source, offline = Cloud.enabled) }
    }

    // ---------- line mode ----------

    private fun setVertical(v: Boolean) {
        vertical = v
        b.guideView.vertical = v
        b.btnOrient.text = if (v) "Box: horizontal" else "Box: vertical"
    }

    /** Starts reading the stamp from the live camera; stops by itself on a result. */
    private fun startAuto() {
        synchronized(autoReads) { autoReads.clear() }
        autoCount = 0
        autoFirstText = 0L
        autoUntil = System.currentTimeMillis() + AUTO_MAX_MS
        b.btnRead.isEnabled = false
        b.btnAgain.visibility = View.VISIBLE
        b.btnAgain.text = "Next part"
        b.verdict.text = "Checking…"
        b.verdict.setTextColor(color(R.color.muted))
        status(if (expected != null) "Point at the stamped number. Looking for ${expected}…" else "Point at the stamped number…")
    }

    /**
     * One live frame (worker thread). Alternates plain / contrast-boosted image and, for vertical
     * numbers, the two rotations, so a few frames cover all variants. With a QR value it stops on
     * the first frame that contains that number; the timer starts once text is first seen.
     */
    private fun autoFrame(bmp: Bitmap) {
        val now = System.currentTimeMillis()
        val n = autoCount++
        val crop = ImageTools.sizeForOcr(cropToGuide(bmp))
        val img = if (n % 2 == 0) crop else ImageTools.enhance(crop)
        val rot = if (vertical) (if ((n / 2) % 2 == 0) 90 else 270) else 0
        val lines = try {
            Tasks.await(textRecognizer.process(InputImage.fromBitmap(ImageTools.rotate(img, rot), 0)))
                .textBlocks.flatMap { blk -> blk.lines.map { it.text } }
        } catch (_: Exception) { emptyList() }
        if (autoUntil == 0L) return
        synchronized(autoReads) { autoReads.add(lines) }
        if (autoFirstText == 0L && Chassis.rank(listOf(lines)).isNotEmpty()) autoFirstText = now
        val since = if (autoFirstText > 0L) now - autoFirstText else 0L

        val exp = expected
        if (exp != null) {
            val hit = Chassis.findExpected(exp, lines)
            if (hit != null) { autoUntil = 0L; runOnUiThread { autoDone(hit, true, since) }; return }
        } else {
            val top = Chassis.rank(synchronized(autoReads) { autoReads.toList() }).firstOrNull()
            if (top != null && top.second >= 3) { autoUntil = 0L; runOnUiThread { autoDone(top.first, null, since) }; return }
        }
        if ((autoFirstText > 0L && since > AUTO_WINDOW_MS) || now > autoUntil) {
            autoUntil = 0L
            val best = Chassis.rank(synchronized(autoReads) { autoReads.toList() }).firstOrNull()?.first
            runOnUiThread { autoDone(best, false, since) }
        }
    }

    /** matched: true = stamp equals QR, false = not confirmed, null = no QR to compare with. */
    private fun autoDone(read: String?, matched: Boolean?, ms: Long) {
        b.btnRead.isEnabled = true
        val secs = String.format(Locale.US, "%.1f", ms / 1000.0)
        when (matched) {
            true -> {
                b.stVal.setText(read)
                beep()
                status("PASS in $secs s. Saved. Next part in a moment…")
                saveRecord(auto = true, res = "MATCH")
                handler.postDelayed({ if (stampMode && autoUntil == 0L && b.stVal.text.toString() == read) nextPart() }, NEXT_PART_MS)
            }
            false -> {
                b.stVal.setText(read ?: "")
                beepFail()
                b.verdict.text = "NOT VERIFIED: could not find ${expected} on the part."
                b.verdict.setTextColor(color(R.color.bad))
                status("Stamp not confirmed" + (if (read != null) " (best reading: $read)" else "") +
                        ". Check the part, then tap Read again, or Save to log it as failed.", true)
            }
            null -> {
                b.stVal.setText(read)
                beep()
                status("Read \"$read\" in $secs s. Check it against the part.")
            }
        }
    }

    /** Clears everything and goes back to scanning the next QR label. */
    private fun nextPart() {
        handler.removeCallbacksAndMessages(null)
        autoUntil = 0L
        expected = null
        b.qrVal.setText(""); b.stVal.setText("")
        showChips(b.qrFields, b.qrFieldsLabel, emptyList(), b.qrVal)
        showChips(b.stFields, b.stFieldsLabel, emptyList(), b.stVal)
        b.modeGroup.check(b.btnModeQr.id)
        setMode(false)
    }

    private fun beepFail() {
        b.previewView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 700)
        } catch (_: Exception) {}
    }

    /**
     * Runs on the worker thread: focus on the box, take a full-quality photo, then a second one
     * with the phone light switched the other way. Two lightings show punched / cast characters
     * from different shadows, which helps a lot on worn metal.
     */
    private fun captureAndRead() {
        val cam = camera
        try {
            val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
            cam?.cameraControl?.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(4, TimeUnit.SECONDS).build()
            )?.get(2500, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}

        val first = takeStill()
        if (first == null) {
            runOnUiThread { b.btnRead.isEnabled = true; status("Could not take the photo. Try again.", true) }
            return
        }
        runOnUiThread { showShot(first); if (Cloud.enabled) status("Photo taken. Taking a second one with the light…") }

        val shots = mutableListOf(first)
        if (Cloud.enabled && cam?.cameraInfo?.hasFlashUnit() == true) {
            val wasOn = torchOn
            try {
                cam.cameraControl.enableTorch(!wasOn).get(1500, TimeUnit.MILLISECONDS)
                Thread.sleep(700) // let exposure settle to the new light
                takeStill()?.let { shots.add(it) }
            } catch (_: Exception) {
            } finally {
                try { cam.cameraControl.enableTorch(wasOn).get(1500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            }
        }
        runOnUiThread { status(if (Cloud.enabled) "Reading the photo…" else "Reading on the phone…") }
        readCloud(shots, if (vertical) "vertical" else "horizontal", "camera", if (vertical) listOf(90, 270) else listOf(0))
    }

    /** Takes one still photo and returns it cropped to the yellow box (with margin), upright, at most 1600 px. */
    private fun takeStill(): Bitmap? {
        val ic = imageCapture ?: return null
        val done = CountDownLatch(1)
        var out: Bitmap? = null
        ic.takePicture(captureExec, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val raw = image.toBitmap()
                    val rot = image.imageInfo.rotationDegrees
                    image.close()
                    val full = ImageTools.rotate(raw, rot)
                    if (full !== raw) raw.recycle()
                    val crop = cropToGuide(full, 1.4f)
                    out = ImageTools.fitLong(crop, 1600)
                    if (full !== crop) full.recycle()
                } catch (_: Throwable) {
                    try { image.close() } catch (_: Exception) {}
                } finally { done.countDown() }
            }
            override fun onError(exception: ImageCaptureException) { done.countDown() }
        })
        done.await(12, TimeUnit.SECONDS)
        return out
    }

    /** Shows the picture that is being read, so a blurry or badly framed shot is obvious. */
    private fun showShot(bmp: Bitmap) {
        b.shot.setImageBitmap(bmp)
        b.shot.visibility = View.VISIBLE
    }

    private fun showCloudResult(r: Cloud.Result, source: String) {
        b.btnRead.isEnabled = true
        photoBusy = false; b.btnPhoto.isEnabled = true
        val options = (listOfNotNull(r.chassis) + r.others).distinct()
        if (r.chassis == null && r.others.isEmpty()) {
            showChips(b.stFields, b.stFieldsLabel, emptyList(), b.stVal)
            status("No number found" + (if (r.note.isNotEmpty()) ": ${r.note}" else ".") +
                    if (source == "photo") " Try a closer photo, or type it." else " Zoom in, add light and try again, or type it.", true)
            return
        }
        b.stVal.setText(r.chassis ?: r.others.first())
        showChips(b.stFields, b.stFieldsLabel, if (options.size > 1) options.map { it to it } else emptyList(), b.stVal)
        beep()
        val low = r.confidence.equals("low", true)
        status("Read with ${r.confidence} confidence." + (if (r.note.isNotEmpty()) " ${r.note}" else "") +
                " Check it against the part.", low)
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
                // Photo direction is unknown, so the phone reader tries all four.
                runOnUiThread { showShot(bmp) }
                readCloud(listOf(bmp), "unknown", "photo", listOf(0, 90, 180, 270))
            }
        }
    }

    /** Picks the reading most passes agreed on; the rest are offered as chips. */
    private fun finishStampRead(source: String, offline: Boolean = false) {
        b.btnRead.isEnabled = true
        photoBusy = false; b.btnPhoto.isEnabled = true
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
        status((if (offline) "Offline, read on the phone. " else "") + "Read \"${best.first}\" in ${best.second} of $passes passes. " +
                if (strong) "Check it against the part." else "Unsure: check it, tap another reading below, or read again.", !strong)
    }

    // ---------- compare ----------

    private fun refresh() {
        val qrRaw = b.qrVal.text.toString().trim(); val stRaw = b.stVal.text.toString().trim()
        b.qrChecks.text = if (qrRaw.isEmpty()) "" else "${qrRaw.length} characters"
        b.stChecks.text = if (stRaw.isEmpty()) "" else "${stRaw.length} characters"
        val a = Chassis.norm(qrRaw); val s = Chassis.norm(stRaw)
        if (a.isNotEmpty() && s.isNotEmpty()) {
            val same = Chassis.same(a, s)
            b.verdict.text = if (same) "MATCH: stamped number is the same as the QR label." else "Mismatch: " + Chassis.diff(a, s)
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

    private fun saveRecord(auto: Boolean = false, res: String? = null) {
        val qr = b.qrVal.text.toString().trim()
        val st = b.stVal.text.toString().trim()
        if (qr.isEmpty() && st.isEmpty()) { status("Nothing to save yet.", true); return }
        val old = loadRecords()
        val rec = JSONObject().put("t", System.currentTimeMillis()).put("qr", qr).put("st", st)
            .put("res", res ?: result(qr, st, null))
        val list = JSONArray().put(rec)
        for (i in 0 until old.length()) list.put(old.get(i))
        prefs().edit().putString(KEY, list.toString()).apply()
        renderRecords()
        if (!auto) {
            status("Record saved.")
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun result(qr: String, st: String, stored: String?) = stored?.takeIf { it.isNotEmpty() }
        ?: if (qr.isEmpty() || st.isEmpty()) "-" else if (Chassis.same(qr, st)) "MATCH" else "MISMATCH"

    private fun renderRecords() {
        val list = loadRecords()
        if (list.length() == 0) { b.records.text = "No records yet."; return }
        val fmt = SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val qr = r.optString("qr"); val st = r.optString("st")
            sb.append(fmt.format(Date(r.optLong("t")))).append("  ").append(result(qr, st, r.optString("res"))).append('\n')
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
                .append(',').append(result(qr, st, r.optString("res")).lowercase()).append('\n')
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
        private const val AUTO_WINDOW_MS = 3000L   // give up 3 s after the number is first in view
        private const val AUTO_MAX_MS = 20000L     // or 20 s after the QR scan if nothing is seen
        private const val NEXT_PART_MS = 1800L
    }
}
