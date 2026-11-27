package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.client.gui.summary.widgets.ModelWidget
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.RenderablePokemon
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext

/**
 * A card for the raid that is about to start: the boss, what it is weak to, and the time
 * left before the fight opens.
 *
 * Tropimon announces a raid with a notification ("Activité - Raid") when the boss appears
 * on the timetable, or when a player starts one ("X a déclenché un Raid !", same title);
 * the raid itself starts five minutes later. Its own card is gone after a few seconds,
 * which leaves most of those five minutes without anything on screen. This one stays until
 * the countdown ends or until it is put away with its own key (Delete by default, the same
 * key brings it back). It deliberately survives the trip to the raid: the boss only becomes
 * known on arrival, which is exactly when its weaknesses are worth reading.
 *
 * **Missed announcements.** The notification only reaches the players online at that
 * moment. A player who joins a minute later, or is between two of Tropimon's servers, gets
 * nothing: every client that heard it tells [RaidRelay], and a client that joins asks it
 * for a raid in progress. The arena's own countdown opens the card too, once there.
 *
 * **The boss.** The announcement does not say which Pokémon it is — neither the
 * notification nor the chat names it before the results. It can only be read off the
 * world: once the boss entity is loaded on this client, i.e. when the player is near the
 * raid arena, or learnt from another player through [RaidRelay]. The boss is recognised by
 * where it stands: it always appears on the same spot of the capital, and does not always
 * wear a boss variant.
 */
object RaidCard {

    private const val RAID_TITLE = "gui.tropimod.notification.raid.title"
    private const val COUNTDOWN_MS = 5 * 60_000L
    private const val W = 176
    private const val MODEL = 36

    private class Boss(val name: String, val types: List<String>, val species: com.cobblemon.mod.common.pokemon.Species,
                       val aspects: Set<String>, val reports: Int) {
        // The model widget clips to its own rectangle in real screen pixels, whatever matrix
        // is in force: it is therefore built at the card's size and drawn outside its matrix.
        var widget: ModelWidget? = null
        var widgetScale = 0f
    }

    // Where the boss appears, on the capital server (block -165 73 -163, taken at its centre).
    private const val BOSS_X = -164.5
    private const val BOSS_Y = 73.0
    private const val BOSS_Z = -162.5
    private const val BOSS_RADIUS = 10.0
    private const val BOSS_HEIGHT = 12.0
    /**
     * Raid bosses are drawn three times their size (every one logged so far: scale 3.0,
     * level 100), where the wild Pokémon of Tropimon vary by a fifth at most (1.2). Only
     * that sets the boss apart from a wild Pokémon walking through the arena as it spawns —
     * a Dedenne passed a Volcarona boss there, both in reach.
     */
    private const val BOSS_MIN_SCALE = 2.0f
    // What tells the capital's arena apart from the same coordinates on any other server: a
    // 3×3 of Tropimon's reinforced glass centred on -165 74 -163.
    private const val ARENA_GLASS = "tropimod:light_gray_reinforced_glass"
    private const val ARENA_X = -165
    private const val ARENA_Y = 74
    private const val ARENA_Z = -163

    /** How long after the start the boss is still looked for: a raid lasts a few minutes. */
    private const val WATCH_AFTER_START_MS = 10 * 60_000L
    private const val ASK_EVERY_TICKS = 200   // 10 s

    @Volatile private var startsAt = 0L
    /** Counts the raids announced: [startsAt] alone cannot tell, the server's countdown corrects it. */
    @Volatile private var raidId = 0
    @Volatile private var mega = false
    @Volatile private var slot = 0L
    /** Waved away: nothing is drawn, but the boss is still looked for (and shared). */
    @Volatile private var hidden = false
    /** What the relay answered, handed over from its network thread to the tick. */
    @Volatile private var relayed: RaidRelay.Answer? = null
    /** A raid in progress the relay knew of, asked on joining; handed over to the tick. */
    @Volatile private var latest: RaidRelay.Latest? = null
    /** When to ask the relay for a raid in progress (0 = not asked for). */
    private var askLatestAt = 0L
    private var boss: Boss? = null
    /** Seen with this client's own eyes, and so already reported. */
    private var seenHere = false
    private var scanTicks = 0
    private var askTicks = ASK_EVERY_TICKS
    private val logged = mutableSetOf<String>()

