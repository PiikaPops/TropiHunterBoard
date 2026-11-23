package com.hunterboard.controller

import com.cobblemon.mod.common.client.gui.interact.wheel.ArrowButton
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelButton
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelGUI
import com.cobblemon.mod.common.client.gui.interact.wheel.Orientation
import com.cobblemon.mod.common.client.gui.summary.Summary
import com.cobblemon.mod.common.client.gui.ExitButton
import com.cobblemon.mod.common.client.gui.summary.SummaryButton
import com.cobblemon.mod.common.client.gui.summary.widgets.PartySlotWidget
import com.cobblemon.mod.common.client.gui.summary.widgets.PartyWidget
import com.cobblemon.mod.common.client.gui.summary.widgets.screens.SummaryTab
import com.cobblemon.mod.common.client.gui.summary.widgets.screens.moves.MoveSlotWidget
import com.cobblemon.mod.common.client.gui.summary.widgets.screens.moves.MovesWidget
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.api.gui.ParentWidget
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.cobblemon.mod.common.client.gui.pc.StorageSlot
import com.cobblemon.mod.common.client.gui.pc.StorageWidget
import com.hunterboard.ModConfig
import com.hunterboard.ScaledScreen
import com.hunterboard.pc.HunterPcScreen
import org.lwjgl.glfw.GLFW
import com.hunterboard.mixin.controller.EntryListAccessor
import com.hunterboard.mixin.controller.MouseAccessor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.ParentElement
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.EntryListWidget
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.gui.widget.ClickableWidget
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * The controller support's actual work on Tropimon's and Cobblemon's screens, kept free of
 * Controlify's classes: `controlify/ControlifyCompat.kt` reads the controller and calls in
 * here, and the development checks call in here without one.
 */

/** Screens that are drawn for a mouse and get Controlify's cursor instead of its focus navigation. */
object ControllerScreens {
    private val CURSOR_PACKAGES = listOf(
        "com.cobblemon.mod.common.client.gui.",   // PC, summary, battle, Pokédex, trades…
        "fr.erusel.tropimodclient.",              // Tropimon: navigator, town, GTS, shop, gyms…
        "fr.tropimon.",                           // Tropimon's other client mods
        "com.hunterboard.",                       // HunterBoard's own screens
    )

    /**
     * True for a screen whose buttons only react to the pointer: Controlify's default for an
     * unknown screen moves a focus they never show and Enter presses they ignore.
     */
    fun wantsCursor(screen: Screen): Boolean {
        val name = screen.javaClass.name
        return CURSOR_PACKAGES.any { name.startsWith(it) }
    }
}

/**
 * Where a stick points on Cobblemon's interaction wheel. While set, the wheel is drawn as if
 * the mouse were there (InteractWheelAimMixin), so Cobblemon's own highlight and tooltip
 * follow the stick; only for the wheel it was set on, and only until the real mouse moves:
 * someone on mouse and keyboard, even with a controller plugged in, always has the wheel as
 * Cobblemon made it. Never set without Controlify.
 */
object WheelAim {
    private const val UNSEEN = Int.MIN_VALUE

    @Volatile private var target: WeakReference<Any>? = null
    @Volatile private var x = 0
    @Volatile private var y = 0
    /** The real mouse position when the aim was first drawn, to notice it moving. */
    @Volatile private var realX = UNSEEN
    @Volatile private var realY = UNSEEN

    fun set(screen: Any, x: Int, y: Int) {
        if (isOn(screen) && x == this.x && y == this.y) return
        this.x = x; this.y = y
        realX = UNSEEN; realY = UNSEEN
        target = WeakReference(screen)
    }

    fun clear() { target = null }
    fun isOn(screen: Any): Boolean = target?.get() === screen

    @JvmStatic fun mouseX(screen: Any, original: Int): Int {
        if (!isOn(screen)) return original
        if (realX == UNSEEN) realX = original
        else if (abs(original - realX) > 1) { clear(); return original }
        return x
    }

