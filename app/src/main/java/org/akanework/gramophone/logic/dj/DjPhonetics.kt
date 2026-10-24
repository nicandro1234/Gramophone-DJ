package org.akanework.gramophone.logic.dj

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONObject

object DjPhonetics {

    val BUILTIN_PRONUNCIATIONS = listOf(
        // Rock, Metalcore & Post-Hardcore (Catalog artists)
        "Bring Me The Horizon" to "Bring Mi De Joraizon",
        "Pierce The Veil" to "Pirs De Veil",
        "Falling In Reverse" to "Fóling In Rivérs",
        "A Day To Remember" to "A Déi Tu Rimémber",
        "Asking Alexandria" to "Ásking Alejándria",
        "Sleeping With Sirens" to "Slíping Uid Sáirens",
        "Silverstein" to "Sílverstain",
        "Architects" to "Árquitects",
        "Spiritbox" to "Spíritbox",
        "Electric Callboy" to "Eléctric Cólboi",
        "All Shall Perish" to "Ol Shal Pérish",
        "After The Burial" to "Áfter De Bériol",
        "Within Destruction" to "Uidín Distráckshon",
        "Whitechapel" to "Uáitchapel",
        "Subliminal Fear" to "Sublíminal Fíar",
        "Breathe Carolina" to "Brid Karoláina",
        "Devin Townsend" to "Dévin Táunsend",
        "Devin Townsend Project" to "Dévin Táunsend Próyect",
        "Heaven Shall Burn" to "Jéven Shal Bern",
        "Motion City Soundtrack" to "Móushon Síti Sáundtrak",
        "Dance Gavin Dance" to "Dans Gávin Dans",
        "The Devil Wears Prada" to "De Dévil Uers Práda",
        "The Dillinger Escape Plan" to "De Dílinguer Iskéip Plan",
        "Chiodos" to "Chióudos",
        "I See Stars" to "Ai Si Stars",
        "Flyleaf" to "Fláilif",
        "Killswitch Engage" to "Kílsuitch Enguéidsh",
        "Slipknot" to "Slípknot",
        "System of a Down" to "Sístem of a Dáun",
        "Deftones" to "Déftouns",
        "Avenged Sevenfold" to "Avéndsh Sévonfold",
        "Breaking Benjamin" to "Bréiking Bényamin",
        "Three Days Grace" to "Zri Déis Greis",
        "Papa Roach" to "Pápa Rouch",
        "Limp Bizkit" to "Limp Bízkit",
        "Korn" to "Korn",
        "My Chemical Romance" to "Mai Químical Románs",
        "Fall Out Boy" to "Fol Aut Bói",
        "Panic! At The Disco" to "Pánic At De Dísco",
        "All Time Low" to "Ol Taim Lóu",
        "Sum 41" to "Sam Fórti Uan",
        "Blink-182" to "Blink Uan Eiti Tu",
        "Dead Rabbitts" to "Ded Rábits",
        "Crossfaith" to "Crósfeiz",
        "Within Temptation" to "Uidín Témptéishon",
        "Apocalyptica" to "Apocalíptica",
        "Amaranthe" to "Amaránte",
        "Adema" to "Adéma",
        "Atreyu" to "Atréyu",
        "Mudvayne" to "Madvéin",

        // Classic Rock & Legends
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
        "KISS" to "Kis",
        "Metallica" to "Metálica",
        "Iron Maiden" to "Airon Méiden",
        "Bee Gees" to "Bi Yís",
        "ABBA" to "Ába",
        "Fleetwood Mac" to "Flítwud Mak",
        "Nine Inch Nails" to "Nain Inch Néils",

        // Indie, Alternative & Gaming Soundtracks (Catalog artists)
        "Cage The Elephant" to "Keidsh Di Élefant",
        "The 1975" to "De Naintin Sevengti Faiv",
        "Twenty One Pilots" to "Tüenti Uan Pailots",
        "Imagine Dragons" to "Imáyin Drágons",
        "Arctic Monkeys" to "Árctic Mónkis",
        "The Strokes" to "De Stróuks",
        "The Neighbourhood" to "De Néiborjud",
        "Foster The People" to "Fóster De Pípol",
        "Younger Hunger" to "Yánguer Jánguer",
        "Joywave" to "Yóigüeiv",
        "girl in red" to "Guerl In Red",
        "Crumb" to "Cram",
        "Ghostemane" to "Góustmein",
        "\$uicideboy\$" to "Suisáidbois",
        "XXXTENTACION" to "Equis Equis Equis Tentación",
        "Poppy" to "Pópi",
        "Blood Cultures" to "Blad Cálchurs",
        "Sidewalks and Skeletons" to "Sáidüolks and Skéletons",
        "Bastille" to "Bastíl",
        "City Girl" to "Síti Guerl",
        "The Living Tombstone" to "De Líving Túmstoun",
        "Halocene" to "Jálosin",
        "Darren Korb" to "Dárren Korb",
        "Ashley Barrett" to "Áshli Bárret",
        "League of Legends" to "Lig of Léyends",
        "K/DA" to "Kei Di Ei",
        "GameChops" to "Guéim Chops",
        "RichaadEB" to "Ríchaad I Bi",
        "Savage Garden" to "Sávadsh Gárden",
        "Caravan Palace" to "Cáravan Pálas",
        "Joji" to "Yóyi",
        "K.Flay" to "Kei Fléi",

        // Electronic, EDM & Synth (Catalog artists)
        "Gorillaz" to "Gorílas",
        "Daft Punk" to "Daft Pank",
        "Skrillex" to "Skrílex",
        "Klaypex" to "Kléipex",
        "Nujabes" to "Nuyábes",
        "Veela" to "Víla",
        "Crystal Castles" to "Crístal Cásols",
        "Steve Aoki" to "Stiv Aóki",
        "Lindsey Stirling" to "Líndsi Stírling",
        "Kill The Noise" to "Kil De Nóis",
        "ItaloBrothers" to "Italobráders",
        "Blue Stahli" to "Blu Stáli",
        "Perturbator" to "Perturbéitor",
        "Sophie Powers" to "Sófi Páüers",
        "Feint" to "Feint",
        "Dimitri Vegas" to "Dimítri Végas",
        "Like Mike" to "Laik Máik",
        "Hardwell" to "Járdüel",
        "Gym Class Heroes" to "Yim Clas Jírous",
        "Celldweller" to "Seldüéler",
        "Infected Mushroom" to "Infécted Máshrum",
        "Bingo Players" to "Bíngo Pléiers",
        "Pendulum" to "Péndiulum",
        "Virtual Riot" to "Vértual Ráiot",
        "Pegboard Nerds" to "Pégbord Nerds",
        "David Guetta" to "Déivid Guéta",
        "Avicii" to "Avíchi",
        "Calvin Harris" to "Cálvin Járris",
        "Marshmello" to "Marchmelo",
        "The Chainsmokers" to "De Chéinsmoukers",
        "Swedish House Mafia" to "Suídish Jaus Máfia",
        "Tiësto" to "Tiésto",
        "Martin Garrix" to "Martin Gárrix",
        "Deadmau5" to "Dedmaus",
        "Kygo" to "Káigo",
        "Zedd" to "Zed",
        "Krewella" to "Kruéla",
        "Darude" to "Darúd",
        "Aero Chord" to "Éiro Kord",

        // Asian Pop, J-Rock & Anime (Catalog artists)
        "Anamanaguchi" to "Anamanagúchi",
        "BABYMETAL" to "Béibi Métal",
        "BLACKPINK" to "Blak Pink",
        "aespa" to "Éspa",
        "(G)I-DLE" to "Áidol",
        "i-dle" to "Áidol",
        "Red Velvet" to "Red Vélvet",
        "Akira Yamaoka" to "Akíra Yamaóka",

        // Pop & Mainstream
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
        "Ellie Goulding" to "Éli Gúlding",
        "Mimi Webb" to "Mími Uéb",
        "Lana Del Rey" to "Lana Del Rei",
        "MARINA" to "Marína",
        "Melanie Martinez" to "Mélani Martínez",

        // Hip-hop & Urban
        "Eminem" to "Éminem",
        "Drake" to "Dréik",
        "Kanye West" to "Kanye Uést",
        "Kendrick Lamar" to "Kéndrik Lamár",
        "Travis Scott" to "Trávis Skot",
        "Snoop Dogg" to "Snup Dog",
        "Bad Bunny" to "Bad Bóni"
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

    /**
     * Checks if an artist is already registered in built-ins or user overrides.
     */
    fun isKnownArtist(artistName: String, context: Context): Boolean {
        val trimmed = artistName.trim().lowercase()
        if (BUILTIN_PRONUNCIATIONS.any { it.first.trim().lowercase() == trimmed }) {
            return true
        }
        val userRules = loadUserOverrides(context)
        return userRules.any { it.first.trim().lowercase() == trimmed }
    }

    /**
     * Returns the full set of known artist names (lowercased) for quick lookup.
     */
    fun getAllKnownArtists(context: Context): Set<String> {
        val set = mutableSetOf<String>()
        for ((orig, _) in BUILTIN_PRONUNCIATIONS) {
            set.add(orig.trim().lowercase())
        }
        for ((orig, _) in loadUserOverrides(context)) {
            set.add(orig.trim().lowercase())
        }
        return set
    }

    fun loadUserOverrides(context: Context): List<Pair<String, String>> {
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

        // Otherwise parse lines or comma-separated pairs: "Queen = Cuín\nThe Weeknd = De Güikend"
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

    fun saveUserOverrides(context: Context, newEntries: Map<String, String>) {
        if (newEntries.isEmpty()) return
        val currentOverrides = loadUserOverrides(context).toMap().toMutableMap()
        for ((k, v) in newEntries) {
            if (k.isNotBlank() && v.isNotBlank()) {
                currentOverrides[k.trim()] = v.trim()
            }
        }

        val sb = StringBuilder()
        for ((k, v) in currentOverrides) {
            sb.append("$k = $v\n")
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("dj_custom_pronunciations", sb.toString().trim()).apply()
    }
}
