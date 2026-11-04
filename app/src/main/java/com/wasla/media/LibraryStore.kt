package com.wasla.media

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedMedia(val id: String, val title: String, val kind: String, val fileName: String) {
    fun file(context: Context): File = File(LibraryStore.mediaDir(context), fileName)
}

object LibraryStore {
    private fun index(context: Context) = File(context.filesDir, "library.json")
    fun mediaDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "media").apply { mkdirs() }

    @Synchronized fun all(context: Context): MutableList<SavedMedia> {
        val raw = runCatching { JSONArray(index(context).readText()) }.getOrDefault(JSONArray())
        return (0 until raw.length()).mapNotNull { n ->
            runCatching {
                val item = raw.getJSONObject(n)
                SavedMedia(item.getString("id"), item.getString("title"), item.getString("kind"), item.getString("fileName"))
            }.getOrNull()
        }.filter { it.file(context).isFile }.toMutableList()
    }

    @Synchronized fun add(context: Context, item: SavedMedia) {
        val items = all(context)
        items.add(0, item)
        save(context, items)
    }

    @Synchronized fun move(context: Context, id: String, direction: Int) {
        val items = all(context)
        val from = items.indexOfFirst { it.id == id }
        val to = from + direction
        if (from >= 0 && to in items.indices && items[from].kind == items[to].kind) {
            java.util.Collections.swap(items, from, to)
            save(context, items)
        }
    }

    @Synchronized fun remove(context: Context, id: String) {
        val items = all(context)
        items.find { it.id == id }?.file(context)?.delete()
        items.removeAll { it.id == id }
        save(context, items)
    }

    private fun save(context: Context, items: List<SavedMedia>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("title", item.title)
                .put("kind", item.kind).put("fileName", item.fileName))
        }
        val tmp = File(context.filesDir, "library.tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(index(context))) {
            index(context).writeText(array.toString())
            tmp.delete()
        }
    }
}