    @JvmStatic fun mouseY(screen: Any, original: Int): Int {
        if (!isOn(screen)) return original
        if (realY == UNSEEN) realY = original
        else if (abs(original - realY) > 1) { clear(); return original }
        return y
    }
}

/** Cobblemon's interaction wheel as a radial menu: eight directions, pages, press. */
object WheelControl {
    /** How far from the wheel's centre a direction's button is aimed at (the ring is 85 px). */
    private const val REACH = 55.0

    private val orientationField by lazy { InteractWheelButton::class.java.getDeclaredField("orientation").apply { isAccessible = true } }
    private val enabledField by lazy { InteractWheelButton::class.java.getDeclaredField("isEnabled").apply { isAccessible = true } }
    private val iconField by lazy { InteractWheelButton::class.java.getDeclaredField("iconResource").apply { isAccessible = true } }

    /** The option buttons of the page on screen; the blank slices filling a short page are left out. */
    fun buttons(screen: InteractWheelGUI): List<InteractWheelButton> =
        screen.children().filterIsInstance<InteractWheelButton>()
            .filter { it.visible && runCatching { iconField.get(it) != null }.getOrDefault(true) }

    fun orientationOf(button: InteractWheelButton): Orientation = orientationField.get(button) as Orientation

    fun isEnabled(button: InteractWheelButton): Boolean =
        button.active && runCatching { enabledField.getBoolean(button) }.getOrDefault(true)

    /** Screen angle of a direction, in degrees, y pointing down (east 0, south 90). */
    fun angleOf(o: Orientation): Double = when (o) {
        Orientation.NORTH -> -90.0
        Orientation.NORTHEAST -> -45.0
        Orientation.EAST -> 0.0
        Orientation.SOUTHEAST -> 45.0
        Orientation.SOUTH -> 90.0
        Orientation.SOUTHWEST -> 135.0
        Orientation.WEST -> 180.0
        Orientation.NORTHWEST -> -135.0
    }

    /** The button whose direction is nearest to (dx, dy), y down; null on an empty page. */
    fun pick(screen: InteractWheelGUI, dx: Double, dy: Double): InteractWheelButton? {
        val a = Math.toDegrees(atan2(dy, dx))
        return buttons(screen).minByOrNull { b ->
            val d = abs(angleOf(orientationOf(b)) - a) % 360.0
            if (d > 180.0) 360.0 - d else d
        }
    }

    private val hoverTest by lazy {
        InteractWheelButton::class.java.getDeclaredMethod("isHovered", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }
    private val aimCache = WeakHashMap<InteractWheelButton, Pair<Int, Int>>()

    /**
     * The point of [button]'s slice, in screen pixels, where the wheel is drawn as hovered:
     * found with Cobblemon's own hover test (slices overlap in their corners), the hoverable
     * point nearest the middle of the slice. A button is rebuilt on every resize, so the
     * cache follows by itself.
     */
    fun aimPoint(screen: InteractWheelGUI, button: InteractWheelButton): Pair<Int, Int> = aimCache.getOrPut(button) {
        val inside = ArrayList<Pair<Int, Int>>()
        runCatching {
            for (y in button.y until button.y + button.height step 2) for (x in button.x until button.x + button.width step 2) {
                if (hoverTest.invoke(button, x.toFloat(), y.toFloat()) as Boolean) inside += x to y
            }
        }
        if (inside.isEmpty()) {
            // no hover test to ask: along the direction, inside the ring
            val size = InteractWheelGUI.SIZE
            val r = Math.toRadians(angleOf(orientationOf(button)))
            Pair(((screen.width - size) / 2 + size / 2 + cos(r) * REACH).toInt(), ((screen.height - size) / 2 + size / 2 + sin(r) * REACH).toInt())
        } else {
            val mx = inside.sumOf { it.first }.toDouble() / inside.size
            val my = inside.sumOf { it.second }.toDouble() / inside.size
            inside.minBy { (x, y) -> (x - mx) * (x - mx) + (y - my) * (y - my) }
        }
    }

    /** Shows [button] as hovered (null: back to the mouse). */
    fun aim(screen: InteractWheelGUI, button: InteractWheelButton?) {
        if (button == null) { WheelAim.clear(); return }
        val (x, y) = aimPoint(screen, button)
        WheelAim.set(screen, x, y)
    }

    /** Presses [button] as a click would; false when the option is greyed out. */
    fun press(button: InteractWheelButton): Boolean {
        if (!button.visible || !isEnabled(button)) return false
        button.onPress()
        return true
    }

    /** Next ([step] > 0) or previous page, through the wheel's own arrows; false without one. */
    fun page(screen: InteractWheelGUI, step: Int): Boolean {
        val arrow = screen.children().filterIsInstance<ArrowButton>()
            .firstOrNull { it.visible && it.active && it.isRight == (step > 0) } ?: return false
        arrow.onPress()
        return true
    }
}

/** Cobblemon's summary: its four tabs and the party, without the mouse. */
object SummaryControl {
    /** Info, moves, stats, marks (Summary.INFO..MARKS). */
    const val TABS = 4

