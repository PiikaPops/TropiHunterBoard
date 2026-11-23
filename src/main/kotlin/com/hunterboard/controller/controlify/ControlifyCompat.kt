package com.hunterboard.controller.controlify

import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelButton
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelGUI
import com.cobblemon.mod.common.client.gui.summary.Summary
import com.cobblemon.mod.common.client.keybind.keybinds.DownShiftPartyBinding
import com.cobblemon.mod.common.client.keybind.keybinds.PartySendBinding
import com.cobblemon.mod.common.client.keybind.keybinds.PokeNavigatorBinding
import com.cobblemon.mod.common.client.keybind.keybinds.SummaryBinding
import com.cobblemon.mod.common.client.keybind.keybinds.UpShiftPartyBinding
import com.hunterboard.HunterBoard
import com.hunterboard.ModConfig
import com.hunterboard.Translations
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.hunterboard.controller.ControllerScreens
import com.hunterboard.controller.PcControl
import com.hunterboard.pc.HunterPcScreen
import com.hunterboard.controller.ScreenTargets
import com.hunterboard.controller.SummaryTargets
import com.hunterboard.controller.InventoryTabs
import com.hunterboard.controller.SummaryControl
import com.hunterboard.controller.WheelAim
import com.hunterboard.controller.WheelControl
import dev.isxander.controlify.Controlify
import dev.isxander.controlify.InputMode
import dev.isxander.controlify.api.ControlifyApi
import dev.isxander.controlify.api.bind.ControlifyBindApi
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.api.bind.RadialIcon
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint
import dev.isxander.controlify.api.entrypoint.InitContext
import dev.isxander.controlify.api.entrypoint.PreInitContext
import dev.isxander.controlify.api.vmousesnapping.SnapPoint
import dev.isxander.controlify.bindings.BindContext
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.bindings.input.ButtonInput
import dev.isxander.controlify.bindings.input.EmptyInput
import dev.isxander.controlify.bindings.input.Input
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.GamepadInputs
import dev.isxander.controlify.screenop.ScreenControllerEventListener
import dev.isxander.controlify.screenop.ScreenProcessor
import dev.isxander.controlify.screenop.ScreenProcessorFactory
import dev.isxander.controlify.screenop.ScreenProcessorProvider
import dev.isxander.controlify.virtualmouse.VirtualMouseBehaviour
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.option.KeyBinding
import net.minecraft.item.ItemStack
import net.minecraft.registry.Registries
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.joml.Vector2d
import java.util.Collections
import java.util.Optional
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * HunterBoard's support for Controlify, the controller mod, on Tropimon's and Cobblemon's
 * screens. Controlify calls this through its "controlify" entrypoint only, so none of this
 * package is loaded, or needed, without it.
 *
 *  - Their screens are drawn for a mouse: they get Controlify's cursor ([CursorProcessor]).
 *  - Cobblemon's interaction wheel becomes a radial menu ([WheelProcessor]).
 *  - The summary changes tab and Pokémon with the shoulders and triggers ([SummaryProcessor]).
 *  - In the inventory, LB/RB jump to Tropimon's tabs above it ([InventoryTabsListener]).
 *  - Cobblemon's main keys get controller bindings ([Binds]).
 */
class ControlifyCompat : ControlifyEntrypoint {
    override fun onControlifyPreInit(ctx: PreInitContext) {
        // each part on its own: whatever Controlify changes, the game starts as without HunterBoard
        Safe.run("bindings") { Binds.register(ctx.bindings()) }
        // every screen without a processor of its own comes through here
        Safe.run("screens") {
            ScreenProcessorProvider.registerProvider(Screen::class.java, ScreenProcessorFactory.Factory<Screen> { Processors.forScreen(it) })
        }
        Safe.run("inventory tabs") { InventoryTabsListener.register() }
        Safe.run("riding inputs") { RideInputs.register() }
        HunterBoard.LOGGER.info("Controlify found: controller support for Tropimon's and Cobblemon's screens")
    }

    override fun onControlifyInit(ctx: InitContext) {}

    override fun onControllersDiscovered(api: ControlifyApi) {}
}

/**
 * Runs HunterBoard's part of Controlify's work so that a failure, a Cobblemon or Controlify
 * update renaming something, only turns that part off (logged once) instead of crashing.
 */
