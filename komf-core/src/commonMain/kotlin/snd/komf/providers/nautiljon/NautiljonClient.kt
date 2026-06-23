package snd.komf.providers.nautiljon

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import snd.komf.browser.HtmlFetcher
import snd.komf.model.Image
import snd.komf.providers.nautiljon.model.SearchResult
import snd.komf.providers.nautiljon.model.NautiljonSeries
import snd.komf.providers.nautiljon.model.NautiljonSeriesId
import snd.komf.providers.nautiljon.model.NautiljonVolume
import snd.komf.providers.nautiljon.model.NautiljonVolumeId

const val nautiljonBaseUrl = "https://www.nautiljon.com"

class NautiljonClient(
    private val ktor: HttpClient,
    private val htmlFetcher: HtmlFetcher? = null,
) {
    private val parser = NautiljonParser()

    suspend fun searchSeries(name: String): Collection<SearchResult> {
        val url = "$nautiljonBaseUrl/mangas?q=$name"
        val document = if (htmlFetcher != null) {
            htmlFetcher.fetchHtml(url)
        } else {
            ktor.get("$nautiljonBaseUrl/mangas") { parameter("q", name) }.bodyAsText()
        }
        return parser.parseSearchResults(document)
    }

    suspend fun getSeries(seriesId: NautiljonSeriesId): NautiljonSeries {
        val url = "$nautiljonBaseUrl/mangas/${seriesId.value}.html"
        val document = if (htmlFetcher != null) {
            htmlFetcher.fetchHtml(url)
        } else {
            ktor.get(url).bodyAsText()
        }
        return parser.parseSeries(document)
    }

    suspend fun getBook(seriesId: NautiljonSeriesId, bookId: NautiljonVolumeId): NautiljonVolume {
        val url = "$nautiljonBaseUrl/mangas/${seriesId.value}/volume-${bookId.value}.html"
        val document = if (htmlFetcher != null) {
            htmlFetcher.fetchHtml(url)
        } else {
            ktor.get(url).bodyAsText()
        }
        return parser.parseVolume(document)
    }

    suspend fun getSeriesThumbnail(series: NautiljonSeries): Image? {
        val url = series.imageUrl ?: return null
        val bytes: ByteArray = ktor.get(url).body()
        return Image(bytes)
    }

    suspend fun getVolumeThumbnail(volume: NautiljonVolume): Image? {
        val url = volume.imageUrl ?: return null
        val bytes: ByteArray = ktor.get(url).body()
        return Image(bytes)
    }
}