    private val cls = Summary::class.java
    private val displayMain by lazy { cls.getDeclaredMethod("displayMainScreen", Int::class.javaPrimitiveType).apply { isAccessible = true } }
    private val mainIndex by lazy { cls.getDeclaredField("mainScreenIndex").apply { isAccessible = true } }
    private val party by lazy { cls.getDeclaredField("party").apply { isAccessible = true } }
    private val selected by lazy { cls.getDeclaredField("selectedPokemon").apply { isAccessible = true } }

    fun tab(summary: Summary): Int = mainIndex.getInt(summary)

    fun switchTab(summary: Summary, step: Int) {
        displayMain.invoke(summary, Math.floorMod(tab(summary) + step, TABS))
    }

    /** Index in the party of the Pokémon shown. */
    fun selection(summary: Summary): Int = (party.get(summary) as List<*>).indexOf(selected.get(summary))

    /** The next ([step] > 0) or previous Pokémon of the party, skipping empty slots; false if alone. */
    fun switchPokemon(summary: Summary, step: Int): Boolean {
        val list = party.get(summary) as List<*>
        if (list.isEmpty()) return false
        val current = list.indexOf(selected.get(summary)).coerceAtLeast(0)
        for (i in 1 until list.size) {
            val j = Math.floorMod(current + step * i, list.size)
            if (list[j] != null) { summary.switchSelection(j); return true }
        }
        return false
    }
}

/**
 * The buttons a server mod puts above the player's inventory, Tropimon's tabs (navigator,
 * Pokémon, town, trainer card) among them, gone through in order with the shoulder buttons.
 */
object InventoryTabs {
    /** InventoryScreen's panel height; the tabs sit wholly above it. */
    private const val PANEL_H = 166
    private val last = WeakHashMap<Screen, Int>()

    fun tabs(screen: InventoryScreen): List<ClickableWidget> {
        val top = (screen.height - PANEL_H) / 2
        return screen.children().filterIsInstance<ClickableWidget>()
            .filter { it.visible && it.active && it.y + it.height <= top + 3 }
            .sortedBy { it.x }
    }

