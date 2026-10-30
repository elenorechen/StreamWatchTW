package tw.plli.streamwatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知上的「知道了」按鈕 */
class AckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        MonitorEngine.ack(context, pkg)
    }

    companion object {
        const val EXTRA_PKG = "pkg"
    }
}
