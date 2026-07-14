package com.cereveil.child.ml

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.text.Normalizer

private const val TAG = "IndiBertTokenizer"
private const val MAX_LENGTH = 128
private const val PAD_ID = 0L
private const val UNK_ID = 1L
private const val CLS_ID = 2L
private const val SEP_ID = 3L
private const val METASPACE = '\u2581' // ▁

/**
 * Pure Kotlin Unigram tokenizer for indic-bert (ai4bharat/indic-bert).
 *
 * Parses tokenizer.json from HuggingFace Tokenizers format:
 * - Normalizer: NFKD → StripAccents → Lowercase → collapse spaces
 * - PreTokenizer: WhitespaceSplit + Metaspace (prepend ▁)
 * - Model: Unigram with 200K vocab, Viterbi decoding
 * - PostProcessor: [CLS] tokens [SEP], pad to 128
 */
internal class IndiBertTokenizer(context: Context) {

    /** token string → (id, log_prob) */
    private val vocab = mutableMapOf<String, Pair<Int, Float>>()

    /** For reverse lookup if needed */
    private val idToToken = mutableMapOf<Int, String>()

    init {
        val json = context.assets.open("models/fraud-classifier/tokenizer.json")
            .bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val model = root.getJSONObject("model")
        val vocabArray = model.getJSONArray("vocab")

        for (i in 0 until vocabArray.length()) {
            val entry = vocabArray.getJSONArray(i)
            val token = entry.getString(0)
            val score = entry.getDouble(1).toFloat()
            vocab[token] = Pair(i, score)
            idToToken[i] = token
        }
        Log.d(TAG, "Loaded ${vocab.size} vocab entries")
    }

    data class TokenizerOutput(
        val inputIds: LongArray,
        val attentionMask: LongArray,
        val tokenTypeIds: LongArray
    )

    fun encode(text: String): TokenizerOutput {
        // Step 1: Normalize (matching tokenizer.json normalizer sequence)
        val normalized = normalize(text)

        // Step 2: Pre-tokenize (WhitespaceSplit + Metaspace)
        val words = preTokenize(normalized)

        // Step 3: Unigram tokenize each word via Viterbi
        val tokenIds = mutableListOf<Long>()
        for (word in words) {
            tokenIds.addAll(unigramTokenize(word))
        }

        // Step 4: Truncate to max_length - 2 (reserve [CLS] and [SEP])
        val maxTokens = MAX_LENGTH - 2
        val truncated = if (tokenIds.size > maxTokens) tokenIds.subList(0, maxTokens) else tokenIds

        // Step 5: Build final sequences with special tokens
        val inputIds = LongArray(MAX_LENGTH) { PAD_ID }
        val attentionMask = LongArray(MAX_LENGTH) { 0L }
        val tokenTypeIds = LongArray(MAX_LENGTH) { 0L }

        inputIds[0] = CLS_ID
        attentionMask[0] = 1L

        for (i in truncated.indices) {
            inputIds[i + 1] = truncated[i]
            attentionMask[i + 1] = 1L
        }

        inputIds[truncated.size + 1] = SEP_ID
        attentionMask[truncated.size + 1] = 1L

        return TokenizerOutput(inputIds, attentionMask, tokenTypeIds)
    }

    /**
     * Normalize: Replace `` and '' with ", NFKD, strip accents, lowercase, collapse spaces.
     * Matches the normalizer sequence in tokenizer.json.
     */
    private fun normalize(text: String): String {
        var s = text
        s = s.replace("``", "\"").replace("''", "\"")
        // NFKD decomposition
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        // Strip combining marks (accents)
        s = s.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        s = s.lowercase()
        // Second NFKD pass (matching tokenizer.json which has it twice)
        s = Normalizer.normalize(s, Normalizer.Form.NFKD)
        s = s.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        s = s.lowercase()
        // Collapse multiple spaces
        s = s.replace(Regex(" {2,}"), " ")
        return s.trim()
    }

    /**
     * Pre-tokenize: split on whitespace, then prepend ▁ to each piece.
     * This matches WhitespaceSplit + Metaspace(prepend_scheme="always").
     */
    private fun preTokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { "$METASPACE$it" }
    }

    /**
     * Unigram tokenization via Viterbi dynamic programming.
     *
     * Finds the segmentation that maximizes the sum of log-probabilities.
     * Falls back to <unk> for characters not in vocab.
     */
    private fun unigramTokenize(word: String): List<Long> {
        val n = word.length
        if (n == 0) return emptyList()

        // best[i] = (bestScore, bestEnd) for text[0..i)
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestEnd = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        for (end in 1..n) {
            // Try all substrings ending at 'end'
            val maxStart = maxOf(0, end - 64) // limit substring length to 64 chars
            for (start in maxStart until end) {
                val sub = word.substring(start, end)
                val entry = vocab[sub] ?: continue
                val score = bestScore[start] + entry.second
                if (score > bestScore[end]) {
                    bestScore[end] = score
                    bestEnd[end] = start
                }
            }

            // If no vocab entry found ending here, try single character as unk
            if (bestScore[end] == Float.NEGATIVE_INFINITY && end > 0) {
                val unkScore = bestScore[end - 1] + (vocab["<unk>"]?.second ?: -100f)
                if (unkScore > bestScore[end]) {
                    bestScore[end] = unkScore
                    bestEnd[end] = end - 1
                }
            }
        }

        // Backtrack to get the best segmentation
        val segments = mutableListOf<Long>()
        var pos = n
        while (pos > 0) {
            val start = bestEnd[pos]
            if (start < 0) {
                // Should not happen, but fallback to UNK for single char
                segments.add(UNK_ID)
                pos--
            } else {
                val sub = word.substring(start, pos)
                val entry = vocab[sub]
                segments.add(entry?.first?.toLong() ?: UNK_ID)
                pos = start
            }
        }

        segments.reverse()
        return segments
    }
}