    /** The tab after ([dir] > 0) or before the last one reached; the first or last one to start. */
    fun step(screen: InventoryScreen, dir: Int): ClickableWidget? {
        val tabs = tabs(screen)
        if (tabs.isEmpty()) return null
        val i = last[screen]
        val next = if (i == null || i >= tabs.size) (if (dir > 0) 0 else tabs.size - 1) else Math.floorMod(i + dir, tabs.size)
        last[screen] = next
        return tabs[next]
    }
}

/** A point the controller's cursor can rest on, in screen pixels; [range]: how far it pulls the cursor. */
data class SnapTarget(val x: Int, val y: Int, val range: Int)

/**
 * What the cursor can jump to on a screen drawn for a mouse. Controlify takes every widget,
 * hidden or greyed out ones included, and makes each pull the cursor from as far as its
 * own size: next to a neighbour, a small push lands on the neighbour. Here: visible and
 * active widgets only, inside lists only the rows really on screen (a row with nothing
 * clickable in it counts as one, by its middle), each pulling from half its size.
 */
object ScreenTargets {
    /**
     * Real GUI pixels per pixel of [screen]'s own layout: below 1 on HunterBoard's screens
     * when Minecraft's GUI scale is above HunterBoard's cap (ScaledScreen lays out in virtual
     * pixels, drawn through a smaller matrix). The cursor and Controlify work in GUI pixels.
     */
    fun guiPerPixel(screen: Screen): Float = (screen as? ScaledScreen)?.uiFactor?.takeIf { it < 1f } ?: 1f

    /** A point in GUI pixels, in [screen]'s own layout pixels. */
    fun toLayout(screen: Screen, p: Pair<Double, Double>): Pair<Double, Double> {
        val f = guiPerPixel(screen)
        return if (f == 1f) p else Pair(p.first / f, p.second / f)
    }

    /** In GUI pixels, whatever the screen's own layout. */
    fun generic(screen: Screen): List<SnapTarget> {
        val out = ArrayList<SnapTarget>()
        walk(screen.children(), out)
        val laid = tidy(screen, out)
        val f = guiPerPixel(screen)
        if (f == 1f) return laid
        return laid.map { SnapTarget(Math.round(it.x * f), Math.round(it.y * f), maxOf(3, Math.round(it.range * f))) }
    }

    fun of(w: ClickableWidget): SnapTarget =
        SnapTarget(w.x + w.width / 2, w.y + w.height / 2, maxOf(3, minOf(w.width, w.height) / 2))

    private fun walk(elements: List<Element>, out: MutableList<SnapTarget>) {
        for (e in elements) {
            when (e) {
                is EntryListWidget<*> -> if (e.visible) rows(e, out)
                is ClickableWidget -> {
                    if (!e.visible || !e.active) continue
                    val before = out.size
                    if (e is ParentElement) walk(e.children(), out)
                    // Cobblemon's containers (the PC's storage, the summary's pages) keep their widgets apart
                    if (e is ParentWidget) walk(e.children, out)
                    if (out.size == before) out += of(e)
                }
                is ParentElement -> walk(e.children(), out)
            }
        }
    }

    /**
     * The rows of [list] on screen: their buttons, or the row itself when it has none.
     * Tropimon's rows keep their buttons to themselves (two homes or towns side by side, GTS
     * offers...): not listed as children, only placed as the row draws; they are found in the
     * row's fields. A row with no button at all is aimed where it answers the mouse.
     */
    fun rows(list: EntryListWidget<*>, out: MutableList<SnapTarget>) {
        val acc = list as EntryListAccessor
        val h = acc.`hunterboard$itemHeight`()
        for ((i, entry) in list.children().withIndex()) {
            val top = acc.`hunterboard$rowTop`(i)
            val mid = top + h / 2
            if (mid < list.y || mid > list.bottom) continue
            val inRow = ArrayList<SnapTarget>()
            if (entry is ParentElement) walk(entry.children(), inRow)
            if (inRow.isEmpty()) walk(heldWidgets(entry), inRow)
            val inside = inRow.filter { it.y in top - 2..top + h + 2 && it.x >= list.x && it.x < list.right }
            if (inside.isNotEmpty()) { out += inside; continue }
            val box = OWN_BOXES[entry.javaClass.name]
            out += if (box != null) {
                SnapTarget(list.rowLeft + box[0] + box[2] / 2, top + box[1] + box[3] / 2, maxOf(3, minOf(box[2], box[3]) / 2))
            } else {
                // the row as drawn: no wider than the list (a narrow list keeps Minecraft's 220-pixel row width)
                val right = minOf(list.rowLeft + list.rowWidth, list.right)
                SnapTarget((list.rowLeft + right) / 2, mid, maxOf(3, h / 2))
            }
        }
    }

