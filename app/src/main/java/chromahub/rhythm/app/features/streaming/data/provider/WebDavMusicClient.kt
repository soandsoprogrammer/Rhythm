/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.util.Xml
import chromahub.rhythm.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal class WebDavMediaAccess(val authorization: String, val cacheKey: String)

internal class WebDavMusicClient(
    private val context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    class Connection(val root: HttpUrl, username: String, password: String) {
        val key = hash("$root\n$username")
        val authorization = Credentials.basic(username, password, Charsets.UTF_8)
        private val folderSegments = root.pathSegments.dropLast(1)

        fun contains(url: HttpUrl): Boolean =
            url.scheme == root.scheme && url.host == root.host && url.port == root.port &&
                url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
                url.pathSegments.take(folderSegments.size) == folderSegments

        fun relativeSegments(url: HttpUrl): List<String> = url.pathSegments.drop(folderSegments.size)

        fun trackId(url: HttpUrl): String = "dav-${hash("$key\n$url")}"
    }

    data class Catalog(
        val songs: List<ProviderSong>,
        val audioUrls: Map<String, String>
    ) {
        val songsByAlbum = songs.groupBy { it.albumId }
        val albums = songsByAlbum.values.map { tracks ->
            val first = tracks.first()
            ProviderAlbum(first.albumId.orEmpty(), first.album, first.artist, songCount = tracks.size)
        }
        val artists = songs.groupBy { it.artist }.map { (artist, tracks) ->
            ProviderArtist("dav-${hash(artist)}", artist, songCount = tracks.size,
                albumCount = tracks.map { it.albumId }.distinct().size)
        }
    }

    private data class Snapshot(val key: String, val catalog: Catalog, val completedAt: Long)
    private data class Entry(val url: HttpUrl, val directory: Boolean)

    private val scanLock = Mutex()
    @Volatile private var snapshot: Snapshot? = null

    fun url(connection: Connection, id: String, cachedUrl: String? = null): String? {
        val value = snapshot?.takeIf { it.key == connection.key }?.catalog?.audioUrls?.get(id) ?: cachedUrl
        val url = value?.toHttpUrlOrNull() ?: return null
        return url.toString().takeIf { connection.contains(url) && connection.trackId(url) == id }
    }

    suspend fun check(connection: Connection): Result<Boolean> = onIo {
        if (list(connection, connection.root, depth = 0).none { it.directory && it.url == connection.root }) {
            throw IOException(context.getString(R.string.shenye_webdav_folder_missing))
        }
        true
    }

    suspend fun catalog(
        connection: Connection,
        refresh: Boolean = false,
        onProgress: ((Int, Int, Int) -> Unit)? = null
    ): Result<Catalog> = onIo {
        scanLock.withLock {
            val now = System.nanoTime()
            snapshot?.takeIf {
                it.key == connection.key && (!refresh || now - it.completedAt < TimeUnit.SECONDS.toNanos(10))
            }?.let { return@withLock it.catalog }

            val pending = ArrayDeque<HttpUrl>()
            val visited = HashSet<HttpUrl>()
            val tracks = LinkedHashMap<String, ProviderSong>()
            val urls = HashMap<String, String>()
            pending.add(connection.root)

            while (pending.isNotEmpty()) {
                ensureActive()
                val folder = pending.removeFirst()
                if (!visited.add(folder)) continue
                val entries = list(connection, folder, depth = 1)
                for (entry in entries) {
                    val parentSegments = folder.pathSegments.dropLast(1)
                    val childSegments = entry.url.pathSegments.let {
                        if (entry.directory) it.dropLast(1) else it
                    }
                    if (childSegments.dropLast(1) != parentSegments) continue
                    if (entry.directory) {
                        pending.add(entry.url)
                    } else {
                        val song = parseTrack(connection, entry.url) ?: continue
                        tracks[song.providerId] = song
                        urls[song.providerId] = entry.url.toString()
                    }
                }
                onProgress?.invoke(visited.size, visited.size + pending.size, tracks.size)
            }
            val catalog = Catalog(tracks.values.sortedWith(compareBy<ProviderSong> { it.album }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title }), urls)
            snapshot = Snapshot(connection.key, catalog, System.nanoTime())
            catalog
        }
    }

    private fun parseTrack(connection: Connection, url: HttpUrl): ProviderSong? {
        val segments = connection.relativeSegments(url)
        val file = segments.lastOrNull() ?: return null
        val ext = file.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in AUDIO_TYPES) return null
        val title = file.substringBeforeLast('.')
        val album = segments.getOrNull(segments.lastIndex - 1)
            ?: connection.root.pathSegments.dropLast(1).lastOrNull()
            ?: context.getString(R.string.shenye_webdav_source)
        val artist = segments.getOrNull(segments.lastIndex - 2)
            ?: context.getString(R.string.shenye_webdav_unknown_artist)
        val folder = url.newBuilder().removePathSegment(url.pathSize - 1).addPathSegment("").build()
        return ProviderSong(
            providerId = connection.trackId(url), title = title, artist = artist, album = album,
            durationMs = 0, albumId = "dav-${hash("${connection.key}\n$folder")}",
            albumArtist = artist, trackNumber = TRACK_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull(),
            codec = ext
        )
    }

    private fun list(connection: Connection, url: HttpUrl, depth: Int): List<Entry> {
        val request = Request.Builder().url(url)
            .header("Depth", depth.toString())
            .header("Authorization", connection.authorization)
            .header("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", PROPERTIES.toRequestBody())
            .build()

        return http.newCall(request).execute().use { response ->
            when (response.code) {
                207 -> Unit
                401, 403 -> throw IOException(context.getString(R.string.shenye_webdav_access_denied))
                404 -> throw IOException(context.getString(R.string.shenye_webdav_folder_missing))
                429 -> throw IOException(context.getString(R.string.shenye_webdav_rate_limited))
                else -> throw IOException("WebDAV HTTP ${response.code}")
            }
            val entries = parseEntries(response.body.byteStream(), url, connection)
            if (depth == 1 && url.host == NUTSTORE_HOST && entries.size >= 750) {
                throw IOException(context.getString(R.string.shenye_webdav_directory_limit))
            }
            entries
        }
    }

    private fun parseEntries(input: InputStream, requested: HttpUrl, connection: Connection): List<Entry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(input, null)
        }
        while (parser.nextToken() != XmlPullParser.START_TAG) {
            if (parser.eventType == XmlPullParser.DOCDECL || parser.eventType == XmlPullParser.END_DOCUMENT) {
                throw IOException("Invalid WebDAV directory response")
            }
        }
        if (parser.namespace != "DAV:" || parser.name != "multistatus") {
            throw IOException("Invalid WebDAV directory response")
        }
        return buildList {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (!parser.isDavTag("response", 2)) continue
                var href: String? = null
                var collection: Boolean? = null
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 2) break
                    when {
                        parser.isDavTag("href", 3) -> href = parser.nextText().trim()
                        parser.isDavTag("propstat", 3) -> readResourceType(parser)?.let { collection = it }
                    }
                }
                val directory = collection ?: continue
                href ?: continue
                val url = requested.resolve(href)?.takeIf(connection::contains) ?: continue
                val canonical = if (directory && !url.encodedPath.endsWith('/')) {
                    url.newBuilder().addPathSegment("").build()
                } else url
                if (connection.contains(canonical)) add(Entry(canonical, directory))
            }
        }
    }

    private fun readResourceType(parser: XmlPullParser): Boolean? {
        var successful = false
        var collection: Boolean? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == 3) break
            when {
                parser.isDavTag("status", 4) -> successful =
                    parser.nextText().trim().split(' ').getOrNull(1) == "200"
                parser.isDavTag("resourcetype", 5) -> collection = false
                parser.isDavTag("collection", 6) && collection != null -> collection = true
            }
        }
        return collection.takeIf { successful }
    }

    private fun XmlPullParser.isDavTag(tag: String, level: Int): Boolean =
        eventType == XmlPullParser.START_TAG && depth == level && namespace == "DAV:" && name == tag

    private suspend fun <T> onIo(block: suspend kotlinx.coroutines.CoroutineScope.() -> T): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    companion object {
        const val NUTSTORE_URL = "https://dav.jianguoyun.com/dav/音乐/"
        private const val NUTSTORE_HOST = "dav.jianguoyun.com"
        private val AUDIO_TYPES = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "webm")
        private val TRACK_NUMBER = Regex("^\\s*(\\d{1,3})[. _-]")
        private const val PROPERTIES = """<?xml version="1.0" encoding="UTF-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""

        fun isNutstoreAddress(value: String): Boolean =
            value.trim().toHttpUrlOrNull()?.host == NUTSTORE_HOST

        fun musicFolder(url: String): HttpUrl? {
            val normalized = url.toHttpUrlOrNull() ?: return null
            val loopback = normalized.host in setOf("127.0.0.1", "localhost", "::1")
            if (!normalized.isHttps && !loopback) return null
            if (normalized.host == NUTSTORE_HOST &&
                (normalized.pathSegments.firstOrNull() != "dav" || normalized.pathSegments.drop(1).all { it.isBlank() })) return null
            return if (normalized.encodedPath.endsWith('/')) normalized else normalized.newBuilder().addPathSegment("").build()
        }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).toLowerHex()
    }
}
