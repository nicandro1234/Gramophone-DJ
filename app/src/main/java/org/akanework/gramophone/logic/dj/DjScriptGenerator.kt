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
import java.util.concurrent.TimeUnit

class DjScriptGenerator(
    private val context: Context,
    private val apiKeyProvider: () -> String? = { null },
    private val endpointProvider: () -> String? = { null },
    private val modelProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "DjScriptGenerator"
        const val OPENCODE_BASE_URL = "https://opencode.ai/zen/go/v1/chat/completions"
        const val DEFAULT_OPENCODE_MODEL = "deepseek-v4.1-flash"

        const val DIRECT_DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

        private const val DJ_SYSTEM_PROMPT =
            "Sos Livi, la DJ y locutora de radio oficial de este reproductor de música. " +
            "Hablás con acento rioplatense natural, cálido y fresco, usando voseo ('te traigo', 'escuchá', 'vamos con', 'arrancamos con'). " +
            "Tu misión es presentar el siguiente bloque musical en 1 o 2 oraciones muy cortas (máximo 20 a 25 palabras en total). " +
            "REGLAS OBLIGATORIAS PARA SÍNTESIS DE VOZ: " +
            "1. El texto se envía directamente a un sintetizador de voz (TTS). Cualquier símbolo será pronunciado en voz alta. " +
            "2. NUNCA uses comillas, asteriscos, guiones, barras, paréntesis ni corchetes. " +
            "3. NUNCA uses emojis, emoticones ni hashtags. " +
            "4. NUNCA agregues acotaciones como (música de fondo), ni 'Livi:'. " +
            "5. Escribí únicamente texto corrido en castellano limpio con letras, puntos y comas."

        suspend fun fetchModels(
            apiKey: String?,
            customEndpoint: String? = null
        ): Result<List<String>> = withContext(Dispatchers.IO) {
            if (apiKey.isNullOrBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key no configurada"))
            }

            val isOpenCode = apiKey.startsWith("oc_sk_") || apiKey.contains("opencode")
            val url = if (!customEndpoint.isNullOrBlank()) {
                val trimmed = customEndpoint.trim()
                if (trimmed.endsWith("/chat/completions")) {
                    trimmed.replace("/chat/completions", "/models")
                } else if (trimmed.endsWith("/")) {
                    "${trimmed}models"
                } else {
                    "$trimmed/models"
                }
            } else {
                if (isOpenCode) "https://opencode.ai/zen/go/v1/models" else "https://api.deepseek.com/models"
            }

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer $apiKey")

                if (isOpenCode) {
                    requestBuilder.addHeader("x-opencode-session", "gramophone-dj-livi")
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    return@withContext Result.failure(
                        IllegalStateException("Error ${response.code}: $err")
                    )
                }

                val bodyStr = response.body?.string() ?: return@withContext Result.failure(
                    IllegalStateException("Respuesta vacía del servidor")
                )
                val json = JSONObject(bodyStr)
                val dataArray = json.optJSONArray("data") ?: json.optJSONArray("models")
                val modelList = mutableListOf<String>()

                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val itemObj = dataArray.optJSONObject(i)
                        val id = itemObj?.optString("id")?.takeIf { it.isNotBlank() }
                            ?: dataArray.optString(i).takeIf { it.isNotBlank() }
                        if (id != null && !modelList.contains(id)) {
                            modelList.add(id)
                        }
                    }
                }

                if (modelList.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("No se encontraron modelos en la respuesta"))
                }

                Result.success(modelList)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
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
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No API key configured, using offline template generator.")
            return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
        }

        val isOpenCode = apiKey.startsWith("oc_sk_") || apiKey.contains("opencode")
        val endpoint = endpointProvider()?.takeIf { it.isNotBlank() }
            ?: if (isOpenCode) OPENCODE_BASE_URL else DIRECT_DEEPSEEK_URL

        val rawModel = modelProvider()?.takeIf { it.isNotBlank() }
        val model = if (rawModel.isNullOrBlank() || rawModel == "custom") {
            if (isOpenCode) DEFAULT_OPENCODE_MODEL else DEFAULT_DEEPSEEK_MODEL
        } else {
            rawModel
        }

        try {
            val userPrompt = buildUserPrompt(upcomingSongs, blockTheme, previousSong)
            val jsonPayload = JSONObject().apply {
                put("model", model)
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
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")

            if (isOpenCode) {
                requestBuilder.addHeader("x-opencode-session", "gramophone-dj-livi")
            }

            val request = requestBuilder.post(body).build()
            Log.d(TAG, "Requesting script from $endpoint using model $model (isOpenCode=$isOpenCode)")

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                Log.w(TAG, "API call failed (${response.code} ${response.message}): $errBody")
                return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
            }

            val responseBody = response.body?.string() ?: return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong)
            val jsonResponse = JSONObject(responseBody)
            val choices = jsonResponse.getJSONArray("choices")
            if (choices.length() > 0) {
                val script = choices.getJSONObject(0).getJSONObject("message").getString("content").trim()
                Log.i(TAG, "Generated script: $script")
                return@withContext sanitizeScript(script)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling LLM endpoint ($endpoint), falling back to local script", e)
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
            sb.append("Acabamos de escuchar ${previousSong.title} de ${previousSong.artist}. ")
        }
        sb.append("El próximo bloque es: $blockTheme. ")
        sb.append("Las canciones que vienen son: ")
        upcomingSongs.take(3).forEachIndexed { index, song ->
            sb.append("${song.title} de ${song.artist}")
            if (index < upcomingSongs.take(3).lastIndex) sb.append(", ")
        }
        sb.append(". Hacé una intro radial hablada, cortísima y sin comillas, asteriscos, paréntesis ni emojis.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?
    ): String {
        if (upcomingSongs.isEmpty()) return "Seguimos con más música acá en tu reproductor."
        val firstSong = upcomingSongs.first()
        val templates = listOf(
            "Qué temazo! Ahora seguimos con un bloque de $blockTheme. Arrancamos con ${firstSong.title} de ${firstSong.artist}, subí el volumen!",
            "Seguimos viaje con $blockTheme. Escuchá lo que se viene ahora: ${firstSong.title} por ${firstSong.artist}.",
            "Para los que pedían más ritmo, arrancamos este bloque de $blockTheme con ${firstSong.title} de ${firstSong.artist}. Que no pare la música!",
            "Esto suena tremendo. Te dejo con ${firstSong.title} de ${firstSong.artist} en este especial de $blockTheme."
        )
        return sanitizeScript(templates.random())
    }

    fun sanitizeScript(rawScript: String): String {
        var text = rawScript

        // Remove prefixes like "Livi:", "DJ:", "Locutor:"
        text = text.replace(Regex("^(Livi|DJ|Locutora?|Host):\\s*", RegexOption.IGNORE_CASE), "")

        // Remove anything inside parentheses, brackets, or braces
        text = text.replace(Regex("\\(.*?\\)"), " ")
        text = text.replace(Regex("\\[.*?\\]"), " ")
        text = text.replace(Regex("\\{.*?\\}"), " ")

        // Remove markdown formatting: **, *, __, _, ~~, #, `, ´
        text = text.replace(Regex("[*_~#`´]"), "")

        // Remove quotation marks of all kinds: ", ', “, ”, « », etc.
        text = text.replace(Regex("[\"“”'«»‘’]"), "")

        // Remove technical symbols: /, \, |, +, =, <, >, @, %, &, ^, ~, $
        text = text.replace(Regex("[/\\\\|+=<>@%&^~\\$]"), " ")

        // Replace hyphens and dashes with comma (so TTS does not pronounce "guión")
        text = text.replace(Regex("\\s*[-–—]+\\s*"), ", ")

        // Remove emojis and miscellaneous symbols (Unicode Symbol, Modifier, Other, etc.)
        text = text.replace(Regex("[\\p{So}\\p{Sk}\\p{Sm}\\p{Sc}\\p{Cn}]"), "")

        // Clean up ellipses (...) to a single period
        text = text.replace(Regex("\\.{2,}"), ".")

        // Clean up repeated punctuation (e.g. "!!", "??", ",,")
        text = text.replace(Regex("!+"), "!")
        text = text.replace(Regex("\\?+"), "?")
        text = text.replace(Regex(",+"), ",")

        // Remove inverted Spanish exclamation/question marks so TTS doesn't stumble
        text = text.replace("¡", "").replace("¿", "")

        // Collapse multiple spaces
        text = text.replace(Regex("\\s+"), " ").trim()

        return text
    }
}
