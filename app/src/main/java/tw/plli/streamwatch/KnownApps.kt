package tw.plli.streamwatch

/** 常見音樂 App，會排在「選擇 App」清單最上面 */
object KnownApps {
    val names: Map<String, String> = linkedMapOf(
        "com.iloen.melon" to "Melon",
        "com.ktmusic.geniemusic" to "Genie",
        "com.neowiz.android.bugs" to "Bugs",
        "skplanet.musicmate" to "FLO",
        "com.naver.vibe" to "VIBE",
        "com.spotify.music" to "Spotify",
        "com.google.android.apps.youtube.music" to "YouTube Music",
        "com.google.android.youtube" to "YouTube",
        "com.apple.android.music" to "Apple Music",
        "com.skysoft.kkbox.android" to "KKBOX",
        "jp.linecorp.linemusic.android" to "LINE MUSIC",
        "com.amazon.mp3" to "Amazon Music",
        "com.soundcloud.android" to "SoundCloud",
        "com.stationhead.app" to "Stationhead",
    )

    /**
     * 有些 App 自己不播音樂，而是叫另一個 App 在背景播放，播放狀態會掛在那個 App 身上。
     * 例如 Stationhead（Android 版）要連結 Spotify，音樂其實是 Spotify App 在播。
     * 監控左邊的 App 時，右邊這些 App 的播放狀態也算它的。
     */
    val playsVia: Map<String, List<String>> = mapOf(
        "com.stationhead.app" to listOf("com.spotify.music"),
    )

    fun sourcesFor(pkg: String): List<String> = listOf(pkg) + (playsVia[pkg] ?: emptyList())
}
