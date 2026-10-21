package eu.kanade.tachiyomi.extension.all.rule34

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDateTime
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * rule34.xxx - every post is one entry with a single chapter containing one image.
 *
 * List: /index.php?page=post&s=list&tags=<tags>&pid=<(page-1)*42>
 * Post: /index.php?page=post&s=view&id=<id>
 */
@Source
abstract class Rule34 : KeiSource() {

    private val postsPerPage = 42
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // Video posts (webm/mp4) can't be shown in a reader, so they are skipped.
    private val listSelector = "div.image-list span.thumb:not(:has(img.webm-thumb))"
    private val nextSelector = "div.pagination a[alt=next]"

    // ============================== Lists ==============================

    private suspend fun fetchList(page: Int, tags: String): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("index.php")
            .addQueryParameter("page", "post")
            .addQueryParameter("s", "list")
            .addQueryParameter("tags", tags.ifBlank { "all" })
            .addQueryParameter("pid", ((page - 1) * postsPerPage).toString())
            .build()

        val document = client.get(url, headers).asJsoup()
        val mangas = document.select(listSelector).mapNotNull(::postFromElement)
        return MangasPage(mangas, document.selectFirst(nextSelector) != null)
    }

    private fun postFromElement(element: Element): SManga? {
        val link = element.selectFirst("a") ?: return null
        val id = link.absUrl("href").toHttpUrl().queryParameter("id") ?: return null
        return SManga.create().apply {
            url = id
            title = "Post $id"
            thumbnail_url = element.selectFirst("img")?.absUrl("src")
            status = SManga.COMPLETED
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage =
        fetchList(page, "sort:score:desc $DEFAULT_EXCLUDE")

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        fetchList(page, DEFAULT_EXCLUDE)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val tags = mutableListOf<String>()
        tags += query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

        filters.filterIsInstance<RatingFilter>().firstOrNull()?.value
            ?.takeIf { it.isNotEmpty() }?.let { tags += "rating:$it" }
        filters.filterIsInstance<SortFilter>().firstOrNull()?.value
            ?.takeIf { it.isNotEmpty() }?.let { tags += "sort:$it" }
        if (filters.filterIsInstance<ExcludeAiFilter>().firstOrNull()?.state == true) {
            tags += "-ai_generated"
        }
        if (filters.filterIsInstance<ExcludeLoliShotaFilter>().firstOrNull()?.state != false) {
            tags += DEFAULT_EXCLUDE.split(" ")
        }
        return fetchList(page, tags.joinToString(" "))
    }

    // ============================== URLs ==============================

    private fun postUrl(id: String) = "$baseUrl/index.php?page=post&s=view&id=$id"

    override fun getMangaUrl(manga: SManga) = postUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter) = postUrl(chapter.url)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.queryParameter("s") != "view") return null
        val id = url.queryParameter("id") ?: return null
        return SManga.create().apply {
            this.url = id
            title = "Post $id"
            status = SManga.COMPLETED
        }
    }

    // ============================== Details + chapter ==============================

    private fun Document.stat(label: String): String? =
        select("#stats li").map { it.text() }
            .firstOrNull { it.startsWith("$label:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()

    private fun Document.tagsOfType(type: String): List<String> =
        select("#tag-sidebar li.tag-type-$type").mapNotNull { li ->
            li.select("a").firstOrNull { it.attr("href").contains("s=list") }?.text()
        }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(postUrl(manga.url), headers).asJsoup()

        val artists = document.tagsOfType("artist")
        val characters = document.tagsOfType("character")
        val posted = document.stat("Posted")?.substringBefore(" by")?.trim()

        val details = SManga.create().apply {
            url = manga.url
            title = "Post ${manga.url}"
            author = artists.joinToString().ifBlank { null }
            artist = author
            status = SManga.COMPLETED
            genre = (
                document.tagsOfType("copyright") +
                    characters +
                    document.tagsOfType("general") +
                    document.tagsOfType("metadata")
                ).joinToString()
            description = buildString {
                if (artists.isNotEmpty()) appendLine("Artist: ${artists.joinToString()}")
                if (characters.isNotEmpty()) appendLine("Characters: ${characters.joinToString()}")
                document.stat("Rating")?.let { appendLine("Rating: $it") }
                document.stat("Score")?.let { appendLine("Score: ${it.substringBefore(" ")}") }
                document.stat("Size")?.let { appendLine("Size: $it") }
                document.stat("Source")?.let { appendLine("Source: $it") }
                posted?.let { appendLine("Posted: $it") }
            }.trim()
        }

        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Post"
            chapter_number = 1f
            date_upload = dateFormat.tryParseDateTime(posted, ZoneId.of("UTC"))
        }

        return SMangaUpdate(details, listOf(chapter))
    }

    // ============================== Pages ==============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(postUrl(chapter.url), headers).asJsoup()

        val fromLink = document.select("div.link-list a")
            .firstOrNull { it.text().equals("Original image", ignoreCase = true) }
            ?.absUrl("href")

        val fromScript = document.select("script").map { it.data() }
            .firstOrNull { "'domain'" in it && "'img'" in it }
            ?.let { script ->
                val domain = Regex("'domain'\\s*:\\s*'([^']+)'").find(script)?.groupValues?.get(1)
                val baseDir = Regex("'base_dir'\\s*:\\s*'([^']+)'").find(script)?.groupValues?.get(1)
                val dir = Regex("'dir'\\s*:\\s*'?(\\d+)'?").find(script)?.groupValues?.get(1)
                val img = Regex("'img'\\s*:\\s*'([^']+)'").find(script)?.groupValues?.get(1)
                if (domain != null && baseDir != null && dir != null && img != null) {
                    "${domain.trimEnd('/')}/$baseDir/$dir/$img"
                } else {
                    null
                }
            }

        val raw = fromLink?.ifBlank { null }
            ?: fromScript
            ?: document.selectFirst("img#image")?.absUrl("src")
            ?: return emptyList()

        // "https://host//images/..." -> "https://host/images/..."
        val url = raw.replace(Regex("(?<!:)/{2,}"), "/")

        val path = url.substringBefore("?").lowercase()
        if (path.endsWith(".webm") || path.endsWith(".mp4")) {
            throw Exception("Video posts are not supported.")
        }
        return listOf(Page(0, imageUrl = url))
    }

    // ============================== Filters ==============================

    private open class PairSelect(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val value: String get() = options[state].second
    }

    private class RatingFilter : PairSelect(
        "Rating",
        listOf("Any" to "", "Safe" to "safe", "Questionable" to "questionable", "Explicit" to "explicit"),
    )

    private class SortFilter : PairSelect(
        "Sort",
        listOf("Newest" to "", "Score" to "score:desc", "Recently updated" to "updated:desc"),
    )

    private class ExcludeAiFilter : Filter.CheckBox("Exclude AI-generated", false)

    private class ExcludeLoliShotaFilter : Filter.CheckBox("Exclude loli/shota tags", true)

    override fun getFilterList(data: JsonElement?) = FilterList(
        Filter.Header("Search = space-separated tags, e.g. bleach inoue_orihime"),
        RatingFilter(),
        SortFilter(),
        ExcludeAiFilter(),
        ExcludeLoliShotaFilter(),
    )

    companion object {
        private const val DEFAULT_EXCLUDE = "-loli -shota"
    }
}
