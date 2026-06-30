package com.xmu.rollcall.service

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.xmu.rollcall.net.XmuLoginClient
import com.xmu.rollcall.utils.LocationSolver
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

// Data Models
data class RollcallRecord(
    val course_title: String,
    val created_by_name: String,
    val department_name: String,
    val is_expired: Boolean,
    val is_number: Boolean,
    val is_radar: Boolean,
    val rollcall_id: Int,
    val rollcall_status: String,
    val scored: Boolean,
    val status: String
) {
    val isAnswered: Boolean
        get() = status == "on_call_fine"

    val isActive: Boolean
        get() = !is_expired && !isAnswered

    val typeLabel: String
        get() = when {
            is_radar -> "雷达签到"
            is_number -> "数字签到"
            else -> "二维码签到"
        }
}

data class AnswerOutcome(
    val rollcall: RollcallRecord,
    val action: String,
    val success: Boolean,
    val message: String,
    val numberCode: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val responseStatus: Int? = null
)

data class AnswerBatchResult(
    val accountUsername: String,
    val queriedAt: Date,
    val rollcalls: List<RollcallRecord>,
    val outcomes: List<AnswerOutcome>
)

class RollcallService(
    private val loginClient: XmuLoginClient,
    private val username: String
) {
    private val gson = Gson()

    companion object {
        private const val BASE_URL = "https://lnt.xmu.edu.cn"
        private const val PROFILE_URL = "$BASE_URL/api/profile"
        private const val ROLLCALLS_URL = "$BASE_URL/api/radar/rollcalls"

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to "https://ids.xmu.edu.cn/authserver/login"
        )
    }

    /**
     * Recursively searches for the 'number_code' field inside JSON payload.
     */
    private fun findNumberCode(element: JsonElement, depth: Int = 0, maxDepth: Int = 10): String? {
        if (depth > maxDepth) return null
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.has("number_code")) {
                val codeElement = obj.get("number_code")
                if (!codeElement.isJsonNull) {
                    return codeElement.asString
                }
            }
            for (entry in obj.entrySet()) {
                val nestedCode = findNumberCode(entry.value, depth + 1, maxDepth)
                if (nestedCode != null) return nestedCode
            }
        } else if (element.isJsonArray) {
            val array = element.asJsonArray
            for (item in array) {
                val nestedCode = findNumberCode(item, depth + 1, maxDepth)
                if (nestedCode != null) return nestedCode
            }
        }
        return null
    }

    /**
     * Fetch student profile.
     */
    fun fetchProfile(): Map<String, Any>? {
        val requestBuilder = Request.Builder().url(PROFILE_URL)
        DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        loginClient.okHttpClient.newCall(request).execute().use { response ->
            if (response.code != 200) return null
            val bodyString = response.body?.string() ?: return null
            return gson.fromJson(bodyString, Map::class.java) as? Map<String, Any>
        }
    }

    /**
     * Fetch active or past rollcalls from TronClass.
     */
    fun fetchRollcalls(): List<RollcallRecord> {
        val requestBuilder = Request.Builder().url(ROLLCALLS_URL)
        DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        loginClient.okHttpClient.newCall(request).execute().use { response ->
            if (response.code != 200) {
                throw Exception("查询签到失败，HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw Exception("查询签到返回空数据")
            val payload = gson.fromJson(bodyString, JsonObject::class.java)
            val rollcallsArray = payload.getAsJsonArray("rollcalls") ?: return emptyList()
            
            return rollcallsArray.map { element ->
                gson.fromJson(element, RollcallRecord::class.java)
            }
        }
    }

    /**
     * Inspect rollcalls without submitting any answers.
     */
    fun inspectActiveRollcalls(): AnswerBatchResult {
        val rollcalls = fetchRollcalls()
        val outcomes = rollcalls.map { rollcall ->
            when {
                rollcall.is_expired -> AnswerOutcome(rollcall, "expired", false, "签到已过期。")
                rollcall.isAnswered -> AnswerOutcome(rollcall, "already_answered", true, "签到已完成。")
                rollcall.is_number -> {
                    val code = fetchNumberCode(rollcall)
                    if (code != null) {
                        AnswerOutcome(rollcall, "detected", false, "检测到数字签到码：$code", numberCode = code)
                    } else {
                        AnswerOutcome(rollcall, "detected", false, "检测到数字签到，但未获取到签到码。")
                    }
                }
                rollcall.is_radar -> AnswerOutcome(rollcall, "detected", false, "检测到雷达签到，需一键自动应答。")
                else -> AnswerOutcome(rollcall, "detected", false, "二维码或暂不支持的签到类型。")
            }
        }
        return AnswerBatchResult(username, Date(), rollcalls, outcomes)
    }

    /**
     * Answers all active rollcalls.
     */
    fun answerActiveRollcalls(): AnswerBatchResult {
        val rollcalls = fetchRollcalls()
        val outcomes = rollcalls.map { rollcall -> answerRollcall(rollcall) }
        return AnswerBatchResult(username, Date(), rollcalls, outcomes)
    }

    fun answerRollcall(rollcall: RollcallRecord): AnswerOutcome {
        if (rollcall.is_expired) {
            return AnswerOutcome(rollcall, "expired", false, "该签到已过期。")
        }
        if (rollcall.isAnswered) {
            return AnswerOutcome(rollcall, "already_answered", true, "该签到已完成。")
        }
        if (rollcall.is_radar) {
            return answerRadarRollcall(rollcall)
        }
        if (rollcall.is_number && rollcall.status == "absent") {
            return answerNumberRollcall(rollcall)
        }
        if (rollcall.is_number) {
            return AnswerOutcome(rollcall, "skipped", false, "当前状态为 ${rollcall.status}，未执行数字签到。")
        }
        return AnswerOutcome(rollcall, "unsupported", false, "二维码签到暂不支持自动应答。")
    }

    private fun fetchNumberCode(rollcall: RollcallRecord): String? {
        val codeUrl = "$BASE_URL/api/rollcall/${rollcall.rollcall_id}/student_rollcalls"
        val requestBuilder = Request.Builder().url(codeUrl)
        DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        try {
            loginClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.code != 200) return null
                val bodyString = response.body?.string() ?: return null
                val jsonElement = gson.fromJson(bodyString, JsonElement::class.java)
                return findNumberCode(jsonElement)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun answerNumberRollcall(rollcall: RollcallRecord): AnswerOutcome {
        val numberCode = fetchNumberCode(rollcall)
            ?: return AnswerOutcome(rollcall, "failed", false, "获取签到码失败，响应中没有 number_code。")

        val answerUrl = "$BASE_URL/api/rollcall/${rollcall.rollcall_id}/answer_number_rollcall"
        val payload = mapOf(
            "deviceId" to UUID.randomUUID().toString(),
            "numberCode" to numberCode
        )
        val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(answerUrl).put(requestBody)
        DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        return try {
            loginClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    if (confirmRollcallAnswered(rollcall.rollcall_id)) {
                        AnswerOutcome(rollcall, "answered", true, "数字签到成功。", numberCode = numberCode, responseStatus = 200)
                    } else {
                        AnswerOutcome(
                            rollcall,
                            "pending_confirmation",
                            false,
                            "提交接口返回 200，但复查未确认完成，请手动核对。",
                            numberCode = numberCode,
                            responseStatus = 200
                        )
                    }
                } else {
                    AnswerOutcome(rollcall, "failed", false, "提交数字签到失败，HTTP ${response.code}", numberCode = numberCode, responseStatus = response.code)
                }
            }
        } catch (e: Exception) {
            AnswerOutcome(rollcall, "failed", false, "提交数字签到错误：${e.message}", numberCode = numberCode)
        }
    }

    private fun answerRadarRollcall(rollcall: RollcallRecord): AnswerOutcome {
        val url = "$BASE_URL/api/rollcall/${rollcall.rollcall_id}/answer"
        val probePoints = listOf(
            Pair(24.3, 118.0),
            Pair(24.6, 118.2)
        )

        val probeResults = mutableListOf<Triple<Double, Double, Double>>() // Lat, Lon, Distance
        var lastStatus = 0

        for ((lat, lon) in probePoints) {
            val payload = buildRadarPayload(lat, lon)
            val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder().url(url).put(requestBody)
            DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
            val request = requestBuilder.build()

            try {
                loginClient.okHttpClient.newCall(request).execute().use { response ->
                    lastStatus = response.code
                    if (response.code == 200) {
                        return if (confirmRollcallAnswered(rollcall.rollcall_id)) {
                            AnswerOutcome(rollcall, "answered", true, "雷达签到成功（探测点直达）。", latitude = lat, longitude = lon, responseStatus = 200)
                        } else {
                            AnswerOutcome(rollcall, "pending_confirmation", false, "提交接口返回 200，但复查未确认完成，请手动核对。", latitude = lat, longitude = lon, responseStatus = 200)
                        }
                    }
                    val bodyString = response.body?.string() ?: ""
                    val resultObj = gson.fromJson(bodyString, JsonObject::class.java)
                    val distance = resultObj?.get("distance")?.asDouble
                    if (distance != null) {
                        probeResults.add(Triple(lat, lon, distance))
                    }
                }
            } catch (e: Exception) {
                // Ignore probe errors and continue
            }
        }

        if (probeResults.size < 2) {
            return AnswerOutcome(rollcall, "failed", false, "雷达签到失败，服务端未返回足够的距离信息。", responseStatus = lastStatus)
        }

        val (p1, p2) = probeResults
        val solvedPoints = LocationSolver.solveTwoPoints(
            p1.first, p1.second,
            p2.first, p2.second,
            p1.third, p2.third
        )

        if (solvedPoints.isNullOrEmpty()) {
            return AnswerOutcome(rollcall, "failed", false, "雷达签到失败，无法求解定位坐标。", responseStatus = lastStatus)
        }

        for ((lat, lon) in solvedPoints) {
            val payload = buildRadarPayload(lat, lon)
            val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            val requestBuilder = Request.Builder().url(url).put(requestBody)
            DEFAULT_HEADERS.forEach { (key, value) -> requestBuilder.header(key, value) }
            val request = requestBuilder.build()

            try {
                loginClient.okHttpClient.newCall(request).execute().use { response ->
                    lastStatus = response.code
                    if (response.code == 200) {
                        return if (confirmRollcallAnswered(rollcall.rollcall_id)) {
                            AnswerOutcome(rollcall, "answered", true, "雷达定位签到成功。", latitude = lat, longitude = lon, responseStatus = 200)
                        } else {
                            AnswerOutcome(rollcall, "pending_confirmation", false, "提交接口返回 200，但复查未确认完成，请手动核对。", latitude = lat, longitude = lon, responseStatus = 200)
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next solved coordinate
            }
        }

        return AnswerOutcome(rollcall, "failed", false, "雷达签到失败，坐标提交未获通过，HTTP $lastStatus", responseStatus = lastStatus)
    }

    private fun confirmRollcallAnswered(rollcallId: Int): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) {
                Thread.sleep(800L)
            }
            val latest = try {
                fetchRollcalls().firstOrNull { it.rollcall_id == rollcallId }
            } catch (e: Exception) {
                null
            }
            if (latest?.isAnswered == true) {
                return true
            }
        }
        return false
    }

    private fun buildRadarPayload(latitude: Double, longitude: Double): Map<String, Any?> {
        return mapOf(
            "accuracy" to 35,
            "altitude" to 0,
            "altitudeAccuracy" to null,
            "deviceId" to UUID.randomUUID().toString(),
            "heading" to null,
            "latitude" to latitude,
            "longitude" to longitude,
            "speed" to null
        )
    }
}
