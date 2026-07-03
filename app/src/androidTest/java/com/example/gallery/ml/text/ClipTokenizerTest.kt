package com.example.gallery.ml.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.LongBuffer

/**
 * Instrumented tests for [ClipTokenizer].
 *
 * Verifies the BPE tokenizer's asset parsing, token encoding sequences,
 * context padding (77 slots), unknown word handling, and context truncation rules.
 */
@RunWith(AndroidJUnit4::class)
class ClipTokenizerTest {

    private lateinit var tokenizer: ClipTokenizer

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Load default vocabulary and merges from assets
        tokenizer = ClipTokenizer(context)
    }

    @Test
    fun test_tokenizer_loads_vocabulary_and_merges_successfully() {
        assertNotNull(tokenizer.encoder)
        assertTrue(tokenizer.encoder.isNotEmpty())
        assertTrue(tokenizer.encoder.containsKey("<start_of_text>"))
        assertTrue(tokenizer.encoder.containsKey("<end_of_text>"))
    }

    @Test
    fun test_tokenize_standard_phrase() {
        val text = "a photo of a cat"
        val buffer: LongBuffer = tokenizer.tokenize(text, contextLength = 77, truncate = false)

        assertNotNull(buffer)
        assertEquals(77, buffer.capacity())

        // Read the first few tokens
        val tokens = LongArray(77)
        buffer.get(tokens)

        // Verify start of text token is first
        val startToken = tokenizer.encoder["<start_of_text>"]!!.toLong()
        assertEquals(startToken, tokens[0])

        // Verify end of text token is placed after the encoded phrase tokens
        val endToken = tokenizer.encoder["<end_of_text>"]!!.toLong()
        
        // Find index of end of text token in the buffer
        val endTokenIndex = tokens.indexOf(endToken)
        assertTrue(endTokenIndex > 1) // Must be present after start and actual words

        // Verify remaining padded slots are filled with 0
        for (i in (endTokenIndex + 1) until 77) {
            assertEquals(0L, tokens[i])
        }
    }

    @Test
    fun test_tokenize_empty_string() {
        val buffer = tokenizer.tokenize("", contextLength = 77)
        val tokens = LongArray(77)
        buffer.get(tokens)

        val startToken = tokenizer.encoder["<start_of_text>"]!!.toLong()
        val endToken = tokenizer.encoder["<end_of_text>"]!!.toLong()

        assertEquals(startToken, tokens[0])
        assertEquals(endToken, tokens[1])
        for (i in 2 until 77) {
            assertEquals(0L, tokens[i])
        }
    }

    @Test
    fun test_tokenize_case_insensitivity_and_whitespace() {
        val text1 = "   A   cAt   "
        val text2 = "a cat"

        val buffer1 = tokenizer.tokenize(text1, contextLength = 77)
        val buffer2 = tokenizer.tokenize(text2, contextLength = 77)

        val tokens1 = LongArray(77)
        val tokens2 = LongArray(77)

        buffer1.get(tokens1)
        buffer2.get(tokens2)

        assertArrayEquals(tokens1, tokens2)
    }

    @Test
    fun test_tokenize_truncation_rules() {
        // Generate a very long sequence of words to exceed the context length of 10
        val longText = (1..20).joinToString(" ") { "word" }

        // With truncate = true, it should truncate and set the last element to <end_of_text>
        val buffer = tokenizer.tokenize(longText, contextLength = 10, truncate = true)
        val tokens = LongArray(10)
        buffer.get(tokens)

        val endToken = tokenizer.encoder["<end_of_text>"]!!.toLong()
        assertEquals(endToken, tokens[9])
    }

    @Test(expected = RuntimeException::class)
    fun test_tokenize_too_long_without_truncation_throws() {
        val longText = (1..100).joinToString(" ") { "word" }
        tokenizer.tokenize(longText, contextLength = 10, truncate = false)
    }
}
