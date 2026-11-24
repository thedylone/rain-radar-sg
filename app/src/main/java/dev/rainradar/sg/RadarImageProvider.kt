package dev.rainradar.sg

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Read-only provider exposing just the rendered widget image (public weather data). */
class RadarImageProvider : ContentProvider() {
    companion object {
        fun uri(ctx: Context, version: Long): Uri =
            Uri.parse("content://${ctx.packageName}.radar/${Updater.WIDGET_IMG}?v=$version")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment != Updater.WIDGET_IMG || mode != "r") throw FileNotFoundException()
        val f = File(context!!.filesDir, Updater.WIDGET_IMG)
        if (!f.exists()) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun onCreate() = true
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
