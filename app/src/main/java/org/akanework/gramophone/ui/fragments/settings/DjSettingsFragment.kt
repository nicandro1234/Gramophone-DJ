package org.akanework.gramophone.ui.fragments.settings

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.dj.DjEngine
import org.akanework.gramophone.logic.dj.DjPhoneticsSync
import org.akanework.gramophone.logic.dj.DjScriptGenerator
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity
import org.json.JSONArray
import org.akanework.gramophone.logic.allowDiskAccessInStrictMode

class DjSettingsActivity : BaseSettingsActivity(
    R.string.settings_dj_title,
    { DjSettingsFragment() })

class DjSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_dj, rootKey)

        allowDiskAccessInStrictMode {
            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            // Automatic migration from deepseek_api_key to opencode_api_key
            if (!prefs.contains("opencode_api_key") && prefs.contains("deepseek_api_key")) {
                prefs.edit().putString("opencode_api_key", prefs.getString("deepseek_api_key", null)).apply()
            }
            val apiKeyPref = findPreference<EditTextPreference>("opencode_api_key")
                ?: findPreference<EditTextPreference>("deepseek_api_key")
            val modelPref = findPreference<ListPreference>("dj_api_model")
            val customModelPref = findPreference<EditTextPreference>("dj_api_model_custom")
            val fetchModelsPref = findPreference<Preference>("dj_fetch_models")

            loadCachedModels(modelPref, prefs)

            customModelPref?.isVisible = (modelPref?.value == "custom")

            modelPref?.setOnPreferenceChangeListener { _, newValue ->
                customModelPref?.isVisible = (newValue == "custom")
                true
            }

            apiKeyPref?.setOnPreferenceChangeListener { _, newValue ->
                val newKey = (newValue as? String)?.trim()
                if (!newKey.isNullOrBlank()) {
                    fetchLiveModels(modelPref, prefs, overrideKey = newKey, silent = false)
                }
                true
            }

            fetchModelsPref?.setOnPreferenceClickListener {
                fetchLiveModels(modelPref, prefs, silent = false)
                true
            }

            // Auto-fetch fresh models from OpenCode API on opening settings if key is present
            val currentApiKey = (prefs.getString("opencode_api_key", null)
                ?: prefs.getString("deepseek_api_key", null))?.trim()
            if (!currentApiKey.isNullOrBlank()) {
                fetchLiveModels(modelPref, prefs, silent = true)
            }

        val voicePref = findPreference<ListPreference>("dj_tts_voice")
        loadAvailableVoices(voicePref, prefs)

        val syncPronunciationsPref = findPreference<Preference>("dj_sync_pronunciations")
        syncPronunciationsPref?.setOnPreferenceClickListener {
            syncPronunciationsWithLibrary(prefs, syncPronunciationsPref)
            true
        }

        findPreference<Preference>("dj_test_speech")?.setOnPreferenceClickListener {
            testDjVoice()
            true
        }

        findPreference<SwitchPreferenceCompat>("dj_mode_enabled")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                GramophonePlaybackService.instanceForWidgetAndLyricsOnly?.djEngine?.setDjModeEnabled(enabled)
                true
            }
        }
    }

    private fun loadCachedModels(modelPref: ListPreference?, prefs: SharedPreferences) {
        if (modelPref == null) return
        val cachedJson = prefs.getString("dj_cached_models_json", null)
        if (!cachedJson.isNullOrBlank()) {
            try {
                val jsonArray = JSONArray(cachedJson)
                val list = mutableListOf<String>()
                for (i in 0 until jsonArray.length()) {
                    list.add(jsonArray.getString(i))
                }
                if (list.isNotEmpty()) {
                    updateModelListEntries(modelPref, list)
                    return
                }
            } catch (_: Exception) {}
        }

        // When no cache exists yet, keep the initial XML entries intact and ensure current value is present
        val currentVal = modelPref.value
        if (!currentVal.isNullOrEmpty() && currentVal != "custom") {
            val values = modelPref.entryValues ?: emptyArray()
            if (!values.contains(currentVal)) {
                val newEntries = (modelPref.entries ?: emptyArray()).toMutableList()
                val newValues = values.toMutableList()
                newEntries.add(0, currentVal)
                newValues.add(0, currentVal)
                modelPref.entries = newEntries.toTypedArray()
                modelPref.entryValues = newValues.toTypedArray()
            }
        }
    }

    private fun updateModelListEntries(modelPref: ListPreference, models: List<String>) {
        val entries = ArrayList<CharSequence>()
        val entryValues = ArrayList<CharSequence>()

        for (m in models) {
            entries.add(m)
            entryValues.add(m)
        }
        entries.add(getString(R.string.settings_dj_model_custom_option))
        entryValues.add("custom")

        modelPref.entries = entries.toTypedArray()
        modelPref.entryValues = entryValues.toTypedArray()
    }

    private fun loadAvailableVoices(voicePref: ListPreference?, prefs: SharedPreferences) {
        if (voicePref == null) return
        val context = context ?: return
        val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
        val djEngine = service?.djEngine ?: DjEngine.getInstance(
            context = context,
            playerProvider = { service?.endedWorkaroundPlayer?.exoPlayer }
        )

        djEngine.duckingCoordinator.getAvailableSpanishVoices { voices ->
            val entries = mutableListOf<CharSequence>("Automática (Mejor calidad detectada)")
            val entryValues = mutableListOf<CharSequence>("auto")

            for (v in voices) {
                val isNeural = v.name.contains("network", ignoreCase = true) || v.name.contains("neural", ignoreCase = true)
                val typeTag = if (isNeural) " [Alta fidelidad]" else ""
                val label = "${v.locale.displayName} - ${v.name}$typeTag"
                entries.add(label)
                entryValues.add(v.name)
            }

            voicePref.entries = entries.toTypedArray()
            voicePref.entryValues = entryValues.toTypedArray()
            if (voicePref.value == null) {
                voicePref.value = "auto"
            }
        }
    }

    private fun fetchLiveModels(
        modelPref: ListPreference?,
        prefs: SharedPreferences,
        overrideKey: String? = null,
        silent: Boolean = false
    ) {
        val context = context ?: return
        val apiKey = (overrideKey
            ?: prefs.getString("opencode_api_key", null)
            ?: prefs.getString("deepseek_api_key", null))?.trim()
        if (apiKey.isNullOrBlank()) {
            if (!silent) {
                Toast.makeText(context, "Configurá primero tu API Key de OpenCode", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val endpoint = prefs.getString("dj_api_endpoint", null)?.trim()
        if (!silent) {
            Toast.makeText(context, "Consultando modelos disponibles a OpenCode...", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            val result = DjScriptGenerator.fetchModels(apiKey, endpoint)
            result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        if (!silent) {
                            Toast.makeText(context, "No se encontraron modelos", Toast.LENGTH_SHORT).show()
                        }
                        return@fold
                    }

                    val jsonArray = JSONArray(models)
                    prefs.edit().putString("dj_cached_models_json", jsonArray.toString()).apply()

                    if (modelPref != null) {
                        val previousValue = modelPref.value
                        updateModelListEntries(modelPref, models)
                        if (!previousValue.isNullOrBlank() && (models.contains(previousValue) || previousValue == "custom")) {
                            modelPref.value = previousValue
                        } else if (models.isNotEmpty()) {
                            val defaultModel = if (models.contains("deepseek-v4.1-flash")) "deepseek-v4.1-flash" else models.first()
                            modelPref.value = defaultModel
                        }
                    }

                    if (!silent) {
                        Toast.makeText(context, "¡Listo! ${models.size} modelos cargados", Toast.LENGTH_SHORT).show()
                    }
                },
                onFailure = { error ->
                    if (!silent) {
                        Toast.makeText(context, "Error: ${error.localizedMessage ?: error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
    }

    private fun testDjVoice() {
        val context = requireContext()
        Toast.makeText(context, "Probando voz de Livi...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val djEngine = service?.djEngine ?: DjEngine.getInstance(
                context = context,
                playerProvider = { service?.endedWorkaroundPlayer?.exoPlayer },
                apiKeyProvider = {
                    prefs.getString("opencode_api_key", null)?.trim()?.takeIf { it.isNotBlank() }
                        ?: prefs.getString("deepseek_api_key", null)?.trim()
                },
                endpointProvider = { prefs.getString("dj_api_endpoint", null) },
                modelProvider = {
                    val m = prefs.getString("dj_api_model", null)
                    if (m == "custom") prefs.getString("dj_api_model_custom", null) else m
                }
            )

            djEngine.testVoice()
        }
    }

    private fun syncPronunciationsWithLibrary(prefs: SharedPreferences, pref: Preference) {
        val context = requireContext()
        val apiKey = (prefs.getString("opencode_api_key", null)
            ?: prefs.getString("deepseek_api_key", null))?.trim()
        if (apiKey.isNullOrBlank()) {
            Toast.makeText(context, "Configurá primero tu API Key de OpenCode", Toast.LENGTH_LONG).show()
            return
        }

        val endpoint = prefs.getString("dj_api_endpoint", null)?.trim()
        val rawModel = prefs.getString("dj_api_model", null)
        val model = if (rawModel == "custom") prefs.getString("dj_api_model_custom", null) else rawModel

        pref.isEnabled = false
        Toast.makeText(context, "Escaneando artistas de tu catálogo...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val result = DjPhoneticsSync.syncWithLibrary(
                    context = context,
                    apiKey = apiKey,
                    customEndpoint = endpoint,
                    customModel = model,
                    onProgress = { current, total ->
                        if (total > 0 && current > 0) {
                            Toast.makeText(context, "Sincronizando: $current / $total artistas...", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                pref.isEnabled = true
                result.fold(
                    onSuccess = { addedCount ->
                        if (addedCount > 0) {
                            Toast.makeText(
                                context,
                                "¡Listo! Se agregaron $addedCount pronunciaciones al diccionario",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                "¡Tu biblioteca ya está al día con el diccionario!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            context,
                            "Error al sincronizar: ${error.localizedMessage ?: error.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            } catch (e: Exception) {
                pref.isEnabled = true
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
