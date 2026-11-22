package br.baixador.app

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    // fila de links esperando a escolha do formato (antes era um só e o 2º link apagava o 1º)
    private val choices = mutableStateListOf<Choice>()
    val choice: Choice? get() = choices.firstOrNull()

    var message by mutableStateOf<String?>(null)
    var engineVersion by mutableStateOf<String?>(null)

    private val jobs = HashMap<String, Job>()

    private const val UPDATE_EVERY = 24L * 60 * 60 * 1000
    private const val KEY_UPDATE = "ultimaAtualizacao"

    fun dateText(ms: Long): String =
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(ms))

    fun init(application: Application) {
        app = application
        FormatPrefs.init(app)
        items.addAll(Store.load(app))
        val keep = items.map { it.id }.toSet()
        scope.launch(Dispatchers.IO) {
            // sobras de downloads interrompidos (o Android fechou o app no meio)
            app.cacheDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("dl_") }
                ?.forEach { it.deleteRecursively() }
            Engine.cleanThumbs(app, keep)
        }
        scope.launch(Dispatchers.IO) {
            EngineState.ready.first { it }
            engineVersion = try { YoutubeDL.version(app) } catch (e: Exception) { null }
            if (EngineState.updating.value) runUpdate(silent = true)
        }
    }

    private fun prefs() = app.getSharedPreferences("motor", Context.MODE_PRIVATE)

    /** Atualização automática do yt-dlp: no máximo uma vez por dia. */
    fun updateDue(): Boolean =
        System.currentTimeMillis() - prefs().getLong(KEY_UPDATE, 0L) > UPDATE_EVERY

    /** Espera o motor ficar pronto e a atualização do yt-dlp terminar. */
    private suspend fun awaitEngine(item: DownloadItem) {
        if (!EngineState.ready.value) {
            item.status = "Iniciando o motor..."
            combine(EngineState.ready, EngineState.error) { r, e -> r || e != null }.first { it }
            if (!EngineState.ready.value) throw IllegalStateException(EngineState.error.value ?: "Motor indisponível")
        }
        if (EngineState.updating.value) {
            item.status = "Atualizando o yt-dlp..."
            // se a internet travar a atualização, não segura o download para sempre
            withTimeoutOrNull(60_000) { EngineState.updating.first { !it } }
        }
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    /** Passo 1: lê o link e abre a janela de escolha de formato. */
    fun add(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            message = "Cole um link válido começando com http:// ou https://"
            return
        }
        val item = DownloadItem(newId(), u)
        item.status = "Analisando..."
        items.add(0, item)
        analyze(item)
    }

    private fun analyze(item: DownloadItem) {
        jobs[item.id] = scope.launch {
            try {
                awaitEngine(item)
                item.status = "Analisando..."
                val info = Engine.info(item.url)
                item.title = info.title
                item.thumbnail = info.thumbnail
                item.duration = info.duration
                cacheThumb(item)
                val saved = FormatPrefs.last
                if (FormatPrefs.auto && saved != null) {
                    start(item, saved.toSelection())   // "usar sempre este formato": não pergunta
                } else {
                    item.status = "Escolha o formato"
                    choices.add(Choice(item, info))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(item, e)
            }
        }
    }

    /** Guarda a miniatura dentro do app (as do Instagram/TikTok expiram em poucos dias). */
    private fun cacheThumb(item: DownloadItem) {
        val remote = item.thumbnail ?: return
        if (remote.startsWith("file:")) return
        scope.launch {
            val local = withContext(Dispatchers.IO) { Engine.cacheThumb(app, remote, item.id) } ?: return@launch
            if (items.contains(item)) {
                item.thumbnail = local
                Store.save(app, items)
            }
        }
    }

    /** Passo 2: o usuário escolheu o formato; começa a baixar. */
    fun choose(sel: Selection) {
        if (choices.isEmpty()) return
        val c = choices.removeAt(0)
        FormatPrefs.remember(sel)
        start(c.item, sel)
        // marcou "Usar sempre este formato": os outros links da fila vão no mesmo formato
        if (sel.remember) {
            val auto = FormatPrefs.last?.toSelection() ?: sel
            while (choices.isNotEmpty()) start(choices.removeAt(0).item, auto)
        }
    }

    fun dismissChoice() {
        if (choices.isEmpty()) return
        val c = choices.removeAt(0)
        jobs.remove(c.item.id)?.cancel()
        items.remove(c.item)
        Engine.deleteThumb(app, c.item.id)
        Store.save(app, items)
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            message = "Não consegui ativar o download em segundo plano: ${e.message}"
        }
    }

    private fun start(item: DownloadItem, sel: Selection) {
        item.selection = sel
        item.active = true
        item.failed = false
        item.done = false
        item.percent = 0f
        item.detail = ""
        item.status = "Baixando"
        Store.save(app, items)   // se o app for fechado no meio, volta como "Interrompido"
        startService()
        jobs[item.id] = scope.launch {
            try {
                awaitEngine(item)
                item.status = "Baixando"
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
                Notifier.done(app, item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YoutubeDL.CanceledException) {
                items.remove(item)
                Store.save(app, items)
            } catch (e: Exception) {
                fail(item, e)
                Notifier.failed(app, item)
            } finally {
                item.active = false
            }
        }
    }

    private fun fail(item: DownloadItem, e: Exception) {
        val (msg, tech) = Errors.explain(e.message ?: e.toString())
        item.failed = true
        item.status = "Erro: $msg"
        item.detail = tech
        Store.save(app, items)
    }

    /** Botão "Tentar de novo": repete com o mesmo formato (ou analisa de novo, se nem chegou a escolher). */
    fun retry(item: DownloadItem) {
        if (item.active) return
        Notifier.cancel(app, item)
        val sel = item.selection
        if (sel != null) {
            start(item, sel)
        } else {
            item.failed = false
            item.detail = ""
            item.status = "Analisando..."
            analyze(item)
        }
    }

    fun remove(item: DownloadItem) {
        if (!item.done && !item.failed) {
            scope.launch(Dispatchers.IO) { runCatching { YoutubeDL.destroyProcessById(item.id) } }
        }
        choices.removeAll { it.item === item }
        jobs.remove(item.id)?.cancel()
        items.remove(item)
        Notifier.cancel(app, item)
        Engine.deleteThumb(app, item.id)
        Store.save(app, items)
    }

    /** Botão "Cancelar" da notificação de progresso. */
    fun cancelActive() {
        items.filter { it.active }.forEach { remove(it) }
    }

    fun updateEngine() {
        if (EngineState.updating.value) return
        if (items.any { it.active }) {
            message = "Espere os downloads terminarem para atualizar o yt-dlp."
            return
        }
        message = "Atualizando o yt-dlp..."
        EngineState.updating.value = true
        scope.launch { runUpdate(silent = false) }
    }

    /** silent = automática (só avisa se baixou versão nova). */
    private suspend fun runUpdate(silent: Boolean): Unit = withContext(Dispatchers.IO) {
        EngineState.updating.value = true
        try {
            val status = YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
            engineVersion = runCatching { YoutubeDL.version(app) }.getOrNull()
            prefs().edit().putLong(KEY_UPDATE, System.currentTimeMillis()).apply()
            val v = engineVersion?.let { " ($it)" } ?: ""
            if (status == YoutubeDL.UpdateStatus.DONE) message = "yt-dlp atualizado$v"
            else if (!silent) message = "O yt-dlp já está na versão mais nova$v"
        } catch (e: Exception) {
            if (!silent) message = "Falha ao atualizar: ${e.message}"
        } finally {
            EngineState.updating.value = false
        }
        Unit
    }
}
