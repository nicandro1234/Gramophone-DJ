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
            "Eres Livi, la locutora de radio y DJ mexicana oficial de este reproductor de música. " +
            "Tienes un estilo fresco, auténtico, variado y espontáneo, como una locutora real en cabina de radio mexicana. " +
            "Tu misión es presentar canciones con total libertad creativa, energía y buena vibra en unas 25 a 35 palabras. " +
            "REGLAS DE VARIEDAD Y ESTILO: " +
            "1. LIBERTAD Y VARIEDAD: Jamás uses siempre las mismas muletillas ni la misma estructura. Varía tu apertura (a veces con una exclamación, a veces con una pregunta, a veces directo al tema o conectando con el artista). " +
            "2. LENGUAJE NATURAL MEXICANO: Usa modismos y expresiones naturales de México con soltura (joyita, rolón, temazo, qué chulada, rolita, échale oído, vámonos tendidos, pónganle oreja, qué buena rola, con todo el power, para agarrar ritmo, etc.), pero rótalos; jamás repitas las mismas palabras en cada salida. " +
            "3. FORMATO: Escribe ÚNICAMENTE las palabras exactas que vas a decir al aire. Jamás expliques tu razonamiento ni agregues notas. " +
            "4. Habla 100% en español de México. " +
            "5. NUNCA uses comillas, asteriscos, guiones, barras, paréntesis ni emojis. " +
            "6. NUNCA incluyas prefijos como 'Livi:' ni acotaciones."

        private val CREATIVE_ANGLES = listOf(
            "Haz una entrada animada y con mucha energía.",
            "Comenta de forma relajada y cómplice con quien escucha.",
            "Dale un toque espontáneo como si improvisaras al aire en vivo.",
            "Conecta la vibra o el cierre del tema anterior con el que viene.",
            "Presenta la rola directo al grano con mucho estilo y actitud.",
            "Recomienda la canción como una joya imperdible para cantar o bailar.",
            "Ponle sazón fiestero y motivante para levantar el ánimo.",
            "Haz un comentario fresco sobre el flow, el ritmo o la voz del tema."
        )

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
                put("temperature", 0.85)
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
        val nextSong = upcomingSongs.firstOrNull()
        val angle = CREATIVE_ANGLES.random()
        val sb = StringBuilder()
        sb.append("Estás al aire en cabina. ")
        if (previousSong != null) {
            sb.append("Sonó recién: '${previousSong.title}' de ${previousSong.artist}. ")
        }
        if (nextSong != null) {
            sb.append("Siguiente tema: '${nextSong.title}' de ${nextSong.artist}. ")
        }
        sb.append("Bloque/estilo: $blockTheme. ")
        sb.append("Consigna creativa para esta salida: $angle ")
        sb.append("Extensión objetivo: alrededor de 25 a 35 palabras. ")
        sb.append("IMPORTANTE: PROHIBIDO iniciar con 'Aquí arranca' o 'Ahora arranca'. Inventa una entrada diferente, fresca, variada y con personalidad propia.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?
    ): String {
        if (upcomingSongs.isEmpty()) return "Seguimos con buena música en tu reproductor. Disfruta el momento y súbele al volumen."
        val firstSong = upcomingSongs.first()
        val title = firstSong.title
        val artist = firstSong.artist
        val prev = previousSong?.title

        val templates = listOf(
            if (prev != null) "Dejamos atrás a $prev y nos vamos tendidos con este temazo: $title de $artist. ¡Súbele que se pone bueno!"
            else "¡Vámonos con todo! Échale oído a esta joyita que suena ahora mismo: $title de $artist. ¡Disfrútala!",

            "¿Cómo va esa vibra? Aquí seguimos con lo mejor de $blockTheme y se viene $title con el talento de $artist. ¡Pónganle oreja!",

            "¡Qué chulada de música! Ahora le toca brillar a $artist con $title. Ponte cómodo y disfruta de este rolón.",

            "Para toda la banda que le gusta el buen ritmo, seguimos en $blockTheme escuchando $title de $artist. ¡Que no pare la música!",

            "Subiendo el nivel de la sintonía, aquí te traigo una verdadera joya: $title a cargo de $artist. ¡A gozarla!",

            "Si necesitabas energía para tu día, estás en el lugar correcto. Suena fuerte $title de $artist en este bloque de $blockTheme.",

            "¡Esto se pone cada vez mejor! Quédate en sintonía porque lo que viene es pura calidad: $title de $artist.",

            "Sin rodeos y directito al corazón, nos arrancamos con $title por $artist. ¡Súbele al volumen y canta con todo!",

            "¡Uf, qué manera de disfrutar la música! Nos acompaña $artist con su rolón $title dentro de $blockTheme. ¡A todo volumen!",

            "Seguimos con la mejor selección del día. Prepárate para cantar con $title de $artist, una rola perfecta para el momento."
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
