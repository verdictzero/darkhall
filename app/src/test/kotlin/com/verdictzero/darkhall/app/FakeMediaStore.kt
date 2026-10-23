package com.verdictzero.darkhall.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Minimal stand-in for the system MediaStore under Robolectric: inserted images become
 * real files in a temp directory so the export path can be checked end to end.
 */
class FakeMediaStore : ContentProvider() {
    companion object {
        val dir: File by lazy { kotlin.io.path.createTempDirectory("darkhall-media").toFile() }
        val inserted = mutableListOf<Pair<Uri, ContentValues>>()
        fun fileFor(uri: Uri) = File(dir, uri.lastPathSegment!! + ".jpg")
    }

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val id = inserted.size + 1
        val out = Uri.parse("content://media/external_primary/images/media/$id")
        inserted += out to ContentValues(values ?: ContentValues())
        fileFor(out).createNewFile()
        return out
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode))

    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 1
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor = MatrixCursor(arrayOf("_id"))
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun getType(uri: Uri) = "image/jpeg"
}
