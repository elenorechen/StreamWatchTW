package tw.plli.streamwatch

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var setupBox: LinearLayout
    private lateinit var masterBtn: TextView
    private lateinit var masterHint: TextView
    private lateinit var appsBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private lateinit var remoteBox: LinearLayout
    private lateinit var statsText: TextView
    private var pendingShowCard = false

    private class CardViews(
        val chip: TextView,
        val detail: TextView,
        val pauseBtn: TextView,
        val autoSwitch: Switch
    )

    private val cards = HashMap<String, CardViews>()
    private var lastSetupKey = ""

    private val refresher = object : Runnable {
        override fun run() {
            refreshLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Alerts.ensureChannels(this)
        window.statusBarColor = Pal.bg
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val scroll = ScrollView(this).apply { setBackgroundColor(Pal.bg) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(40))
        }
        scroll.addView(root)
        setContentView(scroll)

        // ---- 標題 ----
        root.addView(label("STREAM WATCH · 台灣中文版", 11f, Pal.sub, bold = true))
        root.addView(label("刷瓜小幫手", 28f, Pal.text, bold = true).margins(this, top = 4) )
        root.addView(label("刷歌手機停止播放時，通知你的手機和電腦。", 14f, Pal.sub).margins(this, top = 4, bottom = 14))

        // ---- 初次設定檢查 ----
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(setupBox)

        // ---- 主開關 ----
        masterBtn = pillButton("", Pal.greenStrong, android.graphics.Color.WHITE) { toggleMaster() }
        masterBtn.textSize = 17f
        masterBtn.setPadding(dp(12), dp(15), dp(12), dp(15))
        root.addView(masterBtn.margins(this, top = 6))
        masterHint = label("", 13f, Pal.sub).apply { gravity = Gravity.CENTER }
        root.addView(masterHint.margins(this, top = 6))

        root.addView(
            pillButton("＋ 選擇要監控的 App", Pal.purple, Pal.purpleText) { showAppPicker() }
                .margins(this, top = 10)
        )

        // ---- App 卡片 ----
        root.addView(sectionTitle("各 App 狀態"))
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appsBox)

        // ---- 刷歌紀錄 ----
        root.addView(sectionTitle("今日刷歌紀錄"))
        val statsBox = cardBox()
        statsText = label("", 14f, Pal.text)
        statsBox.addView(statsText)
        statsBox.addView(pillButton("📸 產生分享小卡", Pal.green, Pal.greenText) { showCardDialog() }.margins(this, top = 12))
        statsBox.addView(label("只記錄「監控中」的時間，資料只存在這支手機。", 12f, Pal.sub).margins(this, top = 8))
        root.addView(statsBox)
        pendingShowCard = intent?.getBooleanExtra(EXTRA_SHOW_CARD, false) == true

        // ---- 通知設定 ----
        root.addView(sectionTitle("通知設定"))
        settingsBox = cardBox()
        root.addView(settingsBox)

        // ---- 其他裝置 ----
        root.addView(sectionTitle("傳送到日常手機和電腦"))
        remoteBox = cardBox()
        root.addView(remoteBox)

        // ---- 狀態說明 ----
        root.addView(sectionTitle("狀態說明"))
        root.addView(buildLegend())

        root.addView(
            label(
                "※ 這個 App 只會提醒，不會自動幫你恢復播放。\n※ 只讀取音樂 App 的播放狀態，不會讀取或保存其他通知內容。",
                12f, Pal.sub
            ).margins(this, top = 18)
        )
    }

    override fun onResume() {
        super.onResume()
        WatchListenerService.requestRebindIfNeeded(this)
        lastSetupKey = ""
        rebuildApps()
        rebuildSettings()
        rebuildRemote()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
        if (pendingShowCard) {
            pendingShowCard = false
            handler.postDelayed({ showCardDialog() }, 600)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_CARD, false)) pendingShowCard = true
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    private fun sectionTitle(t: String): TextView {
        val tv = label(t, 17f, Pal.text, bold = true)
        tv.margins(this, top = 26, bottom = 10)
        return tv
    }

    // ================= 權限檢查 =================

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, WatchListenerService::class.java)
        return flat.split(":").any {
            val cn = ComponentName.unflattenFromString(it)
            cn != null && cn.packageName == me.packageName && cn.className == me.className
        }
    }

    private fun hasPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ignoresBattery(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun refreshSetup() {
        val a = hasListenerAccess()
        val n = hasPostNotifications()
        val b = ignoresBattery()
        val connected = MonitorEngine.listenerConnected
        val key = "$a$n$b$connected"
        if (key == lastSetupKey) return
        lastSetupKey = key

        setupBox.removeAllViews()
        if (a && n && b && connected) return

        val box = cardBox().apply { background = rounded(Pal.warnBg, 20) }
        box.addView(label("先完成這幾個設定", 16f, Pal.warnText, bold = true))

        box.addView(setupRow("① 通知存取權", a, "用來讀取音樂 App 的播放狀態") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        if (!a) {
            box.addView(label(
                "找到「刷瓜小幫手」並開啟。如果開關是灰色按不下去：到手機「設定 → 應用程式 → 刷瓜小幫手」，按右上角「⋮」→「允許受限制的設定」，再回來開。",
                12f, Pal.warnText
            ).margins(this, top = 4))
            box.addView(label("開啟 App 資訊頁 ›", 13f, Pal.purpleText, bold = true).apply {
                setPadding(0, dp(6), 0, dp(4))
                setOnClickListener {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            })
        } else if (!connected) {
            box.addView(label(
                "已開啟，但服務還沒連上。請把通知存取權關掉再打開一次，或重新開機。",
                12f, Pal.warnText
            ).margins(this, top = 4))
        }

        box.addView(setupRow("② 允許通知", n, "停止時才能跳出提醒") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        })
        box.addView(setupRow("③ 電池不要最佳化", b, "避免手機休眠時監控被停掉") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        setupBox.addView(box.margins(this, bottom = 12))
    }

    private fun setupRow(title: String, done: Boolean, desc: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Pal.text, bold = true))
        texts.addView(label(desc, 12f, Pal.sub))
        row.addView(texts, lp(0, WRAP, 1f))
        if (done) {
            row.addView(label("✓ 完成", 14f, Pal.okText, bold = true))
        } else {
            row.addView(pillButton("去設定", Pal.card, Pal.warnText, onClick).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
            })
        }
        return row
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        lastSetupKey = ""
    }

    // ================= 主開關 =================

    private fun toggleMaster() {
        if (!prefs.monitoringActive) {
            if (prefs.monitoredPackages.isEmpty()) {
                toast("請先選擇要監控的 App")
                showAppPicker()
                return
            }
            if (!hasListenerAccess()) {
                toast("請先開啟「通知存取權」")
                return
            }
            prefs.monitoringActive = true
            toast("開始監控")
        } else {
            prefs.monitoringActive = false
            toast("已停止監控")
        }
        refreshLive()
    }

    // ================= App 卡片 =================

    private fun rebuildApps() {
        appsBox.removeAllViews()
        cards.clear()
        val pkgs = prefs.monitoredPackages.sortedBy { MonitorEngine.appLabel(this, it).lowercase() }
        if (pkgs.isEmpty()) {
            appsBox.addView(cardBox().apply {
                addView(label("還沒有選擇 App。按上面的「選擇要監控的 App」，勾選你用來刷歌的音樂 App。", 14f, Pal.sub))
            })
            return
        }
        for (pkg in pkgs) appsBox.addView(buildAppCard(pkg).margins(this, bottom = 12))
        refreshLive()
    }

    private fun buildAppCard(pkg: String): LinearLayout {
        val card = cardBox()

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this)
        try {
            icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
        } catch (_: Exception) {
        }
        head.addView(icon, lp(dp(34), dp(34)))
        head.addView(
            label(MonitorEngine.appLabel(this, pkg), 17f, Pal.text, bold = true).apply {
                setPadding(dp(10), 0, 0, 0)
            },
            lp(0, WRAP, 1f)
        )
        card.addView(head)

        val chip = label("", 13f, Pal.text, bold = true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val chipWrap = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipWrap.addView(chip, lp(WRAP, WRAP))
        card.addView(chipWrap.margins(this, top = 12))

        val detail = label("", 14f, Pal.sub)
        card.addView(detail.margins(this, top = 6))

        card.addView(pillButton("開啟 App", Pal.green, Pal.greenText) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) startActivity(i) else toast("找不到這個 App")
        }.margins(this, top = 12))

        val pauseBtn = pillButton("", Pal.purple, Pal.purpleText) {
            prefs.setPaused(pkg, !prefs.isPaused(pkg))
            refreshLive()
        }
        card.addView(pauseBtn.margins(this, top = 8))

        val autoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autoRow.addView(label("恢復播放時自動重新監控", 13f, Pal.sub), lp(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = prefs.isAutoResume(pkg)
            setOnCheckedChangeListener { _, on -> prefs.setAutoResume(pkg, on) }
        }
        autoRow.addView(sw)
        card.addView(autoRow.margins(this, top = 10))

        cards[pkg] = CardViews(chip, detail, pauseBtn, sw)
        return card
    }

    private fun refreshLive() {
        refreshSetup()
        refreshStats()

        val active = prefs.monitoringActive
        masterBtn.text = if (active) "■ 停止全部監控" else "▶ 開始全部監控"
        masterBtn.background = rounded(if (active) Pal.purpleText else Pal.greenStrong, 16)
        masterHint.text = when {
            !active -> "目前沒有在監控"
            !MonitorEngine.listenerConnected -> "⚠️ 監控服務還沒連上，請檢查通知存取權"
            else -> "監控中 · 每 3 秒檢查一次"
        }

        val now = SystemClock.elapsedRealtime()
        for ((pkg, v) in cards) {
            val st = MonitorEngine.statuses[pkg]
            val state = when {
                !active -> WatchState.IDLE
                st == null -> if (prefs.isPaused(pkg)) WatchState.PAUSED else WatchState.UNKNOWN
                else -> st?.state ?: WatchState.UNKNOWN
            }
            val (bg, fg) = Pal.chip(state)
            v.chip.text = state.label
            v.chip.setTextColor(fg)
            v.chip.background = rounded(bg, 10)

            v.detail.text = detailText(state, st, now)

            val paused = prefs.isPaused(pkg)
            v.pauseBtn.text = if (paused) "恢復監控" else "暫停監控"
        }
    }

    private fun detailText(state: WatchState, st: AppStatus?, now: Long): String {
        if (state == WatchState.IDLE) return "按「開始全部監控」後會顯示狀態"
        if (state == WatchState.PAUSED) return "這個 App 暫停監控中，不會通知"
        if (state == WatchState.PLAYING) {
            val t = st?.songTitle
            val a = st?.artist
            return when {
                t != null && a != null -> "♪ $t — $a"
                t != null -> "♪ $t"
                else -> "正在播放"
            }
        }
        if (st == null || st.badSince == 0L) return "等待確認中…"
        if (st.acked) return "已按「知道了」，恢復播放前不再提醒"
        val elapsed = (now - st.badSince) / 1000
        if (st.alertsSent == 0) {
            val left = prefs.thresholdSec - elapsed
            return "已持續 ${MonitorEngine.formatDuration(elapsed)} · " +
                (if (left > 0) "${MonitorEngine.formatDuration(left)}後通知" else "即將通知")
        }
        return "已持續 ${MonitorEngine.formatDuration(elapsed)} · 已通知 ${st.alertsSent}/${prefs.maxAlerts} 次"
    }

    // ================= 選擇 App =================

    private fun showAppPicker() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val installed = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .filter { it != packageName }
            .distinct()

        val known = KnownApps.names.keys.toList()
        val sorted = installed.sortedWith(
            compareBy<String>(
                { if (it in known) known.indexOf(it) else Int.MAX_VALUE },
                { MonitorEngine.appLabel(this, it).lowercase() }
            )
        )
        val labels = sorted.map {
            val name = MonitorEngine.appLabel(this, it)
            if (it in known) "★ $name" else name
        }.toTypedArray()
        val current = prefs.monitoredPackages
        val checked = BooleanArray(sorted.size) { sorted[it] in current }

        AlertDialog.Builder(this)
            .setTitle("選擇要監控的 App（★ 為常見音樂 App）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("確定") { _, _ ->
                val chosen = sorted.filterIndexed { i, _ -> checked[i] }.toSet()
                prefs.monitoredPackages = chosen
                prefs.pausedPackages = prefs.pausedPackages.intersect(chosen)
                rebuildApps()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 通知設定 =================

    private fun rebuildSettings() {
        settingsBox.removeAllViews()
        settingsBox.addView(choiceRow(
            "停止多久才通知", MonitorEngine.formatDuration(prefs.thresholdSec.toLong()),
            listOf(15, 30, 60, 120, 180, 300), { MonitorEngine.formatDuration(it.toLong()) }, prefs.thresholdSec
        ) { prefs.thresholdSec = it })
        settingsBox.addView(choiceRow(
            "重複提醒間隔", "${prefs.repeatMin} 分鐘",
            listOf(1, 3, 5, 10, 15), { "$it 分鐘" }, prefs.repeatMin
        ) { prefs.repeatMin = it })
        settingsBox.addView(choiceRow(
            "同一次停止最多提醒", "${prefs.maxAlerts} 次",
            listOf(1, 2, 3, 5, 10), { "$it 次" }, prefs.maxAlerts
        ) { prefs.maxAlerts = it })
        settingsBox.addView(switchRow("震動", "提醒時震動", prefs.vibrate) { prefs.vibrate = it })
        settingsBox.addView(switchRow("鬧鐘音效", "用鬧鐘音量響 10 秒（刷歌手機上）", prefs.alarmSound) { prefs.alarmSound = it })
        settingsBox.addView(switchRow("恢復播放時通知", "提醒過之後恢復播放，再通知一次", prefs.notifyRecovery) { prefs.notifyRecovery = it })
        settingsBox.addView(switchRow("充電中斷提醒", "充電線鬆脫時通知", prefs.chargeAlert) { prefs.chargeAlert = it })
        settingsBox.addView(switchRow("電量過低提醒", "沒在充電且電量低於設定值時通知", prefs.batteryAlert) { prefs.batteryAlert = it })
        settingsBox.addView(choiceRow(
            "電量提醒門檻", "${prefs.batteryThreshold}%",
            listOf(10, 15, 20, 30, 50), { "$it%" }, prefs.batteryThreshold
        ) { prefs.batteryThreshold = it })
        settingsBox.addView(pillButton("在這支手機測試通知", Pal.green, Pal.greenText) {
            Alerts.infoNotify(this, prefs, Alerts.ID_TEST, "🔔 測試通知", "看得到這則就代表通知正常。", important = true, remote = false)
        }.margins(this, top = 14))
    }

    private fun choiceRow(
        title: String, value: String, options: List<Int>, fmt: (Int) -> String, current: Int, onPick: (Int) -> Unit
    ): LinearLayout {
        val row = rowBase()
        row.addView(label(title, 15f, Pal.text), lp(0, WRAP, 1f))
        row.addView(label("$value ›", 15f, Pal.purpleText, bold = true))
        row.setOnClickListener {
            val names = options.map(fmt).toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(names, options.indexOf(current)) { d, which ->
                    onPick(options[which])
                    d.dismiss()
                    rebuildSettings()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return row
    }

    private fun switchRow(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = rowBase()
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Pal.text))
        texts.addView(label(desc, 12f, Pal.sub))
        row.addView(texts, lp(0, WRAP, 1f))
        val sw = Switch(this).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        return row
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(11), 0, dp(11))
        isClickable = true
    }

    // ================= 其他裝置（ntfy） =================

    private fun rebuildRemote() {
        remoteBox.removeAllViews()
        val topic = prefs.topic
        val webUrl = "${prefs.ntfyServer}/$topic"

        remoteBox.addView(switchRow("傳送通知到其他裝置", "透過免費的 ntfy 服務傳到日常手機和電腦", prefs.remoteEnabled) {
            prefs.remoteEnabled = it
        })
        remoteBox.addView(choiceTextRow("這支手機的名稱", prefs.deviceName) { v ->
            prefs.deviceName = v.ifBlank { Build.MODEL ?: "刷歌手機" }
        })

        remoteBox.addView(label("連結代碼", 13f, Pal.sub).margins(this, top = 10))
        remoteBox.addView(label(topic, 18f, Pal.text, bold = true).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            background = rounded(Pal.bg, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }.margins(this, top = 4))
        remoteBox.addView(label("🔒 代碼不要公開，知道代碼的人都收得到你的通知。", 12f, Pal.warnText).margins(this, top = 6))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(pillButton("傳給自己", Pal.green, Pal.greenText) { shareSetup(topic, webUrl) }, lp(0, WRAP, 1f))
        btnRow.addView(pillButton("複製代碼", Pal.purple, Pal.purpleText) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("連結代碼", topic))
            toast("已複製代碼")
        }, lp(0, WRAP, 1f).apply { leftMargin = dp(8) })
        remoteBox.addView(btnRow.margins(this, top = 12))

        remoteBox.addView(pillButton("遠端通知測試", Pal.green, Pal.greenText) {
            toast("送出中…")
            Alerts.sendRemote(
                prefs, "🔔 刷瓜小幫手測試", "日常手機和電腦收得到這則，就代表設定成功。",
                priority = 4, tags = listOf("bell"), force = true
            ) { ok, msg ->
                toast(if (ok) "測試通知已送出，去日常手機和電腦看看" else msg)
            }
        }.margins(this, top = 8))

        remoteBox.addView(label(
            "日常手機：安裝 ntfy App →「＋」訂閱主題 → 貼上連結代碼（伺服器用 ${prefs.ntfyServer}）。\n" +
                "電腦：用瀏覽器打開下面的網址，允許通知，分頁保持開著。",
            12f, Pal.sub
        ).margins(this, top = 12))
        remoteBox.addView(label(webUrl, 13f, Pal.purpleText, bold = true).apply {
            setTextIsSelectable(true)
        }.margins(this, top = 4))

        remoteBox.addView(label("進階", 13f, Pal.sub, bold = true).margins(this, top = 16))
        remoteBox.addView(choiceTextRow("ntfy 伺服器", prefs.ntfyServer) { v ->
            prefs.ntfyServer = if (v.startsWith("http")) v else Prefs.DEFAULT_SERVER
        })
        remoteBox.addView(label("重新產生連結代碼 ›", 14f, Pal.warnText, bold = true).apply {
            setPadding(0, dp(10), 0, dp(4))
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("重新產生連結代碼？")
                    .setMessage("舊代碼會失效，日常手機和電腦要重新訂閱新代碼。代碼不小心外流時才需要這樣做。")
                    .setPositiveButton("重新產生") { _, _ ->
                        prefs.regenerateTopic()
                        rebuildRemote()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
    }

    private fun choiceTextRow(title: String, value: String, onSave: (String) -> Unit): LinearLayout {
        val row = rowBase()
        row.addView(label(title, 15f, Pal.text), lp(0, WRAP, 1f))
        row.addView(label("$value ›", 14f, Pal.purpleText, bold = true).apply {
            maxWidth = dp(190)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.setOnClickListener {
            val input = EditText(this).apply {
                setText(value)
                inputType = InputType.TYPE_CLASS_TEXT
                setSelection(text.length)
            }
            val wrap = LinearLayout(this).apply {
                setPadding(dp(20), dp(8), dp(20), 0)
                addView(input, lp(MATCH, WRAP))
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("儲存") { _, _ ->
                    onSave(input.text.toString().trim())
                    rebuildRemote()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return row
    }

    /** 用分享功能把代碼傳給自己（LINE、Messenger、Email…），避免手打或截圖辨識打錯 */
    private fun shareSetup(topic: String, webUrl: String) {
        val text = """
            刷瓜小幫手 連結代碼（不要外流）
            $topic

            ▶ 日常手機：安裝 ntfy App →「＋」→ 主題貼上上面的代碼，伺服器用 ${prefs.ntfyServer}
            ▶ 電腦：用瀏覽器打開 $webUrl ，允許通知，分頁保持開著
        """.trimIndent()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, "傳給自己"))
    }

    // ================= 狀態說明 =================

    private fun buildLegend(): LinearLayout {
        val box = cardBox()
        val items = listOf(
            WatchState.PLAYING to "音樂 App 正在播放。",
            WatchState.BUFFERING to "正在載入或等待網路。",
            WatchState.STOPPED to "暫停、停止或播放錯誤。",
            WatchState.UNKNOWN to "讀不到播放狀態，App 可能被關掉了。",
            WatchState.PAUSED to "你暫停了這個 App 的監控，不會通知。",
            WatchState.IDLE to "還沒按「開始全部監控」。"
        )
        for ((state, desc) in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val (bg, fg) = Pal.chip(state)
            row.addView(label(state.label, 12f, fg, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(bg, 10)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, lp(dp(84), WRAP))
            row.addView(label(desc, 13f, Pal.sub).apply { setPadding(dp(10), 0, 0, 0) }, lp(0, WRAP, 1f))
            box.addView(row)
        }
        box.addView(label(
            "「緩衝中」「停止」「無法確認」持續超過設定時間就會通知；之後依設定的間隔再提醒。按通知上的「知道了」或恢復播放就會停止提醒。",
            12f, Pal.sub
        ).margins(this, top = 8))
        return box
    }

    // ================= 刷歌紀錄小卡 =================

    private fun refreshStats() {
        val s = Stats.summary(this)
        val t = s.today
        statsText.text = buildString {
            append("播放時數：${Stats.formatHm(t.playSec)}\n")
            append("最長連續播放：${Stats.formatHm(t.longestSec)}\n")
            append("中斷次數：${t.interrupts} 次")
            if (t.recovered > 0) append("（平均 ${MonitorEngine.formatDuration(t.avgRecoverSec)}救回）")
            append("\n連續刷歌天數：${s.streakDays} 天（每天播放滿 1 小時才算）")
        }
    }

    private fun showCardDialog() {
        val bmp = try {
            StatsCard.render(Stats.summary(this))
        } catch (e: Exception) {
            toast("產生小卡失敗")
            return
        }
        val img = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        val b = AlertDialog.Builder(this)
            .setTitle("刷歌紀錄小卡")
            .setView(img)
            .setPositiveButton("分享") { _, _ -> shareCard(bmp) }
            .setNegativeButton("關閉", null)
        if (Build.VERSION.SDK_INT >= 29) b.setNeutralButton("存到相簿") { _, _ -> saveCard(bmp) }
        b.show()
    }

    private fun shareCard(bmp: android.graphics.Bitmap) {
        try {
            val uri = CardProvider.saveForShare(this, bmp)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "今日刷歌紀錄 ☆彡 #刷瓜小幫手")
                clipData = ClipData.newRawUri("刷歌紀錄小卡", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "分享刷歌紀錄小卡"))
        } catch (e: Exception) {
            toast("分享失敗")
        }
    }

    private fun saveCard(bmp: android.graphics.Bitmap) {
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "刷歌紀錄_${System.currentTimeMillis()}.png")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/刷瓜小幫手")
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException()
            contentResolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            toast("已存到相簿（刷瓜小幫手 資料夾）")
        } catch (e: Exception) {
            toast("儲存失敗")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SHOW_CARD = "show_card"
    }
}
