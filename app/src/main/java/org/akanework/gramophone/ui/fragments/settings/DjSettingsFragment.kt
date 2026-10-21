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
import org.akanework.gramophone.logic.dj.DjScriptGenerator
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity
import org.json.JSONArray

class DjSettingsActivity : BaseSettingsActivity(
    R.string.settings_dj_title,
    { DjSettingsFragment() })

class DjSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_dj, rootKey)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val apiKeyPref = findPreference<EditTextPreference>("deepseek_api_key")
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

        // Auto-fetch fresh models from API on opening settings if key is present
        val currentApiKey = prefs.getString("deepseek_api_key", null)?.trim()
        if (!currentApiKey.isNullOrBlank()) {
            fetchLiveModels(modelPref, prefs, silent = true)
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

        // When no cache exists yet, show current value if set + custom option
        val currentVal = modelPref.value
        val entries = ArrayList<CharSequence>()
        val entryValues = ArrayList<CharSequence>()
        if (!currentVal.isNullOrEmpty() && currentVal != "custom") {
            entries.add(currentVal)
            entryValues.add(currentVal)
        }
        entries.add(getString(R.string.settings_dj_model_custom_option))
        entryValues.add("custom")
        modelPref.entries = entries.toTypedArray()
        modelPref.entryValues = entryValues.toTypedArray()
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

    private fun fetchLiveModels(
        modelPref: ListPreference?,
        prefs: SharedPreferences,
        overrideKey: String? = null,
        silent: Boolean = false
    ) {
        val context = context ?: return
        val apiKey = (overrideKey ?: prefs.getString("deepseek_api_key", null))?.trim()
        if (apiKey.isNullOrBlank()) {
            if (!silent) {
                Toast.makeText(context, "Configurá primero tu API Key", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val endpoint = prefs.getString("dj_api_endpoint", null)?.trim()
        if (!silent) {
            Toast.makeText(context, "Consultando modelos disponibles a la API...", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
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
        Toast.makeText(context, "Sintetizando voz de prueba con Kokoro...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val djEngine = service?.djEngine ?: DjEngine.getInstance(
                context = context,
                playerProvider = { service?.endedWorkaroundPlayer?.exoPlayer },
                apiKeyProvider = { prefs.getString("deepseek_api_key", null) },
                endpointProvider = { prefs.getString("dj_api_endpoint", null) },
                modelProvider = {
                    val m = prefs.getString("dj_api_model", null)
                    if (m == "custom") prefs.getString("dj_api_model_custom", null) else m
                }
            )

            val dummyMediaItem = MediaItem.Builder()
                .setMediaId("test_1")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("De Música Ligera")
                        .setArtist("Soda Stereo")
                        .setAlbumTitle("Canción Animal")
                        .setRecordingYear(1990)
                        .setGenre("Rock Nacional")
                        .build()
                )
                .build()

            djEngine.triggerImmediateIntervention(
                currentSong = null,
                upcomingSongs = listOf(dummyMediaItem)
            )
        }
    }
}
