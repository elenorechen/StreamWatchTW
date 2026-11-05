package tw.plli.streamwatch

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/** 把小卡圖片提供給其他 App（Threads、IG、LINE…）分享用。只開放 cards 資料夾裡的圖片。 */
class CardProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (!name.matches(Regex("[A-Za-z0-9_-]+\\.png"))) return null
        val f = File(cardsDir(context!!), name)
        return if (f.exists()) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "tw.plli.streamwatch.cards"

        fun cardsDir(ctx: Context) = File(ctx.cacheDir, "cards").also { it.mkdirs() }

        /** 存成暫存檔，回傳可以分享的網址 */
        fun saveForShare(ctx: Context, bmp: Bitmap): Uri {
            val dir = cardsDir(ctx)
            dir.listFiles()?.forEach { it.delete() }
            val name = "card_${System.currentTimeMillis()}.png"
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return Uri.parse("content://$AUTHORITY/$name")
        }
    }
}
