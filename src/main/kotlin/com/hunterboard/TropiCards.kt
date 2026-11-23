package com.hunterboard

import net.minecraft.client.option.KeyBinding
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Answers Tropimon's notification cards the way the player does with the keyboard.
 *
 * A card that offers a choice (the capture card's "Keep [Y]" / "Release [N]") is answered
 * by TropiModClient itself: its `NotificationManager` reads the two keys while the card is
 * on screen, closes the card with the outcome, then tells its listeners — one of which
 * sends `NotificationOutcomePayload(id, outcome)` to the server. The server holds the
 * pending capture and does the rest, wherever the Pokémon went, PC included.
 *
 * [decline] takes exactly the path the N key takes (`NotificationManager.answer`), so the
 * card plays its sound, slides out, and the server receives the usual packet — nothing
 * HunterBoard builds itself. The answer only counts while the card is on screen: a card
 * left alone closes with `EXPIRED`, which the server receives too, and after that the
 * Pokémon stays.
 *
 * Everything is reflective, as with [TropiNotifications]: the classes ship in a
 * proprietary mod. Without TropiModClient, or if its shape changes, [available] is false
 * and callers fall back to what they did before.
 */
object TropiCards {

    private const val PKG = "fr.erusel.tropimodclient.client"
    private const val OUTCOMES_KEPT = 64

    private class Api(
        val current: Method,
        val answer: Method?,
        val finish: Method,
        val declined: Any,
        val duration: Method,
        val preferred: Method?,
        val keybinds: Class<*>?
    )

    private var api: Api? = null
    private var broken = false

    /** Answers seen for recent cards, by notification id: ACCEPTED, DECLINED or EXPIRED. */
    private val outcomes = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > OUTCOMES_KEPT
    }

    val available: Boolean get() = api != null

    /** A card on screen. [handle] is TropiModClient's `ActiveNotification`. */
    class Card(val id: String, val visible: Boolean, val remainingMs: Long, internal val handle: Any)

    fun init() {
        if (api != null || broken) return
        try {
            val loader = TropiCards::class.java.classLoader
            val manager = Class.forName("$PKG.notification.NotificationManager", true, loader)
            val active = Class.forName("$PKG.notification.ActiveNotification", false, loader)
            val outcome = Class.forName("fr.tropimon.tropimoncore.data.notification.NotificationOutcome", true, loader)
            val notification = Class.forName("fr.tropimon.tropimoncore.data.notification.Notification", false, loader)
            val listener = Class.forName("$PKG.notification.NotificationManager\$OutcomeListener", false, loader)
            val durations = Class.forName("$PKG.notification.NotificationDuration", false, loader)

            val declined = outcome.enumConstants.first { (it as Enum<*>).name == "DECLINED" }
            val answer = try {
                manager.getDeclaredMethod("answer", active, outcome).apply { isAccessible = true }
            } catch (_: Exception) { null }

            // Every answer, from the keys or from here, comes back through the listeners.
            val proxy = Proxy.newProxyInstance(loader, arrayOf(listener)) { self, method, args ->
                when (method.name) {
                    "onOutcome" -> { record(args); null }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.getOrNull(0)
                    "toString" -> "HunterBoardOutcomeListener"
                    else -> null
                }
            }
            manager.getMethod("onOutcome", listener).invoke(null, proxy)

            api = Api(
                current = manager.getMethod("current"),
                answer = answer,
                finish = active.getMethod("finish", outcome),
                declined = declined,
                duration = durations.getMethod("of", notification),
                preferred = try { durations.getMethod("currentMillis") } catch (_: Exception) { null },
                keybinds = try { Class.forName("$PKG.input.TropimonKeybinds", false, loader) } catch (_: Exception) { null }
            )
            HunterBoard.LOGGER.info("Tropimon cards: answering is available")
        } catch (_: ClassNotFoundException) {
            broken = true // not on Tropimon's client: nothing to answer
        } catch (e: Exception) {
            broken = true
            HunterBoard.LOGGER.warn("Tropimon cards: cannot answer them (${e.javaClass.simpleName}: ${e.message})")
        }
    }

    private fun record(args: Array<out Any?>?) {
        try {
            val notification = args?.getOrNull(0) ?: return
            val outcome = (args.getOrNull(1) as? Enum<*>)?.name ?: return
            val id = notification.javaClass.getMethod("id").invoke(notification)?.toString() ?: return
            outcomes[id] = outcome
        } catch (_: Exception) {}
    }

    /** How the card with this id was closed, or null while it is pending or on screen. */
    fun outcomeOf(id: String): String? = outcomes[id]

    /** The card on screen right now, if any. */
    fun current(): Card? {
        val a = api ?: return null
        return try {
            val active = a.current.invoke(null) ?: return null
            val cls = active.javaClass
            val notification = cls.getMethod("notification").invoke(active)
            val id = notification.javaClass.getMethod("id").invoke(notification)?.toString() ?: return null
            val phase = (cls.getMethod("phase").invoke(active) as? Enum<*>)?.name
            val remaining = (cls.getMethod("remaining").invoke(active) as? Float) ?: 0f
            val total = (a.duration.invoke(null, notification) as? Long) ?: 0L
            Card(id, phase == "VISIBLE", (remaining * total).toLong(), active)
        } catch (e: Exception) {
            fail("read the current card", e)
            null
        }
    }

    /** Presses "Release" on this card, as the N key does. False when it could not. */
    fun decline(card: Card): Boolean {
        val a = api ?: return false
        return try {
            if (a.answer != null) a.answer.invoke(null, card.handle, a.declined)
            else a.finish.invoke(card.handle, a.declined)
            true
        } catch (e: Exception) {
            fail("answer a card", e)
            false
        }
    }

    /**
     * How long a capture card stays up: the player's own setting in Tropimon's options
     * (2 to 32 s, 16 by default), since the server leaves the capture card's duration open.
     */
    fun cardMillis(): Long = try {
        (api?.preferred?.invoke(null) as? Int)?.toLong() ?: DEFAULT_CARD_MS
    } catch (_: Exception) { DEFAULT_CARD_MS }

    private const val DEFAULT_CARD_MS = 16_000L

    /** The key the player presses to keep ("Y" by default), as the controls screen names it. */
    fun acceptKey(): String = keyName("accept") ?: "Y"

    /** Tropimon's "accept" key binding, to read its state; null without TropiModClient. */
    fun acceptBinding(): KeyBinding? = try {
        api?.keybinds?.getMethod("accept")?.invoke(null) as? KeyBinding
    } catch (_: Exception) { null }

    private fun keyName(which: String): String? = try {
        val kb = api?.keybinds?.getMethod(which)?.invoke(null) as? KeyBinding
        kb?.boundKeyLocalizedText?.string?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** One failure switches answering off for the session: callers fall back, the log stays quiet. */
    private fun fail(what: String, e: Exception) {
        val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
        HunterBoard.LOGGER.warn("Tropimon cards: could not $what (${cause.javaClass.simpleName}: ${cause.message})")
        api = null
        broken = true
    }
}
