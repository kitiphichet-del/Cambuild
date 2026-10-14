package com.cambuild.lumacam

import android.Manifest
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var photoView: ImageView
    private lateinit var message: TextView
    private lateinit var compareBtn: Button
    private lateinit var root: LinearLayout
    private var capture: ImageCapture? = null
    private var selector = CameraSelector.DEFAULT_BACK_CAMERA
    private var original: Bitmap? = null
    private var enhanced: Bitmap? = null
    private var showingOriginal = false
    private var mode = "Auto"
    private var busy = false
    private val gold = Color.rgb(244, 197, 94)
    private var camera: Camera? = null
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var gridOn = false
    private var currentZoom = 1f
    private lateinit var grid: ThirdsGrid
    private lateinit var zoomBar: LinearLayout
    private lateinit var captureRow: LinearLayout
    private lateinit var reviewRow: LinearLayout
    private lateinit var flashControl: TextView
    private lateinit var gridControl: TextView
    private lateinit var focusRing: TextView
    private lateinit var thumbnail: ImageView
    private lateinit var galleryGlyph: TextView
    private val zoomControls = mutableListOf<Pair<Float, TextView>>()
    private val modes = mutableMapOf<String, TextView>()

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else status("อนุญาตกล้องเพื่อถ่ายภาพ หรือใช้ปุ่มเลือกรูป")
    }
    private val gallery = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) process(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun button(label: String, bg: Int = Color.rgb(42, 50, 68)): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(bg)
            setPadding(dp(4), 0, dp(4), 0)
        }

    private fun oval(fill: Int, border: Int = Color.TRANSPARENT, thickness: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (thickness > 0) setStroke(dp(thickness), border)
        }

    private fun rounded(fill: Int, radius: Int = 17): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
        }

    private fun txt(value: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

    private fun chip(value: String): TextView =
        txt(value, 12f, Color.WHITE, true).apply {
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(36, 38, 44), 22)
            isClickable = true
            isFocusable = true
        }

    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(57)))
        header.addView(txt("LUMACAM", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(txt("✦  AI ENHANCE", 10f, gold, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(48, 39, 25), 10)
        })

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(47)))
        toolbar.addView(txt("●  PHOTO", 11f, gold, true).apply {
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -1, 1f))
        flashControl = chip("⚡ OFF").apply {
            contentDescription = "Change flash mode"
            setOnClickListener { cycleFlash() }
        }
        toolbar.addView(flashControl, LinearLayout.LayoutParams(-2, dp(35)).apply {
            rightMargin = dp(8)
        })
        gridControl = chip("▦ GRID").apply {
            contentDescription = "Rule of thirds grid"
            setOnClickListener {
                gridOn = !gridOn
                grid.visibility = if (gridOn && photoView.visibility == View.GONE) View.VISIBLE else View.GONE
                gridControl.setTextColor(if (gridOn) gold else Color.WHITE)
                status(if (gridOn) "เปิดเส้นตารางแบ่งภาพ" else "ปิดเส้นตารางแบ่งภาพ")
            }
        }
        toolbar.addView(gridControl, LinearLayout.LayoutParams(-2, dp(35)))

        val finder = FrameLayout(this).apply { setBackgroundColor(Color.rgb(20, 22, 27)) }
        root.addView(finder, LinearLayout.LayoutParams(-1, 0, 1f))
        viewFinder = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            contentDescription = "Live viewfinder. Tap to focus."
        }
        finder.addView(viewFinder, FrameLayout.LayoutParams(-1, -1))
        viewFinder.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && photoView.visibility == View.GONE) {
                focusAt(event.x, event.y)
                true
            } else event.action == MotionEvent.ACTION_DOWN
        }
        grid = ThirdsGrid(this).apply { visibility = View.GONE }
        finder.addView(grid, FrameLayout.LayoutParams(-1, -1))
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(15, 16, 20))
            visibility = View.GONE
            contentDescription = "Enhanced photo preview"
        }
        finder.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        finder.addView(txt("LIVE  ·  CAMERA", 10f, Color.WHITE, true).apply {
            background = rounded(Color.argb(172, 10, 10, 14), 10)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }, FrameLayout.LayoutParams(-2, dp(29), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(13); topMargin = dp(13)
        })
        focusRing = txt("◎", 46f, gold).apply {
            visibility = View.GONE
        }
        finder.addView(focusRing, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.START))

        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.argb(215, 14, 15, 19), 26)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        finder.addView(zoomBar, FrameLayout.LayoutParams(-2, dp(51), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(17)
        })
        listOf(0.5f to "0.5×", 1f to "1×", 2f to "2×").forEach { (ratio, caption) ->
            val zoom = txt(caption, 13f, if (ratio == 1f) Color.BLACK else Color.WHITE, true).apply {
                background = oval(if (ratio == 1f) gold else Color.TRANSPARENT)
                contentDescription = "Zoom " + caption
                setOnClickListener { setZoom(ratio) }
            }
            zoomBar.addView(zoom, LinearLayout.LayoutParams(dp(41), dp(41)).apply {
                leftMargin = dp(5); rightMargin = dp(5)
            })
            zoomControls.add(ratio to zoom)
        }

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(9), dp(10), dp(2))
            setBackgroundColor(Color.rgb(12, 13, 18))
        }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        val modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        footer.addView(modeBar, LinearLayout.LayoutParams(-1, dp(41)))
        listOf("Auto" to "AUTO", "Natural" to "NATURAL", "Night" to "NIGHT", "Portrait" to "PORTRAIT").forEach { (key, caption) ->
            val modeButton = txt(caption, 11f, if (key == mode) gold else Color.LTGRAY, key == mode).apply {
                contentDescription = "Enhancement mode " + caption
                setOnClickListener {
                    if (!busy) {
                        mode = key
                        refreshModes()
                        val previous = original
                        if (previous != null && photoView.visibility == View.VISIBLE) enhance(previous)
                        else status("โหมด " + caption + " พร้อมใช้งาน")
                    }
                }
            }
            modeBar.addView(modeButton, LinearLayout.LayoutParams(0, -1, 1f))
            modes[key] = modeButton
        }

        captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(3))
        }
        footer.addView(captureRow, LinearLayout.LayoutParams(-1, dp(110)))
        val galleryBtn = FrameLayout(this).apply {
            contentDescription = "Choose photo from gallery"
            isClickable = true
            isFocusable = true
            setOnClickListener { if (!busy) gallery.launch("image/*") }
        }
        captureRow.addView(galleryBtn, LinearLayout.LayoutParams(0, dp(75), 1f))
        val square = FrameLayout(this).apply {
            background = rounded(Color.rgb(37, 40, 45), 14)
            clipToOutline = true
        }
        galleryBtn.addView(square, FrameLayout.LayoutParams(dp(53), dp(53), Gravity.CENTER))
        thumbnail = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        square.addView(thumbnail, FrameLayout.LayoutParams(-1, -1))
        galleryGlyph = txt("▧", 28f)
        square.addView(galleryGlyph, FrameLayout.LayoutParams(-1, -1))

        val shutter = FrameLayout(this).apply {
            contentDescription = "Take photograph"
            isClickable = true
            isFocusable = true
            background = oval(Color.TRANSPARENT, Color.WHITE, 3)
            setOnClickListener { if (!busy) takePhoto() }
        }
        captureRow.addView(shutter, LinearLayout.LayoutParams(dp(89), dp(89)))
        shutter.addView(View(this).apply {
            background = oval(Color.WHITE)
        }, FrameLayout.LayoutParams(dp(69), dp(69), Gravity.CENTER))
        val switch = FrameLayout(this).apply {
            contentDescription = "Switch front and back cameras"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (!busy) {
                    selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA)
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    currentZoom = 1f
                    startCamera()
                }
            }
        }
        captureRow.addView(switch, LinearLayout.LayoutParams(0, dp(75), 1f))
        switch.addView(txt("↻", 36f), FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER))

        reviewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(5), dp(11), dp(5), dp(11))
        }
        footer.addView(reviewRow, LinearLayout.LayoutParams(-1, dp(110)))
        fun reviewButton(caption: String, primary: Boolean = false, onTap: () -> Unit) {
            val btn = txt(caption, 11f, if (primary) Color.BLACK else Color.WHITE, true).apply {
                background = rounded(if (primary) gold else Color.rgb(36, 38, 45), 12)
                setOnClickListener { onTap() }
            }
            reviewRow.addView(btn, LinearLayout.LayoutParams(0, dp(49), if (primary) 1.28f else 1f).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        reviewButton("BEFORE / AFTER") {
            if (!busy && original != null && enhanced != null) {
                showingOriginal = !showingOriginal
                photoView.setImageBitmap(if (showingOriginal) original else enhanced)
                status(if (showingOriginal) "ภาพต้นฉบับ" else "ภาพที่ปรับแล้ว")
            }
        }
        reviewButton("SAVE BOTH", true) { if (!busy) save() }
        reviewButton("RETAKE") { if (!busy) showReview(false) }

        message = txt("● READY  •  TAP TO FOCUS", 10f, Color.rgb(151, 154, 160))
        footer.addView(message, LinearLayout.LayoutParams(-1, dp(29)))
    }

    private fun refreshModes() {
        modes.forEach { (key, button) ->
            button.setTextColor(if (key == mode) gold else Color.LTGRAY)
            button.typeface = Typeface.create("sans-serif-medium",
                if (key == mode) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun showReview(show: Boolean) {
        photoView.visibility = if (show) View.VISIBLE else View.GONE
        captureRow.visibility = if (show) View.GONE else View.VISIBLE
        reviewRow.visibility = if (show) View.VISIBLE else View.GONE
        zoomBar.visibility = if (show) View.GONE else View.VISIBLE
        grid.visibility = if (gridOn && !show) View.VISIBLE else View.GONE
        focusRing.visibility = View.GONE
        if (!show) status("● READY  •  " + mode.uppercase() + "  •  TAP TO FOCUS")
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        capture?.flashMode = flashMode
        flashControl.text = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "⚡ AUTO"
            ImageCapture.FLASH_MODE_ON -> "⚡ ON"
            else -> "⚡ OFF"
        }
        flashControl.setTextColor(if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.WHITE else gold)
        status("FLASH " + flashControl.text)
    }

    private fun refreshZoom() {
        val state = camera?.cameraInfo?.zoomState?.value
        zoomControls.forEach { (ratio, button) ->
            val valid = state != null &&
                ratio >= state.minZoomRatio - 0.015f && ratio <= state.maxZoomRatio + 0.015f
            button.isEnabled = valid
            button.alpha = if (valid) 1f else 0.30f
            val active = valid && kotlin.math.abs(currentZoom - ratio) < 0.02f
            button.background = oval(if (active) gold else Color.TRANSPARENT)
            button.setTextColor(if (active) Color.BLACK else Color.WHITE)
        }
    }

    private fun setZoom(ratio: Float) {
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.zoomState.value ?: return
        if (ratio < state.minZoomRatio - 0.015f || ratio > state.maxZoomRatio + 0.015f) return
        currentZoom = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        activeCamera.cameraControl.setZoomRatio(currentZoom)
        refreshZoom()
        status("ZOOM " + ratio.toString() + "×")
    }

    private fun focusAt(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val point = viewFinder.meteringPointFactory.createPoint(x, y)
        activeCamera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        val lp = focusRing.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (x.toInt() - dp(32)).coerceAtLeast(0)
        lp.topMargin = (y.toInt() - dp(32)).coerceAtLeast(0)
        focusRing.layoutParams = lp
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().alpha(0f).setDuration(800L).withEndAction {
            focusRing.visibility = View.GONE
        }.start()
    }

    private class ThirdsGrid(context: Context) : View(context) {
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 255, 255, 255)
            strokeWidth = context.resources.displayMetrics.density
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            for (i in 1..2) {
                val x = width * i / 3f
                val y = height * i / 3f
                canvas.drawLine(x, 0f, x, height.toFloat(), pen)
                canvas.drawLine(0f, y, width.toFloat(), y, pen)
            }
        }
    }

    private fun status(s: String) { message.text = s }

    private fun startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
                capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, capture)
                capture?.flashMode = flashMode
                currentZoom = 1f
                refreshZoom()
            } catch (e: Exception) { status("ไม่สามารถเปิดกล้อง: ${e.localizedMessage}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val camera = capture ?: run { status("กำลังเตรียมกล้อง"); return }
        busy = true
        status("กำลังถ่ายภาพ...")
        val file = File.createTempFile("lumacam_", ".jpg", cacheDir)
        camera.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { process(Uri.fromFile(file)) }
                override fun onError(exception: ImageCaptureException) { busy = false; status("ถ่ายภาพไม่สำเร็จ: ${exception.message}") }
            }
        )
    }

    private fun decode(uri: Uri): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "ไม่พบไฟล์ภาพ" }
            BitmapFactory.decodeStream(ins, null, options)
        }
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 2800) sample *= 2
        options.inJustDecodeBounds = false
        options.inSampleSize = sample
        val decoded = contentResolver.openInputStream(uri).use { ins ->
            requireNotNull(ins) { "ไม่พบไฟล์ภาพ" }
            BitmapFactory.decodeStream(ins, null, options)
        } ?: error("ไฟล์ภาพไม่ถูกต้อง")
        val orientation = try {
            contentResolver.openInputStream(uri).use { ins ->
                if (ins == null) ExifInterface.ORIENTATION_NORMAL
                else ExifInterface(ins).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        return if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun process(uri: Uri) {
        busy = true
        status("กำลังอ่านและปรับภาพ...")
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decode(uri) }
                original = bitmap
                val faceBoxes = detectFaces(bitmap)
                val output = withContext(Dispatchers.Default) {
                    PhotoProcessor.enhance(bitmap, mode, faceBoxes)
                }
                enhanced = output
                showingOriginal = false
                photoView.setImageBitmap(output)
                thumbnail.setImageBitmap(output)
                galleryGlyph.visibility = View.GONE
                showReview(true)
                status("ปรับภาพด้วย $mode สำเร็จ ${if (mode == "Portrait") "· พบใบหน้า ${faceBoxes.size}" else "· ทำงานบนเครื่อง"}")
            } catch (e: Exception) { status("ประมวลผลไม่ได้: ${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    private fun enhance(bitmap: Bitmap) {
        busy = true
        status("กำลังปรับภาพโหมด $mode ...")
        lifecycleScope.launch {
            try {
                val faces = detectFaces(bitmap)
                val edited = withContext(Dispatchers.Default) {
                    PhotoProcessor.enhance(bitmap, mode, faces)
                }
                enhanced = edited
                showingOriginal = false
                photoView.setImageBitmap(edited)
                thumbnail.setImageBitmap(edited)
                galleryGlyph.visibility = View.GONE
                showReview(true)
                status("ปรับภาพโหมด $mode สำเร็จ")
            } catch (e: Exception) { status("ไม่สามารถปรับภาพได้: ${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    private suspend fun detectFaces(bitmap: Bitmap): List<Rect> {
        if (mode != "Portrait") return emptyList()
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .build()
        )
        return try {
            detector.process(InputImage.fromBitmap(bitmap, 0)).await().map { it.boundingBox }
        } catch (_: Exception) {
            emptyList()
        } finally {
            detector.close()
        }
    }

    private fun save() {
        val a = original ?: run { status("กรุณาถ่ายหรือเลือกรูปก่อน"); return }
        val b = enhanced ?: run { status("ยังไม่มีรูปที่ปรับแล้ว"); return }
        busy = true
        status("กำลังบันทึกสองภาพ...")
        lifecycleScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val saved = withContext(Dispatchers.IO) {
                    saveOne(a, "LumaCam_${stamp}_ORIGINAL.jpg")
                    saveOne(b, "LumaCam_${stamp}_ENHANCED.jpg")
                }
                status("บันทึกเรียบร้อย: Pictures/LumaCam (2 ภาพ)")
            } catch (e: Exception) { status("บันทึกไม่สำเร็จ: ${e.localizedMessage}") }
            finally { busy = false }
        }
    }

    private fun saveOne(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LumaCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("ไม่สามารถสร้างไฟล์")
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) { "เขียนภาพไม่สำเร็จ" }
            } ?: error("ไม่สามารถเปิดไฟล์เขียน")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }
}
