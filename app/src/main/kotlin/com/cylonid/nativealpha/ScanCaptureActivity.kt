package com.cylonid.nativealpha

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫码取景页（C-扫码）。按谷歌当前最佳实践实现：
 * - 相机栈：CameraX 1.5.x（推荐栈；弃用 journeyapps 的 Camera1 旧 API）
 * - 权限：ActivityResultContracts.RequestPermission（现代范式）
 * - 解码：复用已依赖的 zxing-core（QR 专用 + TRY_HARDER），不引 ML Kit
 *   （bundled 2-3MB 违背「低损耗」，且许可非 Apache）
 * - 分析分辨率 1280×720（官方性能甜点），KEEP_ONLY_LATEST 背压丢帧
 * - 识别成功即 setResult+finish，路由交 ScanResultRouter（纯函数）
 */
class ScanCaptureActivity : AppCompatActivity() {

    private var camera: Camera? = null
    private var torchOn = false
    /** 识别完成标记：UI 线程写、分析线程读——@Volatile 保可见性 */
    @Volatile
    private var done = false

    private lateinit var previewView: PreviewView
    private lateinit var torchButton: Button

    /** 现代权限范式：注册必须在 CREATED 前完成（字段初始化即注册） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, R.string.scan_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.scan_capture)
        previewView = findViewById(R.id.scan_preview)
        torchButton = findViewById(R.id.scan_torch)
        findViewById<ImageButton>(R.id.scan_close).setOnClickListener { finish() }
        torchButton.setOnClickListener {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
            torchButton.isSelected = torchOn
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** CameraX 装配：Preview（全屏预览）+ ImageAnalysis（1280×720 甜点分辨率） */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, ::analyze) }
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    /** 逐帧解码（KEEP_ONLY_LATEST 背压；识别成功即停并回传） */
    private fun analyze(image: ImageProxy) {
        if (done) {
            image.close()
            return
        }
        val text = try {
            decodeQr(image)
        } finally {
            image.close()
        }
        if (text != null && !done) {
            done = true
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
                finish()
            }
        }
    }

    /** Y 平面 → zxing 亮度源：按行压缩（rowStride≥width），原始帧失败再按
     *  rotationDegrees 旋转重试（竖屏后置相机典型 90°） */
    private fun decodeQr(image: ImageProxy): String? {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        var luminance = ByteArray(width * height)
        if (rowStride == width) {
            buffer.get(luminance)
        } else {
            var pos = 0
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(luminance, pos, width)
                pos += width
            }
        }
        val rotation = image.imageInfo.rotationDegrees
        val attempt = { source: PlanarYUVLuminanceSource ->
            try {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
            } catch (ignored: Exception) {
                null
            } finally {
                reader.reset()
            }
        }
        attempt(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false))
            ?.let { return it }
        if (rotation % 180 != 0) {
            luminance = rotate90(luminance, width, height)
            val rw = height
            val rh = width
            return attempt(PlanarYUVLuminanceSource(luminance, rw, rh, 0, 0, rw, rh, false))
        }
        return null
    }

    /** 亮度矩阵顺时针旋转 90°（宽高互换） */
    private fun rotate90(src: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(src.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[x * height + (height - 1 - y)] = src[y * width + x]
            }
        }
        return out
    }

    override fun onDestroy() {
        analyzerExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        /** 扫描结果 extra（MainScreen 的 ActivityResult 回调读取） */
        const val EXTRA_SCAN_RESULT = "SCAN_RESULT"
    }
}
