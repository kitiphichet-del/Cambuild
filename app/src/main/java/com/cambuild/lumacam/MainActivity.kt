package com.cambuild.lumacam

import android.Manifest
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

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 18, 29))
            setPadding(dp(10), dp(12), dp(10), dp(14))
        }
        setContentView(root)
        val title = TextView(this).apply {
            text = "LumaCam  ·  Smart Photo"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), 0, dp(8))
        }
        root.addView(title)
        message = TextView(this).apply {
            setTextColor(Color.rgb(167, 186, 204))
            textSize = 12.5f
            text = "พร้อมถ่ายภาพ  |  โหมด Auto"
            setPadding(dp(8), 0, 0, dp(9))
        }
        root.addView(message)
        val imageFrame = FrameLayout(this)
        root.addView(imageFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        viewFinder = PreviewView(this)
        imageFrame.addView(viewFinder, FrameLayout.LayoutParams(-1, -1))
        photoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(8, 11, 18))
            visibility = View.GONE
        }
        imageFrame.addView(photoView, FrameLayout.LayoutParams(-1, -1))
        val chooseMode = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chooseMode.addView(modes)
        root.addView(chooseMode, LinearLayout.LayoutParams(-1, dp(56)))
        listOf("Auto", "Natural", "Night", "Portrait").forEach { chosen ->
            val b = button(chosen, if (chosen == mode) Color.rgb(13, 124, 182) else Color.rgb(42,50,68))
            modes.addView(b, LinearLayout.LayoutParams(dp(99), dp(47)).apply { setMargins(dp(2),dp(4),dp(2),0) })
            b.setOnClickListener {
                if (busy) return@setOnClickListener
                mode = chosen
                for (j in 0 until modes.childCount) modes.getChildAt(j).setBackgroundColor(
                    if ((modes.getChildAt(j) as Button).text.toString() == mode) Color.rgb(13,124,182) else Color.rgb(42,50,68)
                )
                val saved = original
                if (saved == null) status("โหมด $mode พร้อมใช้งาน") else enhance(saved)
            }
        }
        val cameraRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(cameraRow, LinearLayout.LayoutParams(-1, dp(67)))
        fun addAction(label: String, weight: Float, callback: () -> Unit): Button {
            val b = button(label, Color.rgb(39, 57, 80))
            cameraRow.addView(b, LinearLayout.LayoutParams(0, dp(57), weight).apply {
                setMargins(dp(3),dp(4),dp(3),0)
            })
            b.setOnClickListener { callback() }
            return b
        }
        addAction("รูปในเครื่อง",1f) { if(!busy) gallery.launch("image/*") }
        addAction("ถ่ายภาพ",1.4f) { if(!busy) takePhoto() }
        addAction("สลับกล้อง",1f) {
            selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            startCamera()
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(actions, LinearLayout.LayoutParams(-1, dp(58)))
        compareBtn = button("ต้นฉบับ / ปรับแล้ว")
        actions.addView(compareBtn, LinearLayout.LayoutParams(0, dp(50),1f).apply { setMargins(dp(3),0,dp(3),0) })
        compareBtn.setOnClickListener {
            showingOriginal = !showingOriginal
            photoView.setImageBitmap(if (showingOriginal) original else enhanced)
            status(if (showingOriginal) "ภาพต้นฉบับ" else "ภาพที่ปรับแต่งแล้ว")
        }
        val saveBtn = button("บันทึก 2 ภาพ", Color.rgb(0, 128, 113))
        actions.addView(saveBtn, LinearLayout.LayoutParams(0, dp(50),1f).apply { setMargins(dp(3),0,dp(3),0) })
        saveBtn.setOnClickListener { if (!busy) save() }
        val back = button("กลับสู่กล้อง", Color.rgb(26,35,50))
        root.addView(back, LinearLayout.LayoutParams(-1, dp(43)).apply { setMargins(dp(3),dp(4),dp(3),0) })
        back.setOnClickListener { photoView.visibility = View.GONE; status("กลับสู่กล้อง • โหมด $mode") }
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
                provider.bindToLifecycle(this, selector, preview, capture)
            } catch (e: Exception) { status("ไม่สามารถเปิดกล้อง: ${e.localizedMessage}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val camera = capture ?: run { status("กำลังเตรียมกล้อง"); return }
        val file = File.createTempFile("lumacam_", ".jpg", cacheDir)
        camera.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { process(Uri.fromFile(file)) }
                override fun onError(exception: ImageCaptureException) { status("ถ่ายภาพไม่สำเร็จ: ${exception.message}") }
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
                photoView.visibility = View.VISIBLE
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
                photoView.visibility = View.VISIBLE
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
