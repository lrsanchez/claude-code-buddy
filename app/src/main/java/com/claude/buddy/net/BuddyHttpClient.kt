package com.claude.buddy.net

import android.util.Log
import com.claude.buddy.protocol.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "BuddyHttpClient"
private const val TIMEOUT_MS = 6_000

class BuddyHttpClient(private val baseUrl: String, private val token: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun openJson(path: String): HttpURLConnection {
        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout    = TIMEOUT_MS
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("ngrok-skip-browser-warning", "1")
        return conn
    }

    suspend fun fetchStatus(): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val conn = openJson("/status")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                json.decodeFromString<Snapshot>(body)
            } else {
                Log.w(TAG, "status ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchStatus: ${e.message}")
            null
        }
    }

    suspend fun sendDecision(id: String, approve: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = openJson("/decision")
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"id":"$id","decision":"${if (approve) "once" else "deny"}"}"""
            conn.outputStream.write(body.toByteArray())
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.d(TAG, "sendDecision: ${e.message}")
            false
        }
    }

    suspend fun fetchAutoToken(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${baseUrl}/auto-token").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.setRequestProperty("ngrok-skip-browser-warning", "1")
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                json.parseToJsonElement(body).let { it as? JsonObject }
                    ?.get("token")?.jsonPrimitive?.content
            } else null
        } catch (e: Exception) {
            Log.d(TAG, "fetchAutoToken: ${e.message}")
            null
        }
    }
}
