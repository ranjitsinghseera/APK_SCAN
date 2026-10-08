package com.sonalika.chassisscan

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Field(val key: String, val value: String)

/** QR parsing and stamped-number candidate ranking. No format rules: values are kept as read. */
object Chassis {

    private val KEY_RX = Regex("(chas+is|chasis|ch\\.?\\s*no|frame\\s*no|vin|vehicle\\s*id|serial)", RegexOption.IGNORE_CASE)

    /** Used only to compare two values (ignores case, spaces and punctuation). */
    fun norm(s: String?): String = (s ?: "").uppercase().replace(Regex("[^A-Z0-9]"), "")

    // ---------- QR payload ----------

    fun parsePayload(raw: String): List<Field> {
        val s = raw.trim()
        val out = mutableListOf<Field>()
        try {
            if (s.startsWith("{")) { flatten(JSONObject(s), "", out); return out }
            if (s.startsWith("[")) { flatten(JSONArray(s), "", out); return out }
        } catch (_: Exception) { out.clear() }
        try {
            if (s.startsWith("http://", true) || s.startsWith("https://", true)) {
                val u = Uri.parse(s)
                u.queryParameterNames.forEach { k -> out.add(Field(k, u.getQueryParameter(k) ?: "")) }
                if (out.isNotEmpty()) return out
            }
        } catch (_: Exception) { out.clear() }
        val pair = Regex("^\\s*([^:=]{1,40}?)\\s*[:=]\\s*(.+?)\\s*$")
        s.split(Regex("[\\n\\r;|&]+|,(?=\\s*[A-Za-z .]+\\s*[:=])")).forEach { p ->
            val m = pair.find(p)
            if (m != null) out.add(Field(m.groupValues[1], m.groupValues[2]))
            else if (p.isNotBlank()) out.add(Field("", p.trim()))
        }
        return out
    }

    private fun flatten(o: Any?, pre: String, out: MutableList<Field>) {
        when (o) {
            is JSONObject -> o.keys().forEach { k -> flatten(o.opt(k), if (pre.isEmpty()) k else "$pre.$k", out) }
            is JSONArray -> for (i in 0 until o.length()) flatten(o.opt(i), "$pre[$i]", out)
            null, JSONObject.NULL -> {}
            else -> out.add(Field(pre, o.toString()))
        }
    }

    /**
     * Picks the value as written in the QR: a field named like chassis / frame no / VIN,
     * or the whole content when the QR holds a single value. Null = let the user tap.
     */
    fun pickFromQr(fields: List<Field>, raw: String): String? {
        fields.firstOrNull { KEY_RX.containsMatchIn(it.key) }?.let { return it.value.trim() }
        if (fields.size == 1) return fields[0].value.trim()
        if (fields.isEmpty()) return raw.trim()
        return null
    }

    // ---------- stamped number ----------

    /** A recognised line as read: uppercase, spaces removed, only letters, digits, - and /. */
    fun cleanLine(s: String): String =
        s.uppercase().replace(Regex("[^A-Z0-9/-]"), "").trim('-', '/')

    /**
     * Ranks lines read across several frames / rotations / image versions.
     * Each attempt is the list of lines from one recognition pass.
     * Rank = how many passes read the exact same text, then length.
     */
    fun rank(attempts: List<List<String>>): List<Pair<String, Int>> {
        val votes = HashMap<String, Int>()
        for (a in attempts) {
            a.map { cleanLine(it) }.filter { it.length >= 3 && it.any(Char::isDigit) }
                .toSet().forEach { votes[it] = (votes[it] ?: 0) + 1 }
        }
        return votes.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .map { it.key to it.value }
    }

    fun diff(a: String, b: String): String {
        if (a.length != b.length) return "lengths differ (${a.length} vs ${b.length})."
        val pos = a.indices.filter { a[it] != b[it] }.joinToString(", ") { "${it + 1}: ${a[it]}/${b[it]}" }
        return "differs at position $pos."
    }
}
