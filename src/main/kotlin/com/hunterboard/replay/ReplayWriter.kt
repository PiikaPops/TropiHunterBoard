package com.hunterboard.replay

import com.hunterboard.HunterBoard
import com.hunterboard.ModConfig
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes a battle log out as a replay that plays in the real Showdown viewer.
 *
 * The file is the same shape as the one Showdown's own "Download replay" button produces:
 * the log sits inline in a `battle-log-data` script block and the page pulls
 * `replay-embed.js` from play.pokemonshowdown.com, which reads that block and renders the
 * battle with sprites, animations and turn controls. Nothing is uploaded and no account is
 * involved, and nothing is uploaded: the file stays on this machine.
 */
object ReplayWriter {

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** `.minecraft/hunterboard/replays`, created on first use. */
    fun replayDir(): Path =
        FabricLoader.getInstance().gameDir.resolve("hunterboard").resolve("replays")

    /**
     * @return the file written, or null if it could not be written.
     */
    /**
     * Wraps a battle log in the replay page.
     *
     * Public because the page — not the log — is what gets shared. Building it here once
     * and handing the same string to the file and to the upload is what keeps the two in
     * step: an earlier version passed the bare log to the uploader, so the published link
     * served protocol text instead of a playable replay while the local copy looked fine.
     */
    fun buildPage(log: String, p1: String, p2: String, format: String): String = page(log, p1, p2, format)

    fun write(log: String, p1: String, p2: String, format: String): Path? =
        writePage(buildPage(log, p1, p2, format), p1, p2)

    fun writePage(page: String, p1: String, p2: String): Path? {
        return try {
            val dir = replayDir()
            Files.createDirectories(dir)
            val name = "${LocalDateTime.now().format(STAMP)}_${safe(p1)}-vs-${safe(p2)}.html"
            val target = dir.resolve(name)

            // Same temp-then-move as the config: a replay half-written by a crash would be
            // a broken page the player cannot tell from a good one.
            val tmp = dir.resolve("$name.tmp")
            Files.writeString(tmp, page)
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            HunterBoard.LOGGER.info("[Replay] wrote $target")
            target
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("[Replay] could not write replay: ${e.message}")
            null
        }
    }

    /** Strips what a filename cannot hold, so a nickname never breaks the path. */
    private fun safe(s: String): String {
        val cleaned = s.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (cleaned.isEmpty()) "player" else cleaned.take(24)
    }

    /**
     * The log is embedded rather than escaped into JavaScript: a `text/plain` script block
     * is read as raw text by the browser, so protocol characters such as `|` and quotes
     * need no quoting. The one sequence that must not appear verbatim is a closing script
     * tag, which would end the block early.
     */
    private fun page(log: String, p1: String, p2: String, format: String): String {
        val safeLog = log.replace("</script", "<\\/script")
        val title = "$format replay: ${esc(p1)} vs. ${esc(p2)}"
        val mute = if (ModConfig.replayMuted) muteScript() else ""
        return """<!DOCTYPE html>
<meta charset="utf-8" />
<title>$title</title>
<!-- Recorded from Cobblemon by HunterBoard. Plays in the Pokémon Showdown replay viewer. -->
<style>
  html, body { font-family: Verdana, sans-serif; font-size: 10pt; margin: 0; padding: 0; }
  body { padding: 12px 0; }
  .battle, .battle-log, .replay-controls { margin: 0 auto; }
  .wrapper { max-width: 1180px; margin: 0 auto; }
</style>
<div class="wrapper replay-wrapper">
  <div class="battle"></div>
  <div class="battle-log"></div>
  <div class="replay-controls"></div>
  <div class="replay-controls-2"></div>
</div>
<script type="text/plain" class="battle-log-data">$safeLog</script>
<script src="https://play.pokemonshowdown.com/js/replay-embed.js"></script>$mute
"""
    }

    /**
     * Starts the replay muted.
     *
     * A replay link opened from Discord plays battle music the moment the page loads, which
     * is the kind of surprise that gets a link closed rather than watched. The player
     * already supports this: it reads the global `BattleSound.muted` flag on startup and,
     * when it is set, mutes itself and drops the "turn sound on?" prompt. So rather than
     * fighting the viewer, this sets the flag it is looking for.
     *
     * The embed installs its own `window.onload`, so that handler is wrapped rather than
     * replaced — assigning over it would stop the replay loading at all. The viewer keeps
     * the Music On/Off buttons in the control bar, so sound is one click away.
     */
    private fun muteScript(): String = """

<script>
(function () {
  var startReplay = window.onload;
  window.onload = function () {
    try { if (window.BattleSound) window.BattleSound.muted = true; } catch (e) {}
    if (startReplay) startReplay.apply(this, arguments);
  };
})();
</script>"""

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
