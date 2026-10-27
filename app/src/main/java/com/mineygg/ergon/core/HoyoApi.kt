package com.mineygg.ergon.core

import com.mineygg.ergon.data.Credentials
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

enum class CheckinStatus { SUCCESS, ALREADY, TOKEN_EXPIRED, CAPTCHA, FAILED }

data class CheckinResult(val status: CheckinStatus, val message: String = "")

data class ApiResult(
    val retcode: Int,
    val message: String,
    val data: JSONObject?,
    val http: Int,
    val network: Boolean = false
)

data class RedeemResult(
    val success: Boolean,
    val message: String,
    val alreadyClaimed: Boolean = false,
    val tokenExpired: Boolean = false,
    val networkError: Boolean = false,
    val rateLimited: Boolean = false
)

object HoyoApi {

    private fun Request.Builder.hoyoHeaders(cookie: String): Request.Builder = apply {
        header("Accept", "application/json, text/plain, */*")
        header("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8")
        header("x-rpc-app_version", "2.34.1")
        header("User-Agent", DeviceHeaders.userAgent())
        header("x-rpc-client_type", "5")
        header("x-rpc-platform", "4")
        header("Referer", "https://act.hoyolab.com/")
        header("Origin", "https://act.hoyolab.com")
        header("Cookie", cookie)
    }

    // ── Daily check-in ──────────────────────────────────────────────────────
    suspend fun checkin(game: Game, cr: Credentials): CheckinResult = withContext(Dispatchers.IO) {
        val cfg = game.checkin
        val cookie = if (cfg.useCookieToken && cr.hasLogin) cr.loginCookie() else cr.hoyolabCookie()
        try {
            val body = JSONObject()
                .put("act_id", cfg.actId)
                .put("lang", "en-us")
                .toString()
                .toRequestBody("application/json;charset=UTF-8".toMediaType())
            val builder = Request.Builder().url(cfg.url).hoyoHeaders(cookie).post(body)
            cfg.signGame?.let { builder.header("x-rpc-signgame", it) }

            Http.client.newCall(builder.build()).execute().use { resp ->
                parseCheckin(resp.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            CheckinResult(CheckinStatus.FAILED, e.message ?: "Network error")
        }
    }

    private fun parseCheckin(text: String): CheckinResult {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: return CheckinResult(CheckinStatus.FAILED, "Invalid response")
        val code = json.optInt("retcode", Int.MIN_VALUE)
        val msg = json.optString("message")
        val risk = json.optJSONObject("data")?.optJSONObject("gt_result")?.optBoolean("is_risk", false) ?: false
        return when {
            code == 0 -> CheckinResult(CheckinStatus.SUCCESS)
            code == -5003 -> CheckinResult(CheckinStatus.ALREADY)
            code == -100 -> CheckinResult(CheckinStatus.TOKEN_EXPIRED)
            risk -> CheckinResult(CheckinStatus.CAPTCHA)
            else -> CheckinResult(CheckinStatus.FAILED, "[$code] $msg")
        }
    }

    // ── Account discovery ───────────────────────────────────────────────────
    suspend fun discover(gameBiz: String, cr: Credentials): List<Account> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://sg-public-api.hoyolab.com/binding/api/getUserGameRolesByLtoken?game_biz=$gameBiz")
                .hoyoHeaders(cr.hoyolabCookie())
                .get()
                .build()
            Http.client.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string().orEmpty())
                if (json.optInt("retcode", -1) != 0) {
                    emptyList<Account>()
                } else {
                    val arr = json.getJSONObject("data").getJSONArray("list")
                    List(arr.length()) { i ->
                        val o = arr.getJSONObject(i)
                        Account(o.getString("game_uid"), o.getString("region"), o.optString("nickname"))
                    }
                }
            }
        } catch (e: Exception) {
            emptyList<Account>()
        }
    }

    /** True if these HoYoLAB tokens are currently accepted by the API. */
    suspend fun validateHoyolab(ltoken: String, ltuid: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://sg-public-api.hoyolab.com/binding/api/getUserGameRolesByLtoken?game_biz=hk4e_global")
                .hoyoHeaders("ltoken_v2=$ltoken; ltuid_v2=$ltuid;")
                .get()
                .build()
            Http.client.newCall(req).execute().use { resp ->
                JSONObject(resp.body?.string().orEmpty()).optInt("retcode", -1) == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Generic JSON call with HoYoLAB headers. GET when [body] is null, otherwise POST. */
    suspend fun call(url: String, cookie: String, body: JSONObject? = null): ApiResult = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().url(url).hoyoHeaders(cookie)
            if (body != null) {
                b.post(body.toString().toRequestBody("application/json;charset=UTF-8".toMediaType()))
            } else {
                b.get()
            }
            Http.client.newCall(b.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    ApiResult(Int.MIN_VALUE, "HTTP ${resp.code}: unreadable response", null, resp.code)
                } else {
                    ApiResult(json.optInt("retcode", Int.MIN_VALUE), json.optString("message"), json.optJSONObject("data"), resp.code)
                }
            }
        } catch (e: IOException) {
            ApiResult(Int.MIN_VALUE, e.message ?: "Network error", null, 0, network = true)
        }
    }

    // ── Code redemption ─────────────────────────────────────────────────────
    private val ALREADY_CLAIMED = setOf(-2017, -2018, -2003, -2001, -2011, -2006)
    private val RATE_LIMITED = setOf(-1003, -1016)

    suspend fun redeem(game: Game, code: String, acc: Account, cr: Credentials): RedeemResult =
        withContext(Dispatchers.IO) {
            val cfg = game.redeem ?: return@withContext RedeemResult(false, "Redeem not supported for ${game.title}")
            if (!cr.hasLogin) return@withContext RedeemResult(false, "cookie_token_v2 not set")

            try {
                val url = cfg.redeemUrl.toHttpUrl().newBuilder()
                    .addQueryParameter("uid", acc.uid)
                    .addQueryParameter("region", acc.region)
                    .addQueryParameter("lang", "en")
                    .addQueryParameter("cdkey", code)
                    .addQueryParameter("game_biz", cfg.gameBiz)
                    .addQueryParameter("sLangKey", "en-us")
                    .build()
                val req = Request.Builder().url(url).hoyoHeaders(cr.loginCookie()).get().build()

                Http.client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()

                    if (resp.code == 429 || text.contains("too many requests", ignoreCase = true)) {
                        return@use RedeemResult(false, "Rate limited (HTTP 429)", rateLimited = true)
                    }
                    if (text.startsWith("<")) {
                        return@use RedeemResult(false, "Server returned HTML (rate limit or CDN block)", rateLimited = true)
                    }
                    val json = runCatching { JSONObject(text) }.getOrNull()
                        ?: return@use RedeemResult(false, "Non-JSON response (HTTP ${resp.code})")

                    val retcode = json.optInt("retcode", Int.MIN_VALUE)
                    val message = json.optString("message").ifEmpty { "Unknown error" }

                    if (retcode == 0) {
                        RedeemResult(true, "Redeemed!")
                    } else {
                        RedeemResult(
                            success = false,
                            message = "[$retcode] $message",
                            alreadyClaimed = retcode in ALREADY_CLAIMED,
                            tokenExpired = retcode == -1071,
                            rateLimited = retcode in RATE_LIMITED
                        )
                    }
                }
            } catch (e: IOException) {
                RedeemResult(false, "Request failed: ${e.message}", networkError = true)
            }
        }
}
