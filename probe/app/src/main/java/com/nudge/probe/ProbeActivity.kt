package com.nudge.probe

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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

    private fun dump() {
        output.text = ""
        val c = controller() ?: return
        val state = c.playbackState
        val md = c.metadata

        log("=== PlaybackState ===")
        log("state=${state?.state} actions=${state?.actions}")
        log("ratingType=${c.ratingType}")

        log("=== CustomActions (action id 是关键) ===")
        state?.customActions?.forEach {
            log("  action='${it.action}'  name='${it.name}'")
        }

        log("=== Metadata keys (共 ${md?.keySet()?.size}) ===")
        md?.keySet()?.forEach { key ->
            val v = when {
                key.contains("RATING") -> md.getRating(key)?.let {
                    "isRated=${it.isRated} hasHeart=${it.hasHeart()} style=${it.ratingStyle}"
                } ?: "null"
                else -> md.getString(key) ?: md.getLong(key).toString()
            }
            log("  $key = $v")
        }
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
