package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
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
        const val OPENCODE_MODELS_URL = "https://opencode.ai/zen/go/v1/models"
        const val DEFAULT_OPENCODE_MODEL = "deepseek-v4.1-flash"

        private const val DJ_SYSTEM_PROMPT =
            "Eres Livi, la DJ y curadora musical personal de este reproductor. " +
            "Tienes un estilo conversacional, relajado, maduro y cercano, como una amiga que sabe mucho de música (inspirado en el tono auténtico y sutil de Spotify AI DJ). " +
            "Tu misión es hacer una intervención muy breve de 15 a 22 palabras presentando el bloque o la canción con total naturalidad. " +
            "REGLAS ESTRICTAS DE ESTILO Y FONÉTICA: " +
            "1. BREVEDAD ABSOLUTA: Alrededor de 15 a 22 palabras (1 o 2 oraciones breves). No te extiendas. " +
            "2. TONO AUTÉNTICO Y RELAJADO: Habla como alguien real y tranquilo. CERO clichés de locutor comercial. PROHIBIDO usar palabras como 'rolón', 'temazo', 'joyita', 'qué chulada', 'vámonos tendidos', 'échale oído', 'con todo el power', 'en cabina', 'en sintonía', 'súbele al volumen', 'pónganle oreja' o 'qué buena rola'. " +
            "3. ENFOQUE EN LA VIBRA O EL ARTISTA: Menciona el estilo, la época, o por qué encaja este momento musical (ej: 'Cambiamos un poco la onda...', 'Seguimos con algo más tranquilo...', 'Un clásico de los 80...'). " +
            "4. CANCIÓN ANTERIOR: Si se indica que la canción previa fue escuchada, puedes conectar sutilmente con ella; si no se menciona o fue saltada, ve DIRECTO al tema que entra sin mirar atrás. " +
            "5. ADAPTACIÓN FONÉTICA INTEGRAL AL ESPAÑOL (MANDATORIO): El sintetizador de voz es un motor TTS en español puro. Si el título o el artista son en inglés u otro idioma extranjero, DEBES TRANSCRIBIR EL TÍTULO COMPLETO Y EL ARTISTA COMPLETO a fonética escrita en español, de forma integral y natural: " +
            "   - Regla de la 'W': Transcribe SIEMPRE con 'U' o 'Hu' (NUNCA uses 'Gü', que suena gutural y tosco). Ejemplos: 'World' -> 'Uerld' (o 'Uorld'), 'Weekend' -> 'Uikend', 'Will' -> 'Uil', 'One' -> 'Uan', 'War' -> 'Uor'. " +
            "   - Regla de la 'S' inicial en inglés: NO agregues una 'E' adelante; escribe 'S' o 'St' (ej: 'Stars' -> 'Stars', 'Silence' -> 'Sáilens', 'Space' -> 'Speis', NUNCA 'Estars' ni 'Espeis'). " +
            "   - Suaviza consonantes finales complejas: 'Ends' -> 'Ens', 'Night' -> 'Nait', 'Echo' -> 'Éco', 'Loved' -> 'Lovd'. " +
            "   - Ejemplos de títulos: 'Till the World Ends' -> 'Til de Uerld Ens'; 'Silence the Echo' -> 'Sáilens di Éko'; 'She Will Be Loved' -> 'Shi Uil Bi Lovd'; 'Smells Like Teen Spirit' -> 'Smels Laik Tin Spírit'. " +
            "   - Ejemplos de artistas: 'I See Stars' -> 'Ai Si Stars'; 'Born Of Osiris' -> 'Born of Osáiris'; 'The Weeknd' -> 'De Uikend'; 'Bring Me The Horizon' -> 'Bring Mi De Joraizon'; 'Maroon 5' -> 'Marún Faiv'. " +
            "   - Si el título o artista ya están en español, déjalos idénticos. " +
            "6. FORMATO LIMPIO: Escribe ÚNICAMENTE las palabras exactas que vas a decir al aire. Sin comillas, sin asteriscos, sin emojis, sin prefijos como 'Livi:'."

        private val CREATIVE_ANGLES = listOf(
            "Comenta el cambio de vibra o ritmo de forma relajada y cómplice.",
            "Presenta el tema destacando la época o el género con naturalidad.",
            "Haz una entrada directa al grano con actitud tranquila y buen gusto.",
            "Conecta sutilmente con el artista y lo que transmite la canción.",
            "Menciona que es un clásico o una canción ideal para este momento.",
            "Da una transición fluida y fresca sin rodeos."
        )

        suspend fun fetchModels(
            apiKey: String?,
            customEndpoint: String? = null
        ): Result<List<String>> = withContext(Dispatchers.IO) {
            if (apiKey.isNullOrBlank()) {
                return@withContext Result.failure(IllegalArgumentException("API Key no configurada"))
            }

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
                OPENCODE_MODELS_URL
            }

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()

                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()
                    .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                    .addHeader("x-opencode-session", "gramophone-dj-livi")

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
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun generateSpeech(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo? = null,
        wasPreviousSongListened: Boolean = true,
        timeoutMs: Long = 8000L
    ): String = withContext(Dispatchers.IO) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("opencode_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("deepseek_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No OpenCode API key configured, using offline template generator.")
            return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
        }

        val endpoint = endpointProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("dj_api_endpoint", null)?.trim()?.takeIf { it.isNotBlank() }
            ?: OPENCODE_BASE_URL

        val rawModel = modelProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("dj_api_model", null)?.trim()?.takeIf { it.isNotBlank() }
        val model = if (rawModel.isNullOrBlank() || rawModel == "custom") {
            val custom = prefs.getString("dj_api_model_custom", null)?.trim()
            if (!custom.isNullOrBlank()) custom else DEFAULT_OPENCODE_MODEL
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
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("x-opencode-session", "gramophone-dj-livi")
                .post(body)
                .build()

            Log.d(TAG, "Requesting script from $endpoint using model $model (timeout=${timeoutMs}ms)")

            val script = withTimeoutOrNull(timeoutMs) {
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.w(TAG, "OpenCode API call failed (${response.code} ${response.message}): $errBody")
                    return@withTimeoutOrNull null
                }

                val responseBody = response.body?.string() ?: return@withTimeoutOrNull null
                Log.d(TAG, "OpenCode response: $responseBody")
                val jsonResponse = JSONObject(responseBody)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val msg = choice.optJSONObject("message")
                    val raw = msg?.optString("content")?.takeIf { it.isNotBlank() }
                        ?: choice.optString("text").takeIf { it.isNotBlank() }
                        ?: msg?.optString("reasoning_content")?.takeIf { it.isNotBlank() }

                    if (!raw.isNullOrBlank()) {
                        val clean = sanitizeScript(raw)
                        val phonetic = DjPhonetics.applyPhonetics(context, clean)
                        if (phonetic.isNotBlank()) {
                            return@withTimeoutOrNull phonetic
                        }
                    } else {
                        Log.w(TAG, "OpenCode returned choice without content: $choice")
                    }
                } else {
                    Log.w(TAG, "OpenCode returned no choices in payload: $responseBody")
                }
                null
            }

            if (!script.isNullOrBlank()) {
                Log.i(TAG, "Generated and sanitized script from OpenCode: $script")
                return@withContext script
            }

            Log.w(TAG, "OpenCode LLM returned empty or timed out (>${timeoutMs}ms), falling back to rich local generator.")
        } catch (e: Exception) {
            Log.w(TAG, "Exception calling OpenCode endpoint ($endpoint), falling back to local script", e)
        }

        generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
    }

    fun detectLanguage(title: String, artist: String, genre: String? = null): String {
        val combined = "$title $artist ${genre ?: ""}".lowercase()
        // Japanese / Chinese / Korean scripts
        if (combined.any { it in '\u4e00'..'\u9fa5' || it in '\u3040'..'\u30ff' || it in '\uac00'..'\ud7af' }) {
            return "Asiático (Japonés/Coreano/Chino)"
        }
        // Obvious Spanish words or accented characters
        val spanishMarkers = listOf("á", "é", "í", "ó", "ú", "ñ", "amor", "corazon", "noche", "vida", "fuego", "cancion", "sol", "luna", "para", "por", "con", "sin", "los", "las", "del", "una", "mundo", "tiempo", "cielo", "ojos", "beso", "musica")
        if (spanishMarkers.any { combined.contains(it) }) {
            return "Español"
        }
        val frenchMarkers = listOf("le", "la", "les", "du", "des", "mon", "amour", "vie", "avec", "pour", "dans")
        if (frenchMarkers.any { Regex("\\b$it\\b").containsMatchIn(combined) }) {
            return "Francés"
        }
        val germanMarkers = listOf("der", "die", "das", "und", "nicht", "ich", "du", "mit")
        if (germanMarkers.any { Regex("\\b$it\\b").containsMatchIn(combined) }) {
            return "Alemán"
        }
        return "Inglés / Extranjero"
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
        sb.append("Estás presentando música de forma relajada y cómplice. ")
        if (previousSong != null && wasPreviousSongListened) {
            val prevLang = detectLanguage(previousSong.title, previousSong.artist, previousSong.genre)
            sb.append("El oyente acaba de terminar de escuchar: '${previousSong.title}' de ${previousSong.artist} (idioma/origen: $prevLang). Puedes hacer una conexión breve y sutil. ")
        } else {
            sb.append("Presenta directamente el nuevo tema sin mencionar canciones anteriores (el oyente estuvo cambiando pistas). ")
        }
        if (nextSong != null) {
            val nextLang = detectLanguage(nextSong.title, nextSong.artist, nextSong.genre)
            sb.append("Siguiente tema: '${nextSong.title}' de ${nextSong.artist}. ")
            sb.append("Idioma/origen detectado: $nextLang. ")
            if (nextLang != "Español") {
                sb.append("FONÉTICA COMPLETA AL ESPAÑOL: Dado que el tema está en $nextLang, transcribe TODO el título y TODO el artista a fonética española integral para que el TTS lo lea con naturalidad (ej: 'Til de Uerld Ens' de 'Ai Si Stars', 'Sáilens di Éko' de 'Born of Osáiris'). Recuerda: 'W' se escribe con 'U' (ej: Uerld, Uikend, Uan), jamás 'Gü'. ")
            }
        }
        sb.append("Bloque/onda: $blockTheme. ")
        sb.append("Enfoque: $angle ")
        sb.append("Extensión: MÁXIMO 15 a 22 palabras. Sencillo, natural y con buena vibra.")
        return sb.toString()
    }

    private fun generateOfflineFallbackScript(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?,
        wasPreviousSongListened: Boolean = true
    ): String {
        if (upcomingSongs.isEmpty()) return "Seguimos con buena música en tu biblioteca."
        val firstSong = upcomingSongs.first()
        val title = firstSong.title
        val artist = firstSong.artist
        val prev = if (wasPreviousSongListened) previousSong?.title else null

        val templates = if (prev != null) {
            listOf(
                "Después de escuchar a $prev, seguimos con $title de $artist.",
                "Buena vibra con lo que sonó recién. Ahora pasamos a $title con $artist.",
                "Manteniendo el ritmo tras $prev, aquí suena $title de $artist.",
                "Seguimos sumando calidad a la playlist con $artist y su tema $title."
            )
        } else {
            listOf(
                "Cambiamos un poco la onda. Vamos a escuchar a $artist con $title.",
                "Seguimos con buena música para acompañarte. Esto es $title de $artist.",
                "Para este momento, una gran canción de $artist: $title.",
                "Nos quedamos con este ritmo. Aquí suena $title de $artist.",
                "Tiempo de disfrutar un buen tema. Se viene $artist con $title.",
                "Un toque de $blockTheme para el momento: $title de $artist."
            )
        }
        val selected = templates.random()
        return DjPhonetics.applyPhonetics(context, sanitizeScript(selected))
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
