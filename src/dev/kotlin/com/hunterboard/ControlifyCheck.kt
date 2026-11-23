package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonProperties
import com.cobblemon.mod.common.client.CobblemonClient
import com.cobblemon.mod.common.util.party
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelGUI
import com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelOption
import com.cobblemon.mod.common.client.gui.interact.wheel.Orientation
import com.cobblemon.mod.common.client.gui.summary.Summary
import com.google.common.collect.ArrayListMultimap
import com.hunterboard.controller.InventoryTabs
import com.hunterboard.controller.RideLook
import com.hunterboard.controller.ScreenTargets
import com.hunterboard.controller.SummaryTargets
import dev.isxander.controlify.api.vmousesnapping.ISnapBehaviour
import com.hunterboard.controller.SummaryControl
import com.hunterboard.controller.WheelAim
import com.hunterboard.controller.WheelControl
import com.hunterboard.controller.controlify.CursorProcessor
import com.hunterboard.controller.controlify.InventoryTabsListener
import com.hunterboard.controller.controlify.ShulkerPreview
import com.hunterboard.controller.controlify.CobblemonPcProcessor
import com.hunterboard.controller.controlify.HunterPcProcessor
import com.hunterboard.controller.PcControl
import com.hunterboard.pc.HunterPcScreen
import com.cobblemon.mod.common.client.gui.pc.PCGUI
import com.cobblemon.mod.common.util.pc
import dev.isxander.controlify.InputMode
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.ContainerComponent
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.item.tooltip.TooltipType
import com.hunterboard.controller.controlify.SummaryProcessor
import com.hunterboard.controller.controlify.WheelProcessor
import dev.isxander.controlify.Controlify
import dev.isxander.controlify.api.bind.ControlifyBindApi
import dev.isxander.controlify.screenop.ScreenProcessorProvider
import dev.isxander.controlify.virtualmouse.VirtualMouseBehaviour
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.InventoryScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.Selectable
import net.minecraft.client.gui.widget.ElementListWidget
import net.minecraft.client.util.ScreenshotRecorder
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import org.joml.Vector3f
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * Development check of the Controlify support, without a controller: the processors
 * Controlify gets for Cobblemon's and HunterBoard's screens, the interaction wheel aimed in
 * its eight directions (Cobblemon's own highlight must follow), pressed and paged, the
 * summary's tabs and Pokémon, and the inventory's top tabs. Writes `controlify_check.txt`
 * and a few pictures in the tour folder.
 *
 * Started by UiTour when `-Puitour.controlify=1` is given; Controlify and YACL must be in
 * run/mods, with -Ploader_version=0.18.4 (Controlify wants at least 0.18).
 */
object ControlifyCheck {
    private var phase = 0
    private var wait = 0
    private var dir = 0
    private val log = StringBuilder()
    private var fails = 0
    private var pressed = ""
    private var wheel: InteractWheelGUI? = null
    private var controllerMode = false
    private var rideYaw = 0f
    private var ridePitch = 0f
    /** Draws a cross on each of the summary's targets, for the picture. */
    private var markTargets = false
    private var marking = false

    private fun markOnce() {
        if (marking) return
        marking = true
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is Summary) net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterRender(screen).register { _, ctx, _, _, _ ->
                if (markTargets) for (t in SummaryTargets.targets(screen)) {
                    ctx.fill(t.x - 4, t.y, t.x + 5, t.y + 1, 0xFFFF2020.toInt())
                    ctx.fill(t.x, t.y - 4, t.x + 1, t.y + 5, 0xFFFF2020.toInt())
                }
            }
        }
    }

    private fun note(s: String) { log.appendLine(s); HunterBoard.LOGGER.info("[ControlifyCheck] $s") }
    private fun check(ok: Boolean, what: String) { if (!ok) fails++; note((if (ok) "PASS " else "FAIL ") + what) }
    private fun shot(client: MinecraftClient, out: File, name: String) =
        ScreenshotRecorder.takeScreenshot(client.framebuffer).use { it.writeTo(File(out, "ctrl_$name.png")) }

    private val dirs = listOf(
        Orientation.EAST, Orientation.SOUTHEAST, Orientation.SOUTH, Orientation.SOUTHWEST,
        Orientation.WEST, Orientation.NORTHWEST, Orientation.NORTH, Orientation.NORTHEAST,
    )

    private fun option(name: String, icon: String) = InteractWheelOption(
        Identifier.of("cobblemon", "textures/gui/interact/interact_wheel_icon_$icon.png"), null, true,
        name, { Vector3f(1f, 1f, 1f) }, { pressed = name },
    )

    private fun <T> onServer(client: MinecraftClient, f: (net.minecraft.server.network.ServerPlayerEntity) -> T): T? {
        val server = client.server ?: return null
        val id = client.player?.uuid ?: return null
        return runCatching { server.submit<T> { f(server.playerManager.getPlayer(id)!!) }.get(5, TimeUnit.SECONDS) }
            .onFailure { note("server call failed: $it") }.getOrNull()
    }

    /** Moves the real mouse to (x, y) in GUI pixels, as the player's hand would. */
    private fun moveMouse(client: MinecraftClient, x: Double, y: Double) {
        val f = client.window.width.toDouble() / client.window.scaledWidth
        val m = net.minecraft.client.Mouse::class.java.getDeclaredMethod("onCursorPos", Long::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType)
            .apply { isAccessible = true }
        m.invoke(client.mouse, client.window.handle, x * f, y * f)
    }

    private var shulkerBox: ItemStack? = null
    private var moved: java.util.UUID? = null
    private var scaleBefore = 0

    /** ShulkerBoxTooltip hands its preview to the tooltip as the stack's tooltip data. */
    private fun previewShown(stack: ItemStack) =
        stack.tooltipData.map { it.javaClass.name.endsWith("PreviewTooltipComponent") }.orElse(false)

    private fun tooltipLines(stack: ItemStack) =
        stack.getTooltip(Item.TooltipContext.DEFAULT, MinecraftClient.getInstance().player, TooltipType.Default.BASIC).map { it.string }

    /** True once finished. */
    fun tick(client: MinecraftClient, out: File): Boolean {
        wait++
        when (phase) {
            0 -> {
                markOnce()
                // the players' GUI scale (the dev window is at 1, where screen and window pixels coincide)
                scaleBefore = client.options.guiScale.value
                client.options.guiScale.value = 2
                client.onResolutionChanged()
                note("GUI scale ${client.window.scaleFactor}")
                check(FabricLoader.getInstance().isModLoaded("controlify"), "Controlify is loaded")
                val ids = ControlifyBindApi.get().allBindIds.toList().map { it.toString() }.toSet()
                for (b in listOf("cobblemon_send", "party_next", "party_prev", "summary", "pokenav")) {
                    check("hunterboard:$b" in ids, "binding hunterboard:$b registered")
                }
                // default controls: HunterBoard's layer over Controlify's defaults
                val packs = client.resourceManager.getAllResources(net.minecraft.util.Identifier.of("controlify", "controllers/default_bind/default.json")).map { it.packId }
                note("default bind layers, lowest first: $packs")
                for (type in listOf("default", "dualsense")) {
                    val provider = Controlify.instance().defaultBindManager().getDefaultBindProvider(net.minecraft.util.Identifier.of("controlify", type))
                    fun bound(id: String) = provider.getDefaultBind(net.minecraft.util.Identifier.of(id.substringBefore(':'), id.substringAfter(':')))
                        ?.relevantInputs?.joinToString { it.path } ?: "?"
                    val want = mapOf("hunterboard:cobblemon_send" to "button/west", "hunterboard:party_prev" to "button/dpad_up",
                        "hunterboard:party_next" to "button/dpad_down", "hunterboard:summary" to "button/back",
                        "controlify:swap_hands" to "", "controlify:open_chat" to "", "controlify:drop" to "", "controlify:change_perspective" to "",
                        "controlify:jump" to "button/south", "controlify:radial_menu" to "button/dpad_right")
                    val got = want.keys.associateWith { bound(it) }
                    check(got == want, "default controls ($type): X sends out, D-pad up/down the party, View the summary, their Controlify actions freed ($got)")
                }
                // HunterBoard's own screen: Controlify's cursor
                val search = PokemonSearchScreen()
                client.setScreen(search)
                val p = ScreenProcessorProvider.provide(search)
                // no controller here: Controlify is installed but the player is on mouse and keyboard
                val mode = Controlify.instance().currentInputMode()
                note("input mode: $mode")
                check(p is CursorProcessor, "a HunterBoard screen gets the cursor processor (${p.javaClass.simpleName})")
                controllerMode = mode == dev.isxander.controlify.InputMode.CONTROLLER
                val expected = CursorProcessor.behaviourFor(controllerMode)
                check(p.virtualMouseBehaviour() == expected, "cursor ${if (controllerMode) "forced on with the controller" else "left alone on mouse and keyboard"} (${p.virtualMouseBehaviour()})")
                check(CursorProcessor.behaviourFor(true) == VirtualMouseBehaviour.ENABLED && CursorProcessor.behaviourFor(false) == VirtualMouseBehaviour.DEFAULT,
                    "the cursor is forced with the controller alone, never in mixed or mouse mode")
                phase = 1; wait = 0
            }
            1 -> if (wait > 5) {
                if (!controllerMode) check(!Controlify.instance().virtualMouseHandler().isVirtualMouseEnabled, "no virtual cursor on mouse and keyboard")
                // the interaction wheel: one option per direction, a second page in the north
                val options = ArrayListMultimap.create<Orientation, InteractWheelOption>()
                val icons = listOf("trade", "held_item", "battle", "cosmetic_item", "shoulder", "ride", "spectate_battle", "exclamation")
                for ((i, o) in dirs.withIndex()) options.put(o, option(o.name, icons[i]))
                options.put(Orientation.NORTH, option("NORTH_2", "battle"))
                val w = InteractWheelGUI(options, Text.literal("Test"))
                client.setScreen(w)
                wheel = w
                val p = ScreenProcessorProvider.provide(w)
                val wheelExpected = if (controllerMode) VirtualMouseBehaviour.DISABLED else VirtualMouseBehaviour.DEFAULT
                check(p is WheelProcessor && p.virtualMouseBehaviour() == wheelExpected,
                    "the interaction wheel gets the radial processor (${p.virtualMouseBehaviour()})")
                phase = 2; wait = 0; dir = 0
            }
            2 -> {
                val w = wheel ?: return true
                val p = ScreenProcessorProvider.provide(w) as WheelProcessor
                if (wait == 3) {
                    val o = dirs[dir]
                    val a = Math.toRadians(WheelControl.angleOf(o))
                    p.aim(cos(a) * 0.9, sin(a) * 0.9)
                } else if (wait == 6) {
                    val o = dirs[dir]
                    val sel = p.selected?.let { WheelControl.orientationOf(it) }
                    val hovered = WheelControl.buttons(w).filter { it.isHovered }.map { WheelControl.orientationOf(it) }
                    check(sel == o && hovered == listOf(o), "stick $o selects $o and only it is lit (selected $sel, lit $hovered)")
                    if (o == Orientation.NORTHEAST) shot(client, out, "wheel_ne")
                    dir++
                    wait = 0
                    if (dir == dirs.size) phase = 20
                }
            }
            20 -> {
                // the real mouse moving takes the wheel back from the stick
                val w = wheel ?: return true
                val p = ScreenProcessorProvider.provide(w) as WheelProcessor
                if (wait == 1) {
                    moveMouse(client, 0.0, 0.0)
                } else if (wait == 3) {
                    p.aim(1.0, 0.0)
                } else if (wait == 6) {
                    check(WheelAim.isOn(w), "the stick's aim holds while the mouse stays still")
                    val west = WheelControl.buttons(w).first { WheelControl.orientationOf(it) == Orientation.WEST }
                    val (x, y) = WheelControl.aimPoint(w, west)
                    moveMouse(client, x.toDouble(), y.toDouble())
                } else if (wait == 9) {
                    val lit = WheelControl.buttons(w).filter { it.isHovered }.map { WheelControl.orientationOf(it) }
                    check(!WheelAim.isOn(w) && lit == listOf(Orientation.WEST), "the mouse moved: it has the wheel back (lit $lit)")
                    // a plain mouse click, as without a controller
                    pressed = ""
                    val (x, y) = WheelControl.aimPoint(w, WheelControl.buttons(w).first { WheelControl.orientationOf(it) == Orientation.WEST })
                    w.mouseClicked(x.toDouble(), y.toDouble(), 0)
                    check(pressed == "WEST", "a mouse click on the wheel works as usual (\"$pressed\")")
                    phase = 3; wait = 0
                }
            }
            3 -> if (wait > 2) {
                val w = wheel ?: return true
                val p = ScreenProcessorProvider.provide(w) as WheelProcessor
                // a slightly off diagonal still lands on the nearest slice
                p.aim(0.9, -0.55)
                check(p.selected?.let { WheelControl.orientationOf(it) } == Orientation.NORTHEAST, "an off-diagonal push picks the nearest slice")
                p.aim(0.0, 1.0)
                check(WheelControl.press(p.selected!!) && pressed == "SOUTH", "A presses the slice aimed at (\"$pressed\")")
                val tip = com.cobblemon.mod.common.client.gui.interact.wheel.InteractWheelButton::class.java.getDeclaredField("iconResource").apply { isAccessible = true }
                val page = InteractWheelGUI::class.java.getDeclaredField("currentPage").apply { isAccessible = true }
                val before = WheelControl.buttons(w).map { tip.get(it).toString().substringAfterLast("icon_").removeSuffix(".png") }
                val p0 = page.getInt(w)
                val paged = WheelControl.page(w, 1)
                val after = WheelControl.buttons(w).map { tip.get(it).toString().substringAfterLast("icon_").removeSuffix(".png") }
                check(paged && page.getInt(w) != p0 && before != after, "LB/RB turn the page (page $p0 -> ${page.getInt(w)}: $before -> $after)")
                // on the second page, the aim still lands on what is shown
                p.aim(0.0, -1.0)
                val sel = p.selected?.let { tip.get(it).toString() } ?: ""
                check(sel.endsWith("icon_battle.png"), "the stick aims at the second page's slice ($sel)")
                phase = 4; wait = 0
            }
            4 -> if (wait > 5) {
                shot(client, out, "wheel_page2")
                // the summary: three Pokémon in the player's party, through the integrated server
                onServer(client) { p ->
                    val party = p.party()
                    for (i in 0 until 6) party.get(i)?.let { party.remove(it) }
                    for (name in listOf("pikachu", "eevee", "gible")) party.add(PokemonProperties.parse("$name level=20").create())
                    party.get(0)?.swapHeldItem(net.minecraft.item.ItemStack(net.minecraft.item.Items.APPLE), false)
                }
                phase = 50; wait = 0
            }
            50 -> if (wait > 20) {
                Summary.open(CobblemonClient.storage.party.slots.filterNotNull(), true, 0)
                phase = 5; wait = 0
            }
            5 -> if (wait > 10) {
                val s = client.currentScreen as? Summary
                check(s != null, "the summary opened")
                if (s == null) { phase = 7; return false }
                val p = ScreenProcessorProvider.provide(s)
                check(p is SummaryProcessor && p.virtualMouseBehaviour() == CursorProcessor.behaviourFor(controllerMode), "the summary gets its processor (${p.virtualMouseBehaviour()})")
                SummaryControl.switchTab(s, 1)
                check(SummaryControl.tab(s) == 1, "RB: moves tab (${SummaryControl.tab(s)})")
                SummaryControl.switchTab(s, -2)
                check(SummaryControl.tab(s) == 3, "LB twice from moves wraps to the marks tab (${SummaryControl.tab(s)})")
                SummaryControl.switchTab(s, -2)
                check(SummaryControl.switchPokemon(s, 1) && SummaryControl.selection(s) == 1, "RT: next Pokémon (${SummaryControl.selection(s)})")
                check(SummaryControl.switchPokemon(s, -1) && SummaryControl.switchPokemon(s, -1) && SummaryControl.selection(s) == 2, "LT twice wraps to the last Pokémon (${SummaryControl.selection(s)})")
                phase = 6; wait = 0
            }
            6 -> if (wait > 5) {
                shot(client, out, "summary")
                // the inventory with four tabs above it, as Tropimon puts them
                val inv = InventoryScreen(client.player!!)
                client.setScreen(inv)
                val top = (inv.height - 166) / 2
                for (i in 0 until 4) {
                    Screens.getButtons(inv).add(ButtonWidget.builder(Text.literal("T$i")) {}.dimensions(inv.width / 2 - 80 + i * 40, top - 22, 36, 20).build())
                }
                val order = listOf(1, 1, 1, 1, -1).map { InventoryTabs.step(inv, it)?.message?.string }
                check(order == listOf("T0", "T1", "T2", "T3", "T2"), "RB/LB go through the top tabs in order ($order)")
                val vm = Controlify.instance().virtualMouseHandler()
                vm.enableVirtualMouse()
                val tab = InventoryTabs.tabs(inv)[1]
                val moved = InventoryTabsListener.moveCursor(tab)
                val m = client.mouse
                val sx = m.x * inv.width / client.window.width
                val sy = m.y * inv.height / client.window.height
                check(moved && tab.isMouseOver(sx, sy), "the cursor lands on the tab (${"%.0f".format(sx)}, ${"%.0f".format(sy)})")
                vm.disableVirtualMouse()
                phase = 60; wait = 0
            }
            60 -> if (wait > 3) {
                // snap points on a HunterBoard screen: what Controlify gets is HunterBoard's list
                val search = PokemonSearchScreen()
                client.setScreen(search)
                val hidden = ButtonWidget.builder(Text.literal("hidden")) {}.dimensions(4, 4, 40, 20).build().apply { visible = false }
                val greyed = ButtonWidget.builder(Text.literal("greyed")) {}.dimensions(4, 30, 40, 20).build().apply { active = false }
                Screens.getButtons(search).add(hidden)
                Screens.getButtons(search).add(greyed)
                val targets = ScreenTargets.generic(search)
                check(targets.none { it.x == 24 && (it.y == 14 || it.y == 40) }, "hidden and greyed-out buttons are no target (${targets.size} targets)")
                val points = HashSet<Any>().also { set -> (search as ISnapBehaviour).`controlify$collectSnapPoints`(java.util.function.Consumer { set.add(it) }) }
                check(points.size == targets.size, "Controlify takes HunterBoard's targets (${points.size} points, ${targets.size} targets)")
                check(targets.all { it.range <= 12 }, "each target pulls from half its size at most (${targets.map { it.range }.distinct()})")
                phase = 600; wait = 0
            }
            600 -> if (wait > 3) {
                // Minecraft's GUI scale above HunterBoard's cap (a GUI scale of 4 with the cap at 2;
                // this window allows 2 at most, so the cap goes down to 1): the screen lays out in
                // virtual pixels, half the size of GUI pixels
                DevAccess.set(ModConfig, "uiScaleCap", 1)
                client.setScreen(PokemonSearchScreen())
                phase = 601; wait = 0
            }
            601 -> if (wait > 5) {
                val search = client.currentScreen as? PokemonSearchScreen
                if (search == null) { check(false, "search screen opened under the cap"); DevAccess.set(ModConfig, "uiScaleCap", 2); phase = 61; return false }
                val f = search.uiFactor
                val widgets = search.children().filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>().filter { it.visible && it.active }
                val targets = ScreenTargets.generic(search)
                note("capped screen: factor $f, ${widgets.size} widgets, targets ${targets.map { "${it.x},${it.y}" }}")
                check(f < 1f && widgets.isNotEmpty(), "HunterBoard's screen is capped (factor $f)")
                val vm = Controlify.instance().virtualMouseHandler()
                vm.enableVirtualMouse()
                val hits = widgets.map { w ->
                    val t = targets.minByOrNull { kotlin.math.abs(it.x - (w.x + w.width / 2) * f) + kotlin.math.abs(it.y - (w.y + w.height / 2) * f) }
                    if (t == null) false else {
                        com.hunterboard.controller.controlify.Cursor.moveTo(vm, t.x, t.y)
                        val (lx, ly) = ScreenTargets.toLayout(search, com.hunterboard.controller.controlify.Cursor.position())
                        w.isMouseOver(lx, ly)
                    }
                }
                vm.disableVirtualMouse()
                check(hits.all { it }, "on a capped HunterBoard screen the cursor jumps onto its widgets (${hits})")
                DevAccess.set(ModConfig, "uiScaleCap", 2)
                phase = 61; wait = 0
            }
            61 -> if (wait > 3) {
                client.setScreen(net.minecraft.client.gui.screen.option.LanguageOptionsScreen(null, client.options, client.languageManager))
                phase = 62; wait = 0
            }
            62 -> if (wait > 5) {
                val screen = client.currentScreen!!
                val list = ScreenTargets.lists(screen).firstOrNull()
                check(list != null, "the language list is found")
                if (list == null) { phase = 63; return false }
                val targets = ScreenTargets.generic(screen)
                val inList = targets.filter { it.x >= list.x && it.x < list.right && it.y >= list.y && it.y < list.bottom }
                val rows = list.children().size
                check(inList.isNotEmpty() && inList.size < rows, "only the rows on screen are targets (${inList.size} of $rows rows)")
                val h = (list as com.hunterboard.mixin.controller.EntryListAccessor).`hunterboard$itemHeight`()
                val firstBefore = inList.minOf { it.y }
                val start = list.scrollAmount
                val scrolled = ScreenTargets.scrollAt(screen, (list.x + list.width / 2).toDouble(), (list.bottom - 5).toDouble(), 1)
                check(scrolled && (list.scrollAmount - start).toInt() == h, "the D-pad past the last row scrolls one row ($start -> ${list.scrollAmount}, row $h)")
                val after = ScreenTargets.generic(screen).filter { it.x >= list.x && it.x < list.right && it.y >= list.y && it.y < list.bottom }
                check(after.isNotEmpty() && after.minOf { it.y } <= firstBefore, "the rows follow the scroll (first target y $firstBefore -> ${after.minOf { it.y }})")
                phase = 620; wait = 0
            }
            620 -> if (wait > 3) {
                client.setScreen(RowsScreen())
                phase = 621; wait = 0
            }
            621 -> if (wait > 5) {
                // Tropimon's rows: two homes side by side held in the row's fields; the capital's narrow list
                val s = client.currentScreen as? RowsScreen ?: run { check(false, "rows screen opened"); phase = 63; return false }
                val targets = ScreenTargets.generic(s)
                val pairs = targets.filter { it.x >= s.pairs.x && it.x < s.pairs.right && it.y >= s.pairs.y && it.y < s.pairs.bottom }
                val buttons = s.pairs.children().flatMap { listOf(it.first, it.second) }
                val onButtons = pairs.all { t -> buttons.any { b -> b.x + b.width / 2 == t.x && b.y + b.height / 2 == t.y && b.isMouseOver(t.x.toDouble(), t.y.toDouble()) } }
                check(pairs.size >= 4 && onButtons, "rows keeping their buttons in fields: each button is a target, on the button (${pairs.size} targets)")
                check(pairs.map { it.x }.distinct().size == 2, "both columns of a two-per-row list are reachable (${pairs.map { it.x }.distinct()})")
                val plain = targets.filter { it.x >= s.plain.x && it.x < s.plain.right && it.y >= s.plain.y && it.y < s.plain.bottom }
                val allPlain = targets.filter { it.y >= s.plain.y && it.y < s.plain.bottom && it.x >= s.plain.x }
                check(plain.isNotEmpty() && plain.size == allPlain.size && plain.all { it.x < s.plain.x + 100 },
                    "a narrow list's rows are aimed inside the list, on their 100-pixel button (x ${plain.map { it.x }.distinct()} for a list at ${s.plain.x})")
                shot(client, out, "rows_targets")
                phase = 63; wait = 0
            }
            63 -> if (wait > 3) {
                markTargets = true
                Summary.open(CobblemonClient.storage.party.slots.filterNotNull(), true, 0)
                phase = 64; wait = 0
            }
            64 -> if (wait > 10) {
                val s = client.currentScreen as? Summary ?: run { check(false, "summary reopened"); phase = 66; return false }
                val targets = SummaryTargets.targets(s)
                note("input mode ${Controlify.instance().currentInputMode()}, controller ${dev.isxander.controlify.api.ControlifyApi.get().currentController.map { it.name() }.orElse("none")}")
                note("summary targets: " + targets.joinToString { "(${it.x},${it.y} r${it.range})" })
                note("summary widgets: " + s.children().joinToString { it.javaClass.simpleName + ((it as? net.minecraft.client.gui.widget.ClickableWidget)?.let { w -> "@${w.x},${w.y} ${w.width}x${w.height}${if (w.visible) "" else " hidden"}" } ?: "") })
                val slots = SummaryTargets.partySlots(s)
                check(slots.size == 3, "the party members are targets (${slots.size})")
                check(SummaryTargets.evolveButton(s) == null, "no evolve target while there is nothing to evolve")
                check(targets.size == 3 + 4 + 1 + 1 + 1, "only the chosen targets: 3 Pokémon, 4 tabs, back, reorder, item (${targets.size})")
                val points = HashSet<Any>().also { set -> (s as ISnapBehaviour).`controlify$collectSnapPoints`(java.util.function.Consumer { set.add(it) }) }
                check(points.size == targets.size, "Controlify takes the summary's targets (${points.size})")
                val c = slots.map { (it.x + it.width / 2).toDouble() to (it.y + it.height / 2).toDouble() }
                val n1 = SummaryTargets.nextPartySlot(s, c[0].first, c[0].second, 1)
                val n2 = SummaryTargets.nextPartySlot(s, c[1].first, c[1].second, 1)
                val n3 = SummaryTargets.nextPartySlot(s, c[2].first, c[2].second, 1)
                val p1 = SummaryTargets.nextPartySlot(s, c[1].first, c[1].second, -1)
                check(n1 === slots[1] && n2 === slots[2] && n3 == null && p1 === slots[0], "the D-pad goes through the party in order (1->2->3, 2->1)")
                val tab = s.children().filterIsInstance<com.cobblemon.mod.common.client.gui.summary.widgets.screens.SummaryTab>().first()
                val near = SummaryTargets.nearest(s, tab.x + 2.0, tab.y + 2.0)
                check(near != null && near.x == tab.x + tab.width / 2 && near.y == tab.y + tab.height / 2, "letting go of the stick settles on the nearest target")
                val vm = Controlify.instance().virtualMouseHandler()
                vm.enableVirtualMouse()
                val next = slots[1]
                com.hunterboard.controller.controlify.Cursor.moveTo(vm, next.x + next.width / 2, next.y + next.height / 2)
                val (px, py) = com.hunterboard.controller.controlify.Cursor.position()
                check(next.isMouseOver(px, py), "the D-pad's jump to the next party member puts the cursor on it (${"%.0f".format(px)}, ${"%.0f".format(py)}, GUI scale ${client.window.scaleFactor})")
                vm.disableVirtualMouse()
                phase = 65; wait = 0
            }
            65 -> if (wait > 2) {
                shot(client, out, "summary_targets")
                markTargets = false
                client.setScreen(null)
                phase = 650; wait = 0
            }
            // the summary paused the game: mounting while paused sends the rider before the mount
            650 -> if (wait > 10) {
                // riding: a Latios (a jet) for the player, mounted through the integrated server
                onServer(client) { p ->
                    val pokemon = PokemonProperties.parse("latios level=50").create()
                    p.party().add(pokemon)
                    val entity = com.cobblemon.mod.common.entity.pokemon.PokemonEntity(p.serverWorld, pokemon)
                    entity.refreshPositionAndAngles(p.x, p.y, p.z, p.yaw, 0f)
                    p.serverWorld.spawnEntity(entity)
                    p.startRiding(entity, true)
                }
                phase = 66; wait = 0
            }
            66 -> if (wait > 30) {
                val player = client.player!!
                check(player.vehicle is com.cobblemon.mod.common.entity.pokemon.PokemonEntity, "the player rides the Latios (${player.vehicle?.type})")
                client.onWindowFocusChanged(true)
                client.mouse.lockCursor()
                val m = client.mouse as com.hunterboard.mixin.controller.MouseAccessor
                rideYaw = player.yaw; ridePitch = player.pitch
                val routed = RideLook.toMouse(player, 0.0, 40.0)
                val queued = m.`hunterboard$getCursorDeltaY`()
                check(routed && queued != 0.0, "on a Cobblemon mount the stick's turn goes to the mouse (queued $queued)")
                // off a mount, nothing changes
                phase = 67; wait = 0
            }
            67 -> if (wait > 5) {
                val player = client.player!!
                val m = client.mouse as com.hunterboard.mixin.controller.MouseAccessor
                val orientation = (player.vehicle as? com.cobblemon.mod.common.OrientationControllable)?.orientationController
                note("after: pitch $ridePitch -> ${player.pitch}, yaw $rideYaw -> ${player.yaw}, orientation active ${orientation?.isActive()}")
                check(m.`hunterboard$getCursorDeltaY`() == 0.0, "the mouse code took the turn (nothing left queued)")
                check(player.pitch != ridePitch || orientation?.isActive() == true, "the turn reached the ride (pitch $ridePitch -> ${player.pitch})")
                onServer(client) { p -> p.stopRiding() }
                phase = 68; wait = 0
            }
            68 -> if (wait > 10) {
                val player = client.player!!
                check(player.vehicle == null && !RideLook.toMouse(player, 0.0, 10.0), "on foot, Controlify turns the camera itself as before")
                phase = 69; wait = 0
            }
            69 -> {
                // ShulkerBoxTooltip's preview, Shift on the keyboard: a filled shulker box in the inventory
                if (!FabricLoader.getInstance().isModLoaded("shulkerboxtooltip")) {
                    note("ShulkerBoxTooltip not in run/mods: shulker box preview not checked")
                    phase = 80; return false
                }
                val box = ItemStack(Items.SHULKER_BOX)
                box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(listOf(
                    ItemStack(Items.DIAMOND, 5), ItemStack(Items.APPLE, 12), ItemStack(Items.ENDER_PEARL, 3), ItemStack(Items.DIAMOND, 7),
                )))
                // survival: the creative mode swaps the inventory for its own screen, and keeps the client's stacks
                onServer(client) { p ->
                    p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL)
                    p.inventory.setStack(9, box.copy())
                }
                shulkerBox = box
                phase = 690; wait = 0
            }
            690 -> if (wait > 10) {
                // the tooltip is the client's own: the box goes there too, whatever the server sends back
                shulkerBox?.let { client.player!!.inventory.setStack(9, it.copy()) }
                client.setScreen(InventoryScreen(client.player!!))
                phase = 70; wait = 0
            }
            70 -> if (wait > 10 && (client.player!!.inventory.getStack(9).isOf(Items.SHULKER_BOX) || wait > 200)) {
                val box = client.player!!.inventory.getStack(9)
                check(box.isOf(Items.SHULKER_BOX), "a filled shulker box in the inventory ($wait ticks)")
                val inv = client.currentScreen as? InventoryScreen
                val slot = inv?.screenHandler?.slots?.firstOrNull { it.inventory === client.player!!.inventory && it.index == 9 }
                if (inv != null && slot != null) moveMouse(client, (inv.width - 176) / 2 + slot.x + 8.0, (inv.height - 166) / 2 + slot.y + 8.0)
                // Controlify refuses the controller mode with no controller plugged in: the preview is told the mode instead
                note("input mode ${Controlify.instance().currentInputMode()}, controller ${dev.isxander.controlify.api.ControlifyApi.get().currentController.map { it.name() }.orElse("none")}")
                ShulkerPreview.testMode = InputMode.KEYBOARD_MOUSE
                check(!ShulkerPreview.open() && !previewShown(box), "on mouse and keyboard, the preview still waits for Shift")
                val keyboardLines = tooltipLines(box)
                ShulkerPreview.testMode = InputMode.MIXED
                check(ShulkerPreview.open() && previewShown(box), "controller and keyboard together: the preview opens without Shift")
                ShulkerPreview.testMode = InputMode.CONTROLLER
                check(ShulkerPreview.open() && previewShown(box), "with the controller, the shulker box shows its contents without Shift")
                val controllerLines = tooltipLines(box)
                note("tooltip, keyboard: $keyboardLines")
                note("tooltip, controller: $controllerLines")
                check(controllerLines.size < keyboardLines.size, "the hints naming keyboard keys are left out with the controller")
                ModConfig.toggleControllerCompat()
                check(!ShulkerPreview.open() && !previewShown(box), "controller support turned off in the options: Shift again")
                ModConfig.toggleControllerCompat()
                phase = 71; wait = 0
            }
            71 -> if (wait > 3) {
                shot(client, out, "shulker_preview")
                ShulkerPreview.testMode = null
                onServer(client) { p ->
                    p.inventory.setStack(9, ItemStack.EMPTY)
                    p.changeGameMode(net.minecraft.world.GameMode.CREATIVE)
                }
                client.setScreen(null)
                phase = 80; wait = 0
            }
            80 -> if (wait > 3) {
                // the summary's moves page: the moves, their reorder arrows and swap buttons
                Summary.open(CobblemonClient.storage.party.slots.filterNotNull(), true, 0)
                phase = 81; wait = 0
            }
            81 -> if (wait > 10) {
                val s = client.currentScreen as? Summary ?: run { check(false, "summary opened for the moves"); phase = 83; return false }
                SummaryControl.switchTab(s, 1)
                phase = 82; wait = 0
            }
            82 -> if (wait > 5) {
                val s = client.currentScreen as? Summary ?: run { check(false, "summary still open"); phase = 83; return false }
                val moves = SummaryTargets.moveSlots(s)
                val targets = SummaryTargets.targets(s)
                fun aimed(w: net.minecraft.client.gui.widget.ClickableWidget) = targets.any { it.x == w.x + w.width / 2 && it.y == w.y + w.height / 2 }
                val buttons = moves.flatMap { m -> m.children.filterIsInstance<net.minecraft.client.gui.widget.ClickableWidget>().filter { it.visible && it.active } }
                check(moves.isNotEmpty() && moves.all { aimed(it) }, "on the moves page each move is a target (${moves.size})")
                check(buttons.size >= moves.size * 3 && buttons.all { aimed(it) }, "its reorder arrows and swap buttons are targets (${buttons.size})")
                shot(client, out, "summary_moves")
                client.setScreen(null)
                phase = 83; wait = 0
            }
            83 -> if (wait > 3) {
                // the PCs: four Pokémon in the first box, server side
                onServer(client) { p ->
                    val pc = p.pc()
                    pc.toList().forEach { pc.remove(it) }
                    listOf("pikachu", "eevee", "zubat", "geodude").forEach { pc.add(PokemonProperties.parse("$it level=10").create()) }
                }
                phase = 84; wait = 0
            }
            84 -> if (wait > 10) {
                onServer(client) { p -> p.server.commandManager.executeWithPrefix(p.commandSource.withLevel(4), "pc") }
                phase = 85; wait = 0
            }
            85 -> {
                val gui = client.currentScreen as? PCGUI
                if (gui == null) { if (wait > 100) { check(false, "Cobblemon's PC opened"); phase = 7 }; return false }
                if (wait < 10) return false
                check(ScreenProcessorProvider.provide(gui) is CobblemonPcProcessor, "Cobblemon's PC gets the PC processor")
                val targets = ScreenTargets.generic(gui)
                val cells = PcControl.boxSlots(gui) + PcControl.partySlots(gui)
                check(cells.size >= 36 && cells.all { c -> targets.any { it.x == c.x + c.width / 2 && it.y == c.y + c.height / 2 } },
                    "every box and party cell of Cobblemon's PC is a target (${cells.size} cells, ${targets.size} targets)")
                val before = gui.storage.box
                PcControl.turnBox(gui, 1)
                val after = gui.storage.box
                PcControl.turnBox(gui, -1)
                check(after == before + 1 && gui.storage.box == before, "LB/RB turn Cobblemon's boxes ($before -> $after -> ${gui.storage.box})")
                shot(client, out, "cobblemon_pc")
                val btn = gui.children().filterIsInstance<ButtonWidget>().firstOrNull { it.message.string.contains("HunterBoard") }
                check(btn != null, "the HunterBoard view's button is in Cobblemon's PC")
                if (btn == null) { phase = 7; return false }
                btn.onPress()
                phase = 86; wait = 0
            }
            86 -> if (wait > 10) {
                val hp = client.currentScreen as? HunterPcScreen ?: run { check(false, "HunterBoard's PC opened"); phase = 7; return false }
                check(ScreenProcessorProvider.provide(hp) is HunterPcProcessor, "HunterBoard's PC gets its processor")
                val targets = PcControl.targets(hp)
                val start = hp.controllerBoxStep(-9999.0, -9999.0, 0)
                note("HunterBoard PC: ${targets.size} targets, first cell ${start?.toList()}")
                check(targets.size >= 66 && start != null, "HunterBoard's PC lists its cells, the party and its buttons (${targets.size})")
                if (start == null) { phase = 7; return false }
                fun step(p: IntArray, dx: Int, dy: Int) = hp.controllerStep(p[0].toDouble(), p[1].toDouble(), dx, dy)
                val right = step(start, 1, 0)
                val down = step(start, 0, 1)
                check(right != null && right[1] == start[1] && right[0] > start[0] && down != null && down[0] == start[0] && down[1] > start[1],
                    "the D-pad goes one cell right and one down (${right?.toList()}, ${down?.toList()})")
                check(step(start, -1, 0) == null && step(start, 0, -1) == null, "past the grid's edge the D-pad leaves it to Controlify")
                var p: IntArray = start
                repeat(6) { p = step(p, 1, 0) ?: p }
                val nextBox = hp.controllerBoxStep(start[0].toDouble(), start[1].toDouble(), 1)
                check(nextBox != null && p.contentEquals(nextBox), "six cells right is the next box's first cell, where RB goes (${p.toList()}, ${nextBox?.toList()})")
                val back = hp.controllerBoxStep(p[0].toDouble(), p[1].toDouble(), -1)
                check(back != null && back.contentEquals(start), "LB comes back to the same cell of the box before")
                // RT: the first Pokémon to the next box's last cell
                var to = nextBox ?: start
                repeat(5) { to = step(to, 1, 0) ?: to }
                repeat(4) { to = step(to, 0, 1) ?: to }
                moved = hp.session.pc.boxes[0].slots[0]?.uuid
                hp.selection.clear(); moved?.let { hp.selection.add(it) }
                val hint = hp.controllerDropHint(to[0].toDouble(), to[1].toDouble())
                val dropped = hp.controllerDrop(to[0].toDouble(), to[1].toDouble())
                check(moved != null && dropped, "RT moves the selection where the cursor is ($hint)")
                shot(client, out, "hunter_pc")
                phase = 87; wait = 0
            }
            87 -> if (wait > 40) {
                val at = onServer(client) { p -> p.pc().get(com.cobblemon.mod.common.api.storage.pc.PCPosition(1, 29))?.uuid }
                check(moved != null && at == moved, "the Pokémon is in the next box's last cell, server side")
                (client.currentScreen as? HunterPcScreen)?.close()
                phase = 7; wait = 0
            }
            7 -> {
                client.setScreen(null)
                client.options.guiScale.value = scaleBefore
                client.onResolutionChanged()
                client.options.write()   // something saves the options meanwhile: leave the file as it was
                note(if (fails == 0) "ALL PASSED" else "$fails FAILED")
                runCatching { File(out, "controlify_check.txt").writeText(log.toString()) }
                phase = 8
                return true
            }
            else -> return true
        }
        return false
    }
}