    /**
     * Rows that test the mouse against a box of their own and hold nothing clickable: the box
     * (left, top, width, height) from the row's left and top. Tropimon's capital (a 100x12
     * button on the left of a 118-pixel list) and its gym queue.
     */
    private val OWN_BOXES = mapOf(
        "fr.erusel.tropimodclient.client.gui.capital.widgets.CapitalEntry" to intArrayOf(0, -1, 100, 12),
        "fr.erusel.tropimodclient.client.gui.gym.terminal.widget.entry.PlayerWaitingEntry" to intArrayOf(0, 0, 96, 18),
    )

    private val heldFields = HashMap<Class<*>, List<java.lang.reflect.Field>>()

    /** The widgets a row keeps in its own fields, alone or in a list, the list itself aside. */
    private fun heldWidgets(entry: Any): List<Element> {
        val fields = heldFields.getOrPut(entry.javaClass) {
            generateSequence<Class<*>>(entry.javaClass) { it.superclass }
                .takeWhile { it != Any::class.java && !it.name.startsWith("net.minecraft.") }
                .flatMap { it.declaredFields.asSequence() }
                .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .filter { ClickableWidget::class.java.isAssignableFrom(it.type) || Collection::class.java.isAssignableFrom(it.type) }
                .mapNotNull { f -> runCatching { f.isAccessible = true; f }.getOrNull() }
                .toList()
        }
        if (fields.isEmpty()) return emptyList()
        val out = ArrayList<Element>()
        for (f in fields) {
            when (val v = runCatching { f.get(entry) }.getOrNull()) {
                is EntryListWidget<*> -> {}
                is ClickableWidget -> out += v
                is Collection<*> -> v.forEach { if (it is ClickableWidget && it !is EntryListWidget<*>) out += it }
            }
        }
        return out
    }

    /** Every list on [screen], nested ones included. */
    fun lists(screen: Screen): List<EntryListWidget<*>> {
        val out = ArrayList<EntryListWidget<*>>()
        fun find(elements: List<Element>) {
            for (e in elements) {
                if (e is EntryListWidget<*>) { if (e.visible) out += e }
                else if (e is ParentElement) find(e.children())
            }
        }
        find(screen.children())
        return out
    }

    /**
     * Scrolls the list under (x, y), in the screen's own pixels ([toLayout]), by one row towards
     * [dir] (1 down, -1 up), as a D-pad press past its last visible row should; false when there
     * is no list there or it is at its end.
     */
    fun scrollAt(screen: Screen, x: Double, y: Double, dir: Int): Boolean {
        val list = lists(screen).firstOrNull { x >= it.x && x < it.right && y >= it.y && y < it.bottom } ?: return false
        val before = list.scrollAmount
        list.scrollAmount = before + dir * (list as EntryListAccessor).`hunterboard$itemHeight`()
        return list.scrollAmount != before
    }

    /** On screen, and not two on the same spot. */
    fun tidy(screen: Screen, targets: List<SnapTarget>): List<SnapTarget> {
        val out = ArrayList<SnapTarget>()
        for (t in targets) {
            if (t.x < 0 || t.y < 0 || t.x > screen.width || t.y > screen.height) continue
            if (out.none { abs(it.x - t.x) <= 2 && abs(it.y - t.y) <= 2 }) out += t
        }
        return out
    }
}

/**
 * The PCs with a controller. Cobblemon's: its cells are buttons already; LB/RB turn its boxes
 * through its own left/right arrow keys (a Pokémon held stays held, to be put down in another
 * box). HunterBoard's: its cells, cards, menus and windows are drawn, so it lists them itself
 * ([HunterPcScreen.controllerTargets]), in its own pixels.
 */
object PcControl {
    private fun field(cls: Class<*>, name: String) = cls.getDeclaredField(name).apply { isAccessible = true }
    private val partySlots by lazy { field(StorageWidget::class.java, "partySlots") }
    private val boxSlots by lazy { field(StorageWidget::class.java, "boxSlots") }