internal object Safe {
    private val reported = HashSet<String>()

    fun run(what: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            if (reported.add(what)) HunterBoard.LOGGER.warn("Controlify support: $what turned off (${e})")
        }
    }

    fun <T> get(what: String, fallback: T, block: () -> T): T =
        try { block() } catch (e: Throwable) {
            if (reported.add(what)) HunterBoard.LOGGER.warn("Controlify support: $what turned off (${e})")
            fallback
        }
}

/** True only when playing with the controller alone: mouse and keyboard, or both at once, keep Controlify's usual behaviour. */
internal fun controllerOnly(): Boolean =
    Safe.get("input mode", false) { Controlify.instance().currentInputMode() == InputMode.CONTROLLER }

/**
 * ShulkerBoxTooltip's preview of a shulker box's contents opens with Shift, which a controller
 * lacks (ShulkerPreviewMixin): with the controller in hand, the preview is always open.
 */
object ShulkerPreview {
    /** The development check's stand-in for the input mode (Controlify refuses the controller mode without one plugged in); null in play. */
    internal var testMode: InputMode? = null

    private fun mode(): InputMode? = testMode ?: Safe.get("shulker box preview", null) { Controlify.instance().currentInputMode() }

    /** The preview opens on its own: controller alone, or controller and keyboard together. */
    @JvmStatic
    fun open(): Boolean = ModConfig.controllerCompat && mode()?.isController == true

    /** "Alt: full preview", "Ctrl: lock": no use with the controller alone. */
    @JvmStatic
    fun hideKeyHints(): Boolean = ModConfig.controllerCompat && mode() == InputMode.CONTROLLER
}

internal object Processors {
    fun forScreen(screen: Screen): ScreenProcessor<in Screen> = Safe.get("processor for ${screen.javaClass.simpleName}", ScreenProcessor(screen)) {
        when {
            !ModConfig.controllerCompat -> ScreenProcessor(screen)
            screen is InteractWheelGUI -> WheelProcessor(screen)
            screen is Summary -> SummaryProcessor(screen)
            screen is PCGUI -> CobblemonPcProcessor(screen)
            screen is HunterPcScreen -> HunterPcProcessor(screen)
            ControllerScreens.wantsCursor(screen) -> CursorProcessor(screen)
            else -> ScreenProcessor(screen)
        }
    }
}

/** A short line of controller glyphs and labels, centred under a panel. */
internal object Hints {
    fun draw(ctx: DrawContext, centerX: Int, y: Int, parts: List<Pair<List<InputBindingSupplier>, String>>, controller: ControllerEntity) {
        val tr = MinecraftClient.getInstance().textRenderer
        ctx.drawCenteredTextWithShadow(tr, line(parts, controller), centerX, y, 0xFFFFFFFF.toInt())
    }

    fun line(parts: List<Pair<List<InputBindingSupplier>, String>>, controller: ControllerEntity, gap: String = "    "): Text {
        val line = Text.empty()
        for ((i, part) in parts.withIndex()) {
            if (i > 0) line.append(gap)
            for (bind in part.first) line.append(bind.on(controller).inputGlyph())
            line.append(" " + part.second)
        }
        return line
    }

    fun fr(en: String, fr: String) = if (Translations.isFrench()) fr else en
}

/**
 * A screen drawn for a mouse (Cobblemon's, Tropimon's, HunterBoard's): Controlify's cursor
 * is on as soon as it opens, so the buttons light up and click as they do with a mouse, and
 * the D-pad jumps from one to the next. A text field clicked opens Controlify's keyboard.
 */
internal open class CursorProcessor(screen: Screen) : ScreenProcessor<Screen>(screen) {
    private var lastFocus: Element? = null
    /** Where the cursor was at the end of the last update, in screen pixels. */
    protected var before: Pair<Double, Double>? = null

    override fun virtualMouseBehaviour(): VirtualMouseBehaviour = behaviourFor(controllerOnly())

