package com.example.gallery.ml

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.util.regex.Pattern

class ClipTokenizer(
    context: Context,
    vocabJsonPath: String = "vocab.json",
    mergesTxtPath: String = "merges.txt"
) {

    private val byteEncoder = createCharDict()
    private val byteDecoder = byteEncoder.map { it.value to it.key }.toMap()

    private val merges: List<Pair<String, String>>
    val encoder: Map<String, Int>
    private val decoder: Map<Int, String>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache: MutableMap<String, String>

    private val pat = Pattern.compile(
        "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
    )

    init {
        val vocab: MutableList<String> = byteEncoder.values.map { it.toString() }.toMutableList()
        vocab.addAll(vocab.map { "$it</w>" }.toList())

        val mergesFileLines: List<String> = readPlainFile(context, mergesTxtPath)
        merges =
            mergesFileLines.subList(1, 49152 - 256 - 2 + 1).map {
                val sp = it.split(" ")
                Pair(sp[0], sp[1])
            }

        vocab.addAll(merges.map { it.first + it.second })
        vocab.addAll(listOf("<|startoftext|>", "<|endoftext|>"))

        encoder = vocab.withIndex()
            .associateBy({ it.value }, { it.index })
        decoder = encoder
            .map { it.value to it.key }.toMap()
        bpeRanks = merges.mapIndexed { index, pair -> pair to index }.toMap()
        cache = mutableMapOf(
            "<|startoftext|>" to "<|startoftext|>", "<|endoftext|>" to "<|endoftext|>"
        )
    }

    private fun bpe(token: String): String {
        if (cache.containsKey(token)) {
            return cache[token]!!
        }

        var word = token.dropLast(1).map { it.toString() }.toMutableList()
        word.add(token.last().toString() + "</w>")
        var pairs = getPairs(word)

        if (pairs.isEmpty()) {
            return "$token</w>"
        }
        while (true) {
            val bigram: Pair<String, String> =
                pairs.minByOrNull { bpeRanks[it] ?: Int.MAX_VALUE } ?: break
            if (!bpeRanks.containsKey(bigram)) break

            val (first, second) = bigram

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

    private fun encode(text: String): List<Int> {
        val cleanedText = whitespaceClean(text).lowercase()
        val matcher = pat.matcher(cleanedText)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            val match = matcher.group()
            matches.add(match)
        }
        val bpeTokens = mutableListOf<Int>()
//        for token in re.findall(self.pat, text):
//        token = ''.join(self.byte_encoder[b] for b in token.encode('utf-8'))
//        bpe_tokens.extend(self.encoder[bpe_token] for bpe_token in self.bpe(token).split(' '))

//        return bpe_tokens
        for (token in matches) {
            val encodedToken = token.toByteArray().map { byteEncoder[it.toInt()] }.joinToString("")
            for (bpeToken in bpe(encodedToken).split(" ")) {
                bpeTokens.add(encoder[bpeToken]!!)
            }
        }
        return bpeTokens
    }

    fun decode(tokens: List<Int>): String {
//        val text = tokens.map { decoder[it]!! }.joinToString("")
        val text = tokens.joinToString("") { decoder[it]!! }
        return text.toByteArray().toString(Charsets.UTF_8).replace("</w>", " ")
    }


    fun tokenize(
        text: String,
        contextLength: Int = 77,
        truncate: Boolean = false
    ): LongBuffer {
        val sotToken: Int = encoder["<|startoftext|>"]!!
        val eotToken: Int = encoder["<|endoftext|>"]!!
//    val allTokens: MutableList<MutableList<Int>> = ArrayList()
        val tokens: MutableList<Int> = ArrayList()
        tokens.add(sotToken)
        tokens.addAll(encode(text))
        tokens.add(eotToken)

        if (tokens.size > contextLength) {
            if (truncate) {
                val truncatedTokens = tokens.subList(0, contextLength)
                truncatedTokens[contextLength - 1] = eotToken
            } else {
                throw java.lang.RuntimeException("Input $text is too long for context length $contextLength")
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

        IntArray(contextLength) {
            if (it < tokens.size)
                tokens[it]
            else 0
        }
        return longBuffer
    }

}


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

@Throws(IOException::class)
private fun assetFilePath(context: Context, assetName: String): String? {
    val file = File(context.filesDir, assetName)
    if (file.exists() && file.length() > 0) {
        return file.absolutePath
    }
    try {
        context.assets.open(assetName).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    } catch (_: Exception) {
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(context, "Load asset failed: $assetName", Toast.LENGTH_SHORT).show()
        }
        return null
    }

}

/**
 * Reads a JSON file from assets and returns its content as a List<String>
 * using Kotlinx Serialization.
 */
private fun readJsonVocabFile(context: Context, assetName: String): List<String> {
    // 1. Get the absolute file path (using your existing helper)
    val filePath =
        assetFilePath(context, assetName) ?: throw IOException("Asset file not found: $assetName")

    // 2. Read the raw JSON string content
    val jsonString = File(filePath).bufferedReader(Charsets.UTF_8).use { it.readText() }

    // 3. Parse the JSON string into a Kotlin List<String>
    // Assuming your vocab.json is formatted as a top-level array of strings: ["token1", "token2", ...]
    return Json.decodeFromString(jsonString)
}

/**
 * Reads a plain text file (like merges.txt) from assets line by line.
 */
private fun readPlainFile(context: Context, assetName: String): List<String> {
    val filePath =
        assetFilePath(context, assetName) ?: throw IOException("Asset file not found: $assetName")

    val result = mutableListOf<String>()
    BufferedReader(
        InputStreamReader(
            FileInputStream(File(filePath)),
            Charsets.UTF_8
        )
    ).use { reader ->
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            result.add(line!!)
        }
    }
    return result
}

private fun getPairs(word: List<String>): Set<Pair<String, String>> {
    return word.zipWithNext().map { it.first to it.second }.toSet()
}

private fun whitespaceClean(text: String): String {
    var cleanedText = text.replace(Regex("\\s+"), " ")
    cleanedText = cleanedText.trim()
    return cleanedText
}