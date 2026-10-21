package org.akanework.gramophone.ui.fragments.settings

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import org.akanework.gramophone.R
import org.akanework.gramophone.logic.GramophonePlaybackService
import org.akanework.gramophone.logic.dj.DjEngine
import org.akanework.gramophone.logic.dj.DjSongInfo
import org.akanework.gramophone.ui.fragments.BasePreferenceFragment
import org.akanework.gramophone.ui.fragments.BaseSettingsActivity

class DjSettingsActivity : BaseSettingsActivity(
    R.string.settings_dj_title,
    { DjSettingsFragment() })

class DjSettingsFragment : BasePreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_dj, rootKey)

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

    private fun testDjVoice() {
        val context = requireContext()
        Toast.makeText(context, "Sintetizando voz de prueba con Kokoro...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val service = GramophonePlaybackService.instanceForWidgetAndLyricsOnly
            val djEngine = service?.djEngine ?: DjEngine.getInstance(
                context = context,
                playerProvider = { service?.endedWorkaroundPlayer?.exoPlayer }
            )

            val dummyUpcoming = listOf(
                DjSongInfo(
                    mediaId = "test_1",
                    title = "De Música Ligera",
                    artist = "Soda Stereo",
                    album = "Canción Animal",
                    year = 1990,
                    genre = "Rock Nacional"
                )
            )

            djEngine.triggerImmediateIntervention(
                currentSong = null,
                upcomingSongs = emptyList()
            )
        }
    }
}
