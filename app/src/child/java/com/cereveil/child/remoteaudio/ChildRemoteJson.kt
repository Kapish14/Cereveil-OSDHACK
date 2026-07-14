package com.cereveil.child.remoteaudio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonElement.remoteObjectOrNull(): JsonObject? = this as? JsonObject
internal fun JsonObject.remoteObjectOrNull(name: String): JsonObject? = get(name)?.remoteObjectOrNull()
internal fun JsonObject.remoteArray(name: String): JsonArray = get(name) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.remoteString(name: String): String =
  get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()
internal fun JsonObject.remoteStringOrNull(name: String): String? =
  get(name)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
internal fun JsonObject.remoteLong(name: String): Long = get(name)?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
