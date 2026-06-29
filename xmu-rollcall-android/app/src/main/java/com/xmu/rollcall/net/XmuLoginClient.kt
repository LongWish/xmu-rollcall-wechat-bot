package com.xmu.rollcall.net

import com.google.gson.Gson
import com.xmu.rollcall.utils.EncryptUtils
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.util.concurrent.TimeUnit

class XmuLoginClient {

    private val cookieManager = CookieManager().apply {
        setCookiePolicy(CookiePolicy.ACCEPT_ALL)
    }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // Save cookies to CookieManager
            val cookieHeaders = cookies.map { it.toString() }
            val headers = mapOf("Set-Cookie" to cookieHeaders)
            try {
                cookieManager.put(url.toUri(), headers)
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            try {
                val cookieMap = cookieManager.get(url.toUri(), emptyMap())
                val cookieHeaders = cookieMap["Cookie"] ?: cookieMap["cookie"] ?: return emptyList()
                return cookieHeaders.flatMap { header ->
                    header.split(";").mapNotNull {
                        Cookie.parse(url, it.trim())
                    }
                }
            } catch (e: IOException) {
                return emptyList()
            }
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
        private const val AUTH_URL = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth"
        private const val TOKEN_URL = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/token"
        private const val LOGIN_ACCESS_TOKEN_URL = "https://lnt.xmu.edu.cn/api/login?login=access_token"
    }

    /**
     * Get all cookies as strings. Used to persist session.
     */
    fun getSerializedCookies(): Map<String, List<String>> {
        val cookiesMap = mutableMapOf<String, List<String>>()
        val domains = listOf("https://c-identity.xmu.edu.cn", "https://lnt.xmu.edu.cn")
        for (domain in domains) {
            val uri = java.net.URI.create(domain)
            val headers = cookieManager.get(uri, emptyMap())
            val cookieList = headers["Cookie"] ?: headers["cookie"]
            if (cookieList != null) {
                cookiesMap[domain] = cookieList
            }
        }
        return cookiesMap
    }

    /**
     * Restore cookies from persistent storage.
     */
    fun restoreCookies(serializedCookies: Map<String, List<String>>) {
        for ((domain, cookies) in serializedCookies) {
            val uri = java.net.URI.create(domain)
            cookieManager.put(uri, mapOf("Set-Cookie" to cookies))
        }
    }

    /**
     * Clears all session cookies
     */
    fun clearCookies() {
        cookieManager.cookieStore.removeAll()
    }

    /**
     * Logs into TronClass and authenticates the session.
     * Throws an exception if login fails.
     */
    fun loginTronClass(username: String, password: String): Boolean {
        try {
            // Step 1: GET to auth endpoint
            val step1Url = "$AUTH_URL?scope=openid&response_type=code&client_id=TronClassH5&redirect_uri=https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true"
            val req1 = Request.Builder()
                .url(step1Url)
                .header("User-Agent", USER_AGENT)
                .build()
            
            val resp1 = okHttpClient.newCall(req1).execute()
            val loc1 = resp1.header("Location") ?: throw Exception("Step 1 did not redirect")
            resp1.close()

            // Step 2: GET the redirect location
            val req2 = Request.Builder()
                .url(loc1)
                .header("User-Agent", USER_AGENT)
                .build()
            val resp2 = okHttpClient.newCall(req2).execute()
            val loc2 = resp2.header("Location") ?: throw Exception("Step 2 did not redirect")
            resp2.close()

            // Step 3: GET the actual login page containing the salt and execution
            val req3 = Request.Builder()
                .url(loc2)
                .header("User-Agent", USER_AGENT)
                .build()
            val resp3 = okHttpClient.newCall(req3).execute()
            val html = resp3.body?.string() ?: throw Exception("Step 3 returned empty body")
            resp3.close()

            // Extract salt and execution from HTML page
            val saltRegex = """id="pwdEncryptSalt"\s+value="([^"]+)"""".toRegex()
            val executionRegex = """name="execution"\s+value="([^"]+)"""".toRegex()

            val salt = saltRegex.find(html)?.groupValues?.get(1) ?: throw Exception("Failed to parse pwdEncryptSalt")
            val execution = executionRegex.find(html)?.groupValues?.get(1) ?: throw Exception("Failed to parse execution")

            // Encrypt the password using AES
            val encryptedPassword = EncryptUtils.encryptPassword(password, salt)

            // Step 4: POST the login form
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", encryptedPassword)
                .add("captcha", "")
                .add("_eventId", "submit")
                .add("cllt", "userNameLogin")
                .add("dllt", "generalLogin")
                .add("lt", "")
                .add("execution", execution)
                .build()

            val req4 = Request.Builder()
                .url(loc2) // POST to same login page URL
                .post(formBody)
                .header("User-Agent", USER_AGENT)
                .build()
            
            val resp4 = okHttpClient.newCall(req4).execute()
            val loc4 = resp4.header("Location") ?: throw Exception("Step 4 login failed (no redirect, check credentials)")
            resp4.close()

            // Step 5: GET the redirect location to callback
            val req5 = Request.Builder()
                .url(loc4)
                .header("User-Agent", USER_AGENT)
                .build()
            val resp5 = okHttpClient.newCall(req5).execute()
            val callbackUrl = resp5.header("Location") ?: throw Exception("Step 5 did not redirect to callback")
            resp5.close()

            // Extract authorization 'code' from callback URL
            val codeRegex = """[?&]code=([^&]+)""".toRegex()
            val code = codeRegex.find(callbackUrl)?.groupValues?.get(1) ?: throw Exception("Failed to extract authorization code")

            // Step 6: POST to token endpoint to fetch access_token
            val tokenFormBody = FormBody.Builder()
                .add("client_id", "TronClassH5")
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true")
                .add("scope", "openid")
                .build()

            val req6 = Request.Builder()
                .url(TOKEN_URL)
                .post(tokenFormBody)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp6 = okHttpClient.newCall(req6).execute()
            val jsonResponse = resp6.body?.string() ?: throw Exception("Step 6 token endpoint returned empty body")
            resp6.close()

            val tokenMap = gson.fromJson(jsonResponse, Map::class.java)
            val accessToken = tokenMap["access_token"] as? String ?: throw Exception("Failed to obtain access token from response")

            // Step 7: POST the access_token to TronClass login API
            val payload = mapOf(
                "access_token" to accessToken,
                "org_id" to 1
            )
            val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())

            val req7 = Request.Builder()
                .url(LOGIN_ACCESS_TOKEN_URL)
                .post(requestBody)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp7 = okHttpClient.newCall(req7).execute()
            val success = resp7.code == 200
            resp7.close()

            return success
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
