package com.example.synergic_pos_offline.utils

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal HTTP client for the admin/auth backend. Centralises the base URL so it
 * only has to change in one place when the server address changes.
 */
object ApiClient {

    /** Base URL of the admin/auth server. Update here when the server address changes. */
    const val BASE_URL = "http://192.168.1.57:3008"

    const val PATH_REGISTER = "/admin/reg_user"
    const val PATH_CHECK_USER = "/admin/check_user"

    data class ApiResult(
        val ok: Boolean,
        val status: Int,
        val body: String,
        val error: String?
    )

    /** Performs a blocking JSON POST. Must be called from a background thread. */
    fun postJson(path: String, payload: JSONObject): ApiResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            ApiResult(status in 200..299, status, body, null)
        } catch (e: Exception) {
            ApiResult(false, -1, "", e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }
}