    override fun handleScreenVMouse(controller: ControllerEntity, vmouse: VirtualMouseHandler) {
        super.handleScreenVMouse(controller, vmouse)
        before?.let { prev -> Safe.run("cursor moves") { afterControlify(controller, vmouse, prev) } }
        before = Cursor.position()
        Safe.run("keyboard on text fields") {
            val focus = screen.focused
            if (focus !== lastFocus) {
                lastFocus = focus
                // only a field just clicked: one focused by the screen on opening keeps quiet
                if (focus is TextFieldWidget && ControlifyBindings.VMOUSE_LCLICK.on(controller).justPressed()) {
                    tryOpenKeyboard(controller, focus)
                }
            }
        }
    }

    /**
     * Runs once Controlify has moved the cursor this update ([prev]: where it was). A D-pad
     * press up or down from inside a list that leaves the list, or goes nowhere, while the
     * list can still scroll that way: the list scrolls one row and the cursor stays, now on
     * the next row.
     */
    protected open fun afterControlify(controller: ControllerEntity, vmouse: VirtualMouseHandler, prev: Pair<Double, Double>) {
        val dir = when {
            ControlifyBindings.VMOUSE_SNAP_DOWN.on(controller).justPressed() -> 1
            ControlifyBindings.VMOUSE_SNAP_UP.on(controller).justPressed() -> -1
            else -> return
        }
        // the lists are laid out in the screen's own pixels (virtual ones on HunterBoard's capped screens)
        val was = ScreenTargets.toLayout(screen, prev)
        val list = ScreenTargets.lists(screen).firstOrNull { inside(it, was) } ?: return
        val now = Cursor.position()
        val movedInList = inside(list, ScreenTargets.toLayout(screen, now)) && (abs(now.first - prev.first) > 1 || abs(now.second - prev.second) > 1)
        if (movedInList) return
        if (ScreenTargets.scrollAt(screen, was.first, was.second, dir)) Cursor.moveTo(vmouse, prev.first.toInt(), prev.second.toInt())
    }

    private fun inside(w: ClickableWidget, p: Pair<Double, Double>) =
        p.first >= w.x && p.first < w.x + w.width && p.second >= w.y && p.second < w.y + w.height

    companion object {
        /** The cursor forced on with the controller alone; Controlify's own choice otherwise. */
        fun behaviourFor(controllerOnly: Boolean): VirtualMouseBehaviour =
            if (controllerOnly) VirtualMouseBehaviour.ENABLED else VirtualMouseBehaviour.DEFAULT
    }
}

/**
 * Cobblemon's interaction wheel (aiming at a player or one of your Pokémon) as a radial
 * menu: either stick or the D-pad points at a slice, A chooses it, B closes, LB/RB turn
 * the page when there are more options than slices.
 */
internal class WheelProcessor(screen: Screen) : ScreenProcessor<Screen>(screen) {
    private val wheel = screen as InteractWheelGUI
    var selected: InteractWheelButton? = null
        private set

    override fun virtualMouseBehaviour(): VirtualMouseBehaviour =
        if (controllerOnly()) VirtualMouseBehaviour.DISABLED else VirtualMouseBehaviour.DEFAULT

    private fun axis(c: ControllerEntity, plus: InputBindingSupplier, minus: InputBindingSupplier): Double =
        (plus.on(c).analogueNow() - minus.on(c).analogueNow()).toDouble()

    private fun pad(c: ControllerEntity, plus: InputBindingSupplier, minus: InputBindingSupplier): Double =
        (if (plus.on(c).digitalNow()) 1.0 else 0.0) - (if (minus.on(c).digitalNow()) 1.0 else 0.0)

    override fun handleComponentNavigation(controller: ControllerEntity) = Safe.run("radial wheel") {
        // the real mouse moved since the stick aimed: it has the wheel back
        if (selected != null && !WheelAim.isOn(wheel)) selected = null
        val rx = axis(controller, ControlifyBindings.RADIAL_AXIS_RIGHT, ControlifyBindings.RADIAL_AXIS_LEFT)
        val ry = axis(controller, ControlifyBindings.RADIAL_AXIS_DOWN, ControlifyBindings.RADIAL_AXIS_UP)
        val lx = axis(controller, ControlifyBindings.GUI_NAVI_RIGHT, ControlifyBindings.GUI_NAVI_LEFT)
        val ly = axis(controller, ControlifyBindings.GUI_NAVI_DOWN, ControlifyBindings.GUI_NAVI_UP)
        val px = pad(controller, ControlifyBindings.VMOUSE_SNAP_RIGHT, ControlifyBindings.VMOUSE_SNAP_LEFT)
        val py = pad(controller, ControlifyBindings.VMOUSE_SNAP_DOWN, ControlifyBindings.VMOUSE_SNAP_UP)
        val (dx, dy) = listOf(rx to ry, lx to ly, px to py).maxBy { (x, y) -> x * x + y * y }
        if (dx * dx + dy * dy >= 0.42 * 0.42) aim(dx, dy)
        // a page turned or the screen resized: the slice aimed at is gone
        val current = selected
        if (current != null && current !in WheelControl.buttons(wheel)) { selected = null; WheelAim.clear() }
    }

