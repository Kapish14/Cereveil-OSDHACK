package com.cereveil.child.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp

enum class ModelSensitivity { Lower, Standard, Higher }
enum class ConfidenceBand { Low, Medium, High }

data class ScamClassification(
  val isScam: Boolean,
  val confidence: Float,
  val confidenceBand: ConfidenceBand,
  val label: String,
  val labelId: Int,
)

data class NsfwClassification(
  val isNsfw: Boolean,
  val confidence: Float,
  val confidenceBand: ConfidenceBand,
)

interface ScamTextClassifier : AutoCloseable {
  val isInitialized: Boolean
  fun initialize()
  fun classify(text: String, sensitivity: ModelSensitivity): ScamClassification
}

interface NsfwImageClassifier : AutoCloseable {
  val isInitialized: Boolean
  fun initialize()
  fun classify(bitmap: Bitmap, sensitivity: ModelSensitivity): NsfwClassification
}

/** Process-owned runtime. Classifier sessions close independently and never close this environment. */
object CereveilOnnxRuntime {
  val environment: OrtEnvironment by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    OrtEnvironment.getEnvironment()
  }
}

internal fun confidenceBand(value: Float) = when {
  value >= .80f -> ConfidenceBand.High
  value >= .55f -> ConfidenceBand.Medium
  else -> ConfidenceBand.Low
}

internal fun softmax(logits: FloatArray): FloatArray {
  val maximum = logits.max()
  val exponentials = FloatArray(logits.size) { exp(logits[it] - maximum) }
  val sum = exponentials.sum()
  return FloatArray(exponentials.size) { exponentials[it] / sum }
}

object SafetyModelDecisionPolicy {
  const val VERSION = "enfold-revive-parity-v1"

  fun scamIsPositive(probabilities: FloatArray, sensitivity: ModelSensitivity): Boolean {
    require(probabilities.size >= 8)
    val bestIndex = probabilities.indices.maxBy { probabilities[it] }
    // Enfold has no sensitivity override: winning label ids 2..7 always alert.
    return bestIndex in 2..7
  }

  fun nsfwThreshold(sensitivity: ModelSensitivity): Float = when (sensitivity) {
    ModelSensitivity.Lower -> .60f
    ModelSensitivity.Standard -> .40f
    // Matches Revive's shipped default for its high-sensitivity protection profile.
    ModelSensitivity.Higher -> .10f
  }
}

class OnDeviceScamTextClassifier(context: Context) : ScamTextClassifier {
  private val appContext = context.applicationContext
  private var session: OrtSession? = null
  private var tokenizer: IndiBertTokenizer? = null
  private var labelMap: Map<Int, String> = emptyMap()
  override val isInitialized get() = session != null

  @Synchronized
  override fun initialize() {
    if (session != null) return
    val labels = appContext.assets.open("models/fraud-classifier/label_map.json")
      .bufferedReader().use { JSONObject(it.readText()) }
    labelMap = buildMap {
      for (key in labels.keys()) put(key.toInt(), labels.getString(key))
    }
    tokenizer = IndiBertTokenizer(appContext)
    val bytes = appContext.assets.open("models/fraud-classifier/model_int8.onnx").use { it.readBytes() }
    session = CereveilOnnxRuntime.environment.createSession(
      bytes,
      OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) },
    )
  }

  @Synchronized
  override fun classify(text: String, sensitivity: ModelSensitivity): ScamClassification {
    val activeSession = checkNotNull(session) { "Scam classifier is not initialized" }
    val encoded = checkNotNull(tokenizer).encode(text)
    val shape = longArrayOf(1, encoded.inputIds.size.toLong())
    OnnxTensor.createTensor(CereveilOnnxRuntime.environment, LongBuffer.wrap(encoded.inputIds), shape).use { ids ->
      OnnxTensor.createTensor(CereveilOnnxRuntime.environment, LongBuffer.wrap(encoded.attentionMask), shape).use { mask ->
        OnnxTensor.createTensor(CereveilOnnxRuntime.environment, LongBuffer.wrap(encoded.tokenTypeIds), shape).use { types ->
          activeSession.run(mapOf(
            "input_ids" to ids,
            "attention_mask" to mask,
            "token_type_ids" to types,
          )).use { result ->
            @Suppress("UNCHECKED_CAST")
            val probabilities = softmax((result[0].value as Array<FloatArray>)[0])
            val bestIndex = probabilities.indices.maxBy { probabilities[it] }
            val positive = SafetyModelDecisionPolicy.scamIsPositive(probabilities, sensitivity)
            val confidence = probabilities[bestIndex]
            return ScamClassification(
              isScam = positive,
              confidence = confidence,
              confidenceBand = confidenceBand(confidence),
              label = labelMap[bestIndex] ?: "unknown",
              labelId = bestIndex,
            )
          }
        }
      }
    }
  }

  @Synchronized
  override fun close() {
    session?.close()
    session = null
    tokenizer = null
    labelMap = emptyMap()
  }
}

class OnDeviceNsfwImageClassifier(context: Context) : NsfwImageClassifier {
  private val appContext = context.applicationContext
  private var session: OrtSession? = null
  private var inputName: String? = null
  override val isInitialized get() = session != null

  @Synchronized
  override fun initialize() {
    if (session != null) return
    val bytes = appContext.assets.open("models/nsfw/model_int8.onnx").use { it.readBytes() }
    session = CereveilOnnxRuntime.environment.createSession(
      bytes,
      OrtSession.SessionOptions().apply { setIntraOpNumThreads(3) },
    ).also { inputName = it.inputNames.first() }
  }

  @Synchronized
  override fun classify(bitmap: Bitmap, sensitivity: ModelSensitivity): NsfwClassification {
    val activeSession = checkNotNull(session) { "NSFW classifier is not initialized" }
    val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, false)
    val pixels = IntArray(PIXEL_COUNT)
    resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
    val direct = ByteBuffer.allocateDirect(3 * PIXEL_COUNT * 4)
      .order(ByteOrder.nativeOrder()).asFloatBuffer()
    writeNormalizedChannels(pixels, direct)
    if (resized !== bitmap) resized.recycle()
    OnnxTensor.createTensor(
      CereveilOnnxRuntime.environment,
      direct,
      longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
    ).use { tensor ->
      activeSession.run(mapOf(checkNotNull(inputName) to tensor)).use { result ->
        @Suppress("UNCHECKED_CAST")
        val probabilities = softmax((result[0].value as Array<FloatArray>)[0])
        val nsfwScore = probabilities[0]
        val threshold = SafetyModelDecisionPolicy.nsfwThreshold(sensitivity)
        return NsfwClassification(nsfwScore >= threshold, nsfwScore, confidenceBand(nsfwScore))
      }
    }
  }

  private fun writeNormalizedChannels(pixels: IntArray, output: FloatBuffer) {
    val values = FloatArray(3 * PIXEL_COUNT)
    for (index in pixels.indices) {
      val pixel = pixels[index]
      values[index] = (((pixel shr 16) and 0xff) / 127.5f) - 1f
      values[PIXEL_COUNT + index] = (((pixel shr 8) and 0xff) / 127.5f) - 1f
      values[2 * PIXEL_COUNT + index] = ((pixel and 0xff) / 127.5f) - 1f
    }
    output.put(values).rewind()
  }

  @Synchronized
  override fun close() {
    session?.close()
    session = null
    inputName = null
  }

  private companion object {
    const val INPUT_SIZE = 384
    const val PIXEL_COUNT = INPUT_SIZE * INPUT_SIZE
  }
}
