package br.baixador.app

import android.app.Application
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow

/** Estado do motor (yt-dlp + FFmpeg embutidos). */
object EngineState {
    val ready = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val updating = MutableStateFlow(false)   // true enquanto o yt-dlp se atualiza (downloads esperam)
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
                // marca ANTES de ficar pronto, para nenhum download começar no meio da atualização
                if (Downloads.updateDue()) EngineState.updating.value = true
                EngineState.ready.value = true
            } catch (e: Throwable) {
                EngineState.error.value = e.message ?: e.toString()
            }
        }.start()
    }
}
