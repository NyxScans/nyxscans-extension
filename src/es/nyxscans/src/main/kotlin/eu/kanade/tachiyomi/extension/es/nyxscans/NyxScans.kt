package eu.kanade.tachiyomi.extension.es.nyxscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * NyxScans (lectornyx.com) source backed by Supabase PostgREST (JSON), no HTML scraping.
 *
 * - `SManga.url` holds the opaque series slug (`series.id`).
 * - `SChapter.url` is `<slug>/<chapterNumber>` and is resolved back against the
 *   `chapters` table in [pageListRequest].
 */
class NyxScans : HttpSource() {

    override val name = "NyxScans"

    override val lang = "es"

    override val baseUrl = "https://lectornyx.com"

    override val supportsLatest = true

    // Supabase PostgREST endpoint. The anon key is public by design; row access is
    // enforced server-side by RLS. These values mirror the public web frontend config.
    private val supabaseUrl = "https://xshcanfefmheixdcgfpz.supabase.co"
    private val anonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhzaGNhbmZlZm1oZWl4ZGNnZnB6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY5MDAzMTcsImV4cCI6MjA5MjQ3NjMxN30.EPL12SBUL9wFpkU94HzvtFA3_A3-I6YDBWxsoj3YvFU"

    private val restBase = "${supabaseUrl.trimEnd('/')}/rest/v1"

    private val pageSize = 30

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("apikey", anonKey)
        .add("Authorization", "Bearer $anonKey")
        .add("Accept", "application/json")

    // ---------------------------------------------------------------------------------------
    // Popular
    // ---------------------------------------------------------------------------------------

    override fun popularMangaRequest(page: Int): Request = seriesRequest(order = "views.desc", page = page)

    override fun popularMangaParse(response: Response): MangasPage = parseMangasPage(response)

    // ---------------------------------------------------------------------------------------
    // Latest updates
    // ---------------------------------------------------------------------------------------

    override fun latestUpdatesRequest(page: Int): Request = seriesRequest(order = "updated_at.desc", page = page)

    override fun latestUpdatesParse(response: Response): MangasPage = parseMangasPage(response)

