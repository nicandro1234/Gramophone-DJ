package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DjScriptGenerator(
    private val context: Context,
    private val apiKeyProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "DjScriptGenerator"
        private const val DEFAULT_DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        private const val DJ_SYSTEM_PROMPT =
            "Sos Livi, la DJ de radio oficial de este reproductor de música. " +
            "Tenés una personalidad cálida, fresca, apasionada por la música, con acento rioplatense natural (usando voseo: 'te traigo', 'escuchá', 'vamos con'). " +
            "Tu misión es presentar el siguiente bloque musical en 1 a 2 oraciones muy cortas (máximo 25 a 35 palabras en total). " +
            "No uses emojis, hashtags ni acotaciones entre paréntesis como (música de fondo). Solo el texto exacto que vas a hablar al micrófono."
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun generateSpeech(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo? = null
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No DeepSeek API key configured, using offline template generator.")
            return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
        }

        try {
            val userPrompt = buildUserPrompt(upcomingSongs, blockTheme, previousSong)
            val jsonPayload = JSONObject().apply {
                put("model", "deepseek-chat")
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", DJ_SYSTEM_PROMPT)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
                put("temperature", 0.7)
                put("max_tokens", 80)
            }

            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(DEFAULT_DEEPSEEK_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "DeepSeek call failed: ${response.code} ${response.message}")
                return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
            }

            val responseBody = response.body?.string() ?: return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val script = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                Log.i(TAG, "DeepSeek generated script: $script")
                return@withContext sanitizeScript(script)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling DeepSeek API, falling back to local script", e)
        }

        generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
    }

    private fun buildUserPrompt(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?
    ): String {
        val sb = StringBuilder()
        if (previousSong != null) {
            sb.append("Acabamos de escuchar \"${previousSong.title}\" de ${previousSong.artist}. ")
        }
        sb.append("El próximo bloque es: $blockTheme. ")
        sb.append("Las canciones que vienen son: ")
        upcomingSongs.take(3).forEachIndexed { index, song ->
            sb.append("${song.title} de ${song.artist}")
            if (song.year != null) sb.append(" (${song.year})")
            if (index < upcomingSongs.take(3).lastIndex) sb.append(", ")
        }
        sb.append(". Hacé una intro radial corta y con onda.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?
    ): String {
        if (upcomingSongs.isEmpty()) return "¡Seguimos con más música acá en tu reproductor!"
        val firstSong = upcomingSongs.first()
        val templates = listOf(
            "¡Qué temazo! Ahora seguimos con un bloque de $blockTheme. Arrancamos con ${firstSong.title} de ${firstSong.artist}, ¡subí el volumen!",
            "Seguimos viaje con $blockTheme. Escuchá lo que se viene ahora: ${firstSong.title} por ${firstSong.artist}.",
            "Para los que pedían más ritmo, arrancamos este bloque de $blockTheme con ${firstSong.title} de ${firstSong.artist}. ¡Que no pare la música!",
            "Esto suena tremendo. Te dejo con ${firstSong.title} de ${firstSong.artist} en este especial de $blockTheme."
        )
        return templates.random()
    }

    private fun sanitizeScript(rawScript: String): String {
        return rawScript
            .replace(Regex("[\"“”]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("\\[.*?\\]"), "")
            .trim()
    }
}
