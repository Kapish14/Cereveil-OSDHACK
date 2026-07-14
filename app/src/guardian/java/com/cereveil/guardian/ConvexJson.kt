package com.cereveil.guardian

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonElement.objectOrNull(): JsonObject? =
  if (this is JsonObject) this else null

internal fun JsonElement.arrayOrEmpty(): JsonArray =
  if (this is JsonArray) this else JsonArray(emptyList())

internal fun JsonObject.objectOrNull(name: String): JsonObject? =
  get(name)?.objectOrNull()

internal fun JsonObject.arrayOrEmpty(name: String): JsonArray =
  get(name)?.arrayOrEmpty() ?: JsonArray(emptyList())

internal fun JsonObject.string(name: String): String =
  get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()

internal fun JsonObject.stringOrNull(name: String): String? =
  get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.boolean(name: String): Boolean =
  get(name)?.jsonPrimitive?.booleanOrNull ?: false

internal fun JsonObject.long(name: String): Long =
  get(name)?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L

internal fun JsonObject.double(name: String): Double =
  get(name)?.jsonPrimitive?.doubleOrNull ?: 0.0
