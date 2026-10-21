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
            "Eres Livi, la DJ y locutora de radio mexicana oficial de este reproductor de música. " +
            "Hablas con un tono mexicano auténtico, fresco, animado y alivianado, usando modismos naturales de México ('rolón', 'buena rola', 'aquí te traigo', 'échense esta rola', 'súbele el volumen', 'chulada de rola', 'para ambientar'). " +
            "Tu misión es presentar el siguiente bloque musical con energía y buena vibra en unas 25 a 35 palabras. " +
            "REGLAS ESTRICTAS: " +
            "1. Escribe ÚNICAMENTE las palabras exactas que vas a decir al aire. Jamás expliques tu razonamiento ni repitas la consigna. " +
            "2. Habla 100% en español de México. " +
            "3. NUNCA uses comillas, asteriscos, guiones, barras, paréntesis ni emojis. " +
            "4. NUNCA incluyas prefijos como 'Livi:' ni acotaciones."

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
                put("temperature", 0.6)
                put("max_tokens", 1024)
            }

            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
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
            val choices = jsonResponse.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val msg = choice.optJSONObject("message")
                // Only take the actual assistant content, NEVER reasoning_content (which is internal thoughts)
                val raw = msg?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: choice.optString("text").takeIf { it.isNotBlank() }

                if (!raw.isNullOrBlank()) {
                    val clean = sanitizeScript(raw)
                    if (clean.isNotBlank()) {
                        Log.i(TAG, "Generated and sanitized script: $clean")
                        return@withContext clean
                    }
                }
            }
            Log.w(TAG, "LLM returned empty or whitespace script, falling back to local generator.")
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
        val first = upcomingSongs.firstOrNull()
        if (first != null) {
            sb.append("Ahora arranca el bloque $blockTheme con el rolón ${first.title} de ${first.artist}. ")
        } else {
            sb.append("Ahora arranca el bloque $blockTheme. ")
        }
        sb.append("Haz una presentación de radio mexicana con buen estilo y energía, de unas 25 a 35 palabras, sin comillas ni asteriscos.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?
    ): String {
        if (upcomingSongs.isEmpty()) return "Seguimos con más música aquí en tu reproductor, súbele al volumen y disfruta el momento."
        val firstSong = upcomingSongs.first()
        val templates = listOf(
            "¡Qué rolón acabamos de escuchar! Ahora nos arrancamos con este bloque de $blockTheme, y para abrir pista te traigo ${firstSong.title} de ${firstSong.artist}. ¡Súbele todo!",
            "Seguimos con todo el ritmo en $blockTheme. Échale oído a esta joyita que se viene: ${firstSong.title} de ${firstSong.artist}, una verdadera chulada para disfrutar.",
            "Para toda la banda que pedía buena música, continuamos en $blockTheme con ${firstSong.title} a cargo de ${firstSong.artist}. ¡Que no pare la fiesta!",
            "¡Esto se está poniendo buenísimo! Quédate conmigo en este especial de $blockTheme y disfruta de ${firstSong.title} por ${firstSong.artist}, te va a encantar."
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
