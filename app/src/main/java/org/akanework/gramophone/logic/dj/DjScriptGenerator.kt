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
        const val DEFAULT_OPENCODE_MODEL = "deepseek-v4.1-flash"

        const val DIRECT_DEEPSEEK_URL = "https://api.deepseek.com/chat/completions"
        const val DEFAULT_DEEPSEEK_MODEL = "deepseek-chat"

        private const val DJ_SYSTEM_PROMPT =
            "Eres Livi, la DJ y curadora musical personal de este reproductor. " +
            "Tienes un estilo conversacional, relajado, maduro y cercano, como una amiga que sabe mucho de música (inspirado en el tono auténtico y sutil de Spotify AI DJ). " +
            "Tu misión es hacer una intervención muy breve de 15 a 22 palabras presentando el bloque o la canción con total naturalidad. " +
            "REGLAS ESTRICTAS DE ESTILO: " +
            "1. BREVEDAD ABSOLUTA: Alrededor de 15 a 22 palabras (1 o 2 oraciones breves). No te extiendas. " +
            "2. TONO AUTÉNTICO Y RELAJADO: Habla como alguien real y tranquilo. CERO clichés de locutor gritón o comercial. PROHIBIDO usar palabras como 'rolón', 'temazo', 'joyita', 'qué chulada', 'vámonos tendidos', 'échale oído', 'con todo el power', 'en cabina', 'en sintonía', 'súbele al volumen', 'pónganle oreja' o 'qué buena rola'. " +
            "3. ENFOQUE EN LA VIBRA O EL ARTISTA: Menciona el estilo, la época, o por qué encaja este momento musical (ej: 'Cambiamos un poco la onda...', 'Seguimos con algo más tranquilo...', 'Un clásico de los 80...'). " +
            "4. CANCIÓN ANTERIOR: Si se indica que la canción previa fue escuchada, puedes conectar sutilmente con ella; si no se menciona o fue saltada, ve DIRECTO al tema que entra sin mirar atrás. " +
            "5. PRONUNCIACIÓN FONÉTICA DE ARTISTAS Y TÍTULOS EN OTROS IDIOMAS: El sintetizador de voz lee en español puro. Cuando menciones artistas o TÍTULOS DE CANCIONES en inglés o con nombres extranjeros, escribe SIEMPRE tanto el artista como el título fonéticamente como sonarían en español para que la voz no los lea con acento roto o literal (por ejemplo: para 'Smells Like Teen Spirit' de Nirvana di 'Smels Laik Tin Spírit' de Nirvána; para 'Bohemian Rhapsody' de Queen di 'Bojímian Rápsodi' de Cuín; para 'In The End' de Linkin Park di 'In Di End' de Línkin Park; para 'Blinding Lights' de The Weeknd di 'Bláinding Láits' de De Güíkend; para 'Drown' de Bring Me The Horizon di 'Draun' de Bring Mi De Joraizon; etc.). Si el nombre o título ya está en español o se lee tal cual, déjalo normal. " +
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
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val apiKey = apiKeyProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("deepseek_api_key", null)?.trim()
        if (apiKey.isNullOrBlank()) {
            Log.d(TAG, "No API key configured, using offline template generator.")
            return@withContext generateOfflineFallbackScript(upcomingSongs, blockTheme, previousSong, wasPreviousSongListened)
        }

        val isOpenCode = apiKey.startsWith("oc_sk_") || apiKey.contains("opencode")
        val endpoint = endpointProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("dj_api_endpoint", null)?.trim()?.takeIf { it.isNotBlank() }
            ?: if (isOpenCode) OPENCODE_BASE_URL else DIRECT_DEEPSEEK_URL

        val rawModel = modelProvider()?.trim()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("dj_api_model", null)?.trim()?.takeIf { it.isNotBlank() }
        val model = if (rawModel.isNullOrBlank() || rawModel == "custom") {
            val custom = prefs.getString("dj_api_model_custom", null)?.trim()
            if (!custom.isNullOrBlank()) custom else if (isOpenCode) DEFAULT_OPENCODE_MODEL else DEFAULT_DEEPSEEK_MODEL
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
                        val phonetic = DjPhonetics.applyPhonetics(context, clean)
                        if (phonetic.isNotBlank()) {
                            return@withTimeoutOrNull phonetic
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
        sb.append("Estás presentando música de forma relajada y cómplice. ")
        if (previousSong != null && wasPreviousSongListened) {
            sb.append("El oyente acaba de terminar de escuchar: '${previousSong.title}' de ${previousSong.artist}. Puedes hacer una conexión breve y sutil. ")
        } else {
            sb.append("Presenta directamente el nuevo tema sin mencionar canciones anteriores (el oyente estuvo cambiando pistas). ")
        }
        if (nextSong != null) {
            sb.append("Siguiente tema: '${nextSong.title}' de ${nextSong.artist}. ")
            sb.append("FONÉTICA: Si el título '${nextSong.title}' o el artista '${nextSong.artist}' están en inglés u otro idioma extranjero, adáptalos fonéticamente a cómo se leen en español para que el lector TTS los pronuncie con naturalidad. ")
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
