package com.nudge.probe

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.media.Rating
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

private const val TAG = "NudgeProbe"
private const val NETEASE = "com.netease.cloudmusic"

/**
 * 探针：验证对网易云 MediaSession 的收藏（红心）调用是否生效。
 *
 * 可通过 adb 驱动，无需触碰手机：
 *   adb shell am broadcast -a com.nudge.probe.DUMP
 *   adb shell am broadcast -a com.nudge.probe.RATE
 *   adb shell am broadcast -a com.nudge.probe.CUSTOM
 * 结果同时输出到 logcat(TAG=NudgeProbe) 和界面。
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var output: TextView

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.nudge.probe.DUMP" -> dump()
                "com.nudge.probe.RATE" -> sendRating()
                "com.nudge.probe.CUSTOM" -> sendCustomLike()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(Button(this@ProbeActivity).apply {
                text = "0. 授予通知使用权"
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            })
            addView(Button(this@ProbeActivity).apply {
                text = "1. Dump 会话信息"
                setOnClickListener { dump() }
            })
            addView(Button(this@ProbeActivity).apply {
                text = "2. setRating(heart=true)"
                setOnClickListener { sendRating() }
            })
            addView(Button(this@ProbeActivity).apply {
                text = "3. sendCustomAction(like)"
                setOnClickListener { sendCustomLike() }
            })
            addView(ScrollView(this@ProbeActivity).apply { addView(output) })
        }
        setContentView(root)

        val filter = IntentFilter().apply {
            addAction("com.nudge.probe.DUMP")
            addAction("com.nudge.probe.RATE")
            addAction("com.nudge.probe.CUSTOM")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread { output.append(msg + "\n") }
    }

    /** 取网易云的 MediaController，取不到时返回 null 并已打日志。 */
    private fun controller(): MediaController? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, ProbeNotificationListener::class.java)
        val sessions = try {
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            log("!! 无通知使用权: ${e.message}")
            return null
        }
        val c = sessions.firstOrNull { it.packageName == NETEASE }
        if (c == null) log("!! 未找到网易云会话，当前: ${sessions.map { it.packageName }}")
        return c
    }

    /** 全量 dump：遍历所有会话，把每个字段按真实类型读出来。 */
    private fun dump() {
        output.text = ""
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, ProbeNotificationListener::class.java)
        val sessions = try {
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            log("!! 无通知使用权: ${e.message}")
            return
        }

        log("共 ${sessions.size} 个会话: ${sessions.map { it.packageName }}")
        sessions.forEachIndexed { i, c ->
            log("")
            log("######## [$i] ${c.packageName} ########")
            dumpController(c)
        }
    }

    private fun dumpController(c: MediaController) {
        val state = c.playbackState
        val md = c.metadata

        log("=== Controller ===")
        log("  sessionTag=${c.tag}")
        log("  ratingType=${c.ratingType}  (0=NONE 1=HEART 2=THUMB 3/4/5=STARS 6=PERCENT)")
        log("  sessionActivity=${c.sessionActivity != null}")
        c.playbackInfo?.let {
            log("  volume: type=${it.playbackType} current=${it.currentVolume}/${it.maxVolume} " +
                "control=${it.volumeControl}")
            log("  audioAttrs=${it.audioAttributes}")
        }
        dumpBundle("  sessionExtras", c.extras)

        log("=== PlaybackState ===")
        if (state == null) {
            log("  null")
        } else {
            log("  state=${state.state} (${stateName(state.state)})")
            log("  position=${state.position}ms (${fmtMs(state.position)})")
            log("  bufferedPosition=${state.bufferedPosition}ms")
            log("  playbackSpeed=${state.playbackSpeed}")
            log("  lastPositionUpdateTime=${state.lastPositionUpdateTime} (SystemClock.elapsedRealtime 基准)")
            log("  activeQueueItemId=${state.activeQueueItemId}")
            log("  errorMessage=${state.errorMessage}")
            log("  actions=${state.actions} -> ${decodeActions(state.actions)}")
            dumpBundle("  stateExtras", state.extras)

            log("  --- CustomActions (${state.customActions.size}) ---")
            state.customActions.forEach {
                log("    action='${it.action}'  name='${it.name}'  icon=${it.icon}")
                dumpBundle("      extras", it.extras)
            }
        }

        log("=== Metadata (共 ${md?.keySet()?.size ?: 0} key) ===")
        if (md == null) {
            log("  null")
        } else {
            md.keySet().sorted().forEach { key ->
                log("  ${key.removePrefix("android.media.metadata.")} = ${readMetaValue(md, key)}")
            }
        }

        log("=== Queue ===")
        val queue = c.queue
        log("  queueTitle=${c.queueTitle}  size=${queue?.size ?: 0}")
        queue?.take(10)?.forEach { item ->
            val d = item.description
            log("    id=${item.queueId} title='${d.title}' subtitle='${d.subtitle}' " +
                "mediaId=${d.mediaId} iconUri=${d.iconUri}")
        }
        if ((queue?.size ?: 0) > 10) log("    ... 省略 ${queue!!.size - 10} 项")
    }

    /**
     * 按真实类型读取 metadata。MediaMetadata 没有公开的类型查询 API，
     * 因此逐个类型试探：Bitmap/Rating/String/Long，避免 getString 把 Bitmap 读成 null。
     */
    private fun readMetaValue(md: MediaMetadata, key: String): String {
        runCatching { md.getBitmap(key) }.getOrNull()?.let {
            return "Bitmap ${it.width}x${it.height} config=${it.config} bytes=${it.byteCount}"
        }
        runCatching { md.getRating(key) }.getOrNull()?.let {
            return "Rating(isRated=${it.isRated} hasHeart=${it.hasHeart()} " +
                "style=${it.ratingStyle} percent=${runCatching { it.percentRating }.getOrNull()})"
        }
        runCatching { md.getString(key) }.getOrNull()?.let { return "\"$it\"" }
        val l = runCatching { md.getLong(key) }.getOrDefault(0L)
        if (l != 0L) {
            return if (key.endsWith("DURATION")) "$l  (${fmtMs(l)})" else "$l"
        }
        // 走到这里说明是 0L 或读不出来的类型（如 Uri 以 String 存、或空值）
        return "<empty/0>"
    }

    private fun dumpBundle(label: String, b: Bundle?) {
        if (b == null || b.isEmpty) return
        log("$label (${b.size()}):")
        b.keySet().forEach { k ->
            @Suppress("DEPRECATION")
            log("    $k = ${runCatching { b.get(k) }.getOrNull()}")
        }
    }

    private fun stateName(s: Int) = when (s) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_ERROR -> "ERROR"
        else -> "other($s)"
    }

    /** 把 actions 位掩码拆成可读名字，用于判断哪些手势值得绑。 */
    private fun decodeActions(actions: Long): String {
        val names = listOf(
            PlaybackState.ACTION_STOP to "STOP",
            PlaybackState.ACTION_PAUSE to "PAUSE",
            PlaybackState.ACTION_PLAY to "PLAY",
            PlaybackState.ACTION_REWIND to "REWIND",
            PlaybackState.ACTION_SKIP_TO_PREVIOUS to "SKIP_TO_PREVIOUS",
            PlaybackState.ACTION_SKIP_TO_NEXT to "SKIP_TO_NEXT",
            PlaybackState.ACTION_FAST_FORWARD to "FAST_FORWARD",
            PlaybackState.ACTION_SET_RATING to "SET_RATING",
            PlaybackState.ACTION_SEEK_TO to "SEEK_TO",
            PlaybackState.ACTION_PLAY_PAUSE to "PLAY_PAUSE",
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID to "PLAY_FROM_MEDIA_ID",
            PlaybackState.ACTION_PLAY_FROM_SEARCH to "PLAY_FROM_SEARCH",
            PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM to "SKIP_TO_QUEUE_ITEM",
            PlaybackState.ACTION_PLAY_FROM_URI to "PLAY_FROM_URI",
            PlaybackState.ACTION_PREPARE to "PREPARE",
            PlaybackState.ACTION_SET_PLAYBACK_SPEED to "SET_PLAYBACK_SPEED",
        )
        val hit = names.filter { actions and it.first != 0L }.map { it.second }
        return if (hit.isEmpty()) "<none>" else hit.joinToString("|")
    }

    private fun fmtMs(ms: Long): String {
        if (ms < 0) return "$ms"
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun sendRating() {
        val c = controller() ?: return
        log(">>> setRating(newHeartRating(true))")
        c.transportControls.setRating(Rating.newHeartRating(true))
        log("    已发送，请查看网易云红心状态")
    }

    private fun sendCustomLike() {
        val c = controller() ?: return
        val like = c.playbackState?.customActions?.firstOrNull {
            it.action.contains("like", true) || it.name.toString().contains("like", true)
        }
        if (like == null) {
            log("!! 未找到 like custom action")
            return
        }
        log(">>> sendCustomAction('${like.action}')")
        c.transportControls.sendCustomAction(like.action, null)
        log("    已发送，请查看网易云红心状态")
    }
}
