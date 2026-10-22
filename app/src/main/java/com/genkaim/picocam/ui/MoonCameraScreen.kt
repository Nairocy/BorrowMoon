package com.genkaim.picocam.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.genkaim.picocam.MoonViewModel
import com.genkaim.picocam.camera.PhotoStorage
import com.genkaim.picocam.moon.Moon
import com.genkaim.picocam.moon.MoonComposer
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

// ───────────────────────────── 设计变量（红黑） ─────────────────────────────

private val Ink = Color(0xFF000000)
private val Red = Color(0xFFE1121F)
private val RedDeep = Color(0xFF8E0E16)
private val Paper = Color(0xFFF2F2F2)
private val Dim = Color(0xFF8F8F8F)
private val Glass = Color(0xB8000000)
private val GlassEdge = Color(0x22FFFFFF)

private val GlassShape = RoundedCornerShape(28.dp)

@Composable
fun MoonCameraScreen(vm: MoonViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        if (granted) {
            CameraContent(vm)
        } else {
            PermissionWall { launcher.launch(Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun PermissionWall(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("需要相机权限", color = Paper, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "这个 App 只在本机处理画面，不联网、不上传。",
            color = Dim, fontSize = 13.sp,
        )
        Spacer(Modifier.height(28.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Red)
                .clickable(onClick = onRequest)
                .padding(horizontal = 28.dp, vertical = 14.dp),
        ) {
            Text("允许访问相机", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CameraContent(vm: MoonViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val moons by vm.moons.collectAsStateWithLifecycle()
    val zoom by vm.zoomRatio.collectAsStateWithLifecycle()
    val isBack by vm.isBack.collectAsStateWithLifecycle()
    val bound by vm.isBound.collectAsStateWithLifecycle()

    var lastShot by remember { mutableStateOf<File?>(null) }
    var flashAlpha by remember { mutableStateOf(0f) }
    var shutterDown by remember { mutableStateOf(false) }

    LaunchedEffect(flashAlpha) {
        if (flashAlpha > 0f) {
            kotlinx.coroutines.delay(120)
            flashAlpha = 0f
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ── 全屏预览 ──
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    vm.bind(lifecycleOwner, this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // 把点击位置换算成分析画面的归一化坐标，交给 VM 做目标锁定
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val ca = if (h >= w) 3f / 4f else 4f / 3f
                        val va = w / h
                        val dw: Float
                        val dh: Float
                        if (va > ca) {
                            dw = w; dh = dw / ca
                        } else {
                            dh = h; dw = dh * ca
                        }
                        val ox = (w - dw) / 2f
                        val oy = (h - dh) / 2f
                        vm.tapFocus(
                            offset.x, offset.y,
                            ((offset.x - ox) / dw).coerceIn(0f, 1f),
                            ((offset.y - oy) / dh).coerceIn(0f, 1f),
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        vm.pinch(zoomChange)
                    }
                },
        )

        // ── 月亮叠加：只有点击对焦 / 放大后才出现，初始画面与普通相机一致 ──
        if (vm.moonEnabled && vm.moonMode) {
            MoonOverlay(moons)
        }

        // ── 顶部：玻璃胶囊 ──
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircle(onClick = { vm.toggleFlash() }) {
                Icon(
                    imageVector = when (vm.flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Outlined.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Outlined.FlashAuto
                        else -> Icons.Outlined.FlashOff
                    },
                    contentDescription = "闪光灯",
                    tint = Paper,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            MoonToggle(vm.moonEnabled) { vm.toggleMoon() }
            Spacer(Modifier.width(10.dp))
            GlassCircle(onClick = { vm.switchCamera() }) {
                Icon(
                    Icons.Outlined.Cameraswitch,
                    contentDescription = "切换摄像头",
                    tint = if (isBack) Paper else Red,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // ── 底部：变焦 + 快门 ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ZoomChips(zoom, enabled = bound) { vm.setZoomRatio(it) }
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(36.dp))
                    .background(Glass)
                    .border(1.dp, GlassEdge, RoundedCornerShape(36.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 相册缩略图
                Box(
                    Modifier
                        .size(50.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x1AFFFFFF))
                        .border(1.dp, GlassEdge, RoundedCornerShape(13.dp))
                        .clickable { openGallery(context) },
                    contentAlignment = Alignment.Center,
                ) {
                    val shot = lastShot
                    if (shot != null) {
                        val bmp = remember(shot) { runCatching { decodeThumb(shot) }.getOrNull() }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                    } else {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            contentDescription = "相册",
                            tint = Dim,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                // 快门
                val sc by animateFloatAsState(if (shutterDown) 0.88f else 1f, tween(90), label = "shutter")
                Box(
                    Modifier
                        .size(78.dp)
                        .scale(sc)
                        .clip(CircleShape)
                        .border(3.dp, Color(0xE6FFFFFF), CircleShape)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    shutterDown = true
                                    tryAwaitRelease()
                                    shutterDown = false
                                },
                                onTap = {
                                    flashAlpha = 0.7f
                                    vm.takePhoto { f -> lastShot = f }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(62.dp)
                            .clip(CircleShape)
                            .background(Brush.verticalGradient(listOf(Red, RedDeep))),
                    )
                }

                // 占位，保持快门居中
                Box(Modifier.size(50.dp))
            }
        }

        // ── 快门闪白 ──
        AnimatedVisibility(visible = flashAlpha > 0f, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))
        }
    }
}

// ───────────────────────────── 叠加层 ─────────────────────────────

@Composable
private fun MoonOverlay(moons: List<Moon>) {
    if (moons.isEmpty()) return
    val context = LocalContext.current
    val tex = remember { MoonComposer.texture(context).asImageBitmap() }

    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        // 预览内容按 4:3 出流，竖屏显示为 3:4；FILL_CENTER 中心裁切
        // FILL_CENTER = center crop：取 max(W/cw, H/ch) 的缩放，谁溢出就裁谁。
        // 之前分支写反，导致竖屏时按宽度适配（实际应放大约 1.7 倍铺满），
        // 月亮位置和尺寸会一起错——这是「预览与成片不符」的根因。
        val contentAspect = if (size.height >= size.width) 3f / 4f else 4f / 3f
        val viewAspect = size.width / size.height
        val dw: Float
        val dh: Float
        if (viewAspect > contentAspect) {
            // 容器更宽 → 内容按宽度铺满，高度溢出
            dw = size.width
            dh = dw / contentAspect
        } else {
            // 容器更高 → 内容按高度铺满，宽度溢出
            dh = size.height
            dw = dh * contentAspect
        }
        val ox = (size.width - dw) / 2f
        val oy = (size.height - dh) / 2f

        for (m in moons) {
            val cx = ox + m.x * dw
            val cy = oy + m.y * dh
            val rad = m.r * dw * (MoonComposer.SIZE_FACTOR / 2f)
            if (rad < 4f) continue

            // 月晕：Compose 叠加层与 PreviewView 是两个独立图层，做不了真正的 SCREEN 混合，
            // 所以用「先画一圈径向辉光」来伪造发光感，和拍照合成保持同一套参数。
            val glowR = rad * 2.0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x59FFFFFF), Color(0x26FFF3D6), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = glowR,
                    tileMode = TileMode.Clamp,
                ),
                radius = glowR,
                center = Offset(cx, cy),
            )
            drawImage(
                image = tex,
                dstOffset = IntOffset((cx - rad).roundToInt(), (cy - rad).roundToInt()),
                dstSize = IntSize((rad * 2f).roundToInt(), (rad * 2f).roundToInt()),
                alpha = 0.88f,
                style = androidx.compose.ui.graphics.drawscope.Fill,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

// ───────────────────────────── 组件 ─────────────────────────────

@Composable
private fun GlassCircle(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun MoonToggle(on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(if (on) Red.copy(alpha = 0.92f) else Glass)
            .border(1.dp, if (on) Color(0x55FF6B72) else GlassEdge, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.DarkMode,
            contentDescription = null,
            tint = if (on) Color.White else Dim,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (on) "月亮" else "原图",
            color = if (on) Color.White else Dim,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = FontFamily.Default,
        )
    }
}

@Composable
private fun ZoomChips(current: Float, enabled: Boolean, onPick: (Float) -> Unit) {
    val stops = listOf(1f, 2f, 5f)
    Row(
        Modifier
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassEdge, CircleShape)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in stops) {
            val selected = abs(current - s) < 0.25f
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) Red else Color.Transparent)
                    .clickable(enabled = enabled) { onPick(s) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${s.toInt()}x",
                    color = if (selected) Color.White else if (enabled) Paper.copy(alpha = 0.72f) else Dim,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

// ───────────────────────────── 工具 ─────────────────────────────

private fun decodeThumb(file: File): Bitmap? = runCatching {
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
    android.graphics.BitmapFactory.decodeFile(file.path, opts)
}.getOrNull()

private fun openGallery(context: android.content.Context) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_GALLERY)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
