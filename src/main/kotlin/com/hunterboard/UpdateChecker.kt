package com.hunterboard

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

object UpdateChecker {

    private const val MODRINTH_SLUG = "tropihunterboard"
    private const val MODRINTH_URL = "https://modrinth.com/mod/$MODRINTH_SLUG"
    private const val API_URL = "https://api.modrinth.com/v2/project/$MODRINTH_SLUG/version"

    private var latestVersion: String? = null
    private var updateAvailable = false
    private var checked = false

    fun register() {
        // Check in background on mod init
        Thread {
            try {
                checkForUpdate()
            } catch (_: Exception) {}
        }.start()

        // Notify player when they join a world
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            if (updateAvailable && latestVersion != null) {
                client.execute {
                    try {
                        val player = client.player ?: return@execute
                        val currentVersion = getCurrentVersion()
                        val msg = Text.literal("")
                            .append(Text.literal("[HunterBoard] ").styled { it.withColor(0xFFAA00).withBold(true) })
                            .append(Text.literal(
                                if (Translations.isFrench())
                                    "Nouvelle version disponible : v$latestVersion (actuelle : v$currentVersion) - "
                                else
                                    "New version available: v$latestVersion (current: v$currentVersion) - "
                            ).styled { it.withColor(0xFFFFFF).withBold(true) })
                            .append(Text.literal("Modrinth").styled {
                                it.withColor(0x55FF55)
                                    .withBold(true)
                                    .withUnderline(true)
                                    .withClickEvent(ClickEvent(ClickEvent.Action.OPEN_URL, MODRINTH_URL))
                            })
                        player.sendMessage(Text.literal(" "), false)
                        player.sendMessage(Text.literal(" "), false)
                        player.sendMessage(msg, false)
                        player.sendMessage(Text.literal(" "), false)
                        player.sendMessage(Text.literal(" "), false)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun checkForUpdate() {
        if (checked) return
        checked = true

        val currentVersion = getCurrentVersion()
        if (currentVersion.isEmpty()) return

        try {
            val gameVersion = "1.21.1"
            val url = "$API_URL?game_versions=[%22$gameVersion%22]&loaders=[%22fabric%22]"

            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "HunterBoard/$currentVersion")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return

            val versions = JsonParser.parseString(response.body()).asJsonArray
            if (versions.size() == 0) return

            // First entry is the latest version
            val latest = versions[0].asJsonObject
            val versionNumber = latest.get("version_number")?.asString ?: return

            latestVersion = versionNumber.removePrefix("v")
            val current = currentVersion.removePrefix("v")

            if (isNewer(latestVersion!!, current)) {
                updateAvailable = true
                HunterBoard.LOGGER.info("HunterBoard update available: v$latestVersion (current: v$current)")
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("Update check failed: ${e.message}")
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("hunterboard").orElse(null)
                ?.metadata?.version?.friendlyString ?: ""
        } catch (_: Exception) { "" }
    }

    /** Returns true if remote is newer than local using semantic versioning */
    private fun isNewer(remote: String, local: String): Boolean {
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(rParts.size, lParts.size)) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
