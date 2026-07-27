package com.example.synergic_pos_offline.utils

/** Small, dependency-free CSV reader shared by the bulk-upload screens. */
object CsvUtils {

    /** Header-keyed rows (keys lower-cased, trimmed); blank lines dropped. */
    fun parse(text: String): List<Map<String, String>> {
        val rows = tokenize(text).filter { line -> line.any { it.isNotBlank() } }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        return rows.drop(1).map { r ->
            header.mapIndexed { i, key -> key to (r.getOrNull(i)?.trim().orEmpty()) }.toMap()
        }
    }

    /** Minimal RFC-4180 tokenizer: quotes, escaped "" and CRLF aware. */
    private fun tokenize(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> {}
                c == '\n' -> { row.add(field.toString()); rows.add(row); row = mutableListOf(); field.setLength(0) }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
