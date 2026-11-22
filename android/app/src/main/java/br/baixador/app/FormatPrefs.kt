package br.baixador.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Último formato escolhido (mesma lógica do app Windows). */
data class SavedFormat(val isVideo: Boolean, val format: String, val kbps: Int, val height: Int?, val fps: Int, val upTo: Boolean) {

    val text: String
        get() = if (isVideo) "$format " + (if (height == null) "melhor qualidade" else "até ${height}p")
        else if (format == "MP3") "MP3 $kbps kbps" else format

    /** modo automático: vídeo vira "até X p" para não falhar se o vídeo não tiver essa resolução */
    fun toSelection() = Selection(
        isVideo = isVideo, format = format, kbps = kbps,
        video = if (isVideo) VideoOption(text, height, 0, upTo = height != null) else null,
    )

    fun toJson(): String = JSONObject()
        .put("isVideo", isVideo).put("format", format).put("kbps", kbps)
        .put("height", height ?: JSONObject.NULL).put("fps", fps).put("upTo", upTo)
        .toString()

    companion object {
        fun from(s: Selection) = SavedFormat(s.isVideo, s.format, s.kbps, s.video?.height, s.video?.fps ?: 0, s.video?.upTo ?: false)

        fun fromJson(t: String): SavedFormat? = try {
            val o = JSONObject(t)
            SavedFormat(
                o.getBoolean("isVideo"), o.getString("format"), o.optInt("kbps", 320),
                if (o.isNull("height")) null else o.getInt("height"), o.optInt("fps"), o.optBoolean("upTo"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

object FormatPrefs {
    private const val FILE = "formato"
    private lateinit var ctx: Context

    var last by mutableStateOf<SavedFormat?>(null)
        private set
    var auto by mutableStateOf(false)   // true = não abre a janela de formato
        private set

    fun init(c: Context) {
        ctx = c.applicationContext
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        last = p.getString("last", null)?.let { SavedFormat.fromJson(it) }
        auto = p.getBoolean("auto", false) && last != null
    }

    fun remember(sel: Selection) {
        last = SavedFormat.from(sel)
        auto = sel.remember
        save()
    }

    fun askAgain() {
        auto = false
        save()
    }

    private fun save() {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("last", last?.toJson())
            .putBoolean("auto", auto)
            .apply()
    }
}
