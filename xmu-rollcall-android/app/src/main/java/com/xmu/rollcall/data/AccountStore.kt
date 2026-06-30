package com.xmu.rollcall.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Account(
    val username: String,
    val password: String,
    val name: String,
    val serializedCookies: Map<String, List<String>> = emptyMap()
)

class AccountStore(private val sharedPreferences: SharedPreferences) {
    private val gson = Gson()

    companion object {
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE_USERNAME = "active_username"

        /**
         * Factory method to create an EncryptedSharedPreferences based instance on Android.
         */
        fun createEncrypted(context: Context): AccountStore {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val sharedPreferences = EncryptedSharedPreferences.create(
                context,
                "xmu_rollcall_secured_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            return AccountStore(sharedPreferences)
        }
    }

    /**
     * Get all stored accounts.
     */
    fun getAccounts(): List<Account> {
        val json = sharedPreferences.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        val type = object : TypeToken<List<Account>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Saves list of accounts.
     */
    private fun saveAccounts(accounts: List<Account>) {
        val json = gson.toJson(accounts)
        sharedPreferences.edit().putString(KEY_ACCOUNTS, json).apply()
    }

    /**
     * Add or update an account.
     */
    fun addOrUpdateAccount(account: Account) {
        val currentList = getAccounts().toMutableList()
        val index = currentList.indexOfFirst { it.username == account.username }
        if (index >= 0) {
            // Update, preserving cookies if the new account doesn't specify them
            val oldAccount = currentList[index]
            val cookies = if (account.serializedCookies.isNotEmpty()) account.serializedCookies else oldAccount.serializedCookies
            currentList[index] = account.copy(serializedCookies = cookies)
        } else {
            currentList.add(account)
        }
        saveAccounts(currentList)

        // If no active account is set, make this the active one
        if (getActiveUsername() == null) {
            setActiveUsername(account.username)
        }
    }

    /**
     * Delete an account by username.
     */
    fun deleteAccount(username: String) {
        val currentList = getAccounts().filter { it.username != username }
        saveAccounts(currentList)

        // If deleted account was active, unset active
        if (getActiveUsername() == username) {
            val newActive = currentList.firstOrNull()?.username
            setActiveUsername(newActive)
        }
    }

    /**
     * Update only the cookies for a specific account.
     */
    fun updateCookies(username: String, cookies: Map<String, List<String>>) {
        val currentList = getAccounts().toMutableList()
        val index = currentList.indexOfFirst { it.username == username }
        if (index >= 0) {
            val oldAccount = currentList[index]
            currentList[index] = oldAccount.copy(serializedCookies = cookies)
            saveAccounts(currentList)
        }
    }

    /**
     * Get active username.
     */
    fun getActiveUsername(): String? {
        return sharedPreferences.getString(KEY_ACTIVE_USERNAME, null)
    }

    /**
     * Set active username.
     */
    fun setActiveUsername(username: String?) {
        sharedPreferences.edit().putString(KEY_ACTIVE_USERNAME, username).apply()
    }

    /**
     * Get active account details.
     */
    fun getActiveAccount(): Account? {
        val activeUsername = getActiveUsername() ?: return null
        return getAccounts().firstOrNull { it.username == activeUsername }
    }
}