    // Where the card was last drawn, and where the HUD editor is dragging it to.
    var renderedX = 0; private set
    var renderedY = 0; private set
    var renderedW = 0; private set
    var renderedH = 0; private set
    /** Set by the HUD editor while it is open: a sample card is drawn so it can be dragged. */
    var editing = false
    var dragX: Int? = null
    var dragY: Int? = null

    /** The card is on screen. */
    val active: Boolean get() = !hidden && System.currentTimeMillis() < startsAt

    /** A raid was announced recently: the boss is worth finding, card or no card. */
    private val watching: Boolean get() = startsAt != 0L && System.currentTimeMillis() < startsAt + WATCH_AFTER_START_MS

    fun register() {
        // Arrives on the network thread: only plain fields are touched here.
        TropiNotifications.onNotification { notif ->
            if (notif.titleKey == RAID_TITLE) {
                // A scheduled raid is announced a second or two around the hour, never exactly
                // on it; counting from the timetable instead keeps the countdown in step with
                // the server's. A raid triggered by a player ("...raid.triggered", the player's
                // name as argument) has only its announcement to go by, even near the hour.
                // Either way, the arena's own countdown has the last word (serverCountdown).
                val heard = System.currentTimeMillis()
                val byPlayer = "triggered" in notif.descKey
                val at = if (byPlayer) heard else RaidTimerOverlay.scheduledRaidNear(heard, 90_000L) ?: heard
                announced(at, notif.descKey.endsWith("_mega"), heard, byPlayer)
            }
        }
        // Before the notifications, a player's raid was a system message in the chat; the
        // key is still in Tropimon's files. Only a line that starts with the player's name
        // counts: someone repeating the sentence in the chat has their own prefix first.
        // (The arena's countdown is not in the chat as far as seen, but costs nothing to look for.)
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) {
                onServerText(message)
                try {
                    triggeredBy(net.minecraft.util.Formatting.strip(message.string) ?: "")?.let { isMega ->
                        val now = System.currentTimeMillis()
                        announced(now, isMega, now, true)
                    }
                } catch (_: Exception) {}
            }
        }
        // A raid announced before this client arrived: the relay may know of it.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> askLatestAt = System.currentTimeMillis() + 2_000L }
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            try { tick(client) } catch (_: Exception) {}
        }
        HudRenderCallback.EVENT.register { context, _ ->
            try { render(context) } catch (_: Exception) {}
        }
    }

    private val TRIGGERED = Regex("""^\s*[A-Za-z0-9_]{2,16} (?:a déclenché un|triggered an?|has started an?) (m[ée]ga )?raid\s*!""",
        RegexOption.IGNORE_CASE)

    /** The chat line of a raid a player started: whether it is a mega raid; null for any other line. */
    fun triggeredBy(line: String): Boolean? = TRIGGERED.find(line)?.let { it.groupValues[1].isNotEmpty() }

    /**
     * A raid announcement heard here: by notification, or by the chat line of a raid a
     * player started. The same raid may come both ways, a second apart: once is enough.
     */
    private fun announced(announcedAt: Long, isMega: Boolean, heard: Long, byPlayer: Boolean) {
        if (startsAt != 0L && heardAt != 0L && kotlin.math.abs(announcedAt + COUNTDOWN_MS - startsAt) < 60_000L) {
            if (isMega) mega = true
            return
        }
        beginRaid(announcedAt, isMega, heard)
        if (slot != 0L) RaidRelay.announce(slot, isMega)
        // HunterBoard's "raid started" banner and sound, which used to wait for the chat line
        if (byPlayer) MinecraftClient.getInstance().execute { RaidTimerOverlay.raidTriggered(isMega) }
    }

    /**
     * A raid announced at [announcedAt] (or deduced from the arena's countdown), whose
     * notification reached this client at [heard] — 0 when it never did. [knownSlot] is the
     * relay's name for the raid when it was learnt from there.
     */
    private fun beginRaid(announcedAt: Long, isMega: Boolean, heard: Long, knownSlot: Long = 0L) {
        mega = isMega
        heardAt = heard
        calibrated = false
        slot = if (knownSlot != 0L) knownSlot else RaidRelay.slotOf(announcedAt)
        relayed = null
        hidden = false
        lastServerSecs = -1
        corrections = 0
        startsAt = announcedAt + COUNTDOWN_MS
        raidId++
    }

    private fun reset() {
        startsAt = 0L
        boss = null
        relayed = null
        seenHere = false
        hidden = false
        lastServerSecs = -1
        logged.clear()
    }

    fun dismiss() = reset()

    // ── the arena's own countdown ("Le raid commence dans 16s") ────────────────────────
    //
    // Shown over the screen once in the arena, it is the server's clock itself. The card
    // otherwise counts five minutes from the announcement, while the server opens the raid
    // ten to twenty seconds later than that, a little differently each time. Measured here,
    // the true start is shared through the relay with the players not yet in the arena.

    private val COUNTDOWN_TEXT = Regex("""(?i)\braid\b.*?\b(?:commence|d[ée]bute|starts?|begins?)\s+(?:dans|in)\s+(.+)""")
    private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
    private val PART = Regex("""(?i)(\d+)\s*(h|heures?|hours?|min(?:utes?)?|m|s(?:ec(?:ondes?|onds?)?)?)(?![a-z])""")
    private val MEGA = Regex("""(?i)m[ée]ga""")
    @Volatile private var lastServerSecs = -1
    private var corrections = 0
    /** When the announcement reached this client, on its own clock; 0 = picked up in the arena. */
    @Volatile private var heardAt = 0L
    /** [startsAt] read here off the arena's countdown, to the second. */
    @Volatile private var calibrated = false

    /** Called with every action-bar message, title, subtitle, boss bar name and game message. */
    @JvmStatic
    fun onServerText(text: net.minecraft.text.Text?) {
        try {
            val s = net.minecraft.util.Formatting.strip(text?.string) ?: return
            if ("raid" in s.lowercase()) serverCountdown(s)
        } catch (_: Exception) {}
    }

    private fun serverCountdown(s: String) {
        val rest = COUNTDOWN_TEXT.find(s)?.groupValues?.get(1) ?: return
        var secs: Int
        var precise = false   // down to the second: "16s", "1:30", "1 min 30 s" — not "4 min"
        val clock = CLOCK.find(rest)
        if (clock != null) {
            secs = clock.groupValues[1].toInt() * 60 + clock.groupValues[2].toInt()
            precise = true
        } else {
            val parts = PART.findAll(rest).toList()
            if (parts.isEmpty()) return
            secs = 0
            for (p in parts) {
                val n = p.groupValues[1].toInt()
                val unit = p.groupValues[2].lowercase()
                secs += when {
                    unit.startsWith("h") -> n * 3600
                    unit.startsWith("m") -> n * 60
                    else -> { precise = true; n }
                }
            }
        }
        if (secs > COUNTDOWN_MS / 1000 + 30) return   // not this kind of countdown

        val at = System.currentTimeMillis() + secs * 1000L
        if (startsAt == 0L || kotlin.math.abs(at - startsAt) > 90_000L) {
            // The announcement was missed — made while on another server, or before logging
            // in: the arena's count opens the card by itself.
            beginRaid(at - COUNTDOWN_MS, MEGA.containsMatchIn(s), 0L)
            HunterBoard.LOGGER.info("RaidCard: raid picked up from the arena's countdown \"$s\"")
        }
        // The same second may be shown again and again; only the moment a new one appears is
        // exact, and a count in whole minutes is never exact.
        if (!precise || secs == lastServerSecs) return
        // The first value seen may have been on screen for a while already; a change is exact.
        val exact = lastServerSecs != -1
        lastServerSecs = secs
        val off = startsAt - at
        if (kotlin.math.abs(off) >= 1000L && corrections++ < 5) {
            HunterBoard.LOGGER.info("RaidCard: \"$s\" — card was ${"%+.1f".format(off / 1000.0)} s off, corrected")
        }
        startsAt = at
        if (exact && !calibrated) {
            calibrated = true
            // Measured from the announcement, on this client's clock alone: a clock that is
            // off does not matter, the others add the delay to their own announcement time.
            if (heardAt != 0L && slot != 0L) RaidRelay.reportStart(slot, at - heardAt)
        }
    }

    // ── tick: the dismiss key, and looking for the boss ───────────────────────────────

    private var lastRaid = 0

    private fun tick(client: MinecraftClient) {
        // A boss bar may carry the countdown: read its title as the server updates it.
        if (client.world != null) {
            val bars = (client.inGameHud.bossBarHud as Any as com.hunterboard.mixin.BossBarHudAccessor).hunterboardBossBars
            for (bar in bars.values) onServerText(bar.name)
        }
        // The card's key: puts the card away, and brings it back while the raid is still ahead.
        // Every press is used up here, so none is left over for the next raid.
        while (KeyBindings.raidCardKey.wasPressed()) {
            if (startsAt != 0L && System.currentTimeMillis() < startsAt && ModConfig.raidCard) {
                hidden = !hidden
                val key = KeyBindings.raidCardKey.boundKeyLocalizedText.string
                client.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal(
                    if (hidden) t("Raid card hidden ($key to show it again)", "Carte de raid masquée ($key pour la réafficher)")
                    else t("Raid card shown", "Carte de raid affichée")), false)
            }
        }
        askRelayOnJoin(client)
        if (startsAt == 0L) return
        if (!watching) { reset(); return }
        if (raidId != lastRaid) {   // a new raid: forget the previous boss
            lastRaid = raidId
            boss = null
            seenHere = false
            logged.clear()
            askTicks = ASK_EVERY_TICKS
        }

        // With their own eyes first; a sighting made here is also shared.
        if (!seenHere && ++scanTicks >= 20) {
            scanTicks = 0
            findBoss(client)
        }
        // Otherwise from the relay, for as long as the card could use it.
        relayed?.let { answer ->
            relayed = null
            val species = answer.species
            if (!seenHere && species != null) bossOf(species, answer.aspects, answer.reports)?.let { boss = it }
            // The start as measured in the arena by someone else, brought onto this client's clock.
            val delay = answer.delayMs
            if (!calibrated && heardAt != 0L && delay != null) startsAt = heardAt + delay
        }
        val wanted = !seenHere || (!calibrated && heardAt != 0L)
        if (wanted && slot != 0L && System.currentTimeMillis() < startsAt && ++askTicks >= ASK_EVERY_TICKS) {
            askTicks = 0
            RaidRelay.ask(slot) { relayed = it }
        }
    }

    /**
     * Shortly after joining a server with no raid ahead, asks the relay whether one was
     * announced in the last minutes, and opens the card for it: the same card as when the
     * announcement is heard here, counted from when the relay first heard of it.
     */
    private fun askRelayOnJoin(client: MinecraftClient) {
        latest?.let { l ->
            latest = null
            val now = System.currentTimeMillis()
            val heard = now - l.ageMs
            val start = heard + (l.delayMs ?: COUNTDOWN_MS)
            val known = startsAt != 0L && now < startsAt
            if (!known && start > now + 5_000L) {
                beginRaid(heard, l.mega, heard, l.slot)
                HunterBoard.LOGGER.info("RaidCard: raid announced ${l.ageMs / 1000} s before joining, from the relay")
                if (l.species != null || l.delayMs != null) relayed = RaidRelay.Answer(l.species, l.aspects, l.reports, l.delayMs)
            }
        }
        if (askLatestAt == 0L || System.currentTimeMillis() < askLatestAt) return
        askLatestAt = 0L
        if (!ModConfig.raidCard || (startsAt != 0L && System.currentTimeMillis() < startsAt)) return
        // only on Tropimon: a raid there says nothing about any other server
        val address = client.currentServerEntry?.address?.lowercase() ?: return
        if ("tropimon" !in address) return
        RaidRelay.latest { latest = it }
    }

    private fun bossOf(speciesName: String, aspects: Set<String>, reports: Int): Boss? {
        val species = PokemonSpecies.getByName(speciesName) ?: return null
        val form = try { species.getForm(aspects) } catch (_: Exception) { species.standardForm }
        return Boss(species.translatedName.string,
            listOfNotNull(form.primaryType.name, form.secondaryType?.name), species, aspects, reports)
    }

    private fun findBoss(client: MinecraftClient) {
        val world = client.world ?: return
        val arena = inArena(world)
        var best: PokemonEntity? = null
        var bestScore = 0f
        for (entity in world.entities) {
            if (entity !is PokemonEntity) continue
            val p = entity.pokemon
            // a player's Pokémon, sent out or not, is never the boss
            val owned = entity.ownerUuid != null || p.isPlayerOwned()
            if (owned) continue
            val aspects = p.aspects.map { it.lowercase() }
            val variant = RaidBosses.isBossForm(p.form) || aspects.any { RaidBosses.pattern.containsMatchIn(it) }
            val dx = entity.x - BOSS_X
            val dz = entity.z - BOSS_Z
            val flat = kotlin.math.sqrt(dx * dx + dz * dz)
            val onSpot = arena && flat <= BOSS_RADIUS && kotlin.math.abs(entity.y - BOSS_Y) <= BOSS_HEIGHT
            // The boss appears on the same spot of the capital's arena, enlarged; its form
            // proves nothing (a Noivern boss wore no boss aspect). The biggest one standing
            // there wins, then the nearest to the spot.
            var score = 0f
            if (onSpot && p.scaleModifier >= BOSS_MIN_SCALE) score += 20f + p.scaleModifier * 10f - flat.toFloat()
            // Some bosses do carry a boss variant: recognised wherever they are.
            if (variant) score += 10f
            // Only what stands out is noted — ordinary wild Pokémon filled the quota before the
            // boss was ever in sight: anything scored, high-level, oversized or named.
            val notable = score > 0f || onSpot || p.level >= 60 || p.scaleModifier > 1.3f || entity.customName != null
            if (notable && logged.size < 60 && logged.add(p.species.name + aspects)) {
                HunterBoard.LOGGER.info("RaidCard: seen ${p.species.name} form=${p.form.name} aspects=$aspects " +
                    "scale=${p.scaleModifier} level=${p.level} at=${entity.blockPos.toShortString()} arena=$arena score=$score")
            }
            if (score > bestScore) { bestScore = score; best = entity }
        }
        val p = best?.pokemon ?: return
        val name = p.species.resourceIdentifier.path
        boss = bossOf(name, p.aspects, 1) ?: return
        seenHere = true
        HunterBoard.LOGGER.info("RaidCard: boss seen here — $name ${p.aspects}")
        if (slot != 0L) RaidRelay.report(slot, name, p.aspects)
    }

    /** The capital's raid arena is loaded here: its 3×3 of reinforced glass is in place. */
    private fun inArena(world: net.minecraft.client.world.ClientWorld): Boolean {
        for (dx in -1..1) for (dz in -1..1) {
            val state = world.getBlockState(net.minecraft.util.math.BlockPos(ARENA_X + dx, ARENA_Y, ARENA_Z + dz))
            if (net.minecraft.registry.Registries.BLOCK.getId(state.block).toString() != ARENA_GLASS) return false
        }
        return true
    }

    private fun widgetFor(b: Boss, scale: Float): ModelWidget? {
        if (b.widgetScale != scale) {
            b.widgetScale = scale
            b.widget = try {
                val size = (MODEL * scale).toInt()
                ModelWidget(
                    pX = 0, pY = 0, pWidth = size, pHeight = size,
                    pokemon = RenderablePokemon(b.species, b.aspects),
                    baseScale = 1.3f * scale, rotationY = 325f, offsetY = -10.0
                )
            } catch (_: Exception) { null }
        }
        return b.widget
    }

    // ── the card ───────────────────────────────────────────────────────────────────

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    /** Types that hit this boss for double damage or more, the worst first. */
    private fun weaknesses(types: List<String>): List<Pair<String, Float>> {
        val chart = PokemonDetailScreen.DEF_TYPE_CHART
        return PokemonDetailScreen.ATTACK_TYPE_ORDER.map { atk ->
            atk to types.fold(1f) { m, def -> m * (chart[def.lowercase()]?.get(atk) ?: 1f) }
        }.filter { it.second > 1f }.sortedByDescending { it.second }
    }

    private fun render(context: DrawContext) {
        if (!editing && (!active || !ModConfig.raidCard)) { renderedW = 0; return }
        // Out of the way during a battle, like every HunterBoard HUD: only the drawing stops,
        // the countdown and the boss watch go on, and the card is back once the battle ends.
        if (!editing && ModConfig.hideHudInBattle && BattleHelper.isInBattle()) { renderedW = 0; return }
        val client = MinecraftClient.getInstance() ?: return
        if (client.player == null || client.options.hudHidden) return
        val tr = client.textRenderer
        val b = boss

        // Lay the weakness chips out first: their rows decide the card's height.
        val chips = b?.let { weaknesses(it.types) } ?: emptyList()
        val chipRows = mutableListOf<MutableList<Triple<String, Int, Float>>>()
        var rowW = 0
        for ((type, mult) in chips) {
            val label = Translations.formatTypeName(type) + if (mult >= 4f) " ×4" else ""
            val cw = tr.getWidth(label) + 6
            if (chipRows.isEmpty() || rowW + cw > W - 12) { chipRows += mutableListOf<Triple<String, Int, Float>>(); rowW = 0 }
            chipRows.last() += Triple(type, cw, mult)
            rowW += cw + 2
        }

        val bodyTop = 22
        val infoH = MODEL + 4
        val chipsH = if (chipRows.isEmpty()) 0 else 11 + chipRows.size * 12
        val h = bodyTop + infoH + chipsH + 24
        val scale = ModConfig.raidCardScale
        val outW = (W * scale).toInt()
        val outH = (h * scale).toInt()
        val sw = client.window.scaledWidth
        val sh = client.window.scaledHeight
        val custom = dragX?.let { it to (dragY ?: 0) }
            ?: if (ModConfig.raidCardX >= 0) ModConfig.raidCardX to ModConfig.raidCardY else null
        val (outX, outY) = if (custom != null) {
            custom.first.coerceIn(0, maxOf(0, sw - outW)) to custom.second.coerceIn(0, maxOf(0, sh - outH))
        } else when (ModConfig.raidCardAnchor) {
            1 -> (sw - outW - 6) to 6
            2 -> (sw - outW) / 2 to 6
            3 -> 6 to 6
            4 -> 6 to (sh - outH) / 2
            else -> (sw - outW - 6) to (sh - outH) / 2
        }
        renderedX = outX; renderedY = outY; renderedW = outW; renderedH = outH
        // Everything below is laid out at 1:1 from the origin; the matrix places and sizes it.
        val x = 0
        val y = 0
        context.matrices.push()
        context.matrices.translate(outX.toFloat(), outY.toFloat(), 0f)
        context.matrices.scale(scale, scale, 1f)
        try { draw(context, tr, b, chipRows, x, y, h, bodyTop, infoH) } finally { context.matrices.pop() }
        if (b != null) widgetFor(b, scale)?.let { w ->
            try {
                w.x = outX + (6 * scale).toInt()
                w.y = outY + ((bodyTop + 2) * scale).toInt()
                w.render(context, 0, 0, 0f)
            } catch (_: Exception) {}
        }
    }

    private fun draw(context: DrawContext, tr: net.minecraft.client.font.TextRenderer, b: Boss?,
                     chipRows: List<List<Triple<String, Int, Float>>>, x: Int, y: Int, h: Int, bodyTop: Int, infoH: Int) {

        UiKit.panel(context, x, y, W, h)
        UiKit.header(context, tr, x, y, W, if (mega) t("Mega Raid", "Méga Raid") else "Raid")

        // the boss, or the reason it is missing
        val mx = x + 6
        val my = y + bodyTop + 2
        if (UiKit.isModern) UiKit.portrait(context, mx, my, MODEL, MODEL)
        else { context.fill(mx, my, mx + MODEL, my + MODEL, UiKit.SURFACE_SUNKEN); UiKit.border(context, mx, my, MODEL, MODEL, UiKit.BORDER_DIM) }
        val tx = mx + MODEL + 6
        if (b != null) {
            context.drawText(tr, b.name, tx, my + 3, UiKit.TEXT, true)
            var cx = tx
            for (type in b.types) {
                cx += typeChip(context, tr, cx, my + 15, Translations.formatTypeName(type), type) + 2
            }
        } else {
            context.drawText(tr, "?", mx + (MODEL - tr.getWidth("?")) / 2, my + MODEL / 2 - 4, UiKit.TEXT_FAINT, true)
            context.drawText(tr, t("Boss not seen yet", "Boss pas encore vu"), tx, my + MODEL / 2 - 4, UiKit.TEXT_MUTED, true)
        }

        var cy = y + bodyTop + infoH
        if (chipRows.isNotEmpty()) {
            UiKit.sectionTitle(context, tr, t("Weaknesses", "Faiblesses"), x + 6, cy)
            cy += 11
            for (row in chipRows) {
                var cx = x + 6
                for ((type, cw, mult) in row) {
                    val label = Translations.formatTypeName(type) + if (mult >= 4f) " ×4" else ""
                    typeChip(context, tr, cx, cy, label, type)
                    cx += cw + 2
                }
                cy += 12
            }
        }

        // countdown
        val left = if (active) (startsAt - System.currentTimeMillis()).coerceAtLeast(0) else COUNTDOWN_MS * 3 / 4   // sample
        val secs = (left / 1000).toInt()
        val time = "%d:%02d".format(secs / 60, secs % 60)
        val urgent = secs < 60
        context.drawText(tr, t("Starts in ", "Début dans "), x + 6, cy + 2, UiKit.TEXT_MUTED, true)
        context.drawText(tr, time, x + 6 + tr.getWidth(t("Starts in ", "Début dans ")), cy + 2,
            if (urgent) UiKit.DANGER else UiKit.accentBright(), true)
        if (!KeyBindings.raidCardKey.isUnbound) {
            // the key's name varies in length ("Y", "Suppr", "Delete"): the word goes when it would touch the time
            val key = "[${KeyBindings.raidCardKey.boundKeyLocalizedText.string}]"
            val timeEnd = x + 6 + tr.getWidth(t("Starts in ", "Début dans ")) + tr.getWidth(time)
            val hint = listOf("$key " + t("hide", "masquer"), key).firstOrNull { timeEnd + 8 + tr.getWidth(it) <= x + W - 6 }
            if (hint != null) context.drawText(tr, hint, x + W - 6 - tr.getWidth(hint), cy + 2, UiKit.TEXT_FAINT, true)
        }
        val barW = W - 12
        context.fill(x + 6, cy + 13, x + 6 + barW, cy + 16, UiKit.SURFACE_SUNKEN)
        val fill = (barW * left / COUNTDOWN_MS).toInt().coerceIn(0, barW)
        context.fill(x + 6, cy + 13, x + 6 + fill, cy + 16, if (urgent) UiKit.DANGER else UiKit.accent())
    }

    /** A type name on its own colour; returns the chip's width. */
    private fun typeChip(context: DrawContext, tr: net.minecraft.client.font.TextRenderer,
                         x: Int, y: Int, label: String, type: String): Int {
        val w = tr.getWidth(label) + 6
        val color = BattleTracker.TYPE_COLORS[type.lowercase()] ?: UiKit.BORDER
        context.fill(x, y, x + w, y + 11, color)
        context.fill(x, y + 10, x + w, y + 11, UiKit.dim(color, 0.7f))
        context.drawText(tr, label, x + 3, y + 2, 0xFFFFFFFF.toInt(), true)
        return w
    }
}
