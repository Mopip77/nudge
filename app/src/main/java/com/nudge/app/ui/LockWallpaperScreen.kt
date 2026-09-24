package com.nudge.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.media.ArtworkCache
import com.nudge.app.media.CoverAspect
import com.nudge.app.media.TrackInfo
import com.nudge.app.wallpaper.BackdropBaker
import com.nudge.app.wallpaper.LockWallpaperConfig
import com.nudge.app.wallpaper.LockWallpaperService
import com.nudge.app.wallpaper.LockWallpaperStore
import com.nudge.app.wallpaper.LockWallpaperWriter
import com.nudge.app.wallpaper.OriginalWallpaperKind
import com.nudge.app.wallpaper.RestorePlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 预览框高度。够看清上下延伸的层次与示意框的相对位置，
 * 又能让下面的滑块留在同屏内——调参时看不到效果就白调了。
 */
private val PREVIEW_HEIGHT = 380.dp

/** 手机屏幕的宽高比，预览框据此定宽，示意框的位置才与真机对得上。 */
private const val SCREEN_RATIO = 9f / 20f

/**
 * 锁屏封面壁纸的设置页（设置页「显示模式」下的二级菜单）。
 *
 * ## 为什么要实时预览
 *
 * 清晰区该摆在哪，**因人而异**：时钟大小、有没有放小组件、One UI 版本
 * 不同，锁屏上「空着的那块」位置都不一样。写死一个 `centerY` 只对作者
 * 自己合适，所以必须让用户自己调，而调的时候必须看得见。
 *
 * ## 预览画的是**成品本身**
 *
 * 直接显示 [BackdropBaker] 烘焙出的那张 bitmap，而不是用 [AlbumBackdrop]
 * 实时渲染一份「差不多的」——两条渲染路径的实现不同（一个 GPU 一个软件
 * 画布），另画一份的话调好了写进去不一样，预览就失去意义。
 *
 * 这比歌词/封面实验室「用同一套 composable」的口径更严格：这里预览的
 * 就是最终会被写进系统的那些字节。
 *
 * 预览框上叠了时钟与媒体卡片的位置示意（半透明线框），否则用户没法判断
 * 清晰区会不会正好被系统的卡片压住——而那正是要调 `centerY` 的原因。
 */
