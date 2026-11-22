package br.baixador.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Guarda a lista num arquivo JSON dentro do app: os concluídos e também os que falharam
 * ou estavam baixando (se o Android fechar o app no meio, eles voltam como "Interrompido",
 * com o botão "Tentar de novo").
 */
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
                    selection = Selection.fromJson(o.optJSONObject("sel"))
                    folder = if (o.isNull("folder")) null else o.optString("folder").ifBlank { null }
                    if (o.optBoolean("done", true)) {   // históricos antigos só tinham concluídos
                        percent = 1f
                        done = true
                        status = "Concluído"
                        detail = Downloads.dateText(date)
                    } else {
                        failed = true
                        if (o.optBoolean("failed")) {
                            status = o.optString("status")
                            detail = o.optString("detail")
                        } else {
                            status = "Interrompido: o app foi fechado antes de terminar"
                            detail = ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, items: List<DownloadItem>) {
        try {
            val arr = JSONArray()
            items.filter { !it.temp }.take(1000).forEach {
                arr.put(JSONObject().apply {
                    put("id", it.id)
                    put("url", it.url)
                    put("title", it.title)
                    put("thumbnail", it.thumbnail ?: JSONObject.NULL)
                    put("duration", it.duration ?: JSONObject.NULL)
                    put("fileUri", it.fileUri ?: JSONObject.NULL)
                    put("mime", it.mime ?: JSONObject.NULL)
                    put("date", it.date)
                    put("done", it.done)
                    put("failed", it.failed)
                    put("status", it.status)
                    put("detail", it.detail)
                    put("sel", it.selection?.toJson() ?: JSONObject.NULL)
                    put("folder", it.folder ?: JSONObject.NULL)
                })
            }
            val f = file(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            // sem permissão ou disco cheio: ignora, o histórico é opcional
        }
    }
}
