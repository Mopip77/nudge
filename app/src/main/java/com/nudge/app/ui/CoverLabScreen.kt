package com.nudge.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nudge.app.media.ArtworkCache
import com.nudge.app.media.CoverAspect
import com.nudge.app.media.DEFAULT_COVER_ASPECT
import com.nudge.app.media.TrackInfo
import com.nudge.app.media.squareEdgeFor
import kotlin.math.roundToInt

/** 预览框的高度。够看出上下延伸的层次，又不至于把下面的档位挤出屏幕。 */
private val PREVIEW_HEIGHT = 340.dp

/**
 * 封面实验室，**仅 debug 包可见**（入口在设置页「显示模式」下）。
 *
 * ## 为什么需要它
 *
 * 高清封面走网易云的 `?param=WxH`，而那个接口做的是**居中裁切**——
 * 非方比例会把封面的上下裁掉一截。裁多少好看没法推理：4:5 可能正好
 * 去掉留白，9:16 可能把人脸裁没，取决于封面本身的构图。只能逐档切着看。
 *
 * 用真实的 [AlbumBackdrop] 渲染而不是另画一个预览框——与歌词实验室
 * 「用同一套 composable」同一个口径。若这里另写一份，调出来的比例在
 * 真实场景下未必是同样的观感。
 *
 * ## 界面上必须标注实际尺寸
 *
 * 请求长边超过源图时，接口会**静默回落成方图**：既不是请求的比例，
 * 也没有任何错误提示。所以每一档都印出「这一档实际会拿到多大」，
 * 并把因超限而回落的档位标灰——否则调参时只会看到「怎么选哪档都一样」，
 * 却不知道是被接口吞了。
 *
 * 比例**只存内存**（[CoverOverride]），杀进程即回默认，理由同另两个实验室：
 * 这是取景器，调好之后把值抄回 [DEFAULT_COVER_ASPECT]。
 */
@Composable
fun CoverLabScreen(track: TrackInfo?, onBack: () -> Unit) {
    val context = LocalContext.current
    val screenWidthPx = context.resources.displayMetrics.widthPixels

    // 初值读回上次调的值而非恒取默认，否则来回切主界面／实验室
    // 刚试好的一档白丢（同另两个实验室）。
    var aspect by remember { mutableStateOf(CoverOverride.peek() ?: DEFAULT_COVER_ASPECT) }
    // 「进来看一眼」不该点亮主界面的角标，见 LyricsLabScreen 里同名变量。
    var touched by remember { mutableStateOf(CoverOverride.peek() != null) }

    // 本页自己拉一张预览，不依赖 track.hiResArtwork：主界面只在封面模式下拉，
    // 而实验室要在简洁模式下也能用（正是为了决定要不要切过去）。
    var preview by remember { mutableStateOf(track?.artwork) }
    var actualSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var sourceEdge by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    // 「清缓存重拉」的重启开关。只清状态不改 key 的话 effect 不会重跑，
    // 界面就停在空白上——这与歌词实验室里 shiftEpoch 要 key 在单调代号上
    // 是同一类问题。
    var reloadEpoch by remember { mutableStateOf(0) }

    // **aspect 不在 key 里**：请求恒为方图，换档是纯渲染侧的事，
    // 应当立即重绘、零网络。早先它是 key，每换一档都白重拉一次。
    val mediaId = track?.mediaId
    LaunchedEffect(mediaId, reloadEpoch) {
        actualSize = null
        if (mediaId.isNullOrBlank()) {
            preview = track?.artwork
            return@LaunchedEffect
        }
        loading = true
        val hiRes = ArtworkCache.load(mediaId, screenWidthPx)
        loading = false
        preview = hiRes?.bitmap ?: track.artwork
        actualSize = hiRes?.let { it.bitmap.width to it.bitmap.height }
        sourceEdge = hiRes?.sourceShortEdge ?: sourceEdge
    }

    // 同 LyricsLabScreen：同步写在一个 effect 里，touched 同时作为 key，
    // 否则「恢复默认」的 clear() 会被这个 effect 立刻 set 回去，角标灭不掉。
    LaunchedEffect(aspect, touched) {
        if (touched) CoverOverride.set(aspect)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "封面实验室",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        Text(
            text = "封面恒按方图请求，比例完全在渲染侧裁（居中裁，与网易云 " +
                "?param 的结果逐像素相同）。所以换档立即生效、不重新联网，" +
                "每一档都真的有效——不会再被接口静默回落成方图。" +
                "越竖越满，但左右切得越多，封面上贴边的字会先没。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        // 用真实的 AlbumBackdrop 渲染。预览框比整屏矮，看到的上下延伸段
        // 比真机少，所以调完仍要返回主界面在全屏下确认一遍（同歌词实验室）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            AlbumBackdrop(
                artwork = preview,
                aspect = aspect,
                lyricsMode = false,
                headerHeight = 0.dp,
            )
        }

        SourceInfo(
            track = track,
            actualSize = actualSize,
            sourceEdge = sourceEdge,
            loading = loading,
        )

        SectionTitle("裁切比例")
        CoverAspect.entries.forEach { option ->
            AspectRow(
                aspect = option,
                sourceEdge = sourceEdge,
                targetWidth = screenWidthPx,
                selected = aspect == option,
                onClick = {
                    aspect = option
                    touched = true
                },
            )
        }

        SectionTitle("其他")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
                    aspect = DEFAULT_COVER_ASPECT
                    CoverOverride.clear()
                    touched = false
                }
            ) {
                Text("恢复默认")
            }
            TextButton(
                onClick = {
                    // 缓存键虽然带了 aspect，换比例本就会重拉。这个按钮是给
                    // 「接口这次返回异常」用的：清掉之后重跑一次，省得杀进程。
                    ArtworkCache.clear()
                    actualSize = null
                    sourceEdge = null
                    reloadEpoch++
                }
            ) {
                Text("清缓存重拉")
            }
        }

        Text(text = "", modifier = Modifier.padding(bottom = 24.dp))
    }
}

