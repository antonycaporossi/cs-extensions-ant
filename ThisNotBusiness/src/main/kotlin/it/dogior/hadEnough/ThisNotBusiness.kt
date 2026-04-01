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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {

        val responseText = app.get("$mainUrl/api/eventi.json").text
        val data = JSONObject(responseText)
        val events = data.getJSONArray("eventi")

        // Raggruppa per competizione mantenendo l'ordine
        val groups = linkedMapOf<String, MutableList<SearchResponse>>()

        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val link = event.optString("link", "").trim()
            if (link.isBlank()) continue

            val fullLink = when {
                link.startsWith("http") -> link
                link.startsWith("/") -> "$mainUrl$link"
                else -> "$mainUrl/$link"
            }

            val competition = event.optString("competizione", "Altri eventi")
            val eventName = event.optString("evento", "")
            val channel = event.optString("canale", "")
            val time = event.optString("orario", "")
            val emoji = event.optString("emoji", "")
            val logo = event.optString("logo", "")

            val displayName = buildString {
                if (emoji.isNotBlank()) append("$emoji ")
                if (eventName.isNotBlank()) append(eventName)
                if (time.isNotBlank()) append(" ($time)")
                if (channel.isNotBlank()) append(" - $channel")
            }.trim()

            val searchResponse = newLiveSearchResponse(displayName, fullLink, TvType.Live) {
                this.posterUrl = logo.takeIf { it.isNotBlank() }
            }

            groups.getOrPut(competition) { mutableListOf() }.add(searchResponse)
        }

        if (groups.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(groups.map { (category, shows) ->
            HomePageList(category, shows, isHorizontalImages = false)
        })
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".info-wrap > h1, h1")?.text()
            ?: url.substringAfterLast("/")
        val posterStyle = document.selectFirst(".background-image[style]")?.attr("style") ?: ""
        val poster = posterStyle
            .substringAfter("url(", "")
            .substringBefore(")", "")
            .trim().trimStart('\'', '"').trimEnd('\'', '"')
            .let { if (it.isNotBlank()) fixUrl(it) else null }
        val plot = document.selectFirst(".info-wrap .desc, .info-span .desc")?.text()

        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Tenta di estrarre l'URL del flusso da uno script p,a,c,k,e,d
    private fun getStreamUrlFromScript(document: org.jsoup.nodes.Document): String? {
        val scripts = document.body().select("script")
        val packed = scripts.findLast { it.data().contains("eval(") } ?: return null
        val unpacked = getAndUnpack(packed.data())
        return unpacked
            .substringAfter("var src=\"", "")
            .substringBefore("\"", "")
            .takeIf { it.isNotBlank() && (it.startsWith("http") || it.startsWith("//")) }
    }

    // Decodifica il parametro 'ck' (base64 JSON) per le chiavi ClearKey
    private fun decodeKeys(ckParam: String): String? {
        return try {
            var clean = ckParam
            repeat((4 - clean.length % 4) % 4) { clean += "=" }
            val decoded = Base64.decode(clean, Base64.URL_SAFE or Base64.DEFAULT)
                .toString(Charsets.UTF_8)

            val json = try {
                JSONObject(decoded)
            } catch (e: Exception) {
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
                json.keys().asSequence()
                    .filter { it.length > 10 }
                    .mapNotNull { k ->
                        json.optString(k).takeIf { it.isNotBlank() }?.let { "$k:$it" }
                    }
                    .joinToString(",").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w("ThisNotBusiness", "Errore decodifica ck: ${e.message}")
            null
        }
    }

    // Segue la catena iframe/script fino a trovare il flusso (max 4 hop)
    private suspend fun extractStreamFromPage(
        url: String,
        referer: String,
        linkName: String,
        callback: (ExtractorLink) -> Unit,
        depth: Int = 0
    ) {
        if (depth > 4) return
        val fullUrl = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }

        val doc = app.get(fullUrl, referer = referer).document

        // 1. Cerca URL nel JS compresso (eval/packed)
        val streamUrl = getStreamUrlFromScript(doc)
        if (streamUrl != null) {
            val resolvedUrl = if (streamUrl.startsWith("//")) "https:$streamUrl" else streamUrl

            val ck = try {
                android.net.Uri.parse(resolvedUrl).getQueryParameter("ck")
            } catch (e: Exception) { null }
            val keys = ck?.let { decodeKeys(it) }

            Log.d("ThisNotBusiness", "Stream trovato[$depth]: $resolvedUrl | keys=$keys")

            callback(newExtractorLink(
                source = this.name,
                name = linkName,
                url = resolvedUrl,
                type = if (resolvedUrl.contains(".mpd")) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
            ) {
                this.quality = 0
                this.referer = fullUrl
                // Passa le chiavi ClearKey nell'header per i player che le supportano
                if (keys != null) {
                    this.headers = mapOf("clearkey" to keys)
                }
            })
            return
        }

        // 2. Segui iframe
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src") ?: return
        extractStreamFromPage(iframeSrc, fullUrl, linkName, callback, depth + 1)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val buttons = document.select("button.btn[data-link]")

        if (buttons.isEmpty()) {
            // Nessun bottone con link: prova direttamente sulla pagina evento
            extractStreamFromPage(data, mainUrl, "Stream", callback)
        } else {
            buttons.forEach { button ->
                val link = button.attr("data-link").trim()
                if (link.isBlank()) return@forEach
                val linkName = button.text().ifBlank { "Stream" }
                extractStreamFromPage(link, data, linkName, callback)
            }
        }
        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain: Interceptor.Chain -> cfKiller.intercept(chain) }
    }
}
