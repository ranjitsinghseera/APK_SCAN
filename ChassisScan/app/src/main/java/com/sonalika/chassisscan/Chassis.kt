package com.sonalika.chassisscan

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class Field(val key: String, val value: String)

/** Parsing, picking and validation of chassis / VIN numbers. */
object Chassis {

    private val KEY_RX = Regex("(chas+is|chasis|ch\\.?\\s*no|frame\\s*no|vin|vehicle\\s*id)", RegexOption.IGNORE_CASE)
    private val VIN_RX = Regex("[A-HJ-NPR-Z0-9]{17}")

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

    /** Returns the chassis value picked from the QR, or null if unsure. */
    fun pickFromQr(fields: List<Field>, raw: String): String? {
        fields.firstOrNull { KEY_RX.containsMatchIn(it.key) }?.let { return norm(it.value) }
        fields.forEach { f -> VIN_RX.find(norm(f.value))?.let { return it.value } }
        if (fields.size == 1) return norm(fields[0].value)
        return VIN_RX.find(norm(raw))?.value
    }

    // ---------- OCR of stamped number ----------

    data class OcrPick(val value: String, val score: Int)

    /** Picks the most likely chassis number from recognised text lines. */
    fun pickFromOcr(lines: List<String>): OcrPick? {
        val cands = mutableListOf<OcrPick>()
        val cleaned = lines.map { norm(it) }.filter { it.isNotEmpty() }

        // 1. a line that is (or contains) a full VIN
        for (c in cleaned) {
            val fixed = vinFix(c)
            if (fixed.length == 17 && VIN_RX.matches(fixed)) cands.add(OcrPick(fixed, 100 + bonus(fixed)))
            else VIN_RX.findAll(fixed).forEach { cands.add(OcrPick(it.value, 70 + bonus(it.value))) }
        }
        // 2. the number split over two text lines
        if (cands.isEmpty() && cleaned.size > 1) {
            for (i in 0 until cleaned.size - 1) {
                val j = vinFix(cleaned[i] + cleaned[i + 1])
                if (j.length == 17 && VIN_RX.matches(j)) cands.add(OcrPick(j, 60 + bonus(j)))
            }
        }
        // 3. otherwise the longest alphanumeric run (non-VIN local formats)
        if (cands.isEmpty()) {
            cleaned.filter { it.length >= 6 }.maxByOrNull { it.length }?.let { cands.add(OcrPick(it, it.length)) }
        }
        return cands.maxByOrNull { it.score }
    }

    /** VINs never contain I, O or Q, so those reads are almost always 1 / 0. */
    private fun vinFix(s: String) = s.replace('O', '0').replace('Q', '0').replace('I', '1')

    private fun bonus(v: String) = if (checkDigitOk(v)) 20 else 0

    // ---------- validation ----------

    private val TR = mapOf(
        'A' to 1, 'B' to 2, 'C' to 3, 'D' to 4, 'E' to 5, 'F' to 6, 'G' to 7, 'H' to 8,
        'J' to 1, 'K' to 2, 'L' to 3, 'M' to 4, 'N' to 5, 'P' to 7, 'R' to 9,
        'S' to 2, 'T' to 3, 'U' to 4, 'V' to 5, 'W' to 6, 'X' to 7, 'Y' to 8, 'Z' to 9
    )
    private val WT = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    fun checkDigitOk(v: String): Boolean {
        if (v.length != 17 || !VIN_RX.matches(v)) return false
        var sum = 0
        for (i in 0 until 17) {
            val c = v[i]
            sum += (if (c.isDigit()) c - '0' else TR[c] ?: return false) * WT[i]
        }
        val cd = if (sum % 11 == 10) 'X' else ('0' + sum % 11)
        return v[8] == cd
    }

    /** Short checks shown under each value: (text, isProblem) pieces. */
    fun checks(v: String): List<Pair<String, Boolean?>> {
        if (v.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, Boolean?>>()
        out.add(if (v.length == 17) "17 characters" to false else "${v.length} characters (VIN is 17)" to true)
        if (v.any { it == 'I' || it == 'O' || it == 'Q' }) out.add("contains I, O or Q" to true)
        else if (v.length == 17) {
            out.add(if (checkDigitOk(v)) "check digit valid" to false
                    else "check digit not matched (needed only for North American VINs)" to null)
        }
        return out
    }

    fun diff(a: String, b: String): String {
        if (a.length != b.length) return "lengths differ (${a.length} vs ${b.length})."
        val pos = a.indices.filter { a[it] != b[it] }.joinToString(", ") { "${it + 1}: ${a[it]}/${b[it]}" }
        return "differs at position $pos."
    }
}
