package com.genkaim.picocam

import android.app.Application
import android.os.SystemClock
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.genkaim.picocam.camera.PhotoStorage
import com.genkaim.picocam.moon.Moon
import com.genkaim.picocam.moon.MoonComposer
import com.genkaim.picocam.moon.MoonDetector
import com.genkaim.picocam.moon.MoonTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

/**
 * 普通相机 + 月亮替换。
 *
 * 与旧 CameraViewModel 完全独立：不碰拍立得白框 / 相册网格 / 滤镜面板，
 * 只做「预览 + 检测 + 变焦 + 拍照合成」，便于随时回退旧相机页。
 */
class MoonViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MoonVM"
        /** 检测间隔，ms。10Hz 足够，再高只是浪费电。 */
        private const val DETECT_INTERVAL_MS = 90L
    }

    val preview: Preview = Preview.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build()

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .build()

    private val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setOutputImageRotationEnabled(true)
        .build()

    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracker = MoonTracker()

    private var camera: Camera? = null
    private var selector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var owner: LifecycleOwner? = null
    private var surfaceView: PreviewView? = null
    private var lastDetectAt = 0L
    private var lastLogAt = 0L
    @Volatile private var frameCount = 0

    private val _moons = MutableStateFlow<List<Moon>>(emptyList())
    val moons: StateFlow<List<Moon>> = _moons.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _isBack = MutableStateFlow(true)
    val isBack: StateFlow<Boolean> = _isBack.asStateFlow()

    private val _isBound = MutableStateFlow(false)
    val isBound: StateFlow<Boolean> = _isBound.asStateFlow()

    /** 月亮总开关（关闭后只保留普通相机，不再跑检测）。 */
    var moonEnabled by mutableStateOf(true)

    /**
     * 触发态：默认 false —— 初始画面与普通相机完全一致，不显示任何东西。
     * 只有「点击对焦」或「放大到 1.35x 以上」才置 true，月亮才出现。
     */
    var moonMode by mutableStateOf(false)
        private set

    private var moonHideJob: Job? = null

    // —— 单目标锁定 ——
    // 锁定瞬间把目标的归一化位置/半径「冻结」成基准，之后月亮的位置与大小
    // 完全由 (当前变焦 / 锁定变焦) 推算：不依赖逐帧检测，快速捏合也不会丢。
    private var lockX = 0.5f
    private var lockY = 0.5f
    private var baseMoon: Moon? = null
    private var baseZoom = 1f
    private var lostFrames = 0

    fun toggleMoon() {
        moonEnabled = !moonEnabled
        if (!moonEnabled) {
            moonMode = false
            _moons.value = emptyList()
        }
    }

    private fun enterMoonMode(autoHideMs: Long = 0) {
        moonMode = true
        moonHideJob?.cancel()
        if (autoHideMs > 0) {
            moonHideJob = viewModelScope.launch {
                delay(autoHideMs)
                if (_zoomRatio.value < 1.35f) {
                    moonMode = false
                    _moons.value = emptyList()
                }
            }
        }
    }

    /**
     * 点击预览：对焦 + 测光 + 锁定目标。
     * [u] / [v] 是点击位置在分析画面里的归一化坐标，用来挑「离点击点最近」的那个亮斑。
     */
    fun tapFocus(x: Float, y: Float, u: Float, v: Float) {
        val cam = camera
        val view = surfaceView
        if (cam != null && view != null) {
            runCatching {
                val point = view.meteringPointFactory.createPoint(x, y)
                val action = FocusMeteringAction
                    .Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                cam.cameraControl.startFocusAndMetering(action)
            }
        }
        lockX = u
        lockY = v
        baseMoon = null
        baseZoom = 1f
        lostFrames = 0
        enterMoonMode(8000)
    }

    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set

    private var linearZoom = 0f

    init {
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                frameCount++
                if (frameCount % 30 == 1) Log.w(TAG, "frame#$frameCount ${image.width}x${image.height}")
                val now = SystemClock.elapsedRealtime()
                // 未触发（没点对焦、没放大）时完全不检测——初始画面与普通相机一致
                if (!moonEnabled || !moonMode || now - lastDetectAt < DETECT_INTERVAL_MS) return@setAnalyzer
                lastDetectAt = now
                val dets = MoonDetector.detect(image)
                val stable = tracker.update(dets)
                val pick = pickTarget(stable)
                val pred = predicted()
                val agree = pick != null && (
                    pred == null || hypot(pick.x - pred.x, pick.y - pred.y) < 0.20f
                    )
                if (pick != null && agree) {
                    // 检测与几何预测一致 → 以检测结果刷新基准（校正手机移动带来的漂移）
                    baseMoon = pick
                    baseZoom = _zoomRatio.value
                    lostFrames = 0
                    _moons.value = listOf(pick)
                } else {
                    lostFrames++
                    val hold = predicted()
                    _moons.value = if (hold != null && lostFrames <= 40) listOf(hold) else {
                        baseMoon = null
                        lostFrames = 0
                        emptyList()
                    }
                }
                if (now - lastLogAt >= 1500) {
                    lastLogAt = now
                    val pdesc = pick?.let { "x=%.3f y=%.3f r=%.3f".format(it.x, it.y, it.r) } ?: "none"
                    Log.w(TAG, "alive det=${dets.size} stable=${stable.size} pick=$pdesc lost=$lostFrames ${MoonDetector.lastDebug}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "detect failed", t)
            } finally {
                image.close()
            }
        }
    }

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.surfaceView = view
        preview.setSurfaceProvider(view.surfaceProvider)
        val future = ProcessCameraProvider.getInstance(getApplication())
        future.addListener({
            runCatching {
                val provider = future.get()
                provider.unbindAll()
                bindInternal(provider, owner, view)
            }.onFailure { Log.e(TAG, "bind failed", it) }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    private fun bindInternal(provider: ProcessCameraProvider, owner: LifecycleOwner, view: PreviewView) {
        val rotation = view.display?.rotation ?: Surface.ROTATION_0
        preview.targetRotation = rotation
        imageCapture.targetRotation = rotation
        analysis.targetRotation = rotation

        // ViewPort 统一 FOV：没有它，Preview 与 ImageAnalysis 各自裁切，
        // 变焦后两者看到的范围不一致 → 月亮偏移、放大后飞出画面。
        val viewPort = ViewPort.Builder(Rational(3, 4), rotation).build()
        val group = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .addUseCase(analysis)
            .setViewPort(viewPort)
            .build()

        try {
            camera = provider.bindToLifecycle(owner, selector, group)
        } catch (e: IllegalArgumentException) {
            // 三用例并发不受支持：降级为「预览 + 拍照」，检测关闭
            Log.w(TAG, "3-use-case unsupported, fallback to preview+capture", e)
            camera = provider.bindToLifecycle(owner, selector, preview, imageCapture)
        }
        imageCapture.flashMode = flashMode
        linearZoom = 0f
        camera?.cameraControl?.setLinearZoom(0f)
        Log.w(TAG, "bound camera=${camera != null}")
        _isBound.value = true
        tracker.reset()
    }

    /** 双指缩放：增量走 linearZoom，保证 1→2x 与 5→6x 手感一致。 */
    fun pinch(factor: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val span = state.maxZoomRatio / state.minZoomRatio
        if (span <= 1.001f) return
        linearZoom = (linearZoom + ln(factor) / ln(span)).coerceIn(0f, 1f)
        cam.cameraControl.setLinearZoom(linearZoom)
        // 直接用公式推算目标倍率，zoomState 的回读有延迟，会让贴图晚一拍
        _zoomRatio.value = state.minZoomRatio * span.pow(linearZoom)
        refreshPrediction()
        applyMoonTrigger()
    }

    /** 按变焦比例把基准目标推到当前画面：以屏幕中心为锚点缩放。 */
    private fun predicted(): Moon? {
        val b = baseMoon ?: return null
        val z = _zoomRatio.value
        val s = if (baseZoom <= 0.01f) 1f else z / baseZoom
        return b.copy(
            x = 0.5f + (b.x - 0.5f) * s,
            y = 0.5f + (b.y - 0.5f) * s,
            r = b.r * s,
        )
    }

    /** 变焦变化后立刻刷新贴图（不等下一次检测）。 */
    private fun refreshPrediction() {
        predicted()?.let { _moons.value = listOf(it) }
    }

    /**
     * 只保留一个目标：优先离上次锁定位置最近的，首次锁定用点击位置。
     * 这样不会「满屏都是月亮」，也不会在两个亮斑之间来回跳。
     */
    private fun pickTarget(list: List<Moon>): Moon? {
        if (list.isEmpty()) return null
        val cur = predicted()
        val ax = cur?.x ?: lockX
        val ay = cur?.y ?: lockY
        var bestD = if (cur == null) 0.30f else 0.45f
        var best: Moon? = null
        for (m in list) {
            val d = hypot(m.x - ax, m.y - ay)
            if (d < bestD) {
                bestD = d
                best = m
            }
        }
        return best
    }

    /** 放大 → 进月亮模式；回到 1x 附近 → 退出。 */
    private fun applyMoonTrigger() {
        if (_zoomRatio.value >= 1.35f) {
            enterMoonMode()
        } else {
            moonHideJob?.cancel()
            if (moonMode) {
                moonMode = false
                baseMoon = null
                baseZoom = 1f
                lostFrames = 0
                _moons.value = emptyList()
            }
        }
    }

    /** 单击变焦档位（1x / 2x / 5x）。 */
    fun setZoomRatio(target: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.zoomState.value ?: return
        val t = target.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        val span = state.maxZoomRatio / state.minZoomRatio
        linearZoom = if (span <= 1.001f) 0f else (ln(t / state.minZoomRatio) / ln(span)).coerceIn(0f, 1f)
        cam.cameraControl.setLinearZoom(linearZoom)
        _zoomRatio.value = t
        refreshPrediction()
        applyMoonTrigger()
    }

    fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        runCatching { imageCapture.flashMode = flashMode }
    }

    fun switchCamera() {
        val o = owner ?: return
        val v = surfaceView ?: return
        moonMode = false
        baseMoon = null
        baseZoom = 1f
        lostFrames = 0
        _moons.value = emptyList()
        _isBack.value = !_isBack.value
        selector = if (_isBack.value) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val provider = runCatching { ProcessCameraProvider.getInstance(getApplication()).get() }.getOrNull() ?: return
        runCatching {
            provider.unbindAll()
            bindInternal(provider, o, v)
        }.onFailure { Log.e(TAG, "switch failed", it) }
    }

    /**
     * 拍照：抓当前检测快照，落盘后在 IO 线程把月亮烧进照片并写系统相册。
     * @param onSaved 主线程回调，携带最终文件
     */
    fun takePhoto(onSaved: (File) -> Unit) {
        val ctx = getApplication<Application>()
        val file = PhotoStorage.generateFile(ctx)
        val snap = if (moonEnabled) _moons.value else emptyList()
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ioExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    viewModelScope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { MoonComposer.compose(ctx, file, snap) }
                                .onFailure { Log.e(TAG, "compose failed", it) }
                        }
                        onSaved(file)
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePicture failed", exc)
                }
            },
        )
    }

    override fun onCleared() {
        analysisExecutor.shutdown()
        ioExecutor.shutdown()
        super.onCleared()
    }
}
