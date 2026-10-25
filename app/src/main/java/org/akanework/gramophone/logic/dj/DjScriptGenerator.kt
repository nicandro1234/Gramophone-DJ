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
            "Tienes un estilo conversacional, espontáneo, relajado y cercano, como una amiga melómana que conoce muy bien los gustos del oyente (inspirado en el estilo auténtico y variado de Spotify AI DJ). " +
            "Tu misión es acompañar al oyente con intervenciones breves, frescas y variadas. No sigas una fórmula fija ni te repitas. " +
            "LIBERTAD CREATIVA Y VARIEDAD: " +
            "Tienes total libertad para elegir cómo abordar cada intervención según la música, el momento del día y la vibra del set: " +
            "- Puedes comentar la vibra, la energía o el cambio de ritmo del set que entra. " +
            "- Puedes tirar un detalle fresco o curioso del artista o la canción. " +
            "- Puedes conectar con lo que acaba de sonar si se escuchó completo. " +
            "- Puedes hablar del mix como un viaje o simplemente tirar una frase con onda y dar paso directo a la música. " +
            "- Sorprende con giros de lenguaje naturales, sin plantillas rígidas ni monotonía. " +
            "REGLAS TÉCNICAS MANDATORIAS: " +
            "1. BREVEDAD: 1 o 2 oraciones breves (alrededor de 15 a 30 palabras). Es una locución rápida sobre el ducking, no un podcast. " +
            "2. CERO CLICHÉS COMERCIALES: Habla como alguien real. Jamás uses frases de radio barata como 'rolón', 'temazo', 'joyita', 'qué chulada', 'échale oído', 'con todo el power', 'en sintonía', 'súbele al volumen', 'pónganle oreja'. " +
            "3. ADAPTACIÓN FONÉTICA INTEGRAL AL ESPAÑOL (MANDATORIO): El sintetizador de voz es un motor TTS en español puro. Si el título o el artista son en inglés u otro idioma extranjero, DEBES TRANSCRIBIR EL TÍTULO COMPLETO Y EL ARTISTA COMPLETO al 100% a fonética escrita en español (pronunciación figurada): " +
            "   - NO transcribas solo palabras aisladas; TODA la frase del título y el nombre del artista deben quedar en fonética española. " +
            "   - Regla de la 'W': Transcribe SIEMPRE con 'U' o 'Hu' (NUNCA uses 'Gü', que suena tosco). Ejemplos: 'World' -> 'Uerld', 'Weekend' -> 'Uikend', 'Will' -> 'Uil', 'One' -> 'Uan', 'War' -> 'Uor'. " +
            "   - Regla de la 'S' inicial en inglés: NO agregues una 'E' adelante (ej: 'Stars' -> 'Stars', 'Silence' -> 'Sáilens', 'Space' -> 'Speis', jamás 'Estars'). " +
            "   - Suaviza consonantes finales complejas: 'Ends' -> 'Ens', 'Night' -> 'Nait', 'Echo' -> 'Éco', 'Loved' -> 'Lovd'. " +
            "   - Ejemplos holísticos: 'Till the World Ends' de 'Britney Spears' -> 'Til de Uerld Ens' de 'Brítni Spirs'; 'I See Stars' -> 'Ai Si Stars'; 'Born Of Osiris' -> 'Born of Osáiris'; 'She Will Be Loved' de 'Maroon 5' -> 'Shi Uil Bi Lovd' de 'Marún Faiv'. " +
            "   - Si el título o artista ya están en español, déjalos idénticos. " +
            "4. FORMATO LIMPIO: Escribe ÚNICAMENTE las palabras exactas que vas a decir al aire. Sin reflexiones, sin análisis de palabras, sin notas de traducción, sin comillas, sin prefijos."

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
                put("temperature", 0.85)
                put("max_tokens", 1024)
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
                    // Strictly retrieve final generated assistant content. NEVER fall back to reasoning_content (thoughts)!
                    val raw = msg?.optString("content")?.takeIf { it.isNotBlank() }
                        ?: choice.optString("text").takeIf { it.isNotBlank() }

                    if (!raw.isNullOrBlank()) {
                        val clean = sanitizeScript(raw)
                        val phonetic = DjPhonetics.applyPhonetics(context, clean)
                        if (phonetic.isNotBlank()) {
                            return@withTimeoutOrNull phonetic
                        }
                    } else {
                        Log.w(TAG, "OpenCode returned choice without content (finish_reason=${choice.optString("finish_reason")}): $choice")
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
        // Strong Spanish indicators
        val spanishMarkers = listOf(
            "á", "é", "í", "ó", "ú", "ñ", "amor", "corazon", "noche", "vida", "fuego", "cancion",
            "sol", "luna", "para", "por", "con", "sin", "los", "las", "del", "una", "mundo",
            "tiempo", "cielo", "ojos", "beso", "musica", "quiero", "volver", "siempre", "nunca",
            "adios", "hola", "hombre", "mujer", "alma", "suave", "baila", "ritmo", "corazón", "canción"
        )
        if (spanishMarkers.any { Regex("\\b$it\\b").containsMatchIn(combined) || combined.contains("ñ") || combined.contains("á") || combined.contains("í") || combined.contains("ó") || combined.contains("ú") }) {
            return "Español"
        }
        val frenchMarkers = listOf("le", "la", "les", "du", "des", "mon", "amour", "vie", "avec", "pour", "dans", "toujours", "ciel")
        if (frenchMarkers.any { Regex("\\b$it\\b").containsMatchIn(combined) }) {
            return "Francés"
        }
        val germanMarkers = listOf("der", "die", "das", "und", "nicht", "ich", "du", "mit", "nacht", "feuer")
        if (germanMarkers.any { Regex("\\b$it\\b").containsMatchIn(combined) }) {
            return "Alemán"
        }
        return "Inglés (no nativo en español)"
    }

    private fun buildUserPrompt(
        upcomingSongs: List<DjSongInfo>,
        blockTheme: String,
        previousSong: DjSongInfo?,
        wasPreviousSongListened: Boolean
    ): String {
        val nextSong = upcomingSongs.firstOrNull()
        val otherUpcoming = upcomingSongs.drop(1).take(3)
        val sb = StringBuilder()

        // Time of day detection
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 6..11 -> "Mañana (inicio de jornada, vibra fresca y luminosa)"
            in 12..18 -> "Tarde (tarde activa, ritmo continuo)"
            in 19..23 -> "Noche (ambiente nocturno, distensión y disfrute)"
            else -> "Madrugada (calma o trasnoche musical)"
        }

        // Active Mood from user preference
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val djMood = prefs.getString("dj_mood", "classic") ?: "classic"

        sb.append("Contexto para Livi:\n")
        sb.append("- Momento del día actual: $timeOfDay.\n")

        if (previousSong != null && wasPreviousSongListened) {
            val prevLang = detectLanguage(previousSong.title, previousSong.artist, previousSong.genre)
            sb.append("- Canción previa escuchada: '${previousSong.title}' de ${previousSong.artist} ($prevLang).\n")
        } else {
            sb.append("- El oyente estuvo cambiando canciones (no menciones el tema previo).\n")
        }

        sb.append("- Set musical / Bloque: '$blockTheme'.\n")

        if (nextSong != null) {
            val nextLang = detectLanguage(nextSong.title, nextSong.artist, nextSong.genre)
            sb.append("- Canción con la que abre: '${nextSong.title}' de ${nextSong.artist}.\n")
            sb.append("- IDIOMA ORIGINAL DETECTADO DE PRIMERA MANO: $nextLang.\n")
            if (nextLang != "Español") {
                sb.append("  REGLA DE FONÉTICA COMPLETA OBLIGATORIA:\n")
                sb.append("  El tema y el artista están en $nextLang. DEBES TRANSCRIBIR TODO EL TÍTULO Y TODO EL NOMBRE DEL ARTISTA AL 100% EN FONÉTICA ESPAÑOLA (pronunciación figurada).\n")
                sb.append("  NO transcribas solo palabras aisladas ni dejes partes en grafía extranjera: CADA palabra del título ('${nextSong.title}') y del artista ('${nextSong.artist}') debe escribirse tal cual suena fonéticamente en español para que el sintetizador TTS en español puro la pronuncie perfecta.\n")
                sb.append("  * Regla de la 'W': Transcribe SIEMPRE con 'U' (ej: 'World' -> 'Uerld', 'Weekend' -> 'Uikend', 'Will' -> 'Uil', 'One' -> 'Uan', 'War' -> 'Uor'), JAMÁS con 'Gü'.\n")
                sb.append("  * Regla de la 'S' inicial en inglés: NO antepongas una 'E' (ej: 'Stars' -> 'Stars', 'Space' -> 'Speis', 'Silence' -> 'Sáilens', jamás 'Estars').\n")
                sb.append("  * Suaviza consonantes finales: 'Ends' -> 'Ens', 'Night' -> 'Nait', 'Echo' -> 'Éco', 'Loved' -> 'Lovd'.\n")
                sb.append("  * Acentúa con tilde la sílaba tónica inglesa si ayuda a la entonación (ej: 'Brítni Spirs', 'Ai Si Stars', 'Born of Osáiris', 'Marún Faiv').\n")
            } else {
                sb.append("  (Idioma español: mantén el título y artista exactamente iguales sin alterar).\n")
            }
        }

        if (otherUpcoming.isNotEmpty()) {
            val otherArtists = otherUpcoming.map { it.artist }.distinct().filter { it != nextSong?.artist }
            if (otherArtists.isNotEmpty()) {
                sb.append("- Artistas que siguen en este set: ${otherArtists.joinToString(", ")}.\n")
            }
        }

        // Specific Mood Directives (only injected if user selected a custom mood)
        val moodDirective = when (djMood) {
            "car" -> "PARÁMETRO PRIMORDIAL - MODO DJ COCHE / CARRETERA: El usuario va al volante / manejando en la ruta. Transmite actitud rutera, despierta, dinámica y entretenida, ideal para acompañar el camino con buen ritmo."
            "party" -> "PARÁMETRO PRIMORDIAL - MODO DJ FIESTA: El usuario está de fiesta. Mantén una vibra alta, energética, alegre y motivadora para bailar y festejar."
            "chill" -> "PARÁMETRO PRIMORDIAL - MODO DJ REUNIÓN CHILL: El ambiente es una reunión relajada con amigos. Tono suave, tranquilo, cálido, música de fondo que acompaña la charla sin estridencias."
            "metal" -> "PARÁMETRO PRIMORDIAL - MODO DJ METALERO: Actitud metalera y rockera auténtica. Pura potencia, guitarras pesadas, energía cruda y visceral."
            "electro" -> "PARÁMETRO PRIMORDIAL - MODO DJ ELECTRO: Vibra electrónica de pista y club nocturno. Ritmos sintéticos, beats envolventes y actitud dance."
            "morning" -> "PARÁMETRO PRIMORDIAL - MODO DJ MAÑANA: Energía luminosa, fresca y motivadora para arrancar el día con el mejor ánimo."
            "night" -> "PARÁMETRO PRIMORDIAL - MODO DJ NOCHE: Atmósfera nocturna, envolvente, noctámbula y profunda."
            else -> null
        }
        if (moodDirective != null) {
            sb.append("\n$moodDirective\n")
        }

        sb.append("\nMisión: Preséntale la música al oyente con total libertad y espontaneidad. Puedes enfocarte en la vibra general, en los artistas, en un detalle curioso o en una transición ágil según el momento del día y el ambiente. No uses fórmulas fijas; sé auténtica, variada y relajada (15-30 palabras). Escribe únicamente lo que vas a decir al aire:")
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
        val otherArtists = upcomingSongs.drop(1).take(2).map { it.artist }.distinct().filter { it != artist }
        val upcomingHint = if (otherArtists.isNotEmpty()) ", y más adelante suena ${otherArtists.first()}" else ""

        val templates = if (prev != null) {
            listOf(
                "Dejamos atrás a $prev y nos metemos en $blockTheme. Arrancamos con $title de $artist$upcomingHint.",
                "Buena vibra con lo que sonó recién. Se viene $blockTheme: abrimos con $title de $artist.",
                "Cambiamos de ritmo para meternos en $blockTheme. Suena $title de $artist$upcomingHint.",
                "Seguimos sumando calidad. Momento de $blockTheme con $artist y su tema $title."
            )
        } else {
            listOf(
                "Arrancamos un nuevo set: $blockTheme. Esto es $title de $artist$upcomingHint.",
                "Nos metemos en $blockTheme para acompañarte. Suena $title de $artist.",
                "Para este momento, $blockTheme: abrimos cancha con $title de $artist.",
                "Tiempo de disfrutar este mix de $blockTheme. Se viene $artist con $title.",
                "Seguimos con buen ritmo en $blockTheme: suena $title de $artist."
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
