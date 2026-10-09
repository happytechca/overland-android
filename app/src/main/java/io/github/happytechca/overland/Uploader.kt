package io.github.happytechca.overland

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends queued points in batches. A batch is only removed from the queue when the server answers
 * {"result":"ok"} (the Overland contract); anything else leaves it for the next attempt.
 */
object Uploader {

    /**
     * Uploads until the queue is empty or a batch fails. With an empty queue, sends an empty batch
     * so the URL and token still get checked. Returns a short human-readable result.
     */
    @Synchronized
    fun uploadAll(context: Context): String {
        val settings = Settings(context)
        val queue = PointQueue.get(context)

        val batchSize = settings.batchSize.coerceAtLeast(1)
        var sent = 0
        var first = true
        while (true) {
            val batch = queue.oldest(batchSize)
            if (batch.isEmpty() && !first) break
            first = false

            val error = post(settings, OverlandPayload.batch(batch.map { it.second }))
            if (error != null) {
                val result = if (sent > 0) "Sent $sent points, then failed: $error" else "Failed: $error"
                record(settings, result, ok = false)
                return result
            }
            queue.delete(batch.map { it.first })
            sent += batch.size
            if (batch.size < batchSize) break
        }

        val result = if (sent > 0) "OK, sent $sent points" else "OK (connection test, nothing to send)"
        record(settings, result, ok = true)
        return result
    }

    /** POSTs one batch; returns null on success or an error message. */
    private fun post(settings: Settings, body: String): String? {
        if (settings.url.isBlank()) return "no server URL set"
        val conn = try {
            URL(settings.url.trim()).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return "bad URL (${e.message})"
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (settings.token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()

            when {
                json?.optString("result") == "ok" -> null
                json?.has("error") == true -> "HTTP $code: ${json.optString("error")}"
                else -> "HTTP $code: ${text.take(120).ifBlank { "empty response" }}"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            conn.disconnect()
        }
    }

    private fun record(settings: Settings, result: String, ok: Boolean) {
        settings.lastUploadAt = System.currentTimeMillis()
        settings.lastUploadResult = result
        settings.lastUploadOk = ok
    }
}

/**
 * Exponential backoff for automatic uploads: after a failure, waits [baseMs], then twice as long after each
 * further failure, up to [maxMs]. A success resets it.
 */
class Backoff(private val baseMs: Long = 60_000, private val maxMs: Long = 15 * 60_000) {
    private var failures = 0
    var retryAtMs = 0L
        private set

    fun ready(nowMs: Long) = nowMs >= retryAtMs

    fun onFailure(nowMs: Long) {
        failures++
        retryAtMs = nowMs + minOf(baseMs shl minOf(failures - 1, 20), maxMs)
    }

    fun reset() {
        failures = 0
        retryAtMs = 0
    }
}
