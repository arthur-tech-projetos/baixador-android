package br.baixador.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Guarda os downloads concluídos num arquivo JSON dentro do app. */
object Store {
    private fun file(ctx: Context) = File(ctx.filesDir, "historico.json")

    fun load(ctx: Context): List<DownloadItem> {
        return try {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                DownloadItem(o.getString("id"), o.optString("url")).apply {
                    title = o.optString("title")
                    thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail")
                    duration = if (o.isNull("duration")) null else o.optString("duration")
                    fileUri = if (o.isNull("fileUri")) null else o.optString("fileUri")
                    mime = if (o.isNull("mime")) null else o.optString("mime")
                    date = o.optLong("date")
                    percent = 1f
                    done = true
                    status = "Concluído"
                    detail = MainViewModel.dateText(date)
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            items.filter { it.done }.take(300).forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("url", it.url)
                    put("title", it.title)
                    put("thumbnail", it.thumbnail ?: JSONObject.NULL)
                    put("duration", it.duration ?: JSONObject.NULL)
                    put("fileUri", it.fileUri ?: JSONObject.NULL)
                    put("mime", it.mime ?: JSONObject.NULL)
                    put("date", it.date)
                })
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            // sem permissão ou disco cheio: ignora, o histórico é opcional
        }
    }
}
