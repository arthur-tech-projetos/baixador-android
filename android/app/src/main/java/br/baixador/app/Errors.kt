package br.baixador.app

/** Traduz os erros do yt-dlp (em inglês) para uma frase curta em português. */
object Errors {

    /** Devolve (mensagem amigável, linha técnica original — vazia se a amigável já é a original). */
    fun explain(raw: String): Pair<String, String> {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val errLine = lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: raw
        val tech = errLine.removePrefix("ERROR:").trim().take(200)
        val t = raw.lowercase()

        fun has(vararg s: String) = s.any { t.contains(it) }

        val msg = when {
            has("private video", "video is private") -> "este vídeo é privado."
            has("confirm your age", "age-restricted", "inappropriate for some users") ->
                "vídeo com restrição de idade (o YouTube pede login)."
            has("not a bot") -> "o YouTube pediu verificação anti-robô. Tente de novo mais tarde."
            has("login required", "rate-limit reached", "log in", "logged-in", "use --cookies", "cookies") ->
                "este conteúdo só abre com login na conta (comum no Instagram)."
            has("http error 429", "too many requests") -> "o site bloqueou por excesso de pedidos. Espere alguns minutos."
            has("http error 403") -> "o site negou o acesso (403). Tente de novo; se continuar, atualize o yt-dlp."
            has("http error 404") -> "link não encontrado (404)."
            has("unsupported url") -> "este link não é suportado."
            has("requested format is not available") -> "essa qualidade não está disponível para este vídeo. Escolha outra."
            has("premieres in", "live event will begin", "this live event") -> "a transmissão ou estreia ainda não começou."
            has("copyright") -> "vídeo removido por direitos autorais."
            has("not available in your country", "geo-restricted", "geo restricted") -> "vídeo bloqueado no Brasil."
            has("video unavailable", "is unavailable", "has been removed", "no longer available") ->
                "vídeo indisponível ou removido."
            has("no space left", "enospc") -> "sem espaço no celular."
            has(
                "unable to resolve host", "failed to resolve", "name or service not known", "getaddrinfo",
                "network is unreachable", "timed out", "connection reset", "connection refused", "no address associated",
            ) -> "sem conexão com a internet (ou ela caiu)."
            else -> null
        }
        return if (msg == null) Pair(tech, "") else Pair(msg, tech)
    }
}
