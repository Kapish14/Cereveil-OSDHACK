package com.cereveil.child.enrollment

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class AndroidChildDeviceKeyStore : ChildDeviceKeyStore {
  private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

  override fun createKeyAlias(): String {
    val alias = "cereveil-child-${UUID.randomUUID()}"
    val spec = KeyGenParameterSpec.Builder(
      alias,
      KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
    )
      .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
      .setDigests(KeyProperties.DIGEST_SHA256)
      .build()
    KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
      initialize(spec)
      generateKeyPair()
    }
    return alias
  }

  override fun publicKeySpki(alias: String): String =
    base64Url(keyStore.getCertificate(alias).publicKey.encoded)

  override fun sign(alias: String, message: String): String {
    val privateKey = keyStore.getKey(alias, null) as java.security.PrivateKey
    val der = Signature.getInstance("SHA256withECDSA").run {
      initSign(privateKey)
      update(message.toByteArray(Charsets.UTF_8))
      sign()
    }
    return base64Url(derEcdsaToRaw(der))
  }

  private fun derEcdsaToRaw(der: ByteArray): ByteArray {
    require(der.size >= 8 && der[0] == 0x30.toByte())
    var index = 1
    val sequenceLength = readLength(der, index)
    index += sequenceLength.second
    require(der[index++] == 0x02.toByte())
    val rLength = readLength(der, index)
    index += rLength.second
    val r = der.copyOfRange(index, index + rLength.first)
    index += rLength.first
    require(der[index++] == 0x02.toByte())
    val sLength = readLength(der, index)
    index += sLength.second
    val s = der.copyOfRange(index, index + sLength.first)
    return fixed32(r) + fixed32(s)
  }

  private fun readLength(bytes: ByteArray, index: Int): Pair<Int, Int> {
    val first = bytes[index].toInt() and 0xff
    if (first < 128) return first to 1
    val count = first and 0x7f
    var value = 0
    repeat(count) { value = (value shl 8) or (bytes[index + 1 + it].toInt() and 0xff) }
    return value to (count + 1)
  }

  private fun fixed32(value: ByteArray): ByteArray {
    val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
    require(unsigned.size <= 32)
    return ByteArray(32 - unsigned.size) + unsigned
  }

  private fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
