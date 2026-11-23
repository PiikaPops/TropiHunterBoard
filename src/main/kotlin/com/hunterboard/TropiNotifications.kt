package com.hunterboard

import net.minecraft.text.Text

/**
 * The server's notification feed, read without a compile-time dependency on Tropimon.
 *
 * Tropimon moved its announcements out of the chat and into a dedicated notification
 * channel (`tropimon:show_notification_packet`), which is why catch, miracle and item-clear
 * detection all went silent at once: they listened to `ClientReceiveMessageEvents`, and no
 * chat message arrives any more.
 *
 * Reading the channel directly is not possible — Fabric allows a single receiver per
 * payload id and TropiModClient already owns it, so a second registration would throw. The
 * notification is therefore picked up one step later, where TropiModClient hands it to its
 * own manager: `NotificationManager.pushFromServer`, which exists only for server-sent
 * notifications and so cannot be confused with a local or demo one.
 *
 * Everything is read reflectively. The payload classes live in jar-in-jar dependencies of a
 * proprietary mod, so there is nothing to compile against; the shapes are plain Java
 * records (`Notification`, `NotificationText`, `NotificationArg`), which makes their
 * accessors stable and cheap to call.
 */
object TropiNotifications {

    /** One server notification, flattened into what the detectors need. */
    data class Notif(
        val id: String,
        /** Translation key of the title, e.g. "gui.tropimod.notification.hunt.title". */
        val titleKey: String,
        /** Translation key of the body, e.g. "chat.tropimon.boost.activated". */
        val descKey: String,
        /** The body's arguments, each already resolved to a displayable string. */
        val descArgs: List<String>,
        /**
         * The same arguments before translation, which is where the identifiers are:
         * the hunt notification passes its species as `cobblemon.species.pikachu.name`
         * and its ball as `item.cobblemon.poke_ball`.
         */
        val descArgKeys: List<String>,
        /**
         * The body as the player sees it. Tropimon kept the old chat translation keys for
         * several notifications, so this is often character-for-character the message the
         * detectors used to read from the chat — which is why they can be fed unchanged.
         */
        val text: String,
        /** e.g. "tropimon:pokemon_capture" — set only on notifications with a custom renderer. */
        val rendererId: String,
        /** Structured payload: species, level, shiny, ivTotal… depending on the notification. */
        val data: Map<String, String>,
        /**
         * The value of each stat cell, in order. The capture card puts the six IVs there
         * (HP, Atk, Def, SpA, SpD, Spe), which `data` only gives as a total.
         */
        val stats: List<String> = emptyList(),
        /** The card offers a "decline" answer — on the capture card, "Release [N]". */
        val declinable: Boolean = false
    )

    private val listeners = mutableListOf<(Notif) -> Unit>()

    /** True once a notification has actually come through, i.e. the hook is live. */
    var seenAny = false
        private set

    /**
     * Channels seen at least once, so a silent detector can be diagnosed from the log
     * instead of by guesswork. The whole point: when something stops being detected, the
     * first question is whether the packet arrives at all, and nothing else answers it.
     */
    private val seenChannels = mutableSetOf<String>()

    /** Called for every custom payload; logs each Tropimon channel the first time it appears. */
    @JvmStatic
    fun noteChannel(channel: String) {
        if (!channel.startsWith("tropimon:")) return
        if (!seenChannels.add(channel)) return
        HunterBoard.LOGGER.info("Tropimon channel seen: $channel")
    }

    /** The notification channel arrived but its payload was not the expected shape. */
    @JvmStatic
    fun noteUnreadablePayload(className: String, error: Throwable) {
        if (!seenChannels.add("!$className")) return
        HunterBoard.LOGGER.warn(
            "Notification payload $className has no readable getNotification(): ${error.javaClass.simpleName}"
        )
    }

    fun onNotification(listener: (Notif) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Entry point for the mixin. Takes the raw `Notification` record as [Any] so this file
     * compiles without Tropimon on the classpath.
     */
    @JvmStatic
    fun dispatch(raw: Any?) {
        val notif = try { read(raw ?: return) } catch (e: Exception) {
            HunterBoard.LOGGER.warn("Could not read a Tropimon notification: ${e.message}")
            return
        } ?: return

        if (!seenAny) {
            seenAny = true
            HunterBoard.LOGGER.info("Tropimon notification channel is live")
        }
        HunterBoard.LOGGER.info(
            "Notification: title=${notif.titleKey} renderer=${notif.rendererId} " +
                "args=${notif.descArgKeys} data=${notif.data.keys}"
        )

        for (listener in listeners) {
            try { listener(notif) } catch (_: Exception) {}
        }
    }

    // ── reflective reading ───────────────────────────────────────────────────────

    private fun call(target: Any, name: String): Any? = try {
        target.javaClass.getMethod(name).invoke(target)
    } catch (_: Exception) { null }

    private fun read(raw: Any): Notif? {
        val titleKey = textValue(call(raw, "title"))
        val descKey = textValue(call(raw, "description"))
        val args = textArgs(call(raw, "description"))

        @Suppress("UNCHECKED_CAST")
        val data = (call(raw, "data") as? Map<String, String>) ?: emptyMap()

        return Notif(
            id = call(raw, "id")?.toString() ?: "",
            titleKey = titleKey,
            descKey = descKey,
            descArgs = args,
            descArgKeys = rawArgs(call(raw, "description")),
            text = localize(descKey, args),
            rendererId = call(raw, "rendererId")?.toString() ?: "",
            data = data,
            stats = (call(raw, "stats") as? List<*>).orEmpty().map { stat ->
                stat?.let { textValue(call(it, "value")) } ?: ""
            },
            declinable = call(raw, "decline") != null
        )
    }

    /** `NotificationText.value()` — the translation key, or the literal when not translatable. */
    private fun textValue(text: Any?): String =
        if (text == null) "" else call(text, "value")?.toString() ?: ""

    /** Each `NotificationArg` resolved to a displayable string. */
    private fun textArgs(text: Any?): List<String> {
        if (text == null) return emptyList()
        val list = call(text, "args") as? List<*> ?: return emptyList()
        return list.mapNotNull { arg ->
            if (arg == null) return@mapNotNull null
            val value = call(arg, "text")?.toString() ?: return@mapNotNull null
            val translatable = call(arg, "translatable") as? Boolean ?: false
            if (translatable) translateOr(value, call(arg, "fallback")?.toString() ?: value)
            else value
        }
    }

    /** The arguments as the server wrote them, untranslated. */
    private fun rawArgs(text: Any?): List<String> {
        if (text == null) return emptyList()
        val list = call(text, "args") as? List<*> ?: return emptyList()
        return list.mapNotNull { arg -> arg?.let { call(it, "text")?.toString() } }
    }

    /** Resolve a key through the active resource pack, falling back when it is missing. */
    private fun translateOr(key: String, fallback: String): String = try {
        val out = Text.translatable(key).string
        if (out == key) fallback else out
    } catch (_: Exception) { fallback }

    /** Rebuild the sentence the player sees, so text-based detectors keep working. */
    private fun localize(key: String, args: List<String>): String {
        if (key.isEmpty()) return ""
        return try {
            Text.translatable(key, *args.toTypedArray()).string
        } catch (_: Exception) { key }
    }
}
