package com.cereveil.child.ml

import android.content.Context
import java.text.Normalizer
import org.json.JSONObject

internal class IndicBertTokenizer(context: Context) {
  private val vocabulary = mutableMapOf<String, Pair<Int, Float>>()

  init {
    val root = context.assets.open("models/fraud-classifier/tokenizer.json")
      .bufferedReader().use { JSONObject(it.readText()) }
    val values = root.getJSONObject("model").getJSONArray("vocab")
    for (index in 0 until values.length()) {
      val entry = values.getJSONArray(index)
      vocabulary[entry.getString(0)] = index to entry.getDouble(1).toFloat()
    }
  }

  data class Output(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val tokenTypeIds: LongArray,
  )

  fun encode(text: String): Output {
    val tokenIds = normalize(text).split(Regex("\\s+"))
      .filter(String::isNotEmpty)
      .flatMap { unigramTokenize("\u2581$it") }
      .take(MAX_LENGTH - 2)
    val ids = LongArray(MAX_LENGTH)
    val mask = LongArray(MAX_LENGTH)
    ids[0] = CLS_ID
    mask[0] = 1
    tokenIds.forEachIndexed { index, id -> ids[index + 1] = id; mask[index + 1] = 1 }
    ids[tokenIds.size + 1] = SEP_ID
    mask[tokenIds.size + 1] = 1
    return Output(ids, mask, LongArray(MAX_LENGTH))
  }

  private fun normalize(value: String): String {
    var normalized = value.replace("``", "\"").replace("''", "\"")
    repeat(2) {
      normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
    }
    return normalized.replace(Regex(" {2,}"), " ").trim()
  }

  private fun unigramTokenize(word: String): List<Long> {
    if (word.isEmpty()) return emptyList()
    val unknownScore = vocabulary["<unk>"]?.second ?: -100f
    val scores = FloatArray(word.length + 1) { Float.NEGATIVE_INFINITY }.also { it[0] = 0f }
    val starts = IntArray(word.length + 1) { -1 }
    for (end in 1..word.length) {
      for (start in maxOf(0, end - 64) until end) {
        val entry = vocabulary[word.substring(start, end)] ?: continue
        val score = scores[start] + entry.second
        if (score > scores[end]) { scores[end] = score; starts[end] = start }
      }
      if (starts[end] < 0) {
        scores[end] = scores[end - 1] + unknownScore
        starts[end] = end - 1
      }
    }
    val result = mutableListOf<Long>()
    var position = word.length
    while (position > 0) {
      val start = starts[position].coerceAtLeast(position - 1)
      result += vocabulary[word.substring(start, position)]?.first?.toLong() ?: UNK_ID
      position = start
    }
    return result.asReversed()
  }

  private companion object {
    const val MAX_LENGTH = 128
    const val UNK_ID = 1L
    const val CLS_ID = 2L
    const val SEP_ID = 3L
  }
}