/** Two lists drawn the way Tropimon's are, for the cursor's targets. */
private class RowsScreen : Screen(Text.literal("rows")) {
    lateinit var pairs: PairList
    lateinit var plain: PlainList

    override fun init() {
        pairs = addDrawableChild(PairList(client!!).also { it.x = 20 })
        plain = addDrawableChild(PlainList(client!!).also { it.x = 230 })
    }
}

/** Like Tropimon's homes: two buttons per row, kept in fields, not listed as children, placed as the row draws. */
private class PairRow : ElementListWidget.Entry<PairRow>() {
    val first: ButtonWidget = ButtonWidget.builder(Text.literal("a")) {}.dimensions(0, 0, 80, 12).build()
    val second: ButtonWidget = ButtonWidget.builder(Text.literal("b")) {}.dimensions(0, 0, 80, 12).build()
    override fun children(): List<Element> = listOf()
    override fun selectableChildren(): List<Selectable> = listOf()
    override fun render(ctx: DrawContext, index: Int, y: Int, x: Int, w: Int, h: Int, mx: Int, my: Int, hovered: Boolean, delta: Float) {
        first.x = x; first.y = y; first.render(ctx, mx, my, delta)
        second.x = x + 84; second.y = y; second.render(ctx, mx, my, delta)
    }
}

private class PairList(client: MinecraftClient) : ElementListWidget<PairRow>(client, 180, 120, 40, 16) {
    init { repeat(12) { addEntry(PairRow()) } }
    override fun getRowLeft(): Int = x
    override fun getRowWidth(): Int = 170
}

/** Like Tropimon's capital: a 118-pixel list whose rows answer the mouse on their left 100 pixels only. */
private class PlainRow : ElementListWidget.Entry<PlainRow>() {
    override fun children(): List<Element> = listOf()
    override fun selectableChildren(): List<Selectable> = listOf()
    override fun render(ctx: DrawContext, index: Int, y: Int, x: Int, w: Int, h: Int, mx: Int, my: Int, hovered: Boolean, delta: Float) {
        ctx.fill(x, y - 1, x + 100, y + 11, 0xFF555555.toInt())
    }
}

private class PlainList(client: MinecraftClient) : ElementListWidget<PlainRow>(client, 118, 120, 40, 14) {
    init { repeat(12) { addEntry(PlainRow()) } }
    override fun getRowLeft(): Int = x
}
