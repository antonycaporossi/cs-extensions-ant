package com.lagradost

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

class ThisNotBusiness : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://thisnot.business"
    override var name = "ThisNotBusiness"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    private val password = "2025"
    private var loggedIn = false

    // POST login a /index.php, i cookie (PHPSESSID) vengono gestiti automaticamente da app
    private suspend fun ensureLogin() {
        if (loggedIn) return
        app.get("$mainUrl/index.php") // ottieni PHPSESSID
        app.post(
            "$mainUrl/index.php",
            data = mapOf("password" to password, "submit" to ""),
            headers = mapOf(
                "content-type" to "application/x-www-form-urlencoded",
                "origin" to mainUrl,
                "referer" to "$mainUrl/index.php"
            )
        )
        loggedIn = true
        Log.d("ThisNotBusiness", "Login effettuato")
    }

    private fun resolveLink(link: String): String = when {
        link.startsWith("http") -> link
        link.startsWith("/") -> "$mainUrl$link"
        else -> "$mainUrl/$link"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureLogin()

        val responseText = app.get("$mainUrl/api/eventi.json").text
        val root = JSONObject(responseText)
        val events = root.getJSONArray("eventi")

        // Raggruppa per competizione mantenendo l'ordine di arrivo
        val groups = linkedMapOf<String, MutableList<SearchResponse>>()

        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val competition = event.optString("competizione", "Altri eventi")
            val eventName = event.optString("evento", "")
            val time = event.optString("orario", "")
            val emoji = event.optString("emoji", "")
            val logo = event.optString("logo", "")

            val hasCanali = event.has("canali") && event.getJSONArray("canali").length() > 0

            // Dati serializzati che passiamo a load() / loadLinks()
            val dataUrl: String
            val channelLabel: String

            if (hasCanali) {
                val canali = event.getJSONArray("canali")
                // Serializza i canali per trasportarli fino a loadLinks()
                dataUrl = JSONObject().apply {
                    put("type", "canali")
                    put("canali", canali)
                }.toString()
                channelLabel = (0 until canali.length())
                    .mapNotNull {
                        canali.getJSONObject(it).optString("canale", "").takeIf { c -> c.isNotBlank() }
                    }
                    .joinToString(" / ").ifBlank { "Multi-sorgente" }
            } else {
                val rawLink = event.optString("link", "").trim()
                if (rawLink.isBlank()) continue
                dataUrl = resolveLink(rawLink)
                channelLabel = event.optString("canale", "")
            }

            val displayName = buildString {
                if (emoji.isNotBlank()) append("$emoji ")
                if (eventName.isNotBlank()) append(eventName)
                if (time.isNotBlank()) append(" ($time)")
                if (channelLabel.isNotBlank()) append(" - $channelLabel")
            }.trim()

            groups.getOrPut(competition) { mutableListOf() }.add(
                newLiveSearchResponse(displayName, dataUrl, TvType.Live) {
                    this.posterUrl = logo.takeIf { it.isNotBlank() }
                }
            )
        }

        if (groups.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(groups.map { (cat, shows) ->
            HomePageList(cat, shows, isHorizontalImages = false)
        })
    }

    override suspend fun load(url: String): LoadResponse {
        // Caso canali multipli: i dati sono JSON, non un URL
        if (url.startsWith("{")) {
            val data = JSONObject(url)
            val canali = data.getJSONArray("canali")
            val first = canali.optJSONObject(0)
            val title = first?.optString("evento", name) ?: name
            return newLiveStreamLoadResponse(title, url, url)
        }

        // Caso link singolo: fetch della pagina per i metadati
        val doc = app.get(url).document
        val title = doc.selectFirst(".info-wrap > h1, h1")?.text()
            ?: url.substringAfterLast("/")
        val posterStyle = doc.selectFirst(".background-image[style]")?.attr("style") ?: ""
        val poster = posterStyle
            .substringAfter("url(", "").substringBefore(")", "")
            .trim().trimStart('\'', '"').trimEnd('\'', '"')
            .let { if (it.isNotBlank()) fixUrl(it) else null }
        val plot = doc.selectFirst(".info-wrap .desc, .info-span .desc")?.text()

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Decodifica il parametro 'ck' (base64) per ottenere la chiave ClearKey
    // Formati supportati:
    //   - base64("hex_kid:hex_key")          → diretto
    //   - base64('{"keys":[{"kid":...}]}')    → JSON JWK
    private fun decodeKeys(ckParam: String): String? {
        return try {
            var clean = ckParam
            repeat((4 - clean.length % 4) % 4) { clean += "=" }
            val decoded = Base64.decode(clean, Base64.DEFAULT).toString(Charsets.UTF_8).trim()

            // Formato diretto: "hexkid:hexkey"
            if (Regex("^[0-9a-fA-F]+:[0-9a-fA-F]+$").containsMatchIn(decoded)) {
                return decoded
            }

            // Formato JSON
            val json = try { JSONObject(decoded) } catch (e: Exception) {
                return if (decoded.contains(":")) decoded else null
            }

            if (json.has("keys")) {
                val keysArr = json.getJSONArray("keys")
                (0 until keysArr.length()).mapNotNull { i ->
                    val k = keysArr.getJSONObject(i)
                    val kid = k.optString("kid")
                    val kv = k.optString("k")
                    if (kid.isNotBlank() && kv.isNotBlank()) "$kid:$kv" else null
                }.joinToString(",").takeIf { it.isNotBlank() }
            } else {
                // Dizionario piatto { "kid": "key" }
                json.keys().asSequence()
                    .filter { it.length > 10 }
                    .mapNotNull { k -> json.optString(k).takeIf { it.isNotBlank() }?.let { "$k:$it" } }
                    .joinToString(",").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w("ThisNotBusiness", "Errore decodifica ck: ${e.message}")
            null
        }
    }

    // Fetch di una pagina evento → estrae lo stream dall'iframe
    private suspend fun extractFromEventPage(
        pageUrl: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(pageUrl, referer = mainUrl).document

        // Il player è un iframe con id/name "iframe"
        val iframe = doc.selectFirst("iframe#iframe, iframe[name=iframe]") ?: run {
            Log.w("ThisNotBusiness", "Nessun iframe trovato in $pageUrl")
            return
        }
        val src = iframe.attr("src")
        Log.d("ThisNotBusiness", "iframe src: $src")

        // Il sito usa un wrapper chrome-extension: chrome-extension://xxx/player.html#<stream_url>
        val streamUrl = when {
            src.startsWith("chrome-extension://") && src.contains("#") ->
                src.substringAfter("#")
            src.startsWith("//") -> "https:$src"
            src.startsWith("http") -> src
            src.startsWith("/") -> "$mainUrl$src"
            else -> {
                Log.w("ThisNotBusiness", "src iframe non gestita: $src")
                return
            }
        }

        // Estrai chiavi ClearKey dal parametro ck
        val ck = try {
            android.net.Uri.parse(streamUrl).getQueryParameter("ck")
        } catch (e: Exception) { null }
        val keys = ck?.let { decodeKeys(it) }

        Log.d("ThisNotBusiness", "Stream: $streamUrl | keys: $keys")

        callback(
            newExtractorLink(
                source = this.name,
                name = linkName,
                url = streamUrl,
                type = if (streamUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            ) {
                this.quality = 0
                this.referer = pageUrl
                if (keys != null) {
                    this.headers = mapOf("clearkey" to keys)
                }
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLogin()

        if (data.startsWith("{")) {
            // Canali multipli: ogni canale è una sorgente separata
            val json = JSONObject(data)
            val canali = json.getJSONArray("canali")
            for (i in 0 until canali.length()) {
                val canale = canali.getJSONObject(i)
                val link = canale.optString("link", "").trim()
                if (link.isBlank()) continue
                val canaleName = canale.optString("canale", "Stream ${i + 1}")
                extractFromEventPage(resolveLink(link), canaleName, callback)
            }
        } else {
            // Singolo link
            extractFromEventPage(data, "Stream", callback)
        }

        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain: Interceptor.Chain -> cfKiller.intercept(chain) }
    }
}
