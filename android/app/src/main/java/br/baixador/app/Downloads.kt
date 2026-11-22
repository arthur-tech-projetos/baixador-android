package br.baixador.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    /** Janela de formato pendente (de um vídeo ou de uma playlist inteira). */
    class Choice(
        val id: String,
        val info: MediaInfo,
        val item: DownloadItem?,
        val onPick: (Selection) -> Unit,
        val onCancel: () -> Unit,
    )

    /** "Este vídeo está numa playlist: baixar a playlist inteira ou só o vídeo?" */
    class PlaylistAsk(val id: String, val url: String, val playlistUrl: String)

    /** Janela para marcar quais vídeos da playlist baixar. */
    class PlaylistPick(val id: String, val info: PlaylistInfo)

    private lateinit var app: Application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val items = mutableStateListOf<DownloadItem>()

    // filas de janelas: a tela mostra uma de cada vez
    private val asks = mutableStateListOf<PlaylistAsk>()
    private val picks = mutableStateListOf<PlaylistPick>()
    private val choices = mutableStateListOf<Choice>()
    val ask: PlaylistAsk? get() = asks.firstOrNull()
    val pick: PlaylistPick? get() = picks.firstOrNull()
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

    // ---------- entrada: link colado ou compartilhado ----------

    fun add(url: String) {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            message = "Cole um link válido começando com http:// ou https://"
            return
        }
        val pl = playlistOf(u)
        when {
            pl == null -> addVideo(u)
            !pl.second -> addPlaylist(pl.first)
            else -> asks.add(PlaylistAsk(newId(), u, pl.first))
        }
    }

    /**
     * Link do YouTube com "list=": devolve (link da playlist, precisa perguntar?).
     * null = baixar só o vídeo (sem playlist, ou Mix automático "RD...").
     */
    private fun playlistOf(url: String): Pair<String, Boolean>? {
        val u = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = u.host?.lowercase(Locale.ROOT) ?: return null
        if (!host.contains("youtube.com") && host != "youtu.be") return null
        val list = runCatching { u.getQueryParameter("list") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val plUrl = "https://www.youtube.com/playlist?list=" + Uri.encode(list)
        if (u.path?.startsWith("/playlist", ignoreCase = true) == true) return Pair(plUrl, false)
        if (list.startsWith("RD", ignoreCase = true)) return null
        return Pair(plUrl, true)
    }

    /** Resposta da pergunta: true = playlist inteira, false = só o vídeo, null = cancelou. */
    fun answerAsk(whole: Boolean?) {
        if (asks.isEmpty()) return
        val a = asks.removeAt(0)
        when (whole) {
            true -> addPlaylist(a.playlistUrl)
            false -> addVideo(a.url)
            null -> {}
        }
    }

    // ---------- vídeo único ----------

    private fun addVideo(u: String) {
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
                    choices.add(
                        Choice(item.id, info, item, onPick = { start(item, it) }, onCancel = {
                            jobs.remove(item.id)?.cancel()
                            items.remove(item)
                            Engine.deleteThumb(app, item.id)
                            Store.save(app, items)
                        })
                    )
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

    // ---------- playlist ----------

    private fun addPlaylist(plUrl: String) {
        val tmp = DownloadItem(newId(), plUrl)
        tmp.temp = true
        tmp.title = "Playlist"
        tmp.status = "Lendo a playlist..."
        items.add(0, tmp)
        jobs[tmp.id] = scope.launch {
            try {
                awaitEngine(tmp)
                tmp.status = "Lendo a playlist..."
                val info = Engine.playlist(plUrl)
                if (info.entries.isEmpty()) message = "Não encontrei vídeos nessa playlist."
                else picks.add(PlaylistPick(newId(), info))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Não consegui ler a playlist: " + Errors.explain(e.message ?: e.toString()).first
            } finally {
                items.remove(tmp)
                jobs.remove(tmp.id)
            }
        }
    }

    /** Usuário marcou os vídeos da playlist; agora o formato (uma vez só para todos). */
    fun confirmPick(chosen: List<PlaylistEntry>) {
        if (picks.isEmpty()) return
        val p = picks.removeAt(0)
        if (chosen.isEmpty()) return
        val saved = FormatPrefs.last
        if (FormatPrefs.auto && saved != null) {
            enqueue(p.info.title, chosen, saved.toSelection())
            return
        }
        val info = MediaInfo(
            title = p.info.title,
            thumbnail = chosen.firstOrNull { it.thumbnail != null }?.thumbnail,
            duration = if (chosen.size == 1) "1 vídeo" else "${chosen.size} vídeos",
            author = null,
            options = playlistOptions(),
        )
        choices.add(Choice(p.id, info, null, onPick = { enqueue(p.info.title, chosen, it) }, onCancel = {}))
    }

    fun dismissPick() {
        if (picks.isNotEmpty()) picks.removeAt(0)
    }

    // cada vídeo tem resoluções diferentes: na playlist o formato é "até X"
    private fun playlistOptions() = listOf(
        VideoOption("Melhor disponível", null, 0),
        VideoOption("Até 1080p", 1080, 0, upTo = true),
        VideoOption("Até 720p", 720, 0, upTo = true),
        VideoOption("Até 480p", 480, 0, upTo = true),
        VideoOption("Até 360p", 360, 0, upTo = true),
    )

    private fun safeName(n: String): String {
        var s = n.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').trim()
        if (s.length > 80) s = s.take(80).trim()
        return s.ifEmpty { "Playlist" }
    }

    /** Cria os cartões "Na fila" e baixa um vídeo por vez em Downloads/Baixador/<playlist>. */
    private fun enqueue(title: String, chosen: List<PlaylistEntry>, sel: Selection) {
        val folder = safeName(title)
        val list = chosen.map { en ->
            DownloadItem(newId(), en.url).apply {
                this.title = en.title
                thumbnail = en.thumbnail
                duration = en.duration
                this.folder = folder
                selection = sel
                queued = true
                status = "Na fila"
                detail = "Playlist: $title"
            }
        }
        items.addAll(0, list)
        Store.save(app, items)
        runQueue(list, title)
    }

    /** Baixa os itens em sequência; no fim, um aviso só com o resumo. */
    private fun runQueue(list: List<DownloadItem>, title: String?) {
        startService()
        scope.launch {
            var ok = 0
            var tried = 0
            for (item in list) {
                if (!items.contains(item) || !item.queued) continue   // cancelado enquanto esperava
                item.queued = false
                val sel = item.selection ?: continue
                tried++
                prepare(item, sel)
                val j = scope.launch { if (runDownload(item, sel, notify = false)) ok++ }
                jobs[item.id] = j
                j.join()
            }
            if (tried > 0) Notifier.batch(app, title, ok, tried)
        }
    }

    // ---------- janela de formato ----------

    /** O usuário escolheu o formato. */
    fun choose(sel: Selection) {
        if (choices.isEmpty()) return
        val c = choices.removeAt(0)
        FormatPrefs.remember(sel)
        c.onPick(sel)
        // marcou "Usar sempre este formato": os outros da fila vão no mesmo formato
        if (sel.remember) {
            val auto = FormatPrefs.last?.toSelection() ?: sel
            while (choices.isNotEmpty()) choices.removeAt(0).onPick(auto)
        }
    }

    fun dismissChoice() {
        if (choices.isEmpty()) return
        choices.removeAt(0).onCancel()
    }

    // ---------- download ----------

    private fun startService() {
        try {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        } catch (e: Exception) {
            message = "Não consegui ativar o download em segundo plano: ${e.message}"
        }
    }

    private fun prepare(item: DownloadItem, sel: Selection) {
        item.selection = sel
        item.queued = false
        item.active = true
        item.failed = false
        item.done = false
        item.percent = 0f
        item.detail = ""
        item.status = "Baixando"
    }

    private fun start(item: DownloadItem, sel: Selection) {
        prepare(item, sel)
        Store.save(app, items)   // se o app for fechado no meio, volta como "Interrompido"
        startService()
        jobs[item.id] = scope.launch { runDownload(item, sel, notify = true) }
    }

    /** Baixa um item; devolve true se deu certo. notify = aviso individual (na playlist é um resumo no fim). */
    private suspend fun runDownload(item: DownloadItem, sel: Selection, notify: Boolean): Boolean {
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
            cacheThumb(item)
            if (notify) Notifier.done(app, item)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: YoutubeDL.CanceledException) {
            items.remove(item)
            Store.save(app, items)
            return false
        } catch (e: Exception) {
            fail(item, e)
            if (notify) Notifier.failed(app, item)
            return false
        } finally {
            item.active = false
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
        if (item.active || item.queued) return
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

    /** "Tentar todos de novo": refaz os que falharam, um por vez (como na playlist). */
    fun retryAll() {
        val failed = items.filter { it.failed && !it.active && !it.queued }
        failed.filter { it.selection == null }.forEach { retry(it) }
        val list = failed.filter { it.selection != null }
        list.forEach {
            Notifier.cancel(app, it)
            it.failed = false
            it.queued = true
            it.status = "Na fila"
            it.detail = ""
        }
        if (list.isNotEmpty()) runQueue(list, null)
    }

    fun remove(item: DownloadItem) {
        if (!item.done && !item.failed) {
            scope.launch(Dispatchers.IO) { runCatching { YoutubeDL.destroyProcessById(item.id) } }
        }
        item.queued = false
        choices.removeAll { it.item === item }
        jobs.remove(item.id)?.cancel()
        items.remove(item)
        Notifier.cancel(app, item)
        Engine.deleteThumb(app, item.id)
        Store.save(app, items)
    }

    /** Botão "Cancelar" da notificação de progresso (inclui o resto da fila da playlist). */
    fun cancelActive() {
        items.filter { it.active || it.queued }.forEach { remove(it) }
    }

    // ---------- yt-dlp ----------

    fun updateEngine() {
        if (EngineState.updating.value) return
        if (items.any { it.active || it.queued }) {
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
