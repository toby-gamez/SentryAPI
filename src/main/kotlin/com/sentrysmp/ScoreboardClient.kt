package com.sentrysmp

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ScoreboardClient(
    private val baseUrl: String = "https://www.sentrysmp.eu/api",
    private val ttlSeconds: Long = 60
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private data class CacheEntry(val response: ScoreboardResponse, val expiresAt: Instant)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private fun cached(path: String): ScoreboardResponse? {
        val key = path
        val e = cache[key] ?: return null
        return if (Instant.now().isBefore(e.expiresAt)) e.response else run {
            cache.remove(key)
            null
        }
    }

    private fun putCache(path: String, resp: ScoreboardResponse) {
        val expires = Instant.now().plusSeconds(ttlSeconds)
        cache[path] = CacheEntry(resp, expires)
    }

    private fun get(path: String): ScoreboardResponse? {
        cached(path)?.let { return it }

        val url = if (path.startsWith("/")) "$baseUrl$path" else "$baseUrl/$path"
        var attempt = 0
        val maxAttempts = 3
        var backoff = 200L
        while (attempt < maxAttempts) {
            attempt++
            try {
                val req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build()

                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    val body = resp.body()
                    // Try wrapper first, then raw array
                    val parsed: ScoreboardResponse? = try {
                        json.decodeFromString(ScoreboardResponse.serializer(), body)
                    } catch (_: Exception) {
                        try {
                            val list = json.decodeFromString(ListSerializer(ScoreEntry.serializer()), body)
                            ScoreboardResponse(entries = list)
                        } catch (ex: Exception) {
                            println("ScoreboardClient: parse error for $url -> $ex")
                            null
                        }
                    }
                    parsed?.also { putCache(path, it) }
                    return parsed
                } else {
                    println("ScoreboardClient: non-200 ${resp.statusCode()} for $url")
                }
            } catch (e: Exception) {
                println("ScoreboardClient: attempt $attempt error fetching $url -> $e")
            }
            try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
            backoff *= 2
        }
        return null
    }

    fun getAll(): ScoreboardResponse? = get("/scoreboard/all")
    fun getToday(): ScoreboardResponse? = get("/scoreboard/today")
    fun getWeek(): ScoreboardResponse? = get("/scoreboard/week")
    fun getMonth(): ScoreboardResponse? = get("/scoreboard/month")
}

