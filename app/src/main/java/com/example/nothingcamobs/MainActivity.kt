package com.example.nothingcamobs

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    data class LensOption(
        val label: String,
        val logicalId: String,
        val physicalId: String?,
        val focalLength: Float
    )

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null

    private lateinit var previewSurfaceView: SurfaceView
    private var previewSurface: Surface? = null

    // Video Stream & Encoder (1080p 30fps @ 35 Mbps)
    private val streamWidth = 1920
    private val streamHeight = 1080
    private val streamFps = 30
    private val streamBitrate = 35_000_000

    private var mediaCodec: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var clientOutputStream: OutputStream? = null
    @Volatile private var spsPpsConfig: ByteArray? = null
    @Volatile private var isStreaming = false

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private val availableLenses = mutableListOf<LensOption>()
    private var currentLens: LensOption? = null

    // Manuel Kontrol Değişkenleri
    private var isAutoExposure = true
    private var manualIso = 200
    private var manualShutterNs = 16_666_666L // 1/60s
    private var isAutoFocus = true
    private var manualFocusDistance = 0.0f
    private var isoRange: Range<Int> = Range(50, 3200)
    private var minFocusDistance = 10.0f

    private lateinit var statusText: TextView
    private lateinit var lensButtonContainer: LinearLayout

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) initializeSystem()
        else Toast.makeText(this, "Kamera izni gerekli", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupUi()

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraThread = HandlerThread("Camera2Thread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            initializeSystem()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        previewSurfaceView = SurfaceView(this)
        root.addView(previewSurfaceView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Üst Bilgi Çubuğu
        statusText = TextView(this).apply {
            setTextColor(Color.GREEN)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
            textSize = 13f
            text = "OBS Bekleniyor (Port 8888) | 1080p 35Mbps"
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply { setMargins(24, 24, 24, 0) })

        // Sağ Kontrol Paneli (Manuel Ayarlar)
        val controlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99111111.toInt())
            setPadding(24, 20, 24, 20)
        }

        // Auto / Manuel Pozlama Anahtarı
        val aeSwitch = Switch(this).apply {
            text = "Otomatik Pozlama (AE) "
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                isAutoExposure = checked
                updateRepeatingRequest()
            }
        }
        controlPanel.addView(aeSwitch)

        // ISO Kaydırıcı
        val isoLabel = TextView(this).apply { text = "ISO: 200"; setTextColor(Color.LTGRAY); textSize = 12f }
        val isoSeek = SeekBar(this).apply {
            max = 100
            progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    manualIso = isoRange.lower + ((isoRange.upper - isoRange.lower) * (p / 100f)).toInt()
                    isoLabel.text = "ISO: $manualIso"
                    if (!isAutoExposure) updateRepeatingRequest()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        controlPanel.addView(isoLabel)
        controlPanel.addView(isoSeek)

        // Enstantane (Shutter) Kaydırıcı (1/30s - 1/500s)
        val shutterOptions = listOf(
            "1/30s" to 33_333_333L,
            "1/50s" to 20_000_000L,
            "1/60s" to 16_666_666L,
            "1/100s" to 10_000_000L,
            "1/125s" to 8_000_000L,
            "1/250s" to 4_000_000L,
            "1/500s" to 2_000_000L
        )
        val shutterLabel = TextView(this).apply { text = "Enstantane: 1/60s"; setTextColor(Color.LTGRAY); textSize = 12f }
        val shutterSeek = SeekBar(this).apply {
            max = shutterOptions.size - 1
            progress = 2
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    shutterLabel.text = "Enstantane: ${shutterOptions[p].first}"
                    manualShutterNs = shutterOptions[p].second
                    if (!isAutoExposure) updateRepeatingRequest()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        controlPanel.addView(shutterLabel)
        controlPanel.addView(shutterSeek)

        // Odak Kilidi (AF / MF)
        val afSwitch = Switch(this).apply {
            text = "Otomatik Odak (AF) "
            setTextColor(Color.WHITE)
            isChecked = true
            setOnCheckedChangeListener { _, checked ->
                isAutoFocus = checked
                updateRepeatingRequest()
            }
        }
        val focusSeek = SeekBar(this).apply {
            max = 100
            progress = 15
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    manualFocusDistance = (p / 100f) * minFocusDistance
                    if (!isAutoFocus) updateRepeatingRequest()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        controlPanel.addView(afSwitch)
        controlPanel.addView(focusSeek)

        root.addView(controlPanel, FrameLayout.LayoutParams(
            620,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { setMargins(0, 24, 24, 0) })

        // Alt Lens Seçici Çubuk
        lensButtonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 12, 16, 12)
        }
        root.addView(lensButtonContainer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { setMargins(0, 0, 0, 24) })

        setContentView(root)
    }

    private fun initializeSystem() {
        discoverAllPhysicalLenses()
        startHardwareEncoderAndTcpServer()

        previewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.setFixedSize(streamWidth, streamHeight)
                previewSurface = holder.surface
                availableLenses.firstOrNull()?.let { openLens(it) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { previewSurface = null }
        })
    }

    private fun discoverAllPhysicalLenses() {
        availableLenses.clear()
        val backPhysicalList = mutableListOf<LensOption>()

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    chars.physicalCameraIds
                } else emptySet()

                if (physicalIds.isNotEmpty()) {
                    for (physId in physicalIds) {
                        val pChars = cameraManager.getCameraCharacteristics(physId)
                        val focal = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5.0f
                        backPhysicalList.add(LensOption("", id, physId, focal))
                    }
                } else {
                    val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5.0f
                    backPhysicalList.add(LensOption("1x Ana", id, null, focal))
                }
            } else if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 3.0f
                availableLenses.add(LensOption("Ön Kamera", id, null, focal))
            }
        }

        // Arka fiziksel lensleri odak uzaklığına göre sırala ve isimlendir (0.6x, 1x, 3x)
        backPhysicalList.sortBy { it.focalLength }
        val mainFocal = backPhysicalList.getOrNull(1)?.focalLength ?: backPhysicalList.firstOrNull()?.focalLength ?: 5.5f

        val labeledBack = backPhysicalList.map { lens ->
            val ratio = lens.focalLength / mainFocal
            val name = when {
                ratio < 0.8f -> "0.6x Ultra Geniş (ID:${lens.physicalId})"
                ratio > 1.8f -> "${ratio.roundToInt()}x Telefoto (ID:${lens.physicalId})"
                else -> "1x Ana Lens (ID:${lens.physicalId ?: lens.logicalId})"
            }
            lens.copy(label = name)
        }

        // Ana lensi başa koy, sonra diğerlerini ekle
        val sortedFinal = labeledBack.sortedByDescending { it.label.startsWith("1x") } + availableLenses
        availableLenses.clear()
        availableLenses.addAll(sortedFinal)

        runOnUiThread {
            lensButtonContainer.removeAllViews()
            availableLenses.forEach { lens ->
                val btn = Button(this).apply {
                    text = lens.label
                    textSize = 12f
                    setOnClickListener { openLens(lens) }
                }
                lensButtonContainer.addView(btn)
            }
        }
    }

    private fun startHardwareEncoderAndTcpServer() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, streamBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, streamFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
            start()
        }

        isStreaming = true

        // TCP Sunucusu (Port 8888 - OBS Bağlantısı)
        Thread {
            try {
                serverSocket = ServerSocket(8888)
                while (isStreaming) {
                    val client = serverSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    val out = client.getOutputStream()
                    spsPpsConfig?.let { out.write(it) }
                    clientOutputStream = out

                    // Yeni istemci bağlandığında anında Keyframe (I-Frame) üret
                    val params = Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    }
                    mediaCodec?.setParameters(params)

                    runOnUiThread {
                        statusText.text = "OBS BAĞLI (Canlı Akış: 1080p @ 35 Mbps)"
                        statusText.setTextColor(Color.CYAN)
                    }
                }
            } catch (_: Exception) {}
        }.start()

        // Donanım Kodlayıcı Çıktı Döngüsü
        Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            while (isStreaming) {
                val codec = mediaCodec ?: break
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val encodedData = codec.getOutputBuffer(outIndex)
                    if (encodedData != null && bufferInfo.size > 0) {
                        val bytes = ByteArray(bufferInfo.size)
                        encodedData.position(bufferInfo.offset)
                        encodedData.get(bytes)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            spsPpsConfig = bytes
                        }

                        try {
                            clientOutputStream?.write(bytes)
                            clientOutputStream?.flush()
                        } catch (_: Exception) {
                            clientOutputStream = null
                            runOnUiThread {
                                statusText.text = "OBS Bekleniyor (Port 8888)"
                                statusText.setTextColor(Color.GREEN)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun openLens(lens: LensOption) {
        currentLens = lens
        captureSession?.close()
        cameraDevice?.close()

        val targetIdForChars = lens.physicalId ?: lens.logicalId
        val chars = cameraManager.getCameraCharacteristics(targetIdForChars)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
        minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

        cameraManager.openCamera(lens.logicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera, lens)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, cameraHandler)
    }

    private fun createCaptureSession(camera: CameraDevice, lens: LensOption) {
        val pSurface = previewSurface ?: return
        val eSurface = encoderSurface ?: return

        val previewConfig = OutputConfiguration(pSurface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lens.physicalId != null) {
                setPhysicalCameraId(lens.physicalId)
            }
        }
        val encoderConfig = OutputConfiguration(eSurface).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && lens.physicalId != null) {
                setPhysicalCameraId(lens.physicalId)
            }
        }

        previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(pSurface)
            addTarget(eSurface)
        }

        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(previewConfig, encoderConfig),
            sessionExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateRepeatingRequest()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun updateRepeatingRequest() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        // Nothing ISP Kalite İyileştirmeleri (Camo'nun kapattığı keskinlik ve kumlanma azaltma)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(streamFps, streamFps))

        // Pozlama (Auto vs Manuel ISO/Shutter)
        if (isAutoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso.coerceIn(isoRange.lower, isoRange.upper))
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutterNs)
        }

        // Odak (Auto vs Manuel Odak Kilidi)
        if (isAutoFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
        }

        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        captureSession?.close()
        cameraDevice?.close()
        serverSocket?.close()
        mediaCodec?.stop()
        mediaCodec?.release()
        cameraThread.quitSafely()
    }
}
