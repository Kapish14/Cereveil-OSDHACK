package com.cereveil.child.protection

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

class ChildAccessGrantStore(context: Context) {
  private val preferences = context.getSharedPreferences("child_access_grants", Context.MODE_PRIVATE)

  fun active(now: Instant): List<LocalAccessGrant> {
    val grants = preferences.getString("grants", null)?.let { raw ->
      runCatching {
        Json.parseToJsonElement(raw).jsonArray.map { item ->
          val value = item.jsonObject
          LocalAccessGrant(
            packageName = value["packageName"]!!.jsonPrimitive.content,
            startsAt = Instant.ofEpochMilli(value["startsAt"]!!.jsonPrimitive.content.toLong()),
            expiresAt = Instant.ofEpochMilli(value["expiresAt"]!!.jsonPrimitive.content.toLong()),
          )
        }
      }.getOrDefault(emptyList())
    }.orEmpty()
    val active = grants.filter { now.isBefore(it.expiresAt) }
    if (active.size != grants.size) replace(active)
    return active
  }

  fun replace(grants: List<LocalAccessGrant>) {
    val value = buildJsonArray {
      grants.forEach { grant -> add(buildJsonObject {
        put("packageName", grant.packageName)
        put("startsAt", grant.startsAt.toEpochMilli())
        put("expiresAt", grant.expiresAt.toEpochMilli())
      }) }
    }
    preferences.edit().putString("grants", value.toString()).commit()
  }

  fun clear() {
    preferences.edit().clear().commit()
  }
}
