package org.akanework.gramophone.logic.dj

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

object DjPhonetics {

    private val BUILTIN_PRONUNCIATIONS = listOf(
        // Rock & Classic Bands
        "The Beatles" to "Los Bítols",
        "The Rolling Stones" to "De Rólin Stóuns",
        "Queen" to "Cuín",
        "Pink Floyd" to "Pink Flóid",
        "Led Zeppelin" to "Led Zépelin",
        "AC/DC" to "Eisi Disi",
        "ACDC" to "Eisi Disi",
        "Guns N' Roses" to "Gans an Róuses",
        "Guns N Roses" to "Gans an Róuses",
        "Aerosmith" to "Eirosmit",
        "Bon Jovi" to "Bon Yóvi",
        "The Police" to "De Polís",
        "The Cure" to "De Kiur",
        "The Smiths" to "De Smits",
        "Nirvana" to "Nirvána",
        "Green Day" to "Grin Déi",
        "Red Hot Chili Peppers" to "Red Jot Chili Pépers",
        "Foo Fighters" to "Fu Fáiters",
        "Radiohead" to "Reidiojed",
        "Oasis" to "Oéisis",
        "The Killers" to "De Kílers",
        "Coldplay" to "Cóldpley",
        "Linkin Park" to "Línkin Park",
        "Twenty One Pilots" to "Tüenti Uan Pailots",
        "Imagine Dragons" to "Imáyin Drágons",
        "Arctic Monkeys" to "Árctic Mónkis",
        "The Strokes" to "De Stróuks",
        "Gorillaz" to "Gorílas",
        "Daft Punk" to "Daft Pank",
        "KISS" to "Kis",
        "Metallica" to "Metálica",
        "Iron Maiden" to "Airon Méiden",
        "Bee Gees" to "Bi Yís",
        "ABBA" to "Ába",
        "Fleetwood Mac" to "Flítwud Mak",

        // Pop & Modern Icons
        "Michael Jackson" to "Máikel Yákson",
        "The Weeknd" to "De Güíkend",
        "Taylor Swift" to "Téilor Suíft",
        "Billie Eilish" to "Bili Áilish",
        "Dua Lipa" to "Dua Lípa",
        "Post Malone" to "Póst Malón",
        "Bruno Mars" to "Bruno Mars",
        "Adele" to "Adél",
        "Rihanna" to "Riana",
        "Lady Gaga" to "Leidi Gaga",
        "Ed Sheeran" to "Ed Shíran",
        "Ariana Grande" to "Ariana Grande",
        "Justin Bieber" to "Yastin Bíber",
        "Maroon 5" to "Marun Faiv",
        "Black Eyed Peas" to "Blak Aid Pis",
        "One Direction" to "Uan Dirékshon",

        // Hip-hop & Urban
        "Eminem" to "Éminem",
        "Drake" to "Dréik",
        "Kanye West" to "Kanye Uést",
        "Kendrick Lamar" to "Kéndrik Lamár",
        "Travis Scott" to "Trávis Skot",
        "Snoop Dogg" to "Snup Dog",
        "Bad Bunny" to "Bad Bóni",

        // Electronic & EDM
        "David Guetta" to "Déivid Guéta",
        "Avicii" to "Avíchi",
        "Calvin Harris" to "Cálvin Járris",
        "Marshmello" to "Marchmelo",
        "The Chainsmokers" to "De Chéinsmoukers",
        "Swedish House Mafia" to "Suídish Jaus Máfia",
        "Tiësto" to "Tiésto",
        "Martin Garrix" to "Martin Gárrix",
        "Skrillex" to "Skrílex",
        "Deadmau5" to "Dedmaus",
        "Kygo" to "Káigo"
    )

    /**
     * Applies phonetic pronunciation adjustments for English and international artist names.
     * Incorporates both built-in mappings and custom user overrides from settings.
     */
    fun applyPhonetics(context: Context, rawText: String): String {
        var text = rawText

        // 1. Apply user custom overrides first (from SharedPreferences)
        val userRules = loadUserOverrides(context)
        for ((original, replacement) in userRules) {
            if (original.isNotBlank() && replacement.isNotBlank()) {
                val regex = Regex("\\b" + Regex.escape(original.trim()) + "\\b", RegexOption.IGNORE_CASE)
                text = text.replace(regex, replacement.trim())
            }
        }

        // 2. Apply built-in artist phonetic replacements
        for ((original, replacement) in BUILTIN_PRONUNCIATIONS) {
            val regex = Regex("\\b" + Regex.escape(original) + "\\b", RegexOption.IGNORE_CASE)
            text = text.replace(regex, replacement)
        }

        return text
    }

    private fun loadUserOverrides(context: Context): List<Pair<String, String>> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val rawConfig = prefs.getString("dj_custom_pronunciations", null)?.trim() ?: return emptyList()
        val list = mutableListOf<Pair<String, String>>()

        // Try parsing JSON format: {"Queen": "Cuín", "The Cure": "De Kiur"}
        if (rawConfig.startsWith("{") && rawConfig.endsWith("}")) {
            try {
                val json = JSONObject(rawConfig)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) {
                        list.add(k to v)
                    }
                }
                return list
            } catch (_: Exception) {}
        }

        // Otherwise parse lines or comma-separated pairs: "Queen = Cuín, The Weeknd = De Güikend"
        val lines = rawConfig.split(Regex("[\\n,;]+"))
        for (line in lines) {
            val parts = line.split("=")
            if (parts.size == 2) {
                val k = parts[0].trim()
                val v = parts[1].trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    list.add(k to v)
                }
            }
        }
        return list
    }
}
