package com.catsoftfon.sip.data

import com.catsoftfon.sip.model.Contact
import com.catsoftfon.sip.model.ContactSource
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Fetches the PBX's shared directory over HTTP(S) and parses it into Contact objects -
 * mirrors Services/DirectoryImportService.cs from the Windows client. Supports the same two
 * simple formats: CSV ("name,number" per line, with or without a header row) and vCard
 * (one or more "BEGIN:VCARD ... END:VCARD" blocks, reading FN and the first TEL).
 *
 * Call from a background thread - uses plain blocking HttpURLConnection, no extra HTTP
 * library dependency needed for something this simple.
 */
object DirectoryImportService {

    private const val TIMEOUT_MS = 15000

    @Throws(Exception::class)
    fun fetchAndParse(url: String): List<Contact> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            if (this is HttpsURLConnection) {
                // No custom TLS handling - relies on the platform's default trust store,
                // same as any normal HTTPS client.
            }
        }
        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw Exception("Directory server returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
            return if (looksLikeVCard(body)) parseVCard(body) else parseCsv(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun looksLikeVCard(body: String): Boolean =
        body.trim().startsWith("BEGIN:VCARD", ignoreCase = true) || body.contains("BEGIN:VCARD", ignoreCase = true)

    private fun parseCsv(body: String): List<Contact> {
        val result = mutableListOf<Contact>()
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, rawLine) in lines.withIndex()) {
            val cols = rawLine.split(',', ';', '\t').map { it.trim().trim('"') }
            if (cols.size < 2) continue

            // Skip an obvious header row ("name,number" / "Name,Number" / ...).
            if (index == 0 && cols[0].lowercase() in setOf("name", "имя") &&
                cols[1].lowercase() in setOf("number", "номер", "extension", "ext")
            ) {
                continue
            }

            val name = cols[0]
            val number = cols[1].filter { it.isDigit() || it == '+' }
            if (number.isEmpty()) continue

            result.add(
                Contact(
                    name = name.ifBlank { number },
                    number = number,
                    source = ContactSource.DIRECTORY
                )
            )
        }
        return result
    }

    private fun parseVCard(body: String): List<Contact> {
        val result = mutableListOf<Contact>()
        val blocks = body.split(Regex("(?i)BEGIN:VCARD")).drop(1)
        for (block in blocks) {
            val endMatch = Regex("(?i)END:VCARD").find(block)
            val card = if (endMatch != null) block.substring(0, endMatch.range.first) else block
            var name = ""
            var number = ""
            for (lineRaw in card.lineSequence()) {
                val line = lineRaw.trim()
                when {
                    line.startsWith("FN:", ignoreCase = true) -> {
                        name = line.substringAfter(':').trim()
                    }
                    line.startsWith("TEL", ignoreCase = true) && number.isEmpty() -> {
                        val value = line.substringAfter(':', "").trim()
                        number = value.filter { it.isDigit() || it == '+' }
                    }
                }
            }
            if (number.isNotEmpty()) {
                result.add(
                    Contact(
                        name = name.ifBlank { number },
                        number = number,
                        source = ContactSource.DIRECTORY
                    )
                )
            }
        }
        return result
    }
}
