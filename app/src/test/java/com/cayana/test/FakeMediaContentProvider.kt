package com.cayana.test

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.robolectric.shadows.ShadowContentResolver

class FakeMediaContentProvider : ContentProvider() {

    private val items = mutableListOf<ContentValues>()
    private var nextId = 1L

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val cv = ContentValues(values)
        val id = nextId++
        cv.put(MediaStore.Images.Media._ID, id)
        if (!cv.containsKey(MediaStore.Images.Media.DATE_ADDED)) {
            cv.put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
        }
        if (!cv.containsKey(MediaStore.Images.Media.DATE_TAKEN)) {
            cv.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        }
        items.add(cv)
        return ContentUris.withAppendedId(uri, id)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val proj = projection ?: arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE
        )
        val cursor = MatrixCursor(proj)

        var filtered = items.toList()
        if (selection != null && selection.contains("${MediaStore.Images.Media._ID} > ?") && selectionArgs != null && selectionArgs.isNotEmpty()) {
            val threshold = selectionArgs[0].toLongOrNull() ?: 0L
            filtered = filtered.filter { (it.getAsLong(MediaStore.Images.Media._ID) ?: 0L) > threshold }
        }

        if (sortOrder != null && sortOrder.contains("DESC")) {
            filtered = filtered.sortedByDescending { it.getAsLong(MediaStore.Images.Media._ID) ?: 0L }
        } else {
            filtered = filtered.sortedBy { it.getAsLong(MediaStore.Images.Media._ID) ?: 0L }
        }

        if (sortOrder != null && sortOrder.contains("LIMIT 1")) {
            filtered = filtered.take(1)
        }

        for (item in filtered) {
            val row = cursor.newRow()
            for (col in proj) {
                row.add(col, item.get(col))
            }
        }
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return try {
            val id = ContentUris.parseId(uri)
            val removed = items.removeAll { (it.getAsLong(MediaStore.Images.Media._ID) ?: 0L) == id }
            if (removed) 1 else 0
        } catch (_: Exception) {
            val count = items.size
            items.clear()
            count
        }
    }

    override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
        val id = try {
            ContentUris.parseId(uri)
        } catch (_: Exception) {
            throw java.io.FileNotFoundException("Invalid URI: $uri")
        }
        val exists = items.any { (it.getAsLong(MediaStore.Images.Media._ID) ?: 0L) == id }
        if (!exists) {
            throw java.io.FileNotFoundException("Item $id not found in FakeMediaContentProvider")
        }
        val tempFile = java.io.File.createTempFile("fake_media_$id", ".tmp")
        tempFile.deleteOnExit()
        return android.os.ParcelFileDescriptor.open(tempFile, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? {
        val id = try { ContentUris.parseId(uri) } catch (_: Exception) { null }
        if (id != null) {
            val item = items.find { (it.getAsLong(MediaStore.Images.Media._ID) ?: 0L) == id }
            val mime = item?.getAsString(MediaStore.Images.Media.MIME_TYPE)
            if (mime != null) return mime
        }
        return "vnd.android.cursor.dir/image"
    }

    companion object {
        fun register(context: Context): FakeMediaContentProvider {
            val provider = FakeMediaContentProvider()
            val info = ProviderInfo().apply {
                authority = MediaStore.AUTHORITY
                exported = true
            }
            provider.attachInfo(context, info)
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
            return provider
        }
    }
}
