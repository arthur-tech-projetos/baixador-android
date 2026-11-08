package br.baixador.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
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

        val req = YoutubeDLRequest(item.url)
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

        YoutubeDL.execute(req, item.id, { progress, eta, _ -> onProgress(progress, eta) })

        val out = tmp.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
            ?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Nenhum arquivo foi gerado.")
        val saved = saveToDownloads(ctx, out)
        tmp.deleteRecursively()
        saved
    }

    private fun saveToDownloads(ctx: Context, file: File): Pair<String?, String> {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "application/octet-stream"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Baixador")
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
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Baixador")
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
