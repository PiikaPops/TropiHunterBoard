package com.hunterboard

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.pokemon.Species
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Pick one Pokémon: a search box over every implemented species, matched on the French
 * and English names (accents and punctuation ignored) and on the Pokédex number. Arrow
 * keys and Enter work as well as the mouse. Species that already have a capture filter
 * say so, and picking one opens that filter instead of making a second.
 */
class SpeciesPickerScreen(
    private val parent: Screen,
    private val onPick: (Species) -> Unit
) : ScaledScreen(Text.literal("Pick a Pokémon")) {

    private companion object {
        const val ROW_H = 22
        var allSorted: List<Species>? = null
    }

    private fun t(en: String, fr: String) = if (Translations.isFrench()) fr else en

    private lateinit var search: TextFieldWidget
    private var shown: List<Species> = emptyList()
    private var scroll = 0
    private var cursor = 0
    private var lastQuery: String? = null

    private var px = 0; private var pw = 0; private var top = 0; private var bottom = 0
    private var listTop = 0; private var listBottom = 0

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        if (allSorted == null) {
            allSorted = try {
                PokemonSpecies.implemented.sortedBy { it.nationalPokedexNumber }
            } catch (_: Exception) { emptyList() }
        }
        layout()
        val previous = if (::search.isInitialized) search.text else ""
        search = TextFieldWidget(textRenderer, px + 10, top + 24, pw - 20, 16, Text.literal(""))
        UiKit.styleField(search)
        search.setMaxLength(40)
        search.text = previous
        search.setPlaceholder(Text.literal(t("Name or number…", "Nom ou numéro…")).styled { it.withColor(UiKit.TEXT_FAINT) })
        addDrawableChild(search)
        setInitialFocus(search)
        refresh()
    }

    private fun layout() {
        pw = (width - 40).coerceIn(240, 340)
        px = (width - pw) / 2
        top = 16
        bottom = height - 16
        listTop = top + 46
        listBottom = bottom - 18
    }

    private fun refresh() {
        val q = search.text.trim()
        if (q == lastQuery) return
        lastQuery = q
        scroll = 0
        cursor = 0
        val all = allSorted ?: emptyList()
        shown = if (q.isEmpty()) all else {
            val number = q.trimStart('#').toIntOrNull()
            all.filter { sp ->
                (number != null && sp.nationalPokedexNumber == number) ||
                NameUtil.matchesQuery(sp.name, q) ||
                NameUtil.matchesQuery(sp.translatedName.string, q) ||
                NameUtil.matchesQuery(Translations.speciesDisplayName(sp), q)
            }
        }
    }

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        layout()
        refresh()
        search.x = px + 10; search.y = top + 24; search.width = pw - 20

        UiKit.screenDim(context, width, height)
        UiKit.panel(context, px, top, pw, bottom - top)
        UiKit.header(context, textRenderer, px, top, pw, t("Choose a Pokémon", "Choisir un Pokémon"))
        val closeX = px + pw - 12
        UiKit.closeButton(context, textRenderer, closeX, top + 4, mouseX, mouseY)

        val viewH = listBottom - listTop
        val contentH = shown.size * ROW_H
        scroll = scroll.coerceIn(0, maxOf(0, contentH - viewH))

        scissor(context, px + 4, listTop, px + pw - 4, listBottom)
        val first = scroll / ROW_H
        val last = minOf(shown.size - 1, (scroll + viewH) / ROW_H)
        for (i in first..last) {
            if (i < 0) continue
            val sp = shown[i]
            val y = listTop + i * ROW_H - scroll
            val hov = mouseX >= px + 6 && mouseX < px + pw - 8 && mouseY >= y && mouseY < y + ROW_H &&
                mouseY in listTop until listBottom
            UiKit.rowStripe(context, px + 6, y, pw - 14, ROW_H, i)
            if (i == cursor || hov) UiKit.rowHighlight(context, px + 6, y, pw - 14, ROW_H)

            val id = SpriteHelper.getSpriteIdentifier(sp.name)
            if (id != null) {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend()
                context.drawTexture(id, px + 10, y + 1, 0f, 0f, 20, 20, 20, 20)
            }
            context.drawText(textRenderer, "#%03d".format(sp.nationalPokedexNumber), px + 34, y + 7, UiKit.TEXT_FAINT, false)
            context.drawText(textRenderer, sp.translatedName.string, px + 66, y + 7, UiKit.TEXT, true)
            if (CaptureFilters.names(sp.resourceIdentifier.path)) {
                val tag = t("filter ✓", "filtre ✓")
                context.drawText(textRenderer, tag, px + pw - 14 - textRenderer.getWidth(tag), y + 7, UiKit.accent(), false)
            }
        }
        context.disableScissor()
        UiKit.scrollbar(context, px + pw - 7, listTop, listBottom, contentH, scroll)

        if (shown.isEmpty()) {
            val msg = t("No Pokémon matches", "Aucun Pokémon ne correspond")
            context.drawText(textRenderer, msg, px + (pw - textRenderer.getWidth(msg)) / 2, listTop + 10, UiKit.TEXT_FAINT, true)
        }

        UiKit.footer(context, textRenderer, px, bottom, pw,
            t("↑ ↓ then Enter, or click · Esc to go back", "↑ ↓ puis Entrée, ou clic · Échap pour revenir"))
        UiKit.fieldBackground(context, search)
        renderWidgets(context, mouseX, mouseY, delta)
    }

    private fun pick(sp: Species) {
        onPick(sp)
        client?.setScreen(parent)
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val closeX = px + pw - 12
        if (button == 0 && mouseX >= closeX - 2 && mouseX < closeX + 10 && mouseY >= top + 2 && mouseY < top + 15) {
            close(); return true
        }
        if (button == 0 && mouseY >= listTop && mouseY < listBottom && mouseX >= px + 6 && mouseX < px + pw - 8) {
            val i = ((mouseY - listTop + scroll) / ROW_H).toInt()
            shown.getOrNull(i)?.let { pick(it); return true }
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        scroll = (scroll - (verticalAmount * ROW_H * 2).toInt()).coerceIn(0, maxOf(0, shown.size * ROW_H - (listBottom - listTop)))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_UP -> {
                if (shown.isEmpty()) return true
                cursor = (cursor + if (keyCode == GLFW.GLFW_KEY_DOWN) 1 else -1).coerceIn(0, shown.size - 1)
                val y = cursor * ROW_H
                val viewH = listBottom - listTop
                if (y < scroll) scroll = y
                if (y + ROW_H > scroll + viewH) scroll = y + ROW_H - viewH
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                shown.getOrNull(cursor)?.let { pick(it) }
                return true
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() {
        client?.setScreen(parent)
    }
}
