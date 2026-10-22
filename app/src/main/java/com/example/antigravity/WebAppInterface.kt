package com.example.antigravity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

class WebAppInterface(private val activity: Activity) {
    companion object {
        private const val TAG = "WebAppInterface"
        private const val PREFS_NAME = "antigravity_storage"
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var pendingDeepLink: String? = null

    fun setPendingDeepLink(url: String) {
        pendingDeepLink = url
    }

    @JavascriptInterface
    fun getStoredDeepLink(): String? {
        val link = pendingDeepLink
        pendingDeepLink = null
        return link
    }

    @JavascriptInterface
    fun getStorageItems(): String {
        val map = prefs.all
        val json = JSONObject()
        for ((k, v) in map) {
            json.put(k, v?.toString() ?: "")
        }
        return json.toString()
    }

    @JavascriptInterface
    fun updateStorageItems(changesJson: String) {
        try {
            val json = JSONObject(changesJson)
            val editor = prefs.edit()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                editor.putString(key, json.optString(key, ""))
            }
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update storage", e)
        }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open external URL: $url", e)
        }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun updateActiveAgentCount(count: Int) {
        Log.i(TAG, "Active agents count: $count")
    }

    @JavascriptInterface
    fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}
