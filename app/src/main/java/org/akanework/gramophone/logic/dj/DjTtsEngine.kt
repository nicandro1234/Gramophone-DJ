package org.akanework.gramophone.logic.dj

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class DjTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "DjTtsEngine"
        private const val MODEL_FOLDER = "kokoro"
        private const val MODEL_NAME = "model.onnx"
        private const val INT8_MODEL_NAME = "model.int8.onnx"
        private const val VOICES_NAME = "voices.bin"
        private const val TOKENS_NAME = "tokens.txt"
        private const val ESPEAK_DIR = "espeak-ng-data"
        private const val LEXICON_NAME = "lexicon.txt"
    }

    private var offlineTts: OfflineTts? = null
    private val initMutex = Mutex()
    private var isInitialized = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isInitialized && offlineTts != null) return@withContext true

            try {
                val modelDir = File(context.filesDir, MODEL_FOLDER)
                val modelFile = if (File(modelDir, INT8_MODEL_NAME).exists()) {
                    File(modelDir, INT8_MODEL_NAME)
                } else if (File(modelDir, MODEL_NAME).exists()) {
                    File(modelDir, MODEL_NAME)
                } else {
                    null
                }

                if (modelFile != null && modelFile.exists()) {
                    val voicesFile = File(modelDir, VOICES_NAME)
                    val tokensFile = File(modelDir, TOKENS_NAME)
                    val dataDir = File(modelDir, ESPEAK_DIR)
                    val lexiconFile = File(modelDir, LEXICON_NAME)

                    val kokoroConfig = OfflineTtsKokoroModelConfig(
                        model = modelFile.absolutePath,
                        voices = voicesFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = dataDir.absolutePath,
                        lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
                        lang = "es",
                        dictDir = "",
                        lengthScale = 1.0f
                    )

                    val modelConfig = OfflineTtsModelConfig().apply {
                        kokoro = kokoroConfig
                        numThreads = 2
                        debug = false
                        provider = "cpu"
                    }

                    val ttsConfig = OfflineTtsConfig().apply {
                        model = modelConfig
                        silenceScale = 0.2f
                    }

                    offlineTts = OfflineTts(assetManager = context.assets, config = ttsConfig)
                    isInitialized = true
                    Log.i(TAG, "Sherpa-ONNX Kokoro TTS initialized successfully from file storage.")
                    return@withContext true
                } else {
                    // Try to see if assets/kokoro exists
                    try {
                        val assetList = context.assets.list("kokoro") ?: emptyArray()
                        if (assetList.contains(INT8_MODEL_NAME) || assetList.contains(MODEL_NAME)) {
                            val activeModel = if (assetList.contains(INT8_MODEL_NAME)) "kokoro/$INT8_MODEL_NAME" else "kokoro/$MODEL_NAME"
                            val kokoroConfig = OfflineTtsKokoroModelConfig(
                                model = activeModel,
                                voices = "kokoro/$VOICES_NAME",
                                tokens = "kokoro/$TOKENS_NAME",
                                dataDir = "kokoro/$ESPEAK_DIR",
                                lexicon = if (assetList.contains(LEXICON_NAME)) "kokoro/$LEXICON_NAME" else "",
                                lang = "es",
                                dictDir = "",
                                lengthScale = 1.0f
                            )
                            val modelConfig = OfflineTtsModelConfig().apply {
                                kokoro = kokoroConfig
                                numThreads = 2
                                debug = false
                                provider = "cpu"
                            }
                            val ttsConfig = OfflineTtsConfig().apply {
                                model = modelConfig
                                silenceScale = 0.2f
                            }
                            offlineTts = OfflineTts(assetManager = context.assets, config = ttsConfig)
                            isInitialized = true
                            Log.i(TAG, "Sherpa-ONNX Kokoro TTS initialized successfully from Android Assets.")
                            return@withContext true
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Assets kokoro check note: ${e.message}")
                    }

                    Log.w(TAG, "Kokoro model weights not found in assets/kokoro or ${modelDir.absolutePath}")
                    return@withContext false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Sherpa-ONNX TTS", e)
                return@withContext false
            }
        }
    }

    suspend fun synthesizeToWavFile(text: String, speakerId: Int = 0, speed: Float = 1.0f): File? = withContext(Dispatchers.IO) {
        if (!initialize()) {
            Log.w(TAG, "TTS not initialized, cannot synthesize text.")
            return@withContext null
        }

        try {
            val tts = offlineTts ?: return@withContext null
            val audio: GeneratedAudio = tts.generate(text, speakerId, speed)
            if (audio.samples.isEmpty()) {
                Log.w(TAG, "TTS generated 0 audio samples.")
                return@withContext null
            }

            val outputFile = File(context.cacheDir, "dj_livi_${System.currentTimeMillis()}.wav")
            val saved = audio.save(outputFile.absolutePath)
            if (saved && outputFile.exists()) {
                Log.i(TAG, "Audio speech synthesized to: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                return@withContext outputFile
            } else {
                Log.e(TAG, "Failed to save generated audio to ${outputFile.absolutePath}")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error synthesizing audio with Kokoro", e)
            return@withContext null
        }
    }

    fun release() {
        offlineTts?.release()
        offlineTts = null
        isInitialized = false
    }
}