/** 当前用的是哪张图、源图多大。调参前先要知道自己站在哪。 */
@Composable
private fun SourceInfo(
    track: TrackInfo?,
    actualSize: Pair<Int, Int>?,
    sourceEdge: Int?,
    loading: Boolean,
) {
    val base = track?.artwork
    val lines = buildList {
        add("曲目：${track?.title ?: "未检测到播放"}")
        add("mediaId：${track?.mediaId?.takeIf { it.isNotBlank() } ?: "（空）"}")
        add(
            "MediaSession 封面：" +
                (base?.let { "${it.width}×${it.height}" } ?: "无")
        )
        add("网易云原图短边：" + (sourceEdge?.let { "$it" } ?: "未知"))
        add(
            when {
                loading -> "高清方图：拉取中…"
                // 恒为方图，显示的形状由上面选的比例裁出来。
                actualSize != null ->
                    "高清方图：${actualSize.first}×${actualSize.second}（正在用）"
                // 非网易云播放器、无网络、接口改版都走这条。
                else -> "高清方图：未拉到，正在用 MediaSession 那张"
            }
        )
    }
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        lines.forEach {
            Text(
                text = it,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * 一个比例档位。
 *
 * 标注的是**裁切后在屏幕上的框尺寸**（宽恒为屏幕宽，高按比例），
 * 不再是请求尺寸——请求恒为方图，标请求尺寸所有档都一样，没有区分度。
 */
@Composable
private fun AspectRow(
    aspect: CoverAspect,
    sourceEdge: Int?,
    targetWidth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 实际拿到的方图边长，受源图短边限制。
    val edge = sourceEdge?.let { squareEdgeFor(it, targetWidth) }
    val boxHeight = (targetWidth * aspect.heightOverWidth).roundToInt()
    // 拿到的方图铺不满屏幕宽度，渲染时仍要上采样。
    //
    // 标灰的口径是「达不到屏幕分辨率」，**不是**「这一档坏了」——
    // 现在没有哪一档会坏：比例是自己裁的，接口那个静默回落已经不在链路里。
    // 所以这个信号对所有档是同一个值（受限于源图），只是提示这首歌的原图偏小。
    val underfilled = edge != null && edge < targetWidth
    val alpha = if (underfilled) 0.4f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = aspect.displayName,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
            )
            Text(
                text = when {
                    edge == null -> "${aspect.ratioLabel} → 框 ${targetWidth}×$boxHeight"
                    underfilled ->
                        "${aspect.ratioLabel} → 框 ${targetWidth}×$boxHeight" +
                            "（方图仅 $edge，受限于原图短边 $sourceEdge）"
                    else -> "${aspect.ratioLabel} → 框 ${targetWidth}×$boxHeight（方图 $edge）"
                },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f * alpha),
            )
        }
    }
}