    /** Points at the slice nearest to (dx, dy), y down. */
    fun aim(dx: Double, dy: Double) {
        val button = WheelControl.pick(wheel, dx, dy) ?: return
        if (button !== selected) {
            selected = button
            playFocusChangeSound()
        }
        WheelControl.aim(wheel, button)
    }

    override fun handleButtons(controller: ControllerEntity) {
        if (ControlifyBindings.GUI_PRESS.on(controller).justPressed()) Safe.run("radial wheel") {
            selected?.let { if (WheelControl.press(it)) playClackSound() }
        }
        if (ControlifyBindings.GUI_BACK.on(controller).justPressed()) {
            playClackSound()
            WheelAim.clear()
            screen.close()
        }
    }

    override fun handleTabNavigation(controller: ControllerEntity) = Safe.run("radial wheel pages") {
        val step = when {
            ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() -> 1
            ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() -> -1
            else -> 0
        }
        if (step != 0 && WheelControl.page(wheel, step)) {
            selected = null
            WheelAim.clear()
            playClackSound()
        }
    }

    override fun onWidgetRebuild() {
        super.onWidgetRebuild()
        selected = null
        WheelAim.clear()
    }

    override fun onInputModeChanged(mode: InputMode) {
        super.onInputModeChanged(mode)
        if (!mode.isController) { selected = null; WheelAim.clear() }
    }

    override fun render(controller: ControllerEntity, graphics: DrawContext, tickDelta: Float, vmouse: Optional<VirtualMouseHandler>) {
        super.render(controller, graphics, tickDelta, vmouse)
        if (controllerOnly()) Safe.run("hints") { hints(controller, graphics) }
    }

    private fun hints(controller: ControllerEntity, graphics: DrawContext) {
        val parts = mutableListOf(
            listOf(ControlifyBindings.GUI_PRESS) to Hints.fr("Choose", "Choisir"),
            listOf(ControlifyBindings.GUI_BACK) to Hints.fr("Close", "Fermer"),
        )
        if (wheel.children().any { it is com.cobblemon.mod.common.client.gui.interact.wheel.ArrowButton && (it as ClickableWidget).visible }) {
            parts += listOf(ControlifyBindings.GUI_PREV_TAB, ControlifyBindings.GUI_NEXT_TAB) to Hints.fr("Page", "Page")
        }
        val bottom = (screen.height + InteractWheelGUI.SIZE) / 2
        Hints.draw(graphics, screen.width / 2, minOf(bottom + 8, screen.height - 12), parts, controller)
    }
}

/** Cobblemon's summary: the cursor as everywhere, plus LB/RB for the tabs and LT/RT for the Pokémon. */
internal class SummaryProcessor(screen: Screen) : CursorProcessor(screen) {
    private val summary = screen as Summary

