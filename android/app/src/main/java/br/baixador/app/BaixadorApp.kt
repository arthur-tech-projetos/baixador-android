package br.baixador.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado do motor (yt-dlp + FFmpeg embutidos). */
object EngineState {
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
}

class BaixadorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val ctx = applicationContext
        Downloads.init(this)
        // a primeira inicialização extrai o Python e o FFmpeg; leva alguns segundos
        Thread {
            try {
                YoutubeDL.init(ctx)
                FFmpeg.init(ctx)
                EngineState.ready.value = true
            } catch (e: Throwable) {
                EngineState.error.value = e.message ?: e.toString()
            }
        }.start()
    }
}