@Composable
fun LockWallpaperScreen(track: TrackInfo?, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { LockWallpaperStore(context) }
    val writer = remember { LockWallpaperWriter(context) }

    var cfg by remember { mutableStateOf<LockWallpaperConfig?>(null) }
    var cover by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var originalKind by remember { mutableStateOf<OriginalWallpaperKind?>(null) }
    var hasRestoreImage by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cfg = store.currentConfig()
        hasRestoreImage = store.userSuppliedFile.exists()
        // 探测只在**还没开启**时做：开启之后系统里那张就是我们写的，
        // 再探测只会拿到被自己污染的结果。已开启时读持久化的那个。
        originalKind = if (cfg?.enabled == true) store.originalKind() else writer.detectOriginalKind()
    }

    // 取一张封面用于预览。没有正在播放的歌就没得预览——
    // 这是刻意的，用假图调参调出来的值不作数。
    val mediaId = track?.mediaId
    LaunchedEffect(mediaId) {
        val (w, _) = BackdropBaker.screenSize(context)
        cover = if (!mediaId.isNullOrBlank()) {
            ArtworkCache.load(mediaId, w)?.bitmap ?: track.artwork
        } else {
            track?.artwork
        }
    }

    // 参数一变就重新烘焙。烘焙在 IO 线程——全屏图有百来毫秒，
    // 放主线程会让滑块拖起来一顿一顿的。
    val c = cfg
    val src = cover
    LaunchedEffect(src, c?.renderFingerprint) {
        if (src == null || c == null) {
            preview = null
            return@LaunchedEffect
        }
        val (w, h) = BackdropBaker.screenSize(context)
        preview = withContext(Dispatchers.IO) {
            runCatching {
                // 预览按屏幕比例缩小烘焙：观感与成品一致（几何全是比例量），
                // 而重算快得多，拖滑块才跟得上。
                BackdropBaker.bake(src, w / 3, h / 3, context.resources.displayMetrics.density / 3f, c)
            }.getOrNull()
        }
    }

    if (c == null) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("锁屏封面壁纸", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PreviewBox(preview)
        }

        SectionTitle("开关")

        val canEnable = originalKind?.let { RestorePlan.canEnable(it, hasRestoreImage) } ?: false
        SwitchRow(
            label = "启用",
            hint = when {
                // 动态壁纸单独说，且**不能**引导用户去选恢复图——
                // 选了也没用，静态图替不回动态壁纸。
                originalKind == OriginalWallpaperKind.LIVE_WALLPAPER ->
                    "你的锁屏用的是动态壁纸，而应用无权重新设置动态壁纸，" +
                        "开启后将无法还原。请先到「设置 → 壁纸」把锁屏换成普通图片再来。"
                !canEnable ->
                    "你设过独立的锁屏壁纸，而系统不允许应用读取它（Android 13 起）。" +
                        "请先在下方指定一张「恢复用壁纸」，否则关闭功能后回不到原样。"
                originalKind == OriginalWallpaperKind.INHERITED ->
                    "关闭时会清除锁屏壁纸，回到跟随桌面壁纸的状态"
                else -> "关闭时会写回你指定的恢复用壁纸"
            },
            checked = c.enabled,
            enabled = canEnable || c.enabled,
            onChange = { on ->
                cfg = c.copy(enabled = on)
                store.saveBlocking(c.copy(enabled = on))
                if (on) {
                    originalKind?.let { store.saveOriginalKindBlocking(it) }
                    LockWallpaperService.start(context)
                } else {
                    LockWallpaperService.stop(context)
                }
            },
        )

        SectionTitle("构图")

        LockSlider(
            label = "清晰区中心",
            value = c.centerY,
            range = LockWallpaperConfig.CENTER_Y_RANGE,
            display = "%.2f".format(c.centerY),
            hint = "封面最清晰的那一段落在屏幕高度的哪个位置。往下挪可以避开时钟",
            onChange = { cfg = c.copy(centerY = it) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        LockSlider(
            label = "清晰区大小",
            value = c.sharpScale,
            range = LockWallpaperConfig.SHARP_SCALE_RANGE,
            display = "%.2f".format(c.sharpScale),
            hint = "越小清晰的那一段越窄，往外模糊延伸得越多",
            onChange = { cfg = c.copy(sharpScale = it) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        LockSlider(
            label = "模糊强度",
            value = c.maxBlurDp,
            range = LockWallpaperConfig.MAX_BLUR_RANGE,
            display = "${c.maxBlurDp.roundToInt()}dp",
            hint = "最远处那一档的模糊半径",
            onChange = { cfg = c.copy(maxBlurDp = it) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        LockSlider(
            label = "压暗",
            value = c.scrimAlpha,
            range = LockWallpaperConfig.SCRIM_RANGE,
            display = "%.2f".format(c.scrimAlpha),
            hint = "浅色封面下要压暗一些，系统的白色时钟才读得清",
            onChange = { cfg = c.copy(scrimAlpha = it) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        SectionTitle("裁切比例")
        Text(
            text = "宽度恒铺满，高度按比例。越竖占屏越满，但左右切得越多",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        CoverAspect.entries.forEach { a ->
            AspectRow(
                aspect = a,
                selected = a == c.aspect,
                onSelect = {
                    cfg = c.copy(aspect = a)
                    store.saveBlocking(c.copy(aspect = a))
                },
            )
        }

        SectionTitle("行为")

        LockSlider(
            label = "暂停多久后恢复",
            value = c.restoreDelaySec.toFloat(),
            range = LockWallpaperConfig.RESTORE_DELAY_RANGE.first.toFloat()..
                LockWallpaperConfig.RESTORE_DELAY_RANGE.last.toFloat(),
            display = "${c.restoreDelaySec}s",
            hint = "太短的话，切歌间隙和来电都会让壁纸闪一下",
            onChange = { cfg = c.copy(restoreDelaySec = it.roundToInt()) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        SwitchRow(
            label = "熄屏时不更新",
            hint = "屏幕黑着时先攒住，亮屏再写。每次写入都是一次几 MB 的落盘，" +
                "熄屏时写了也看不见",
            checked = c.deferWhileScreenOff,
            onChange = {
                cfg = c.copy(deferWhileScreenOff = it)
                store.saveBlocking(c.copy(deferWhileScreenOff = it))
            },
        )

        SwitchRow(
            label = "非网易云时用低清封面",
            hint = "其他播放器拿不到高清图，只有 363px 的缩略图。关掉则不换壁纸",
            checked = c.fallbackToLowRes,
            onChange = {
                cfg = c.copy(fallbackToLowRes = it)
                store.saveBlocking(c.copy(fallbackToLowRes = it))
            },
        )

        LockSlider(
            label = "渲染分辨率",
            value = c.renderScale,
            range = LockWallpaperConfig.RENDER_SCALE_RANGE,
            display = "%.0f%%".format(c.renderScale * 100),
            // hint 是普通字符串不走 format，百分号不能转义——写成 %% 会原样显示
            hint = "调低能明显加快写入（实测 100% 约 1.5 秒，70% 约 0.8 秒），代价是清晰度",
            onChange = { cfg = c.copy(renderScale = it) },
            onCommit = { store.saveBlocking(cfg ?: c) },
        )

        SectionTitle("恢复用壁纸")
        Text(
            text = if (hasRestoreImage) {
                "已设置。关闭功能时会写回这张图。"
            } else {
                "Android 13 起系统不允许应用读取现有壁纸，所以没法自动备份。" +
                    "若你设过独立的锁屏壁纸，需要自己指定一张，否则关闭后回不到原样。"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        // Photo Picker：Android 13+ 的系统相册选择器，**不需要任何存储权限**
        // （用户选哪张就授哪张的临时读权限）。这正好绕开了「读不到原壁纸」
        // 那条限制——我们读不了壁纸，但用户可以把同一张图交给我们。
        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { input ->
                        android.graphics.BitmapFactory.decodeStream(input)
                    }
                }.getOrNull()?.let {
                    // 存副本而不是记 Uri：用户选的那张之后可能被删或权限被回收，
                    // 而恢复要等到「关掉功能」时才发生，中间可能隔几个月。
                    store.saveUserSupplied(it)
                    hasRestoreImage = true
                }
            }
        }

        TextButton(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(if (hasRestoreImage) "重新选择" else "选择一张图片")
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 预览框。按屏幕比例摆，叠上时钟与媒体卡片的位置示意。
 *
 * 示意框是必要的：调 `centerY` 的目的就是让清晰区避开这两块，
 * 光看一张图判断不了它们会落在哪。
 */
@Composable
private fun PreviewBox(preview: Bitmap?) {
    Box(
        modifier = Modifier
            // 固定高度而非按屏幕比例撑满：预览框要与下面的滑块同屏才有意义
            // ——调参时看不到效果就白调了。宽度由高度按屏幕比例反推，
            // 保证「示意框落在哪」与真机一致。
            .height(PREVIEW_HEIGHT)
            .aspectRatio(SCREEN_RATIO)
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 时钟与媒体卡片的位置示意。比例量自真机 One UI 5.1 的锁屏截图
            // （1080×2400 上时钟约 400~640px、媒体卡片约 730~1120px）。
            GuideBox(topFraction = 0.17f, heightFraction = 0.10f, label = "时钟")
            GuideBox(topFraction = 0.30f, heightFraction = 0.16f, label = "媒体卡片")
        } else {
            Text(
                "播放一首歌后才能预览",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 位置示意框。用 `BoxWithConstraints` 按比例换算成 dp 偏移，
 * 而不是靠嵌套 Column 的 weight——后者要为「上方留白」造一个空 Spacer，
 * 比例一改两处都得跟着算。
 */
@Composable
private fun BoxScope.GuideBox(topFraction: Float, heightFraction: Float, label: String) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val h = maxHeight
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp)
                .offset(y = h * topFraction)
                .height(h * heightFraction)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    hint: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground
                    .copy(alpha = if (enabled) 1f else 0.4f),
            )
            Text(
                text = hint,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * 参数滑块。
 *
 * [onChange] 每帧都调（驱动预览重绘），[onCommit] 只在松手时调——
 * 拖动过程中每帧写一次 DataStore 会让服务侧的配置流疯狂重放。
 */
@Composable
private fun LockSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    hint: String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = display,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = hint,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onCommit,
            valueRange = range,
        )
    }
}

@Composable
private fun AspectRow(aspect: CoverAspect, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = aspect.displayName,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