    /**
     * After Controlify's own move: on a party member, the D-pad goes to the next one (down or
     * right) or the previous one (up or left), in party order rather than across the grid;
     * and when the stick lets go, the cursor settles on the nearest of the summary's targets,
     * so it never rests between them.
     */
    override fun afterControlify(controller: ControllerEntity, vmouse: VirtualMouseHandler, prev: Pair<Double, Double>) {
        super.afterControlify(controller, vmouse, prev)
        val step = when {
            ControlifyBindings.VMOUSE_SNAP_DOWN.on(controller).justPressed() || ControlifyBindings.VMOUSE_SNAP_RIGHT.on(controller).justPressed() -> 1
            ControlifyBindings.VMOUSE_SNAP_UP.on(controller).justPressed() || ControlifyBindings.VMOUSE_SNAP_LEFT.on(controller).justPressed() -> -1
            else -> 0
        }
        if (step != 0) {
            SummaryTargets.nextPartySlot(summary, prev.first, prev.second, step)?.let { slot ->
                Cursor.moveTo(vmouse, slot.x + slot.width / 2, slot.y + slot.height / 2)
            }
            return
        }
        val sticks = listOf(ControlifyBindings.VMOUSE_MOVE_UP, ControlifyBindings.VMOUSE_MOVE_DOWN, ControlifyBindings.VMOUSE_MOVE_LEFT, ControlifyBindings.VMOUSE_MOVE_RIGHT)
            .map { it.on(controller) }
        val moving = sticks.any { it.analogueNow() > 0.05f }
        val wasMoving = sticks.any { it.analoguePrev() > 0.05f }
        if (wasMoving && !moving) {
            val now = Cursor.position()
            SummaryTargets.nearest(summary, now.first, now.second)?.let { Cursor.moveTo(vmouse, it.x, it.y) }
        }
    }

    override fun handleTabNavigation(controller: ControllerEntity) {
        super.handleTabNavigation(controller)
        Safe.run("summary shortcuts") { shortcuts(controller) }
    }

    private fun shortcuts(controller: ControllerEntity) {
        when {
            ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() -> { SummaryControl.switchTab(summary, 1); playClackSound() }
            ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() -> { SummaryControl.switchTab(summary, -1); playClackSound() }
        }
        when {
            ControlifyBindings.VMOUSE_PAGE_DOWN.on(controller).justPressed() -> if (SummaryControl.switchPokemon(summary, 1)) playFocusChangeSound()
            ControlifyBindings.VMOUSE_PAGE_UP.on(controller).justPressed() -> if (SummaryControl.switchPokemon(summary, -1)) playFocusChangeSound()
        }
    }

    override fun render(controller: ControllerEntity, graphics: DrawContext, tickDelta: Float, vmouse: Optional<VirtualMouseHandler>) {
        super.render(controller, graphics, tickDelta, vmouse)
        if (controllerOnly()) Safe.run("hints") { hints(controller, graphics) }
    }

    private fun hints(controller: ControllerEntity, graphics: DrawContext) {
        val bottom = (screen.height + Summary.BASE_HEIGHT) / 2
        Hints.draw(graphics, screen.width / 2, minOf(bottom + 6, screen.height - 12), listOf(
            listOf(ControlifyBindings.GUI_PREV_TAB, ControlifyBindings.GUI_NEXT_TAB) to Hints.fr("Tab", "Onglet"),
            listOf(ControlifyBindings.VMOUSE_PAGE_UP, ControlifyBindings.VMOUSE_PAGE_DOWN) to "Pokémon",
        ), controller)
    }
}

/**
 * Cobblemon's PC: LB/RB turn the boxes, through its own arrow keys (a Pokémon in hand stays
 * in hand, to be put down in another box); LT jumps to the party and back to the box.
 */
internal class CobblemonPcProcessor(screen: Screen) : CursorProcessor(screen) {
    private val pc = screen as PCGUI
    private var backTo: Pair<Int, Int>? = null

    override fun handleTabNavigation(controller: ControllerEntity) {
        super.handleTabNavigation(controller)
        Safe.run("PC shortcuts") { shortcuts(controller) }
    }

    private fun shortcuts(controller: ControllerEntity) {
        when {
            ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() -> PcControl.turnBox(pc, 1)
            ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() -> PcControl.turnBox(pc, -1)
            ControlifyBindings.VMOUSE_PAGE_UP.on(controller).justPressed() -> partyAndBack()
        }
    }

    private fun partyAndBack() {
        val vmouse = Controlify.instance().virtualMouseHandler()
        if (!vmouse.isVirtualMouseEnabled) return
        val (x, y) = Cursor.position()
        fun on(w: ClickableWidget) = x >= w.x && x < w.x + w.width && y >= w.y && y < w.y + w.height
        val party = PcControl.partySlots(pc)
        if (party.any { on(it) }) {
            val back = backTo ?: PcControl.boxSlots(pc).firstOrNull()?.let { it.x + it.width / 2 to it.y + it.height / 2 } ?: return
            Cursor.moveTo(vmouse, back.first, back.second)
        } else {
            val first = party.firstOrNull() ?: return
            backTo = x.toInt() to y.toInt()
            Cursor.moveTo(vmouse, first.x + first.width / 2, first.y + first.height / 2)
        }
        playFocusChangeSound()
    }

