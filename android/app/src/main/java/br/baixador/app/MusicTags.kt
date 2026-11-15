package br.baixador.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Completa as tags de música (álbum, faixa, gênero, ano, capa oficial) buscando no iTunes.
 * Recebe o JSON do yt-dlp (-j), devolve o JSON alterado para usar com --load-info-json.
 * (mesma lógica de desktop/MusicTags.cs)
 */
object MusicTags {

    private val junk = Regex(
        """(?iu)\s*[(\[][^)\]]*(official|oficial|audio|áudio|video|vídeo|clipe|lyric|letra|visuali[sz]er|legendad|tradu|hd|4k|remaster)[^)\]]*[)\]]"""
    )
    private val version = Regex("""(?iu)remix|live|ao vivo|ac[uú]stic|acoustic|karaoke|instrumental|deluxe|edition|edi[cç][aã]o|version|vers[aã]o|sped|slowed|cover""")
    private val various = Regex("""(?iu)v[aá]rios|various|diversos""")
    private val compName = Regex(
        """(?iu)soundtrack|trilha|greatest|best of|melhores|sucessos|\bhits\b|collection|cole[cç][aã]o|colet[aâ]nea|perfil|\btop\b|essencia|essential|cl[aá]ssicos|novidades|anthology|antologia|gold\b|s[eé]rie|filme|movie|novela|carnaval|preferidas|ao vivo em casa"""
    )

    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val d = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (c in d) if (Character.isLetterOrDigit(c) && Character.getType(c) != Character.NON_SPACING_MARK.toInt()) sb.append(c)
        return sb.toString()
    }

    private fun clean(s: String?): String {
        val t = junk.replace(s ?: "", "")
        val sb = StringBuilder()   // tira emojis e símbolos soltos
        for (c in t) {
            val type = Character.getType(c)
            if (type != Character.OTHER_SYMBOL.toInt() && type != Character.SURROGATE.toInt() && type != Character.MODIFIER_SYMBOL.toInt()) sb.append(c)
        }
        return sb.toString().replace(Regex("""\s{2,}"""), " ").trim()
    }

    private fun noParens(s: String?) = (s ?: "").replace(Regex("""\s*[(\[].*?[)\]]"""), "").trim()

    // tira participações ("ft. X", "feat. X", "part. X") e @perfis
    private fun noFeat(s: String?) = (s ?: "")
        .replace(Regex("""(?iu)\s+(ft\.?|feat\.?|featuring|part\.?|participa[cç][aã]o)\s.*$"""), "")
        .replace(Regex("""@\S+"""), "").trim()

    private fun key(s: String?) = norm(noFeat(noParens(s)))

    // "Henrique e Juliano" == "Henrique & Juliano"
    private fun artistKey(s: String?) = norm((s ?: "").replace(Regex("""(?iu)\s*&\s*|\s+(e|and|y|x)\s+"""), " "))

    private fun noAlbumSuffix(s: String?) = (s ?: "").replace(Regex("""(?iu)\s+-\s+(Single|EP)$"""), "")

    /** separa "Artista - Música (Official Audio)" em (artista, música) */
    fun guess(title: String?, channel: String?, ytArtist: String?, ytTrack: String?): Pair<String, String> {
        if (!ytArtist.isNullOrBlank() && !ytTrack.isNullOrBlank())
            return Pair(ytArtist.split(',')[0].trim(), noFeat(clean(ytTrack)))
        val t = clean(title)
        val m = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(t)
        if (m != null) {
            // "MÚSICA - DVD Ao Vivo No Ibirapuera": fica só com a primeira parte
            val song = m.groupValues[2].split(Regex("""\s+[-–—|]\s+"""))[0]
            return Pair(noFeat(m.groupValues[1]), noFeat(song))
        }
        val ch = (channel ?: "").replace(Regex("""(?iu)\s*-\s*Topic$|VEVO$|\s*Official$"""), "").trim()
        return Pair(ch, noFeat(t.split(Regex("""\s+[|]\s+"""))[0]))
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) opt(k) as? String else null

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun enrich(json: String): String {
        val d = JSONObject(json)
        val (artist, song) = guess(d.str("title"), d.str("channel") ?: d.str("uploader"), d.str("artist") ?: d.str("creator"), d.str("track"))
        d.put("description", "")               // a descrição do vídeo só polui as tags
        if (song.isEmpty()) return d.toString()
        d.put("track", song)
        if (artist.isNotEmpty()) d.put("artist", artist)

        try {
            var best: JSONObject? = null
            var bestScore = Int.MIN_VALUE
            val nSong = key(song)
            val nArtist = artistKey(artist)
            val songIsVersion = version.containsMatchIn(d.str("title") ?: song)   // vídeo "ao vivo" => álbum ao vivo é o certo
            for (country in listOf("BR", "US")) {
                val url = "https://itunes.apple.com/search?entity=song&limit=25&country=$country&term=" +
                    URLEncoder.encode("$artist $song", "UTF-8")
                val results = JSONObject(httpGet(url)).optJSONArray("results") ?: JSONArray()
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val tn = r.str("trackName") ?: ""
                    val an = r.str("artistName") ?: ""
                    val cn = r.str("collectionName") ?: ""
                    if (key(tn) != nSong) continue
                    val na = artistKey(an)
                    if (nArtist.isNotEmpty() && !na.contains(nArtist) && !nArtist.contains(na)) continue
                    var score = 0
                    if (norm(tn) == norm(song)) score += 2
                    if (!songIsVersion && version.containsMatchIn(cn)) score -= 3
                    if (!songIsVersion && version.containsMatchIn(tn)) score -= 3
                    if (songIsVersion && (version.containsMatchIn(cn) || version.containsMatchIn(tn))) score += 2
                    if (cn.endsWith(" - Single", ignoreCase = true)) score -= 1
                    if (various.containsMatchIn(r.str("collectionArtistName") ?: "")) score -= 10   // coletânea de vários artistas
                    if (compName.containsMatchIn(cn)) score -= 4                                    // coletânea/trilha do próprio artista
                    if (score > bestScore) { bestScore = score; best = r }
                }
                if (best != null) break
            }
            val b = best
            if (b != null) {
                d.put("track", b.str("trackName"))
                d.put("artist", b.str("artistName"))
                d.put("album", noAlbumSuffix(b.str("collectionName")))
                d.put("album_artist", b.str("collectionArtistName") ?: b.str("artistName"))
                d.put("genre", b.str("primaryGenreName"))
                if (b.has("trackNumber")) d.put("track_number", b.optInt("trackNumber"))
                val rd = b.str("releaseDate")
                if (rd != null && rd.length >= 10) {
                    val ymd = rd.substring(0, 10).replace("-", "")
                    d.put("release_date", ymd)
                    d.put("release_year", rd.substring(0, 4).toInt())
                    d.put("upload_date", ymd)   // vira a tag "Ano"
                }
                val art = b.str("artworkUrl100")
                if (art != null) {
                    val big = art.replace("100x100bb", "1000x1000bb")
                    d.put("thumbnail", big)
                    d.put("thumbnails", JSONArray().put(JSONObject().put("id", "itunes").put("url", big)))
                }
            }
        } catch (_: Exception) {
            // sem internet/iTunes fora: fica só com artista e música
        }
        return d.toString()
    }
}