    // ---------------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------------

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$restBase/series".toHttpUrl().newBuilder()
            .addQueryParameter("order", "updated_at.desc")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())

        if (query.isNotBlank()) {
            url.addQueryParameter("title", "ilike.*${query.trim()}*")
        }

        val genre = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.let { it.values.getOrNull(it.state) }
            ?.takeIf { it.isNotBlank() && it != GENRE_ALL }
        if (genre != null) {
            url.addQueryParameter("genres", "cs.{$genre}")
        }

        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = parseMangasPage(response)

    // ---------------------------------------------------------------------------------------
    // Details
    // ---------------------------------------------------------------------------------------

    override fun mangaDetailsRequest(manga: SManga): Request = GET(
        "$restBase/series?id=eq.${manga.url}",
        headers,
    )

    override fun mangaDetailsParse(response: Response): SManga {
        val row = parseJsonArray(response).firstOrNull()?.jsonObject
            ?: throw Exception("Serie no encontrada")
        return row.toSManga(full = true)
    }

    // ---------------------------------------------------------------------------------------
    // Chapter list
    // ---------------------------------------------------------------------------------------

    override fun chapterListRequest(manga: SManga): Request = GET(
        "$restBase/chapters?series_id=eq.${manga.url}&order=chapter_number.asc",
        headers,
    )

    override fun chapterListParse(response: Response): List<SChapter> =
        parseJsonArray(response).map { it.jsonObject.toSChapter() }

    // ---------------------------------------------------------------------------------------
    // Pages
    // ---------------------------------------------------------------------------------------

    override fun pageListRequest(chapter: SChapter): Request {
        val slug = chapter.url.substringBeforeLast('/')
        val number = chapter.url.substringAfterLast('/')
        return GET(
            "$restBase/chapters?series_id=eq.$slug&chapter_number=eq.$number&select=images",
            headers,
        )
    }

    override fun pageListParse(response: Response): List<Page> {
        val images = parseJsonArray(response).firstOrNull()?.jsonObject?.stringList("images").orEmpty()
        return images.mapIndexed { index, url -> Page(index, imageUrl = normalizeImage(url)) }
    }

    // Not used: pages carry their imageUrl directly. Kept for API completeness.
    override fun imageUrlParse(response: Response): String = response.request.url.toString()

    // ---------------------------------------------------------------------------------------
    // Filters
    // ---------------------------------------------------------------------------------------

    override fun getFilterList(): FilterList = FilterList(GenreFilter())

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun seriesRequest(order: String, page: Int): Request {
        val url = "$restBase/series".toHttpUrl().newBuilder()
            .addQueryParameter("order", order)
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .build()
        return GET(url, headers)
    }

    private fun parseMangasPage(response: Response): MangasPage {
        val arr = parseJsonArray(response)
        val mangas = arr.map { it.jsonObject.toSManga(full = false) }
        return MangasPage(mangas, arr.size >= pageSize)
    }

    private fun parseJsonArray(response: Response): JsonArray {
        val body = response.body?.string().orEmpty()
        if (body.isBlank() || body == "null") return JsonArray(emptyList())
        return (Json.parseToJsonElement(body) as? JsonArray) ?: JsonArray(emptyList())
    }

    private fun JsonObject.toSManga(full: Boolean): SManga = SManga.create().apply {
        url = str("id")
        title = str("title")
        thumbnail_url = normalizeImage(str("cover"))
        author = ""
        artist = ""
        status = parseStatus(str("status"))
        genre = stringList("genres").joinToString(", ")
        description = if (full) synthesizeDescription(this@toSManga) else null
    }

    private fun JsonObject.toSChapter(): SChapter = SChapter.create().apply {
        val number = doubleValue("chapter_number")
        chapter_number = number.toFloat()
        url = "${str("series_id")}/${formatChapterNumber(number)}"
        name = str("title")
        date_upload = parseDate(str("created_at"))
        scanlator = ""
    }

    private fun synthesizeDescription(o: JsonObject): String {
        val genres = o.stringList("genres").joinToString(", ")
        val statusLabel = when (parseStatus(o.str("status"))) {
            SManga.ONGOING -> "En emisión"
            SManga.COMPLETED -> "Finalizado"
            SManga.ON_HIATUS -> "En espera"
            SManga.CANCELLED -> "Cancelado"
            else -> "Desconocido"
        }
        val latest = o.intValue("latest_chapter")
        val source = o.str("source")

        return buildList {
            add(o.str("title"))
            if (genres.isNotEmpty()) add("Géneros: $genres")
            add("Estado: $statusLabel")
            if (latest != null) add("Último capítulo: $latest")
            if (source.isNotEmpty()) add("Fuente original: $source")
        }.joinToString("\n")
    }

    private fun parseStatus(value: String): Int = when (value.trim().lowercase()) {
        "en emisión", "en emision", "ongoing", "emitiendo", "activo", "continua" -> SManga.ONGOING
        "finalizado", "completed", "completado", "terminado", "completo" -> SManga.COMPLETED
        "en espera", "on hold", "hiatus", "en pausa", "pausado" -> SManga.ON_HIATUS
        "cancelado", "cancelled", "abandonado", "dropped" -> SManga.CANCELLED
        else -> SManga.UNKNOWN
    }

    // PostgREST returns ISO-8601 UTC timestamps, e.g. "2026-05-23T15:55:14.922+00:00".
    // java.text is used instead of java.time so it also works on API 21-25 hosts.
    private val isoDateParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun parseDate(raw: String): Long {
        val cleaned = raw.trim().substringBefore('.').substringBefore('+').replace("Z", "")
        return runCatching { isoDateParser.parse(cleaned)?.time ?: 0L }.getOrDefault(0L)
    }

    private fun formatChapterNumber(number: Double): String =
        if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

    private fun normalizeImage(url: String): String =
        url.replace(LEGACY_R2_HOST, "https://cdn.lectornyx.com/")

    // JSON helpers -------------------------------------------------------------

    private fun JsonElement?.asString(): String? = when {
        this == null -> null
        this is JsonPrimitive && isString -> content
        else -> null
    }

    private fun JsonElement?.asDouble(): Double? = when {
        this == null -> null
        this is JsonPrimitive -> doubleOrNull ?: content.toDoubleOrNull()
        else -> null
    }

    private fun JsonElement?.asInt(): Int? = when {
        this == null -> null
        this is JsonPrimitive -> intOrNull ?: content.toIntOrNull()
        else -> null
    }

    private fun JsonObject.str(key: String): String = this[key].asString().orEmpty()

    private fun JsonObject.doubleValue(key: String): Double = this[key].asDouble() ?: 0.0

    private fun JsonObject.intValue(key: String): Int? = this[key].asInt()

    /** [images] may be a JSON array of URLs or a JSON-encoded string. */
    private fun JsonObject.stringList(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        return when (el) {
            is JsonArray -> el.mapNotNull { it.asString() }
            is JsonPrimitive -> if (el.isString) {
                runCatching {
                    (Json.parseToJsonElement(el.content) as? JsonArray)
                        ?.mapNotNull { it.asString() }
                        .orEmpty()
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            else -> emptyList()
        }
    }

    private class GenreFilter : Filter.Select<String>("Género", GENRES)

    private companion object {
        const val GENRE_ALL = "Todos"

        // Snapshot of the current catalog genres; used only to build the filter UI.
        val GENRES = arrayOf(
            GENRE_ALL,
            "Adaptación de Novela",
            "Aventuras",
            "Bondage",
            "Comedia",
            "Comedy",
            "Drama",
            "Ecchi",
            "Escolar",
            "Fantasía",
            "Hardcore",
            "Harem",
            "Isekai",
            "MILF",
            "Mature",
            "Netorare",
            "Recuentos de la vida",
            "Romance",
            "School Life",
            "Seinen",
            "Sistemas",
            "Venganza",
        )

        val LEGACY_R2_HOST = Regex("""https://pub-[a-f0-9]+\.r2\.dev/""")
    }
}