    override fun render(controller: ControllerEntity, graphics: DrawContext, tickDelta: Float, vmouse: Optional<VirtualMouseHandler>) {
        super.render(controller, graphics, tickDelta, vmouse)
        if (controllerOnly()) Safe.run("hints") {
            Hints.draw(graphics, screen.width / 2, minOf((screen.height + PCGUI.BASE_HEIGHT) / 2 + 4, screen.height - 12), listOf(
                listOf(ControlifyBindings.GUI_PREV_TAB, ControlifyBindings.GUI_NEXT_TAB) to Hints.fr("Box", "Boîte"),
                listOf(ControlifyBindings.VMOUSE_PAGE_UP) to Hints.fr("Party", "Équipe"),
            ), controller)
        }
    }
}

/**
 * HunterBoard's PC, made for a mouse (drag and drop, every box at once): the D-pad goes cell
 * by cell across all the boxes and scrolls them, LB/RB put the cursor on the same cell of the
 * next box, RT moves the selection where the cursor is (as a drop would), LT jumps to the party
 * and back. A selects, X opens a Pokémon's menu, Y selects a range (shift click), as with a mouse.
 */
internal class HunterPcProcessor(screen: Screen) : CursorProcessor(screen) {
    private val pc = screen as HunterPcScreen
    private var backTo: Pair<Double, Double>? = null

    private fun here() = ScreenTargets.toLayout(screen, Cursor.position())

    private fun moveTo(vmouse: VirtualMouseHandler, p: IntArray) {
        val f = ScreenTargets.guiPerPixel(screen)
        Cursor.moveTo(vmouse, Math.round(p[0] * f), Math.round(p[1] * f))
    }

    /** The D-pad on the grid: the next cell or card that way, scrolled into view, whatever Controlify picked. */
    override fun afterControlify(controller: ControllerEntity, vmouse: VirtualMouseHandler, prev: Pair<Double, Double>) {
        val (dx, dy) = when {
            ControlifyBindings.VMOUSE_SNAP_UP.on(controller).justPressed() -> 0 to -1
            ControlifyBindings.VMOUSE_SNAP_DOWN.on(controller).justPressed() -> 0 to 1
            ControlifyBindings.VMOUSE_SNAP_LEFT.on(controller).justPressed() -> -1 to 0
            ControlifyBindings.VMOUSE_SNAP_RIGHT.on(controller).justPressed() -> 1 to 0
            else -> return
        }
        val was = ScreenTargets.toLayout(screen, prev)
        pc.controllerStep(was.first, was.second, dx, dy)?.let { moveTo(vmouse, it) }
    }

    override fun handleTabNavigation(controller: ControllerEntity) {
        super.handleTabNavigation(controller)
        Safe.run("PC shortcuts") { shortcuts(controller) }
    }

    private fun shortcuts(controller: ControllerEntity) {
        val vmouse = Controlify.instance().virtualMouseHandler()
        if (!vmouse.isVirtualMouseEnabled) return
        val (x, y) = here()
        when {
            ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() -> pc.controllerBoxStep(x, y, 1)?.let { moveTo(vmouse, it); playClackSound() }
            ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() -> pc.controllerBoxStep(x, y, -1)?.let { moveTo(vmouse, it); playClackSound() }
            ControlifyBindings.VMOUSE_PAGE_DOWN.on(controller).justPressed() -> if (pc.controllerDrop(x, y)) playFocusChangeSound()
            ControlifyBindings.VMOUSE_PAGE_UP.on(controller).justPressed() -> {
                if (pc.controllerOnParty(x, y)) {
                    val back = backTo
                    val to = if (back != null && pc.controllerOnGrid(back.first, back.second)) intArrayOf(back.first.toInt(), back.second.toInt())
                             else pc.controllerBoxStep(x, y, 0)
                    to?.let { moveTo(vmouse, it) }
                } else {
                    backTo = x to y
                    moveTo(vmouse, pc.controllerPartyCell(0))
                }
                playFocusChangeSound()
            }
        }
    }

