package com.hunterboard

import java.text.Normalizer

/**
 * Shared name normalization for matching Pokémon/move/ability names across
 * data sources that disagree on special characters:
 *  - accents: "Flabébé" (lang) vs "Flabebe" (species JSON)
 *  - typographic apostrophe: "Farfetch’d" (species JSON, U+2019) vs "farfetch'd" (typed)
 *  - gender symbols: "Nidoran♀"/"Nidoran♂" vs "nidoranf"/"nidoranm"
 *  - punctuation/spacing: "Mr. Mime" vs "mrmime", "Type: Null" vs "typenull"
 */
object NameUtil {

    /**
     * Canonical key: gender symbols mapped to f/m, diacritics stripped
     * (é→e, à→a…), then everything non a-z0-9 removed.
     *
     * This is the hottest function in the mod — the search filter runs it over every
     * species on each keystroke — so it does the work in a single pass instead of through
     * two regexes. The previous version compiled `\p{M}+` and `[^a-z0-9]` on *every call*,
     * which came to some fourteen thousand pattern compilations per character typed.
     * Results are memoised as well, since the same few hundred names are normalised again
     * at every keystroke.
     */
    fun normalize(name: String): String {
        if (name.isEmpty()) return name
        memo[name]?.let { return it }

        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        for (raw in decomposed) {
            when {
                raw == '♀' -> out.append('f')
                raw == '♂' -> out.append('m')
                // combining diacritical marks, left behind by the NFD decomposition
                raw.isMark() -> {}
                raw in '0'..'9' -> out.append(raw)
                raw in 'a'..'z' -> out.append(raw)
                raw in 'A'..'Z' -> out.append(raw + 32)
                // anything else (spaces, dots, apostrophes, hyphens…) is dropped, and so
                // is any letter that did not decompose into an ASCII base
                else -> {}
            }
        }
        val result = out.toString()
        if (memo.size < MEMO_LIMIT) memo[name] = result
        return result
    }

    /** True for the combining marks NFD leaves after splitting an accented letter. */
    private fun Char.isMark(): Boolean = when (Character.getType(this).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
        else -> false
    }

    /**
     * Bounded on purpose: the keys are species, move and ability names, so a few thousand
     * entries at most, but a stray caller passing user input must not grow it forever.
     */
    private const val MEMO_LIMIT = 8192
    private val memo = HashMap<String, String>(2048)

    /**
     * Accent/punctuation-insensitive substring match for search fields.
     * "flabebe" matches "Flabébé", "farfetch'd" matches "Farfetch’d".
     *
     * Prefer [normalize] on the query once and [matchesNormalizedQuery] in a loop; this
     * form re-normalises the query for every candidate.
     */
    fun matchesQuery(candidate: String, query: String): Boolean {
        if (query.isEmpty()) return true
        return normalize(candidate).contains(normalize(query))
    }

    /** As [matchesQuery], for when the query was already normalised outside the loop. */
    fun matchesNormalizedQuery(candidate: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isEmpty()) return true
        return normalize(candidate).contains(normalizedQuery)
    }
}
