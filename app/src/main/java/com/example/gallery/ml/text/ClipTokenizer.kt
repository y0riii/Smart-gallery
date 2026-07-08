package com.example.gallery.ml.text

import android.content.Context
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.regex.Pattern

/**
 * CLIP's text tokenizer — a byte-level Byte-Pair-Encoding (BPE) tokenizer, ported to Kotlin so it
 * exactly matches what CLIP's text model was trained on. It converts a prompt into the 77-length
 * integer token sequence the ONNX text model expects.
 *
 * Pipeline: text → lower-cased & whitespace-cleaned → split into "words" by [pat] → each word's
 * bytes mapped to printable stand-in chars ([byteEncoder]) → BPE merges applied ([bpe]) → each
 * resulting sub-word looked up in [encoder] to an integer id. The sequence is wrapped with
 * start/end tokens and padded/truncated to 77 in [tokenize].
 *
 * Assets:
 *  - vocab.json  ([encoder]) — sub-word string → integer id.
 *  - merges.txt  ([merges])  — ordered list of BPE merge rules; earlier lines = higher priority.
 */
class ClipTokenizer(
    context: Context,
    vocabJsonPath: String = "vocab.json",
    mergesTxtPath: String = "merges.txt"
) {

    private val byteEncoder = createCharDict()

    private val merges: List<Pair<String, String>>
    val encoder: Map<String, Int>
    private val decoder: Map<Int, String>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache: MutableMap<String, String>

    private val startOfText = "<start_of_text>"
    private val endOfText = "<end_of_text>"

    private val pat = Pattern.compile(
        "${startOfText}|${endOfText}|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
    )

    init {
        val vocabJson = context.assets.open(vocabJsonPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        encoder = Json.decodeFromString(vocabJson)
        decoder = encoder.entries.associate { it.value to it.key }

        val lines = context.assets.open(mergesTxtPath).bufferedReader(Charsets.UTF_8).readLines()
        merges = lines
            .drop(1)
            .filter { it.isNotBlank() }
            .map {
                val parts = it.split(" ")
                parts[0] to parts[1]
            }

        bpeRanks = merges.mapIndexed { index, pair -> pair to index }.toMap()
        cache = mutableMapOf(
            startOfText to startOfText, endOfText to endOfText
        )
    }

    /**
     * Applies BPE merges to a single token, returning its sub-words joined by spaces.
     *
     * Starts with the token split into individual characters (the last one marked with the
     * end-of-word suffix "</w>"), then repeatedly merges the adjacent pair with the highest
     * priority (lowest rank in [bpeRanks]) until no more mergeable pairs remain. Results are
     * memoised in [cache] because the same tokens recur constantly across prompts.
     */
    private fun bpe(token: String): String {
        cache[token]?.let { return it }

        // Split into symbols; the final symbol carries the "</w>" end-of-word marker.
        var word = token.dropLast(1).map { it.toString() }.toMutableList()
        word.add(token.last().toString() + "</w>")
        var pairs = getPairs(word)

        if (pairs.isEmpty()) {
            return "$token</w>"
        }
        while (true) {
            // Pick the highest-priority adjacent pair (lowest merge rank); stop if none qualify.
            val bigram: Pair<String, String> =
                pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (!bpeRanks.containsKey(bigram)) break

            val (first, second) = bigram

            // Walk the symbol list and fuse every occurrence of (first, second) into one symbol.

            val newWord = mutableListOf<String>()
            var i = 0
            while (i < word.size) {
                var j = word.subList(i, word.size).indexOf(first)
                if (j != -1) {
                    j += i
                    newWord.addAll(word.subList(i, j))
                    i = j
                } else {
                    newWord.addAll(word.subList(i, word.size))
                    break
                }

                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i++
                }
            }
            word = newWord

            if (word.size == 1) break
            else pairs = getPairs(word)
        }
        val result = word.joinToString(" ")
        cache[token] = result
        return result
    }

    /** Turns cleaned text into the list of integer token ids (no start/end/pad — that's [tokenize]). */
    private fun encode(text: String): List<Int> {
        val cleanedText = whitespaceClean(text).lowercase()
        val matcher = pat.matcher(cleanedText)
        val bpeTokens = mutableListOf<Int>()
        while (matcher.find()) {
            val token = matcher.group()

            // Map each UTF-8 byte to its printable stand-in char so BPE operates purely on text.
            val encodedToken =
                token.toByteArray().map { byteEncoder[it.toInt() and 0xFF]!! }.joinToString("")

            // Apply BPE, then map each resulting sub-word to its vocab id.
            for (bpeToken in bpe(encodedToken).split(" ")) {
                bpeTokens.add(encoder[bpeToken]!!)
            }
        }
        return bpeTokens
    }

    /**
     * Produces the model's fixed-size input: [start] + encoded tokens + [end], then padded with 0
     * (or truncated to [contextLength], keeping the end token last) and packed into a little-endian
     * LongBuffer of length [contextLength] — the tensor shape the ONNX text model expects.
     */

    fun tokenize(
        text: String,
        contextLength: Int = 77,
        truncate: Boolean = false
    ): LongBuffer {
        val sotToken: Int = encoder[startOfText]!!
        val eotToken: Int = encoder[endOfText]!!
        val tokens: MutableList<Int> = ArrayList()
        tokens.add(sotToken)
        tokens.addAll(encode(text))
        tokens.add(eotToken)

        if (tokens.size > contextLength) {
            if (truncate) {
                val truncatedTokens = tokens.subList(0, contextLength)
                truncatedTokens[contextLength - 1] = eotToken
            } else {
                throw RuntimeException("Input $text is too long for context length $contextLength")
            }
        }
        val intArrayResult = IntArray(contextLength) {
            if (it < tokens.size)
                tokens[it]
            else 0
        }

        val byteBuffer = ByteBuffer.allocateDirect(contextLength * 8)
        byteBuffer.order(ByteOrder.nativeOrder())

        val longBuffer = byteBuffer.asLongBuffer()
        intArrayResult.forEach { intValue ->
            longBuffer.put(intValue.toLong())
        }

        longBuffer.rewind()
        return longBuffer
    }

}


/**
 * Builds CLIP's byte→char map. Bytes that are already printable characters map to themselves;
 * the remaining bytes are mapped to unused code points starting at 256. This lets the BPE step
 * work on ordinary strings while still round-tripping arbitrary UTF-8 bytes losslessly.
 */
private fun createCharDict(): Map<Int, Char> {
    val bytesList = mutableListOf<Int>()
    bytesList.addAll(33..126)
    bytesList.addAll(161..172)
    bytesList.addAll(174..255)
    val charList = bytesList.toMutableList()
    var n = 0
    for (b in 0..255) {
        if (b !in bytesList) {
            bytesList.add(b)
            charList.add(256 + n)
            n++
        }
    }
    return bytesList.zip(charList.map { it.toChar() }).toMap()
}

private fun getPairs(word: List<String>): Set<Pair<String, String>> {
    return word.zipWithNext().map { it.first to it.second }.toSet()
}

private fun whitespaceClean(text: String): String {
    var cleanedText = text.replace(Regex("\\s+"), " ")
    cleanedText = cleanedText.trim()
    return cleanedText
}