package br.baixador.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class VideoOption(val label: String, val height: Int?, val fps: Int, val upTo: Boolean = false)

data class MediaInfo(
    val title: String,
    val thumbnail: String?,
    val duration: String?,
    val author: String?,
    val options: List<VideoOption>,
)

data class Selection(val isVideo: Boolean, val format: String, val video: VideoOption?, val kbps: Int)

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
}