    fun partySlots(pc: PCGUI): List<StorageSlot> = (partySlots.get(pc.storage) as List<*>).filterIsInstance<StorageSlot>().filter { it.visible }
    fun boxSlots(pc: PCGUI): List<StorageSlot> = (boxSlots.get(pc.storage) as List<*>).filterIsInstance<StorageSlot>().filter { it.visible }

    /** Cobblemon's PC: the next ([dir] > 0) or previous box, as its arrow keys do. */
    fun turnBox(pc: PCGUI, dir: Int): Boolean =
        pc.keyPressed(if (dir > 0) GLFW.GLFW_KEY_RIGHT else GLFW.GLFW_KEY_LEFT, 0, 0)

    /** HunterBoard's PC: where the cursor can rest, in GUI pixels. */
    fun targets(pc: HunterPcScreen): List<SnapTarget> {
        val f = ScreenTargets.guiPerPixel(pc)
        return pc.controllerTargets().map { r ->
            SnapTarget(Math.round((r[0] + r[2] / 2) * f), Math.round((r[1] + r[3] / 2) * f), maxOf(3, Math.round(minOf(r[2], r[3]) / 2 * f)))
        }
    }
}

/**
 * Cobblemon's summary, where the cursor only rests on what the player chose: the evolve
 * button when shown, the party members (in their order), the four tabs, the back button,
 * the reorder button and the held item slot; and, once opened, the evolution choices.
 */
object SummaryTargets {
    private fun field(cls: Class<*>, name: String) = cls.getDeclaredField(name).apply { isAccessible = true }
    private val partySlots by lazy { field(PartyWidget::class.java, "partySlots") }
    private val swapButton by lazy { field(PartyWidget::class.java, "swapButton") }
    private val slotPokemon by lazy { field(PartySlotWidget::class.java, "pokemon") }
    private val renderRequirement by lazy { field(SummaryButton::class.java, "renderRequirement") }
    private val scale by lazy { field(SummaryButton::class.java, "scale") }
    private val resource by lazy { field(SummaryButton::class.java, "resource") }
    private val heldItemButton by lazy { field(Summary::class.java, "heldItemVisibilityButton") }
    private val evolveResource by lazy { field(Summary::class.java, "evolveButtonResource").get(null) }

    private fun shown(b: SummaryButton): Boolean = b.visible && runCatching {
        @Suppress("UNCHECKED_CAST") (renderRequirement.get(b) as (SummaryButton) -> Boolean)(b)
    }.getOrDefault(true)

    private fun of(b: SummaryButton): SnapTarget {
        val k = runCatching { scale.getFloat(b) }.getOrDefault(1f).takeIf { it > 0f } ?: 1f
        val w = b.buttonWidth.toFloat() * k
        val h = b.buttonHeight.toFloat() * k
        return SnapTarget((b.buttonX + w / 2).toInt(), (b.buttonY + h / 2).toInt(), maxOf(3, (minOf(w, h) / 2).toInt()))
    }

    /** The party members shown on the side, in their order (empty slots left out). */
    fun partySlots(summary: Summary): List<PartySlotWidget> {
        val party = summary.sideScreen as? PartyWidget ?: return emptyList()
        return (partySlots.get(party) as List<*>).filterIsInstance<PartySlotWidget>()
            .filter { it.visible && slotPokemon.get(it) != null }
    }