    override fun render(controller: ControllerEntity, graphics: DrawContext, tickDelta: Float, vmouse: Optional<VirtualMouseHandler>) {
        super.render(controller, graphics, tickDelta, vmouse)
        pc.controllerHint = if (controllerOnly()) Safe.get("hints", null) { hint(controller) } else null
    }

    private fun hint(controller: ControllerEntity): Text {
        val (x, y) = here()
        val drop = pc.controllerDropHint(x, y)
        val parts = listOf(
            listOf(ControlifyBindings.GUI_PREV_TAB, ControlifyBindings.GUI_NEXT_TAB) to Hints.fr("Box", "Boîte"),
            listOf(ControlifyBindings.VMOUSE_PAGE_UP) to Hints.fr("Party", "Équipe"),
            listOf(ControlifyBindings.VMOUSE_PAGE_DOWN) to (drop ?: Hints.fr("Move here", "Déplacer ici")),
            listOf(ControlifyBindings.VMOUSE_RCLICK) to "Menu",
            listOf(ControlifyBindings.VMOUSE_SHIFT_CLICK) to Hints.fr("Range", "Plage"),
        )
        return Hints.line(parts, controller, "   ")
    }
}

/** The real mouse position in screen pixels, and moving Controlify's cursor there. */
internal object Cursor {
    fun position(): Pair<Double, Double> {
        val c = MinecraftClient.getInstance()
        val w = c.window
        return Pair(c.mouse.x * w.scaledWidth / w.width, c.mouse.y * w.scaledHeight / w.height)
    }

    /**
     * Controlify divides the point by the vector it is given, as its own D-pad snapping does
     * with (scaled size / window size): screen pixels in, window pixels out. Passed the GUI
     * scale itself, the cursor landed at x / scale² (towards the top-left corner).
     */
    fun moveTo(vmouse: VirtualMouseHandler, x: Int, y: Int, range: Int = 0) {
        val window = MinecraftClient.getInstance().window
        vmouse.snapToPoint(
            SnapPoint(x, y, range),
            Vector2d(window.scaledWidth.toDouble() / window.width, window.scaledHeight.toDouble() / window.height),
        )
    }
}

/**
 * The points Controlify's cursor jumps to, on the screens HunterBoard looks after
 * (ScreenSnapMixin): the summary's chosen few, elsewhere [ScreenTargets.generic].
 */
object SnapPoints {
    @JvmStatic
    fun collect(screen: Screen, consumer: java.util.function.Consumer<Any>): Boolean = Safe.get("snap points", false) {
        val targets = when {
            !ModConfig.controllerCompat -> null
            screen is Summary -> SummaryTargets.targets(screen)
            screen is HunterPcScreen -> PcControl.targets(screen)
            ControllerScreens.wantsCursor(screen) -> ScreenTargets.generic(screen)
            else -> null
        }
        targets?.forEach { consumer.accept(SnapPoint(it.x, it.y, it.range)) }
        targets != null
    }
}

/**
 * Cobblemon's mounts read the keyboard's forward and sprint keys themselves: a horse only
 * sprints with forward held (or tapped twice). Controlify moves the player without pressing
 * forward, so on a Cobblemon mount, with the controller alone, the stick pushed forward holds
 * the forward key; Controlify already holds the sprint key for its sprint button.
 */
internal object RideInputs {
    private var holding = false

    fun register() {
        ClientTickEvents.START_CLIENT_TICK.register { client -> Safe.run("riding inputs") { tick(client) } }
    }

    private fun tick(client: MinecraftClient) {
        val player = client.player
        val want = ModConfig.controllerCompat && player != null && client.currentScreen == null &&
            player.vehicle is com.cobblemon.mod.common.entity.pokemon.PokemonEntity &&
            controllerOnly() && player.input.movementForward > 0.5f
        if (want != holding) {
            client.options.forwardKey.isPressed = want
            holding = want
        }
    }
}

/**
 * In the player's inventory (Controlify's own processor, with its cursor), LB/RB put the
 * cursor on the buttons above the panel one after the other: Tropimon's tabs. A clicks.
 * Left alone while the recipe book is open, whose pages LB/RB turn.
 */
