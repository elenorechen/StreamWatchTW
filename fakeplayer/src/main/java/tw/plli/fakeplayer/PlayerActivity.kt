package tw.plli.fakeplayer

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * 測試用：假裝成音樂 App。
 * adb shell am start -n tw.plli.fakeplayer/.PlayerActivity --es state playing|paused|buffering
 */
class PlayerActivity : Activity() {

    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        label = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
        }
        setContentView(label)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        val s = session ?: MediaSession(applicationContext, "fakeplayer").also { session = it }
        val state = i?.getStringExtra("state") ?: "playing"
        val code = when (state) {
            "playing" -> PlaybackState.STATE_PLAYING
            "buffering" -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, i?.getStringExtra("title") ?: "Way 4 Luv")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "PLAVE")
                .build()
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(code, 0L, 1f)
                .build()
        )
        s.isActive = true
        label.text = "測試播放器\n狀態：$state"
    }

    companion object {
        var session: MediaSession? = null
    }
}
