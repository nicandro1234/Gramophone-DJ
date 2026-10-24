package org.akanework.gramophone.logic.dj

import android.content.Context
import android.provider.MediaStore
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

object DjPhoneticsSync {

    private const val TAG = "DjPhoneticsSync"
    private const val BATCH_SIZE = 35

    /**
     * Extracts all unique artists from the user's music library on the device.
     */
    fun extractLibraryArtists(context: Context): List<String> {
        val artists = mutableSetOf<String>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Audio.Media.ARTIST)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"

        try {
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                if (artistCol >= 0) {
                    while (cursor.moveToNext()) {
                        val raw = cursor.getString(artistCol)?.trim() ?: continue
                        if (raw.isNotBlank() && raw != "<unknown>") {
                            // Add whole artist name
                            artists.add(raw)
                            // Also split collaborations into individual artists
                            val subArtists = raw.split(Regex("[,/&]|\\s+(?:feat\\.?|ft\\.?|vs\\.?)\\s+", RegexOption.IGNORE_CASE))
                            for (sub in subArtists) {
                                val s = sub.trim()
                                if (s.length > 1 && !s.all { it.isDigit() || !it.isLetter() }) {
                                    artists.add(s)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying MediaStore for artists", e)
        }

        return artists.toList().sorted()
    }

    /**
     * Finds which artists in the device library are not yet covered by built-in
     * or custom user phonetic pronunciations.
     */
    fun findMissingArtists(context: Context): List<String> {
        val libraryArtists = extractLibraryArtists(context)
        val knownArtists = DjPhonetics.getAllKnownArtists(context)

        return libraryArtists.filter { artist ->
            val lower = artist.trim().lowercase()
            !knownArtists.contains(lower)
        }
    }

    /**
     * Connects to OpenCode / DeepSeek LLM to generate Spanish phonetic respellings
     * for missing foreign artists and stores them into the user's preferences.
     *
     * @return Result containing number of new pronunciations added.
     */
    suspend fun syncWithLibrary(
        context: Context,
        apiKey: String,
        customEndpoint: String? = null,
        customModel: String? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API Key requerida"))
        }

        val missing = findMissingArtists(context)
        if (missing.isEmpty()) {
            Log.i(TAG, "All library artists already exist in phonetics dictionary.")
            return@withContext Result.success(0)
        }

        Log.i(TAG, "Found ${missing.size} missing artists to process with LLM.")

        val isOpenCode = apiKey.startsWith("oc_sk_") || apiKey.contains("opencode")
        val endpoint = customEndpoint?.takeIf { it.isNotBlank() }
            ?: if (isOpenCode) DjScriptGenerator.OPENCODE_BASE_URL else DjScriptGenerator.DIRECT_DEEPSEEK_URL

        val model = customModel?.takeIf { it.isNotBlank() && it != "custom" }
            ?: if (isOpenCode) DjScriptGenerator.DEFAULT_OPENCODE_MODEL else DjScriptGenerator.DEFAULT_DEEPSEEK_MODEL

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build()

        val batches = missing.chunked(BATCH_SIZE)
        val allNewRules = mutableMapOf<String, String>()
        var processedCount = 0

        for ((index, batch) in batches.withIndex()) {
            onProgress?.invoke(processedCount, missing.size)
            Log.d(TAG, "Processing batch ${index + 1}/${batches.size} (${batch.size} artists)")

            val systemPrompt =
                "Eres un asistente especializado en fonética de música para sintetizadores de voz (TTS) en español. " +
                "Tu tarea es convertir nombres de artistas extranjeros o en inglés a su pronunciación fonética escrita en español, de modo que un lector TTS en español lo lea con sonido natural. " +
                "REGLAS ESTRICTAS: " +
                "1. Devuelve ÚNICAMENTE un objeto JSON donde cada clave es el nombre del artista original y el valor es la pronunciación en español. " +
                "2. Si un artista es en español (ej: 'Caifanes', 'Los Bunkers', 'Jorge Drexler', 'Natalia Lafourcade') o se lee exactamente igual en español, NO lo incluyas en el JSON. " +
                "3. No agregues comillas markdown (```json), ni explicaciones, solo el JSON puro. " +
                "Ejemplo de formato: {\"The 1975\": \"De Naintin Sevengti Faiv\", \"Bring Me The Horizon\": \"Bring Mi De Joraizon\", \"Cage The Elephant\": \"Keidsh Di Élefant\"}"

            val userPrompt = "Convierte a fonética española los artistas extranjeros de esta lista que lo requieran:\n" +
                    batch.joinToString("\n") { "- $it" }

            try {
                val jsonPayload = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userPrompt)
                        })
                    })
                    put("temperature", 0.3)
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

                val response = client.newCall(requestBuilder.post(body).build()).execute()
                if (!response.isSuccessful) {
                    val err = response.body?.string() ?: ""
                    Log.w(TAG, "Batch ${index + 1} failed (${response.code}): $err")
                    processedCount += batch.size
                    continue
                }

                val respStr = response.body?.string() ?: ""
                val respJson = JSONObject(respStr)
                val choices = respJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val msg = choice.optJSONObject("message")
                    val rawContent = msg?.optString("content")?.trim() ?: ""

                    // Extract JSON block if wrapped in markdown
                    val cleanJsonStr = rawContent
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    try {
                        val parsed = JSONObject(cleanJsonStr)
                        val keys = parsed.keys()
                        var batchAdded = 0
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val v = parsed.optString(k).trim()
                            if (k.isNotBlank() && v.isNotBlank() && !k.equals(v, ignoreCase = true)) {
                                allNewRules[k.trim()] = v
                                batchAdded++
                            }
                        }
                        Log.i(TAG, "Batch ${index + 1} added $batchAdded phonetic mappings.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse JSON from LLM: $cleanJsonStr", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Exception during batch ${index + 1}", e)
            }

            processedCount += batch.size
        }

        onProgress?.invoke(missing.size, missing.size)

        if (allNewRules.isNotEmpty()) {
            DjPhonetics.saveUserOverrides(context, allNewRules)
            Log.i(TAG, "Saved ${allNewRules.size} new phonetic rules to SharedPreferences.")
        }

        Result.success(allNewRules.size)
    }
}
