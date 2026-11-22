package br.baixador.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object Engine {

    fun clock(sec: Double): String {
        val t = sec.toLong()
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Lê o link e descobre título, miniatura e as qualidades de vídeo disponíveis. */
    suspend fun info(url: String): MediaInfo = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("-J")
        val resp = YoutubeDL.execute(req, null, null)
        val r = JSONObject(resp.out.trim())

        val seen = LinkedHashSet<Int>()
        val fs = r.optJSONArray("formats")
        if (fs != null) {
            for (i in 0 until fs.length()) {
                val f = fs.optJSONObject(i) ?: continue
                if (f.isNull("height")) continue
                val h = f.optInt("height", 0)
                if (h <= 0) continue
                if (f.optString("vcodec") == "none") continue
                var fps = Math.round(f.optDouble("fps", 0.0)).toInt()
                if (fps <= 30) fps = 0
                seen.add(h * 1000 + fps)
            }
        }
        val options = seen
            .sortedWith(compareByDescending<Int> { it / 1000 }.thenByDescending { it % 1000 })
            .map { k ->
                val h = k / 1000
                val f = k % 1000
                VideoOption(if (f > 0) "${h}p$f" else "${h}p", h, f)
            }
            .ifEmpty { listOf(VideoOption("Melhor disponível", null, 0)) }

        val dur = r.optDouble("duration", 0.0)
        MediaInfo(
            title = r.optString("title", url).ifBlank { url },
            thumbnail = if (r.isNull("thumbnail")) null else r.optString("thumbnail"),
            duration = if (dur > 0) clock(dur) else null,
            author = if (r.isNull("uploader")) null else r.optString("uploader"),
            options = options,
        )
    }

    /** Lê a lista de vídeos de uma playlist (rápido: --flat-playlist, até 300 vídeos). */
    suspend fun playlist(url: String): PlaylistInfo = withContext(Dispatchers.IO) {
        val req = YoutubeDLRequest(url)
        req.addOption("--flat-playlist")
        req.addOption("--no-warnings")
        req.addOption("--playlist-end", "300")
        req.addOption("-J")
        val r = JSONObject(YoutubeDL.execute(req, null, null).out.trim())
        val entries = ArrayList<PlaylistEntry>()
        val es = r.optJSONArray("entries")
        if (es != null) {
            for (i in 0 until es.length()) {
                val en = es.optJSONObject(i) ?: continue
                val id = en.str("id")
                var eu = en.str("url") ?: en.str("webpage_url")
                if (eu == null || !eu.startsWith("http")) eu = id?.let { "https://www.youtube.com/watch?v=$it" }
                if (eu == null) continue
                val title = en.str("title") ?: eu
                val dur = en.optDouble("duration", 0.0)
                val thumb =
                    if (eu.contains("youtube.com/watch") && id != null) "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                    else en.optJSONArray("thumbnails")?.optJSONObject(0)?.str("url")
                val unavailable = title.startsWith("[Private") || title.startsWith("[Deleted")
                entries.add(PlaylistEntry(i + 1, eu, title, if (dur > 0) clock(dur) else null, thumb, !unavailable))
            }
        }
        PlaylistInfo(r.str("title") ?: "Playlist", entries)
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).ifBlank { null }

    /**
     * Baixa e converte numa pasta temporária e depois salva em Downloads/Baixador.
     * Devolve (endereço do arquivo, tipo MIME).
     */
    suspend fun download(
        ctx: Context,
        item: DownloadItem,
        sel: Selection,
        onProgress: (Float, Long) -> Unit,
    ): Pair<String?, String> = withContext(Dispatchers.IO) {
        val tmp = File(ctx.cacheDir, "dl_${item.id}")
        tmp.deleteRecursively()
        tmp.mkdirs()

        // áudio: busca álbum/capa/gênero no iTunes e baixa a partir do JSON completado.
        // Com --load-info-json o yt-dlp não renova o link se o YouTube der 403, então:
        // tentativas 1 e 2 com JSON novo; 3ª direto pelo link (sem iTunes).
        var tentativa = 0
        while (true) {
            val infoJson: File? = if (!sel.isVideo && tentativa < 2) prepareInfoJson(item.url, tmp) else null
            try {
                YoutubeDL.execute(buildRequest(item, sel, tmp, infoJson), item.id, { progress, eta, _ -> onProgress(progress, eta) })
                infoJson?.delete()
                break
            } catch (e: YoutubeDLException) {
                infoJson?.delete()
                if (infoJson == null) throw e
                tmp.listFiles()?.forEach { it.deleteRecursively() }
                tentativa++
            }
        }

        val out = tmp.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".json") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Nenhum arquivo foi gerado.")
        val saved = saveToDownloads(ctx, out, item.folder)
        tmp.deleteRecursively()
        saved
    }

    private fun buildRequest(item: DownloadItem, sel: Selection, tmp: File, infoJson: File?): YoutubeDLRequest {
        val req = if (infoJson != null) YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json", infoJson.absolutePath) }
                  else YoutubeDLRequest(item.url)
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--newline")
        req.addOption("--no-mtime")
        req.addOption("-o", File(tmp, "%(title).80s.%(ext)s").absolutePath)

        if (!sel.isVideo) {
            req.addOption("-f", "bestaudio/best")
            req.addOption("-x")
            req.addOption("--audio-format", sel.format.lowercase(Locale.ROOT))
            if (sel.format == "MP3") req.addOption("--audio-quality", "${sel.kbps}K")
            req.addOption("--embed-metadata")
            if (sel.format == "MP3") {
                req.addOption("--embed-thumbnail")
                req.addOption("--convert-thumbnails", "jpg")
                req.addOption("--ppa", "ThumbnailsConvertor+FFmpeg_o:-c:v mjpeg -vf crop=\"'if(gt(ih,iw),iw,ih)':'if(gt(iw,ih),ih,iw)'\"")
            }
        } else {
            val v = sel.video
            val fmt = when {
                v?.height == null -> "bv*+ba/b"
                v.upTo -> "bv*[height<=${v.height}]+ba/b[height<=${v.height}]"
                else -> {
                    val hf = "[height=${v.height}]"
                    val ff = if (v.fps > 0) "[fps>${v.fps - 1}][fps<${v.fps + 1}]" else "[fps<=?30]"
                    "bv*$hf$ff+ba/b$hf$ff/b[height<=${v.height}]"
                }
            }
            req.addOption("-f", fmt)
            req.addOption("-S", "vcodec:h264,res,fps,acodec:m4a")
            req.addOption("--merge-output-format", "mp4")
        }
        return req
    }

    /** Gera o .json do vídeo com as tags do iTunes; null se não deu (aí baixa direto pelo link). */
    private fun prepareInfoJson(url: String, dir: File): File? = try {
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("-j")
        val raw = YoutubeDL.execute(req, null, null).out.trim()
        if (!raw.startsWith("{")) null
        else File(dir, "info.json").apply { writeText(MusicTags.enrich(raw), Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    // ---------- miniaturas guardadas no app (as do Instagram/TikTok expiram) ----------
    private fun thumbsDir(ctx: Context) = File(ctx.filesDir, "miniaturas").apply { mkdirs() }

    /** Baixa a miniatura para dentro do app; devolve "file://..." ou null se não deu. */
    fun cacheThumb(ctx: Context, url: String, id: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Baixador")
            try {
                if (c.responseCode !in 200..299) return null
                val dir = thumbsDir(ctx)
                val tmp = File(dir, "$id.tmp")
                c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                if (tmp.length() == 0L) { tmp.delete(); return null }
                val f = File(dir, id)
                f.delete()
                if (!tmp.renameTo(f)) return null
                "file://" + f.absolutePath
            } finally {
                c.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteThumb(ctx: Context, id: String) {
        runCatching { File(thumbsDir(ctx), id).delete() }
    }

    /** Apaga miniaturas de itens que já saíram da lista. */
    fun cleanThumbs(ctx: Context, keep: Set<String>) {
        runCatching { thumbsDir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() } }
    }

    private fun saveToDownloads(ctx: Context, file: File, folder: String?): Pair<String?, String> {
        val sub = "Baixador" + (folder?.let { "/$it" } ?: "")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + sub)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível criar o arquivo em Downloads.")
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Pair(uri.toString(), mime)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
            dir.mkdirs()
            var dest = File(dir, file.name)
            var n = 1
            while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} (${n++}).${file.extension}")
            file.copyTo(dest)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
            return Pair(null, mime)
        }
    }
}
