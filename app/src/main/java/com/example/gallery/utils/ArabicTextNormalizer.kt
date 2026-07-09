package com.example.gallery.utils

/**
 * Normalizes Arabic text down to the 28 base letters so OCR search is tolerant of the many ways the
 * same word can be written. Applied to BOTH the OCR text we store and the search query, so a user
 * can type a word plainly and still match it regardless of how it appeared in the image.
 *
 * Specifically it:
 *  - strips tashkeel (harakat / tanwin / shadda / sukun), the superscript alef, and the tatweel (ـ)
 *  - unifies the alef forms (أ إ آ ٱ) → ا
 *  - maps alef-maksura (ى) → ي, teh-marbuta (ة) → ه, waw-hamza (ؤ) → و, yeh-hamza (ئ) → ي
 *  - drops the standalone hamza (ء)
 *
 * Non-Arabic characters (Latin letters, digits, punctuation, whitespace) are passed through
 * unchanged, so English OCR/search is completely unaffected.
 */
object ArabicTextNormalizer {

    fun normalize(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                // Alef variants (madda, hamza-above, hamza-below, wasla) → plain alef
                'آ', 'أ', 'إ', 'ٱ' -> sb.append('ا')
                // Alef maksura → yeh
                'ى' -> sb.append('ي')
                // Teh marbuta → heh
                'ة' -> sb.append('ه')
                // Waw with hamza → waw
                'ؤ' -> sb.append('و')
                // Yeh with hamza → yeh
                'ئ' -> sb.append('ي')
                // Standalone hamza → drop (not one of the 28 base letters)
                'ء' -> Unit
                // Tatweel (kashida) → drop
                'ـ' -> Unit
                // Harakat, tanwin, shadda, sukun → drop
                in 'ً'..'ْ' -> Unit
                // Maddah/hamza above & below, superscript alef → drop
                'ٓ', 'ٔ', 'ٕ', 'ٰ' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /** True if [text] contains any character in the Arabic Unicode block. */
    fun containsArabic(text: String): Boolean = text.any { it in '؀'..'ۿ' }
}
