package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            "Eres Livi, la locutora y DJ mexicana oficial de este reproductor musical. " +
            "Tienes un estilo fresco, auténtico, variado y espontáneo, como una locutora real en cabina de radio mexicana, con excelente gusto musical y buena onda. " +
            "Tu misión es presentar canciones con total soltura en unas 25 a 32 palabras. " +
            "REGLAS DE VARIEDAD Y ESTILO: " +
            "1. EXTENSIÓN: Alrededor de 25 a 32 palabras. Ni telegrama de 10 palabras ni discurso largo. " +
            "2. VARIEDAD TOTAL: Jamás uses siempre la misma estructura o muletilla. Rota tus aperturas (a veces una exclamación, a veces una pregunta, a veces directo al tema o destacando al artista o la vibra). " +
            "3. LENGUAJE NATURAL MEXICANO: Usa modismos y expresiones de México con naturalidad y buen gusto (rolón, joyita, temazo, rolita, qué chulada, échale oído, vámonos tendidos, pónganle oreja, qué buena rola, con todo el ritmo, para levantar la vibra, etc.), pero rótalos; jamás repitas las mismas palabras en cada salida. " +
            "4. CANCIÓN ANTERIOR: Si se indica que la canción previa fue escuchada, puedes conectar sutilmente con ella; si fue saltada, ve DIRECTO al tema que entra sin mirar atrás. " +
            "5. FORMATO LIMPIO: Escribe ÚNICAMENTE las palabras exactas que vas a decir al aire. Sin comillas, sin asteriscos, sin emojis, sin prefijos como 'Livi:'."

        private val CREATIVE_ANGLES = listOf(
            "Haz una entrada animada y con mucha energía mexicana.",
            "Comenta de forma relajada y cómplice con quien escucha.",
            "Dale un toque espontáneo como si improvisaras al aire en vivo en la radio.",
            "Conecta la vibra o el cierre del tema anterior con el que viene.",
            "Presenta la rola directo al grano con mucho estilo y actitud.",
            "Recomienda la canción como una joya imperdible para cantar o disfrutar.",
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
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun generateSpeech(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo? = null,
        wasPreviousSongListened: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val apiKey = apiKeyProvider()?.trim()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No API key configured, using offline template generator.")
            return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
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
            val userPrompt = buildUserPrompt(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
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
                put("temperature", 0.8)
                put("max_tokens", 512)
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

            val script = withTimeoutOrNull(2500L) {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.w(TAG, "API call failed (${response.code} ${response.message}): $errBody")
                    return@withTimeoutOrNull null
                }

                val responseBody = response.body?.string() ?: return@withTimeoutOrNull null
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
                            return@withTimeoutOrNull clean
                        }
                    }
                }
                null
            }

            if (!script.isNullOrBlank()) {
                Log.i(TAG, "Generated and sanitized script: $script")
                return@withContext script
            }

            Log.w(TAG, "LLM returned empty or timed out (>2.5s), falling back to rich local generator.")
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling LLM endpoint ($endpoint), falling back to local script", e)
        }

        generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
    }

    private fun buildUserPrompt(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?,
        wasPreviousSongListened: Boolean
    ): String {
        val nextSong = upcomingSongs.firstOrNull()
        val angle = CREATIVE_ANGLES.random()
        val sb = StringBuilder()
        sb.append("Estás al aire presentando música para radio en México. ")
        if (previousSong != null && wasPreviousSongListened) {
            sb.append("Sonó recién: '${previousSong.title}' de ${previousSong.artist}. Puedes hacer una conexión breve y fresca con ella. ")
        } else {
            sb.append("Presenta directamente el nuevo tema sin mencionar canciones anteriores (el oyente estuvo cambiando pistas). ")
        }
        if (nextSong != null) {
            sb.append("Siguiente tema: '${nextSong.title}' de ${nextSong.artist}. ")
        }
        sb.append("Bloque/onda: $blockTheme. ")
        sb.append("Enfoque creativo para esta salida: $angle ")
        sb.append("Extensión objetivo: alrededor de 25 a 32 palabras. Con naturalidad, modismos mexicanos y buena vibra.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?,
        wasPreviousSongListened: Boolean = true
    ): String {
        if (upcomingSongs.isEmpty()) return "Seguimos con buena música en tu reproductor. ¡A disfrutarla!"
        val firstSong = upcomingSongs.first()
        val title = firstSong.title
        val artist = firstSong.artist
        val prev = if (wasPreviousSongListened) previousSong?.title else null

        val templates = if (prev != null) {
            listOf(
                "Dejamos atrás a $prev y nos vamos tendidos con este temazo: $title de $artist. ¡Súbele que se pone bueno!",
                "Qué buen rolón sonó recién. Ahora cambiamos la vibra para escuchar a $artist con $title. ¡Échale oído!",
                "Manteniendo la buena energía tras $prev, aquí suena $title de $artist. Pura calidad para tus oídos.",
                "Seguimos con todo el ritmo. Dejamos sonar a $prev y le damos paso a $artist con esta rolita: $title.",
                "¡Qué chulada de momento musical! Cerramos con $prev y ahora se viene una joyita de $artist llamada $title."
            )
        } else {
            listOf(
                "¡Vámonos con todo! Échale oído a esta joyita que suena ahora mismo: $title de $artist. ¡Disfrútala!",
                "¿Cómo va esa vibra? Aquí seguimos en este bloque de $blockTheme y se viene $title con el talento de $artist.",
                "¡Qué chulada de música! Ahora le toca brillar a $artist con $title. Ponte cómodo y disfruta de este rolón.",
                "Para toda la banda que le gusta el buen ritmo, seguimos escuchando $title de $artist. ¡Que no pare la música!",
                "Subiendo el nivel de la sintonía, aquí te traigo una verdadera joya: $title a cargo de $artist. ¡A gozarla!",
                "Sin rodeos y directito al grano, nos arrancamos con $title por $artist. Una rola perfecta para el momento.",
                "Cambiamos un poco la onda. Vamos a ponerle sabor al día con $artist y este tema que te va a encantar: $title.",
                "Tiempo de disfrutar buena música. Sube el volumen que aquí suena fuerte $title de $artist en este bloque de $blockTheme."
            )
        }
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
