package com.wasla.media

import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * location: a content:// URI (MediaStore, Android 10+), a file:// URI (public folder, Android 8-9),
 * or a bare file name inside [LibraryStore.mediaDir] (entries saved before 1.4, or storage permission denied).
 */
data class SavedMedia(val id: String, val title: String, val kind: String, val location: String) {
    fun uri(context: Context): Uri =
        if ("://" in location) Uri.parse(location) else Uri.fromFile(File(LibraryStore.mediaDir(context), location))

    fun exists(context: Context): Boolean {
        val uri = uri(context)
        if (uri.scheme == "file") return File(uri.path ?: return false).isFile
        return runCatching {
            context.contentResolver.query(uri, arrayOf(BaseColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    fun delete(context: Context) {
        val uri = uri(context)
        if (uri.scheme == "file") File(uri.path ?: return).delete()
        else runCatching { context.contentResolver.delete(uri, null, null) }
    }
}

object LibraryStore {
    private fun index(context: Context) = File(context.filesDir, "library.json")
    fun mediaDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }

    @Synchronized fun all(context: Context): MutableList<SavedMedia> {
        val raw = runCatching { JSONArray(index(context).readText()) }.getOrDefault(JSONArray())
        return (0 until raw.length()).mapNotNull { n ->
            runCatching {
                val item = raw.getJSONObject(n)
                SavedMedia(item.getString("id"), item.getString("title"), item.getString("kind"),
                    item.optString("location").ifEmpty { item.getString("fileName") })
            }.getOrNull()
        }.filter { it.exists(context) }.toMutableList()
    }

    @Synchronized fun add(context: Context, item: SavedMedia) {
        val items = all(context)
        items.add(0, item)
        save(context, items)
    }

    @Synchronized fun move(context: Context, id: String, direction: Int) {
        val items = all(context)
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        // Swap with the nearest item of the same kind, skipping items of other kinds in between.
        val sameKind = items.indices.filter { items[it].kind == items[from].kind }
        val to = sameKind.getOrNull(sameKind.indexOf(from) + direction) ?: return
        java.util.Collections.swap(items, from, to)
        save(context, items)
    }

    @Synchronized fun remove(context: Context, id: String) {
        val items = all(context)
        items.find { it.id == id }?.delete(context)
        items.removeAll { it.id == id }
        save(context, items)
    }

    private fun save(context: Context, items: List<SavedMedia>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("kind", item.kind).put("location", item.location))
        }
        val tmp = File(context.filesDir, "library.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(index(context))) {
            index(context).writeText(array.toString())
            tmp.delete()
        }
    }
}
