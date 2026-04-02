package it.csextensionsant

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import it.csextensionsant.BuildConfig
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.SubtitleHelper
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject
import java.util.UUID

class ThisNotBusiness : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://thisnot.business"
    override var name = "ThisNotBusiness"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    val cfKiller = CloudflareKiller()

    private val password = BuildConfig.THISNOTBUSINESS_PASSWORD
    private var loggedIn = false
    private var sessionCookie: Map<String, String> = emptyMap()

    private suspend fun ensureLogin() {
        if (loggedIn) return
        val getResp = app.get("$mainUrl/index.php")
        // Estrai PHPSESSID manualmente dal Set-Cookie header
        val setCookieHeader = getResp.okhttpResponse.headers("set-cookie")
            .joinToString("; ")
        val phpSessId = Regex("PHPSESSID=([^;,\\s]+)").find(setCookieHeader)?.groupValues?.get(1)
        if (phpSessId != null) {
            sessionCookie = mapOf("PHPSESSID" to phpSessId)
        }
        Log.d("ThisNotBusiness", "PHPSESSID ottenuto: $phpSessId")

        val loginResp = app.post(
            "$mainUrl/index.php",
            data = mapOf("password" to password, "submit" to ""),
            headers = mapOf(
                "content-type" to "application/x-www-form-urlencoded",
                "origin" to mainUrl,
                "referer" to "$mainUrl/"
            ),
            cookies = sessionCookie
        )
        Log.d("ThisNotBusiness", "Login response code: ${loginResp.code}")
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

        val responseText = app.get("$mainUrl/api/eventi.json", cookies = sessionCookie).text
        val root = JSONObject(responseText)
        val events = root.getJSONArray("eventi")

        // Raggruppa per giorno → competizione mantenendo l'ordine di arrivo
        val groups = linkedMapOf<String, MutableList<SearchResponse>>()

        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val competition = event.optString("competizione", "Altri eventi")
            val eventName = event.optString("evento", "")
            val emoji = event.optString("emoji", "")
            val logo = event.optString("logo", "")
            val lingua = SubtitleHelper.fromCodeToLangTagIETF(event.optString("lingua", "it").replace("fi-", " "))
            val giorno = event.optString("giorno", "")
            val orario = event.optString("orario", "")

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
                    put("title", eventName)
                    put("logo", logo)
                    put("lingua", lingua)
                    put("giorno", giorno)
                    put("orario", orario)
                }.toString()
                channelLabel = (0 until canali.length())
                    .mapNotNull {
                        canali.getJSONObject(it).optString("canale", "").takeIf { c -> c.isNotBlank() }
                    }
                    .joinToString(" / ").ifBlank { "Multi-sorgente" }
            } else {
                val rawLink = event.optString("link", "").trim()
                if (rawLink.isBlank()) continue
                channelLabel = event.optString("canale", "")
                dataUrl = JSONObject().apply {
                    put("type", "single")
                    put("title", eventName)
                    put("logo", logo)
                    put("link", rawLink)
                    put("lingua", lingua)
                    put("giorno", giorno)
                    put("orario", orario)
                }.toString()
            }

            val displayName = buildString {
                if (emoji.isNotBlank()) append("$emoji ")
                if (eventName.isNotBlank()) append(eventName)
                if (orario.isNotBlank()) append(" ($orario)")
                if (channelLabel.isNotBlank()) append(" - $channelLabel")
            }.trim()

            val groupKey = if (giorno.isNotBlank()) "$giorno — $competition" else competition
            groups.getOrPut(groupKey) { mutableListOf() }.add(
                newLiveSearchResponse(displayName, dataUrl, TvType.Live) {
                    this.posterUrl = fixUrl(logo).takeIf { it.isNotBlank() }
                    this.lang = lingua
                }
            )
        }

        if (groups.isEmpty()) throw ErrorLoadingException()

        return newHomePageResponse(groups.map { (key, shows) ->
            HomePageList(key, shows, isHorizontalImages = false)
        }, false)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d("ThisNotBusiness", "Loading $url")
        if (url.startsWith("{")) {
            val data = JSONObject(url)
            return when (data.optString("type")) {
                "single" -> {
                    val title = data.optString("title", name)
                    val logo = data.optString("logo", "")
                    newLiveStreamLoadResponse(title, url, url) {
                        this.posterUrl = fixUrl(logo).takeIf { it.isNotBlank() }
                        //this.logoUrl = fixUrl(logo).takeIf { it.isNotBlank() }
                        this.tags = listOf<String>(data.optString("giorno", ""), data.optString("orario", ""))
                        this.plot = "---------"
                    }
                }
                else -> { // "canali"
                    val canali = data.getJSONArray("canali")
                    val first = canali.optJSONObject(0)
                    val title = first?.optString("evento", name) ?: name
                    newLiveStreamLoadResponse(title, url, url)
                }
            }
        }
        throw ErrorLoadingException()
    }

    // Converte base64url → hex (come fa l'estensione Chrome con Nn())
    private fun base64UrlToHex(b64: String): String {
        val standard = b64.replace('-', '+').replace('_', '/')
        val padded = standard + "=".repeat((4 - standard.length % 4) % 4)
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // Converte hex → base64url senza padding (richiesto da DrmExtractorLink.kid / .key)
    private fun hexToBase64(hex: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // Decodifica il parametro 'ck' (base64) per ottenere la chiave ClearKey in formato HEX:HEX
    // Formati supportati:
    //   - base64("hex_kid:hex_key")                → passato direttamente
    //   - base64('{"keys":[{"kid":b64,"k":b64}]}') → kid/k convertiti da base64url a hex
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
                    if (kid.isNotBlank() && kv.isNotBlank())
                        "${base64UrlToHex(kid)}:${base64UrlToHex(kv)}"
                    else null
                }.joinToString(",").takeIf { it.isNotBlank() }
            } else {
                // Dizionario piatto { "kid": "key" } — già in hex
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
        ensureLogin()
        val doc = app.get(pageUrl, referer = mainUrl, cookies = sessionCookie).document
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

        val isDash = streamUrl.contains(".mpd")
        Log.d("ThisNotBusiness", "Stream: $streamUrl | keys (hex): $keys | dash: $isDash")

        // Usa DrmExtractorLink per ClearKey se abbiamo le chiavi e il tipo è DASH
        val firstPair = keys?.split(",")?.firstOrNull()
        val kidKeyParts = firstPair?.split(":")
        if (isDash && kidKeyParts != null && kidKeyParts.size == 2) {
            val kidHex = kidKeyParts[0]
            val keyHex = kidKeyParts[1]
            callback(
                newDrmExtractorLink(
                    source = this.name,
                    name = linkName,
                    url = streamUrl,
                    type = ExtractorLinkType.DASH,
                    uuid = UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e") // ClearKey UUID
                ) {
                    this.quality = 0
                    this.referer = pageUrl
                    this.kid = hexToBase64(kidHex)
                    this.key = hexToBase64(keyHex)
                }
            )
        } else {
            // Fallback: no DRM o stream M3U8
            callback(
                newExtractorLink(
                    source = this.name,
                    name = linkName,
                    url = streamUrl,
                    type = if (isDash) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                ) {
                    this.quality = 0
                    this.referer = pageUrl
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureLogin()

        if (data.startsWith("{")) {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "single" -> {
                    val link = json.optString("link", "").trim()
                    if (link.isNotBlank()) extractFromEventPage(resolveLink(link), "Stream", callback)
                }
                else -> { // "canali"
                    val canali = json.getJSONArray("canali")
                    for (i in 0 until canali.length()) {
                        val canale = canali.getJSONObject(i)
                        val link = canale.optString("link", "").trim()
                        if (link.isBlank()) continue
                        val canaleName = canale.optString("canale", "Stream ${i + 1}")
                        extractFromEventPage(resolveLink(link), canaleName, callback)
                    }
                }
            }
        }

        return true
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain: Interceptor.Chain -> cfKiller.intercept(chain) }
    }
}