    private val mainScreen by lazy { field(Summary::class.java, "mainScreen") }
    private val movesList by lazy { field(MovesWidget::class.java, "moves") }

    /** The moves shown on the moves page, in their order; empty on the other pages. */
    fun moveSlots(summary: Summary): List<MoveSlotWidget> {
        val page = mainScreen.get(summary) as? MovesWidget ?: return emptyList()
        return (movesList.get(page) as List<*>).filterIsInstance<MoveSlotWidget>().filter { it.visible }
    }

    fun evolveButton(summary: Summary): SummaryButton? =
        summary.children().filterIsInstance<SummaryButton>().firstOrNull { resource.get(it) == evolveResource && shown(it) }

    fun targets(summary: Summary): List<SnapTarget> {
        val out = ArrayList<SnapTarget>()
        evolveButton(summary)?.let { out += of(it) }
        partySlots(summary).forEach { out += ScreenTargets.of(it) }
        out += summary.children().filterIsInstance<SummaryTab>().filter { it.visible }.map { ScreenTargets.of(it) }
        out += summary.children().filterIsInstance<ExitButton>().filter { it.visible }.map { ScreenTargets.of(it) }
        (summary.sideScreen as? PartyWidget)?.let { party ->
            (swapButton.get(party) as? SummaryButton)?.takeIf { shown(it) }?.let { out += of(it) }
        }
        (heldItemButton.get(summary) as? SummaryButton)?.takeIf { shown(it) }?.let { out += of(it) }
        // the moves page: each move (its description), its reorder arrows (4x3 pixels) and its swap button
        moveSlots(summary).forEach { slot ->
            out += ScreenTargets.of(slot)
            slot.children.filterIsInstance<ClickableWidget>().filter { it.visible && it.active }.forEach { out += ScreenTargets.of(it) }
        }
        // another side panel (the evolutions, or the moves to learn instead): its rows
        (summary.sideScreen as? EntryListWidget<*>)?.let { ScreenTargets.rows(it, out) }
        return ScreenTargets.tidy(summary, out)
    }

    /** The party member after ([step] > 0) or before the one under (x, y); null off the party or at its end. */
    fun nextPartySlot(summary: Summary, x: Double, y: Double, step: Int): PartySlotWidget? {
        val slots = partySlots(summary)
        val i = slots.indexOfFirst { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
        if (i < 0) return null
        return slots.getOrNull(i + step)
    }

    /** The target nearest to (x, y): where the cursor settles when the stick lets go. */
    fun nearest(summary: Summary, x: Double, y: Double): SnapTarget? =
        targets(summary).minByOrNull { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }
}

/**
 * A controller's camera turn while riding a Cobblemon, handed to the mouse instead: Cobblemon
 * steers its mounts (a jet's pitch and roll, free look) from the mouse code only, which
 * Controlify's turn goes past. Added to the movement the mouse has not applied yet, scaled
 * back by the mouse sensitivity, so the turn ends up the same, through Cobblemon.
 */
object RideLook {
    @JvmStatic
    fun toMouse(player: ClientPlayerEntity, dx: Double, dy: Double): Boolean {
        if (!ModConfig.controllerCompat || player.vehicle !is PokemonEntity) return false
        val client = MinecraftClient.getInstance()
        if (!client.mouse.isCursorLocked || !client.isWindowFocused) return false
        val s = client.options.mouseSensitivity.value * 0.6 + 0.2
        val perPixel = s * s * s * 8.0
        val invert = if (client.options.invertYMouse.value) -1.0 else 1.0
        val m = client.mouse as MouseAccessor
        m.`hunterboard$setCursorDeltaX`(m.`hunterboard$getCursorDeltaX`() + dx / perPixel)
        m.`hunterboard$setCursorDeltaY`(m.`hunterboard$getCursorDeltaY`() + dy / perPixel * invert)
        return true
    }
}
