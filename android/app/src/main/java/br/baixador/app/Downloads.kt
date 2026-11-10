package br.baixador.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Guarda a lista e executa os downloads no processo do app (e não na tela).
 * Assim o download continua se você sair do app ou fechar a tela.
 */
object Downloads {

    class Choice(val item: DownloadItem, val info: MediaInfo)

    private lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val items = mutableStateListOf<DownloadItem>()
    var choice by mutableStateOf<Choice?>(null)
    var message by mutableStateOf<String?>(null)
    var engineVersion by mutableStateOf<String?>(null)

    private val jobs = HashMap<String, Job>()

    fun dateText(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))

    fun init(application: Application) {
        app = application
        items.addAll(Store.load(app))
        scope.launch(Dispatchers.IO) {
            EngineState.ready.first { it }
            engineVersion = try { YoutubeDL.version(app) } catch (e: Exception) { null }
        }
    }

    /** Passo 1: lê o link e abre a janela de escolha de formato. */
    fun add(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            message = "Cole um link válido começando com http:// ou https://"
            return
        }
        val item = DownloadItem(UUID.randomUUID().toString().take(8), u)
        item.status = "Analisando..."
        items.add(0, item)
        jobs[item.id] = scope.launch {
            try {
                if (!EngineState.ready.value) {
                    item.status = "Iniciando o motor..."
                    EngineState.ready.first { it || EngineState.error.value != null }
                    if (!EngineState.ready.value) throw IllegalStateException(EngineState.error.value ?: "Motor indisponível")
                    item.status = "Analisando..."
                }
                val info = Engine.info(u)
                item.title = info.title
                item.thumbnail = info.thumbnail
                item.duration = info.duration
                item.status = "Escolha o formato"
                choice = Choice(item, info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(item, e)
            }
        }
    }

    /** Passo 2: o usuário escolheu o formato; começa a baixar. */
    fun choose(sel: Selection) {
        val c = choice ?: return
        choice = null
        start(c.item, sel)
    }

    fun dismissChoice() {
        val c = choice ?: return
        choice = null
        jobs.remove(c.item.id)?.cancel()
        items.remove(c.item)
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            message = "Não consegui ativar o download em segundo plano: ${e.message}"
        }
    }

    private fun start(item: DownloadItem, sel: Selection) {
        item.active = true
        startService()
        jobs[item.id] = scope.launch {
            item.status = "Baixando"
            try {
                val (uri, mime) = Engine.download(app, item, sel) { p, eta ->
                    item.percent = (p / 100f).coerceIn(0f, 1f)
                    item.status = if (p >= 100f) "Processando" else "Baixando"
                    item.detail = if (eta > 0) "faltam ${eta}s" else ""
                }
                item.fileUri = uri
                item.mime = mime
                item.percent = 1f
                item.date = System.currentTimeMillis()
                item.detail = dateText(item.date)
                item.status = "Concluído"
                item.done = true
                Store.save(app, items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YoutubeDL.CanceledException) {
                items.remove(item)
            } catch (e: Exception) {
                fail(item, e)
            } finally {
                item.active = false
            }
        }
    }

    private fun fail(item: DownloadItem, e: Exception) {
        item.failed = true
        item.status = "Erro: " + (e.message ?: e.toString()).lines().lastOrNull { it.isNotBlank() }.orEmpty()
    }

    fun remove(item: DownloadItem) {
        if (!item.done && !item.failed) {
            scope.launch(Dispatchers.IO) { runCatching { YoutubeDL.destroyProcessById(item.id) } }
        }
        jobs.remove(item.id)?.cancel()
        items.remove(item)
        Store.save(app, items)
    }

    fun updateEngine() {
        message = "Atualizando o yt-dlp..."
        scope.launch(Dispatchers.IO) {
            try {
                val status = YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                engineVersion = YoutubeDL.version(app)
                message = "yt-dlp: ${status ?: "ok"}"
            } catch (e: Exception) {
                message = "Falha ao atualizar: ${e.message}"
            }
        }
    }
}
