package br.baixador.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

data class VideoOption(val label: String, val height: Int?, val fps: Int, val upTo: Boolean = false)

data class MediaInfo(
    val title: String,
    val thumbnail: String?,
    val duration: String?,
    val author: String?,
    val options: List<VideoOption>,
)

data class Selection(val isVideo: Boolean, val format: String, val video: VideoOption?, val kbps: Int, val remember: Boolean = false) {

    /** guardada no histórico para o botão "Tentar de novo" */
    fun toJson(): JSONObject = JSONObject()
        .put("isVideo", isVideo).put("format", format).put("kbps", kbps)
        .put("label", video?.label ?: JSONObject.NULL)
        .put("height", video?.height ?: JSONObject.NULL)
        .put("fps", video?.fps ?: 0)
        .put("upTo", video?.upTo ?: false)

    companion object {
        fun fromJson(o: JSONObject?): Selection? = try {
            if (o == null) null
            else {
                val isVideo = o.getBoolean("isVideo")
                Selection(
                    isVideo = isVideo,
                    format = o.getString("format"),
                    video = if (!isVideo) null else VideoOption(
                        if (o.isNull("label")) "Melhor disponível" else o.getString("label"),
                        if (o.isNull("height")) null else o.getInt("height"),
                        o.optInt("fps"),
                        o.optBoolean("upTo"),
                    ),
                    kbps = o.optInt("kbps", 320),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Um cartão da lista. Os campos são "estado" do Compose: mudar um campo atualiza a tela. */
class DownloadItem(val id: String, val url: String) {
    var title by mutableStateOf(url)
    var thumbnail by mutableStateOf<String?>(null)
    var duration by mutableStateOf<String?>(null)
    var status by mutableStateOf("")
    var detail by mutableStateOf("")
    var percent by mutableFloatStateOf(0f)
    var done by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var fileUri by mutableStateOf<String?>(null)
    var mime by mutableStateOf<String?>(null)
    var date by mutableStateOf(0L)
    var active by mutableStateOf(false)   // true enquanto baixa (mantém o serviço em segundo plano)
    var selection: Selection? = null      // formato escolhido (para "Tentar de novo")
}
