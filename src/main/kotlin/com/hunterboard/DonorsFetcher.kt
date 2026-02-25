package com.hunterboard

import java.net.HttpURLConnection
import java.net.URL

object DonorsFetcher {

    private const val DONORS_URL =
        "https://raw.githubusercontent.com/PiikaPops/TropiHunterBoard/main/donors.txt"
    private const val CACHE_DURATION_MS = 300_000L // 5 minutes

    var donors: List<String> = emptyList()
        private set
    var fetchFailed = false
        private set

    private var lastFetchTime: Long = 0L
    private var fetching = false

    fun fetchIfNeeded() {
        val now = System.currentTimeMillis()
        if (fetching) return
        if (now - lastFetchTime < CACHE_DURATION_MS && lastFetchTime > 0L) return
        fetching = true
        Thread {
            try {
                val conn = URL(DONORS_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    donors = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    fetchFailed = donors.isEmpty()
                } else {
                    fetchFailed = true
                }
                conn.disconnect()
            } catch (_: Exception) {
                fetchFailed = true
            } finally {
                lastFetchTime = System.currentTimeMillis()
                fetching = false
            }
        }.also { it.isDaemon = true }.start()
    }
}
