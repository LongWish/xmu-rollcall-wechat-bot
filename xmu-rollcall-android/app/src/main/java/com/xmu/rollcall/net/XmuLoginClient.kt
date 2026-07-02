package com.xmu.rollcall.net

import com.google.gson.Gson
import com.xmu.rollcall.utils.EncryptUtils
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

private class Ipv4FirstDns(
    private val ipv4FirstHosts: Set<String> = setOf("lnt.xmu.edu.cn")
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val normalizedHost = hostname.lowercase(Locale.US)
        if (normalizedHost !in ipv4FirstHosts) {
            return addresses
        }
        return addresses.sortedBy { it !is Inet4Address }
    }
}

class XmuLoginClient {

    private val storedCookies = mutableListOf<Cookie>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(storedCookies) {
                cookies.forEach { newCookie ->
                    storedCookies.removeAll {
                        it.name == newCookie.name && it.domain == newCookie.domain && it.path == newCookie.path
                    }
                    if (newCookie.expiresAt > System.currentTimeMillis()) {
                        storedCookies.add(newCookie)
                    }
                }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            synchronized(storedCookies) {
                val now = System.currentTimeMillis()
                storedCookies.removeAll { it.expiresAt <= now }
                return storedCookies.filter { it.matches(url) }
            }
        }
    }

    private fun storeCookie(cookie: Cookie) {
        synchronized(storedCookies) {
            storedCookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) {
                storedCookies.add(cookie)
            }
        }
    }

    private fun parsePersistedCookie(url: HttpUrl, cookieString: String): List<Cookie> {
        val trimmed = cookieString.trim()
        if (trimmed.isEmpty()) return emptyList()

        if (looksLikeCookieHeader(trimmed)) {
            return trimmed.split(";").mapNotNull { part ->
                Cookie.parse(url, part.trim())
            }
        }

        return listOfNotNull(Cookie.parse(url, trimmed))
    }

    private fun looksLikeCookieHeader(cookieString: String): Boolean {
        val parts = cookieString.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return false

        val setCookieAttributes = setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")
        return parts.drop(1).any { part ->
            val key = part.substringBefore("=", part).lowercase()
            part.contains("=") && key !in setCookieAttributes
        }
    }

    private fun cookieStorageMap(): Map<String, List<String>> {
        synchronized(storedCookies) {
            val now = System.currentTimeMillis()
            storedCookies.removeAll { it.expiresAt <= now }
            return storedCookies
                .groupBy { cookie -> "https://${cookie.domain.removePrefix(".")}" }
                .mapValues { entry -> entry.value.map { it.toString() } }
        }
    }

    private fun restoreCookieStorage(serializedCookies: Map<String, List<String>>) {
        synchronized(storedCookies) {
            storedCookies.clear()
        }

        for ((domain, cookies) in serializedCookies) {
            val url = domain.toHttpUrlOrNull() ?: "https://${domain.removePrefix(".")}".toHttpUrlOrNull() ?: continue
            for (cookieString in cookies) {
                val parsedCookies = parsePersistedCookie(url, cookieString)
                parsedCookies.forEach { cookie ->
                    storeCookie(cookie)
                }
            }
        }
    }

    val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .dns(Ipv4FirstDns())
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
        return cookieStorageMap()
    }

    /**
     * Restore cookies from persistent storage.
     */
    fun restoreCookies(serializedCookies: Map<String, List<String>>) {
        restoreCookieStorage(serializedCookies)
    }

    /**
     * Clears all session cookies
     */
    fun clearCookies() {
        synchronized(storedCookies) {
            storedCookies.clear()
        }
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
