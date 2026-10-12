package com.sonalika.chassisscan

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends a photo of the stamped number to the n8n webhook, which asks the Claude API to read it.
 * The Claude API key lives only in n8n; the app sends a shared app key so strangers can't use the webhook.
 * URL and app key come from GitHub secrets at build time (see app/build.gradle.kts).
 */
object Cloud {

    val enabled: Boolean get() = BuildConfig.N8N_URL.isNotBlank()

    data class Result(val chassis: String?, val others: List<String>, val confidence: String, val note: String)

    /** Blocking call: run it off the main thread. Throws on any network or server error. */
    fun read(srcs: List<Bitmap>, orientation: String): Result {
        val encoded = srcs.map { Base64.encodeToString(toJpeg(it), Base64.NO_WRAP) }
        val body = JSONObject()
            .put("image", encoded.first())          // older n8n workflow reads only this
            .put("images", JSONArray(encoded))      // updated workflow reads all photos
            .put("media_type", "image/jpeg")
            .put("orientation", orientation)
            .toString().toByteArray(Charsets.UTF_8)

        val conn = URL(BuildConfig.N8N_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-App-Key", BuildConfig.N8N_KEY)
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Server answered $code")

            val j = JSONObject(text)
            if (j.has("error") && !j.isNull("error")) throw IllegalStateException(j.optString("error"))
            val others = mutableListOf<String>()
            j.optJSONArray("others")?.let { a -> for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let(others::add) }
            val chassis = if (j.isNull("chassis")) null else j.optString("chassis").trim().ifEmpty { null }
            return Result(chassis, others, j.optString("confidence", "unknown"), j.optString("note", ""))
        } finally {
            conn.disconnect()
        }
    }

    /** Long side at most 1600 px: enough detail for stamped characters, small enough to upload fast. */
    private fun toJpeg(src: Bitmap): ByteArray {
        val long = maxOf(src.width, src.height)
        val bmp = if (long > 1568) {
            val s = 1568f / long
            Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        } else src
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
    }
}