internal object InventoryTabsListener {
    private val attached: MutableSet<ScreenProcessor<*>> = Collections.newSetFromMap(WeakHashMap())

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is InventoryScreen || !ModConfig.controllerCompat) return@register
            Safe.run("inventory tabs") {
                val processor = ScreenProcessorProvider.provide(screen)
                if (attached.add(processor)) processor.addEventListener(object : ScreenControllerEventListener {
                    override fun onControllerInput(controller: ControllerEntity) = Safe.run("inventory tabs") { onInput(screen, controller) }
                })
            }
        }
    }

    private fun onInput(screen: InventoryScreen, controller: ControllerEntity) {
        if (!ModConfig.controllerCompat || MinecraftClient.getInstance().currentScreen !== screen) return
        if (screen.recipeBookWidget.isOpen) return
        val dir = when {
            ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() -> 1
            ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() -> -1
            else -> return
        }
        val tab = InventoryTabs.step(screen, dir) ?: return
        if (moveCursor(tab)) ScreenProcessor.playFocusChangeSound()
    }

    /** Puts Controlify's cursor on [widget]; false when the cursor is off. */
    fun moveCursor(widget: ClickableWidget): Boolean {
        val vmouse = Controlify.instance().virtualMouseHandler()
        if (!vmouse.isVirtualMouseEnabled) return false
        Cursor.moveTo(vmouse, widget.x + widget.width / 2, widget.y + widget.height / 2, minOf(widget.width, widget.height))
        return true
    }
}

/**
 * Controller bindings for Cobblemon's main keys, listed by Controlify under "HunterBoard
 * (Cobblemon)". By default: X sends out, the D-pad's up and down go up and down the party
 * (as the arrow keys do), View opens the summary; the Pokénav is left to the radial menu
 * (D-pad right).
 *
 * Those buttons carry Controlify defaults in game (swap hands, chat, drop, perspective):
 * `assets/controlify/controllers/default_bind/default.json` is a layer over Controlify's own
 * defaults that frees them (all four stay in the radial menu) and repeats the defaults
 * below. Only defaults: a binding the player changed stays as it is.
 */
internal object Binds {
    private fun icon(api: ControlifyBindApi, name: String, item: String): Identifier {
        val id = Identifier.of("hunterboard", "radial/$name")
        val stack by lazy {
            val found = Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", item))
                .or { Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", "poke_ball")) }
            found.map { ItemStack(it) }.orElse(ItemStack.EMPTY)
        }
        api.registerRadialIcon(id, RadialIcon { ctx, x, y, _ -> ctx.drawItem(stack, x, y) })
        return id
    }

    private fun bind(api: ControlifyBindApi, name: String, key: KeyBinding, default: Input, icon: Identifier) {
        api.registerBinding(ControlifyBindApi.RegistryCallback { b ->
            b.id("hunterboard", name)
                .name(Text.translatable("controlify.hunterboard.$name"))
                .description(Text.translatable("controlify.hunterboard.$name.desc"))
                .category(Text.translatable("controlify.hunterboard.category"))
                .defaultInput(default)
                .allowedContexts(BindContext.IN_GAME)
                .keyEmulation(key)
                .radialCandidate(icon)
        }) { ModConfig.controllerCompat }
    }

    fun register(api: ControlifyBindApi) {
        bind(api, "cobblemon_send", PartySendBinding, ButtonInput(GamepadInputs.WEST_BUTTON), icon(api, "send", "poke_ball"))
        bind(api, "party_next", DownShiftPartyBinding, ButtonInput(GamepadInputs.DPAD_DOWN_BUTTON), icon(api, "party_next", "great_ball"))
        bind(api, "party_prev", UpShiftPartyBinding, ButtonInput(GamepadInputs.DPAD_UP_BUTTON), icon(api, "party_prev", "ultra_ball"))
        bind(api, "summary", SummaryBinding, ButtonInput(GamepadInputs.BACK_BUTTON), icon(api, "summary", "exp_share"))
        bind(api, "pokenav", PokeNavigatorBinding, EmptyInput.INSTANCE, icon(api, "pokenav", "pokedex_red"))
    }
}
