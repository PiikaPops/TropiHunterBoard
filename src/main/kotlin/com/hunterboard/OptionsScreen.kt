package com.hunterboard

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * HunterBoard's settings, as tabs of one-option-per-row lists.
 *
 * Every option is declared once, in [buildTabs], with its label, its control and its
 * description. Drawing, clicking and the hover tooltip are all derived from that single
 * declaration: each frame records the rectangles it drew, and input is matched against
 * them. The previous screen walked the layout twice — once to draw, once in mouseClicked
 * to guess where things had been drawn — and every section added since had pushed the
 * two further apart; this design has no second walk to drift.
 *
 * Rows share one geometry: label on the left, control in a column aligned on the right,
 * so nothing is placed by hand next to something else of a different width.
 */
class OptionsScreen(
    private val parent: Screen
) : ScaledScreen(Text.literal("Options")) {

    companion object {
        /** Reopening the screen lands on the tab that was open last. */
        private var lastTab = 0
        private val lastScroll = mutableMapOf<Int, Int>()

        /** How long the mouse must rest on an option before its description appears. */
        private const val HOVER_DELAY_MS = 2000L
        private const val RESET_CONFIRM_MS = 3000L

        private const val ROW_H = 18
        private const val WIDE_ROW_H = 32
        private const val INFO_H = 12
        private const val SECTION_H = 16
        private const val SECTION_GAP = 6
        private const val CONTROL_H = 14
        private const val TAB_H = 18

        /** Quick picks for the accent colour; the first is HunterBoard's default. */
        private val SWATCHES = intArrayOf(
            0xFFAA00, 0xFFD75E, 0x55FF55, 0x55FFFF, 0x5599FF, 0xAA77FF, 0xFF77CC, 0xFF5555
        )
    }

    private fun t(en: String, fr: String): String = if (Translations.isFrench()) fr else en

    // ── model ───────────────────────────────────────────────────────────────────────────

    private class Desc(val en: String, val fr: String)

    private abstract class Opt(val id: String, val label: () -> String, val desc: Desc?) {
        var enabled: () -> Boolean = { true }
        var visible: () -> Boolean = { true }
    }

    private class Toggle(id: String, label: () -> String, desc: Desc,
                         val get: () -> Boolean, val flip: () -> Unit) : Opt(id, label, desc)

    /** A value stepped through by clicking: left click forward, right click back. */
    private class Cycle(id: String, label: () -> String, desc: Desc,
                        val value: () -> String, val next: () -> Unit,
                        val prev: (() -> Unit)? = null) : Opt(id, label, desc)

    /** Segmented choice, spanning the whole row under its label. */
    private class Choice(id: String, label: () -> String, desc: Desc,
                         val options: () -> List<String>, val selected: () -> Int,
                         val select: (Int) -> Unit,
                         /** Shown outlined: the value in effect when it is not the one chosen. */
                         val detected: () -> Int? = { null }) : Opt(id, label, desc)

    private class Slider(id: String, label: () -> String, desc: Desc,
                         val get: () -> Float, val min: Float, val max: Float,
                         val set: (Float) -> Unit, val format: (Float) -> String,
                         val color: () -> Int) : Opt(id, label, desc)

    private class Action(id: String, label: () -> String, desc: Desc,
                         val button: () -> String, val run: () -> Unit) : Opt(id, label, desc)

    /** A line of context under an option; not interactive. */
    private class Info(id: String, val text: () -> String) : Opt(id, text, null)

    private class Swatches(id: String, label: () -> String, desc: Desc) : Opt(id, label, desc)
    private class HexInput(id: String, label: () -> String, desc: Desc) : Opt(id, label, desc)

    private class Section(val title: () -> String, val opts: List<Opt>)
    private class Tab(val title: () -> String, val sections: List<Section>)

    private fun <T : Opt> T.enabledWhen(cond: () -> Boolean): T = apply { enabled = cond }
    private fun <T : Opt> T.visibleWhen(cond: () -> Boolean): T = apply { visible = cond }

    private val tabs: List<Tab> by lazy { buildTabs() }

    // ── per-frame geometry, recorded while drawing ──────────────────────────────────────

    /**
     * A clickable rectangle. [inList] marks those inside the scrolled list, which only
     * count where the list is visible — a row scrolled under the header keeps its
     * rectangle, and must not catch clicks aimed at the header.
     */
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val inList: Boolean = false,
                      /** Right click only steps a cycle back; everywhere else it does nothing. */
                      val rightClick: Boolean = false,
                      val onClick: (button: Int) -> Unit) {
        fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
    }

    private class Bar(val opt: Slider, val x: Int, val w: Int)

    private class RowRect(val opt: Opt, val y: Int, val h: Int)

    private val hits = mutableListOf<Hit>()
    private val bars = mutableMapOf<String, Bar>()
    private val rows = mutableListOf<RowRect>()

    private var draggingSlider: String? = null
    private var hoverId: String? = null
    private var hoverSince = 0L
    private var resetArmedUntil = 0L

    // Layout, recomputed each frame from the window size.
    private var panelX = 0; private var panelW = 0
    private var panelTop = 0; private var panelBottom = 0
    private var contentX = 0; private var contentRight = 0
    private var contentTop = 0; private var contentBottom = 0
    private var ctrlX = 0; private var ctrlW = 0

    // Scrolling, per tab.
    private var tab = lastTab
    private var scroll = lastScroll[tab] ?: 0
    private var contentHeight = 0
    private var sbDragging = false
    private var sbDragStartY = 0
    private var sbDragStartScroll = 0
    private var sbThumbY = 0
    private var sbThumbH = 0

    // Hex input for the accent colour.
    private lateinit var hexField: TextFieldWidget
    private var hexVisible = false
    private var updatingHex = false

    override fun renderBackground(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {}

    override fun init() {
        super.init()
        hexField = TextFieldWidget(textRenderer, 0, 0, 52, 12, Text.literal("Hex"))
        hexField.setMaxLength(6)
        hexField.text = currentHex()
        hexField.setTextPredicate { text -> text.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }
        hexField.setChangedListener { text ->
            if (!updatingHex && text.length == 6) {
                try {
                    ModConfig.setColor(text.substring(0, 2).toInt(16), text.substring(2, 4).toInt(16), text.substring(4, 6).toInt(16))
                } catch (_: Exception) {}
            }
        }
        hexField.visible = false
        addDrawableChild(hexField)
    }

    private fun currentHex() = "%02X%02X%02X".format(ModConfig.hudColorR, ModConfig.hudColorG, ModConfig.hudColorB)

    // ── the options ─────────────────────────────────────────────────────────────────────

    private fun buildTabs(): List<Tab> {
        val colorDesc = Desc(
            "Colour of the HUDs' titles and borders, and of HunterBoard's screens when the HunterBoard theme is on. Pick a swatch, tune red, green and blue, or type a hex code.",
            "Couleur des titres et bordures des HUD, et des écrans HunterBoard quand le thème HunterBoard est choisi. Choisissez une pastille, réglez rouge, vert et bleu, ou tapez un code hexadécimal."
        )
        val pct = { v: Float -> "${v.roundToInt()}%" }
        val scale = { v: Float -> "%.2fx".format(v) }

        return listOf(
            Tab({ t("General", "Général") }, listOf(
                Section({ t("Interface", "Interface") }, listOf(
                    Toggle("modernUi", { t("New interface (test)", "Nouvelle interface (test)") }, Desc(
                        "The textured interface in Cobblemon's style: charcoal casing, Pokédex-red title bar, cyan highlights. Turn it off to get the original interface back, with the theme chosen below, exactly as it was. In-game HUDs are not affected.",
                        "L'interface texturée dans le style de Cobblemon : boîtier anthracite, barre de titre rouge Pokédex, surbrillances cyan. Désactivez-la pour retrouver l'interface d'origine, avec le thème choisi ci-dessous, exactement comme avant. Les HUD en jeu ne changent pas."),
                        get = { ModConfig.modernUi }, flip = { ModConfig.toggleModernUi() }),
                    Cycle("theme", { t("Screen theme", "Thème des écrans") }, Desc(
                        "Colours and style of HunterBoard's screens (wiki, search, options). The HunterBoard theme uses your accent colour from the Appearance tab; the others bring their own palette. In-game HUDs are not affected.",
                        "Couleurs et style des écrans HunterBoard (wiki, recherche, options). Le thème HunterBoard utilise votre couleur d'accent, réglée dans l'onglet Apparence ; les autres ont leur propre palette. Les HUD en jeu ne changent pas."),
                        value = { themeDisplayName(ModConfig.uiTheme) }, next = { ModConfig.toggleUiTheme() }).enabledWhen { !ModConfig.modernUi },
                    Cycle("uiScale", { t("Interface scale", "Échelle d'interface") }, Desc(
                        "Largest scale HunterBoard's screens are drawn at. When Minecraft's GUI scale is higher, the screens are shrunk to this one so they fit. \"Minecraft\" simply follows your vanilla setting.",
                        "Taille maximale des écrans HunterBoard. Quand l'échelle GUI de Minecraft est plus grande, les écrans sont réduits à celle-ci pour tenir à l'écran. « Minecraft » suit simplement votre réglage vanilla."),
                        value = { UiScale.capLabel(ModConfig.uiScaleCap) }, next = { ModConfig.cycleUiScaleCap() }),
                    Info("guiScale") {
                        val gui = try { client?.window?.scaleFactor ?: 1.0 } catch (_: Exception) { 1.0 }
                        t("Minecraft GUI scale: ", "Échelle GUI de Minecraft : ") + "${gui.toInt()}x"
                    },
                )),
                Section({ t("Controller", "Manette") }, listOf(
                    Toggle("controllerCompat", { t("Controller support (Controlify)", "Compatibilité manette (Controlify)") }, Desc(
                        "Makes Tropimon's and Cobblemon's screens usable with a controller through Controlify. Their screens get Controlify's cursor (left stick, D-pad to jump from button to button, A to click), with its keyboard on text fields; Cobblemon's interaction wheel becomes a radial menu (stick to aim, A to choose, B to close, LB/RB for the next page); in the summary, LB/RB change tab and LT/RT the Pokémon; in the inventory, LB/RB jump to Tropimon's tabs at the top. In game, B sends out your Pokémon (Cobblemon's R key); party, summary and Pokédex can be put in Controlify's radial menu. Takes effect on the next screen opened; the buttons after a restart.",
                        "Rend les écrans de Tropimon et de Cobblemon utilisables à la manette avec Controlify. Leurs écrans ont le curseur de Controlify (stick gauche, croix pour sauter de bouton en bouton, A pour cliquer), avec son clavier sur les champs de texte ; la roue d'interaction de Cobblemon devient un menu radial (stick pour viser, A pour choisir, B pour fermer, LB/RB pour la page suivante) ; dans le résumé, LB/RB changent d'onglet et LT/RT de Pokémon ; dans l'inventaire, LB/RB sautent sur les onglets Tropimon du haut. En jeu, B envoie votre Pokémon (touche R de Cobblemon) ; l'équipe, le résumé et le Pokédex peuvent être mis dans le menu radial de Controlify. S'applique à l'écran suivant ; les boutons après un redémarrage."),
                        get = { ModConfig.controllerCompat }, flip = { ModConfig.toggleControllerCompat() })
                        .visibleWhen { net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("controlify") },
                )),
                Section({ "Pokéradar" }, listOf(
                    Cycle("radar", { t("Radar screen", "Écran du radar") }, Desc(
                        "Which screen the Pokéradar opens: HunterBoard's own, or TropiMod's original one. Both talk to the server the same way.",
                        "Écran ouvert par le Pokéradar : celui de HunterBoard ou celui d'origine de TropiMod. Les deux communiquent de la même façon avec le serveur."),
                        value = { if (ModConfig.useHunterRadar) "HunterBoard" else "TropiMod" }, next = { ModConfig.toggleUseHunterRadar() }),
                    Toggle("radarDark", { t("Dark screen", "Écran sombre") }, Desc(
                        "HunterBoard's radar uses the frame of Tropimon's Pokéradar. On: the screen inside the frame is dark, which makes the Pokémon and the percentages stand out. Off: the original cyan screen.",
                        "Le radar HunterBoard reprend le cadre du Pokéradar de Tropimon. Activé : l'écran à l'intérieur du cadre est sombre, ce qui fait ressortir les Pokémon et les pourcentages. Désactivé : l'écran cyan d'origine."),
                        get = { ModConfig.radarDark }, flip = { ModConfig.toggleRadarDark() }).enabledWhen { ModConfig.useHunterRadar },
                )),
                Section({ "PC" }, listOf(
                    Toggle("hunterPc", { t("HunterBoard PC", "PC HunterBoard") }, Desc(
                        "Adds a \"HunterBoard view\" button to Cobblemon's PC. It opens the whole PC on one screen: every box at a glance or a sortable list, filters (shiny, IVs, ability, type, nature, arrival date, tags…), the full detail of each Pokémon, multiple selection to release, move or arrange several at once, release candidates, favourites and tags. Everything goes through Cobblemon's usual requests, one at a time, each confirmed by the server.",
                        "Ajoute un bouton « Vue HunterBoard » au PC de Cobblemon. Il ouvre tout le PC sur un seul écran : toutes les boîtes d'un coup d'œil ou une liste triable, des filtres (shiny, IV, talent, type, nature, date d'arrivée, tags…), la fiche complète de chaque Pokémon, la sélection multiple pour relâcher, déplacer ou ranger plusieurs Pokémon à la fois, les candidats au relâchement, les favoris et les tags. Tout passe par les requêtes habituelles de Cobblemon, une à la fois, chacune confirmée par le serveur."),
                        get = { ModConfig.hunterPc }, flip = { ModConfig.toggleHunterPc() }),
                    Toggle("hunterPcFirst", { t("Open the HunterBoard PC first", "Ouvrir le PC HunterBoard d'office") }, Desc(
                        "On: opening a PC shows HunterBoard's PC straight away; its \"Back to the PC\" button still leads to Cobblemon's. Off: Cobblemon's PC opens as usual, with the \"HunterBoard view\" button. A PC opened to pick a Pokémon for something else (trade, daycare…) always stays Cobblemon's.",
                        "Activé : ouvrir un PC affiche directement le PC HunterBoard ; son bouton « PC de base » mène toujours à celui de Cobblemon. Désactivé : le PC de Cobblemon s'ouvre normalement, avec le bouton « Vue HunterBoard ». Un PC ouvert pour choisir un Pokémon pour autre chose (échange, pension…) reste toujours celui de Cobblemon."),
                        get = { ModConfig.hunterPcFirst }, flip = { ModConfig.toggleHunterPcFirst() })
                        .enabledWhen { ModConfig.hunterPc },
                    Toggle("classicPcFirst", { t("Open Cobblemon's PC first", "Ouvrir le PC de base") }, Desc(
                        "Tropimon Better PC replaces Cobblemon's PC as soon as it opens. On: Cobblemon's PC opens as usual, and a \"Better PC\" button in it opens Better PC. Off: Better PC opens straight away, as it does without HunterBoard.",
                        "Tropimon Better PC remplace le PC de Cobblemon dès son ouverture. Activé : le PC de Cobblemon s'ouvre normalement, et un bouton « Better PC » y ouvre Better PC. Désactivé : Better PC s'ouvre directement, comme sans HunterBoard."),
                        get = { ModConfig.classicPcFirst }, flip = { ModConfig.toggleClassicPcFirst() })
                        .visibleWhen { com.hunterboard.pc.HunterPc.betterPcInstalled },
                )),
                Section({ "Wiki" }, listOf(
                    Cycle("spawnDisplay", { t("Spawn conditions", "Conditions d'apparition") }, Desc(
                        "How the wiki lays out where and when a Pokémon spawns: as a table or as text.",
                        "Présentation des conditions d'apparition dans le wiki : sous forme de tableau ou de texte."),
                        value = { if (ModConfig.spawnDisplayMode == "table") t("Table", "Tableau") else t("Text", "Texte") },
                        next = { ModConfig.toggleSpawnDisplayMode() }),
                    Toggle("habitatFeed", { t("Tropimon habitats online", "Habitats Tropimon en ligne") }, Desc(
                        "Cobblemon never sends habitat data to players, and Tropimon replaces every habitat. HunterBoard downloads Tropimon's from its relay (the same small server as the raid card) when the game starts, on joining a server and every hour, and only when they changed; the last ones received stay available offline. Nothing is sent but the request. Until the file arrives, or with this off, the wiki shows no habitat (Cobblemon's own are not Tropimon's).",
                        "Cobblemon n'envoie jamais les données d'habitats aux joueurs, et Tropimon les remplace toutes. HunterBoard télécharge celles de Tropimon depuis son relais (le même petit serveur que pour la carte de raid) au lancement, à la connexion à un serveur et toutes les heures, et seulement quand elles ont changé ; les dernières reçues restent disponibles hors ligne. Rien n'est envoyé à part la demande. Tant que le fichier n'est pas reçu, ou si c'est désactivé, le wiki n'affiche aucun habitat (ceux de Cobblemon ne sont pas ceux de Tropimon)."),
                        get = { ModConfig.habitatFeed }, flip = { ModConfig.toggleHabitatFeed() }),
                    Info("habitatFeedState") {
                        val d = HabitatFeed.describe()
                        if (!ModConfig.habitatFeed) t("Habitats: off", "Habitats : désactivés")
                        else if (d == null) t("Habitats: waiting for the Tropimon Wiki", "Habitats : en attente du Wiki de Tropimon")
                        else t("Tropimon habitats of ", "Habitats Tropimon du ") + d
                    },
                )),
                Section({ "Chat" }, listOf(
                    Toggle("bossChat", { t("Hide raid damage messages", "Masquer les dégâts de raid") }, Desc(
                        "Removes the boss damage messages raids post in chat after every attack.",
                        "Retire du chat les messages de dégâts infligés au boss, que les raids publient à chaque attaque."),
                        get = { ModConfig.hideBossDamageChat }, flip = { ModConfig.toggleHideBossDamageChat() }),
                )),
            )),

            Tab({ "HUD" }, listOf(
                Section({ t("Panels", "Panneaux") }, listOf(
                    Action("position", { t("Position and size", "Position et taille") }, Desc(
                        "Opens the editor: drag a HUD to move it, and drag its bottom-right corner to resize it.",
                        "Ouvre l'éditeur : faites glisser un HUD pour le déplacer, et son coin inférieur droit pour le redimensionner."),
                        button = { t("Open editor", "Ouvrir l'éditeur") }, run = { client?.setScreen(HudPositionScreen(this)) }),
                    Toggle("showHunt", { t("Hunting board", "Tableau de chasse") }, Desc(
                        "Shows this week's hunting board: the Pokémon to catch, the Ball each one requires and its bounty. It fills in when you open the hunting board in game.",
                        "Affiche le tableau de chasse de la semaine : les Pokémon à capturer, la Ball demandée pour chacun et la prime. Il se remplit quand vous ouvrez le tableau de chasse en jeu."),
                        get = { ModConfig.showHuntHud }, flip = { ModConfig.toggleHuntHud() }),
                    Toggle("showRaid", { t("Raid timer", "Minuteur de raid") }, Desc(
                        "Shows the countdown to the next raid (Paris time), with a warning 5 minutes before it starts.",
                        "Affiche le compte à rebours jusqu'au prochain raid (heure de Paris), avec une alerte 5 minutes avant le début."),
                        get = { ModConfig.showRaidHud }, flip = { ModConfig.toggleRaidHud() }),
                    Toggle("raidCard", { t("Raid card", "Carte de raid") }, Desc(
                        "When a raid is announced, on the timetable or started by a player, a card shows the boss with its weaknesses and the time left before the raid starts (5 minutes). It goes away when the countdown ends; its key (Delete by default, in Controls) hides it and brings it back.",
                        "Quand un raid est annoncé, à l'horaire ou lancé par un joueur, une carte affiche le boss avec ses faiblesses et le temps restant avant le début du raid (5 minutes). Elle disparaît à la fin du décompte ; sa touche (Suppr par défaut, dans Commandes) la masque et la réaffiche."),
                        get = { ModConfig.raidCard }, flip = { ModConfig.toggleRaidCard() }),
                    Cycle("raidCardPos", { t("Raid card position", "Position de la carte de raid") }, Desc(
                        "Where the raid card appears. Pick a preset here, or drag the card anywhere in the position editor above (\"Position and size\").",
                        "Endroit où la carte de raid apparaît. Choisissez un emplacement prédéfini ici, ou glissez la carte où vous voulez dans l'éditeur de position ci-dessus (« Position et taille »)."),
                        value = { if (ModConfig.raidCardX >= 0) t("Custom", "Personnalisée") else when (ModConfig.raidCardAnchor) {
                            1 -> t("Top right", "Haut à droite"); 2 -> t("Top centre", "Haut au centre")
                            3 -> t("Top left", "Haut à gauche"); 4 -> t("Left, middle", "Gauche, au milieu")
                            else -> t("Right, middle", "Droite, au milieu") } },
                        next = { ModConfig.cycleRaidCardAnchor() }, prev = { ModConfig.cycleRaidCardAnchor(back = true) })
                        .enabledWhen { ModConfig.raidCard },
                    Toggle("raidShare", { t("Share the raid boss", "Partager le boss de raid") }, Desc(
                        "Tropimon does not say which Pokémon the boss is. When you are near it, your mod tells the other HunterBoard players through a small relay server; when you are not, it asks the relay. The raid's announcement is shared the same way, so that a player who joins just after it still gets the card. Only the raid is sent: no name, no position. Off: nothing is sent or asked.",
                        "Tropimon ne dit pas quel Pokémon est le boss. Quand vous êtes près de lui, votre mod l'indique aux autres joueurs HunterBoard via un petit serveur relais ; sinon, il le demande au relais. L'annonce du raid est partagée de la même façon, pour qu'un joueur qui se connecte juste après ait quand même la carte. Seul le raid est envoyé : ni pseudo, ni position. Désactivé : rien n'est envoyé ni demandé."),
                        get = { ModConfig.raidShare }, flip = { ModConfig.toggleRaidShare() }),
                    Toggle("showMiracle", { t("Miracles", "Miracles") }, Desc(
                        "Shows the miracles (boosts) active on the server and how long each has left.",
                        "Affiche les miracles (boosts) actifs sur le serveur et le temps qu'il reste à chacun."),
                        get = { ModConfig.showMiracleHud }, flip = { ModConfig.toggleMiracleHud() }),
                    Toggle("playtime", { t("Session time", "Temps de session") }, Desc(
                        "Shows on the HUD how long the current play session has lasted.",
                        "Affiche sur le HUD la durée de la session de jeu en cours."),
                        get = { ModConfig.showPlaytime }, flip = { ModConfig.toggleShowPlaytime() }),
                )),
                Section({ t("Behaviour", "Comportement") }, listOf(
                    Toggle("merged", { t("Merged HUD", "HUD combiné") }, Desc(
                        "Puts the hunting board, the raid timer and the miracles in a single panel. Off: three separate panels, each placed and sized on its own.",
                        "Réunit le tableau de chasse, le minuteur de raid et les miracles dans un seul panneau. Désactivé : trois panneaux séparés, placés et dimensionnés chacun de son côté."),
                        get = { ModConfig.mergedHudMode }, flip = { ModConfig.toggleMergedHud() }),
                    Toggle("hideBattle", { t("Hide during battles", "Masquer en combat") }, Desc(
                        "Hides every HunterBoard HUD during a Pokémon battle, to keep the battle screen clear. The PvP panels are not affected.",
                        "Cache tous les HUD HunterBoard pendant un combat Pokémon, pour dégager l'écran de combat. Les panneaux PvP ne sont pas concernés."),
                        get = { ModConfig.hideHudInBattle }, flip = { ModConfig.toggleHideHudInBattle() }),
                    Info("toggleKey") {
                        t("The HUD toggle key (B by default) hides or shows every HUD at once.",
                          "La touche du HUD (B par défaut) masque ou affiche tous les HUD d'un coup.")
                    },
                )),
            )),

            Tab({ t("Appearance", "Apparence") }, listOf(
                Section({ t("Hunting board", "Tableau de chasse") }, listOf(
                    Cycle("mode", { t("Detail", "Détail") }, Desc(
                        "What each row of the hunting board shows. Full: model, name and the Ball spelled out. Compact: the Ball as an icon. Minimal: model and name. Icon: the model alone. Icon+: model and Ball. Shortcut: the \$ key by default.",
                        "Ce qu'affiche chaque ligne du tableau. Complet : modèle, nom et Ball en toutes lettres. Compact : la Ball en icône. Minimal : modèle et nom. Icône : le modèle seul. Icône+ : modèle et Ball. Raccourci : touche \$ par défaut."),
                        value = { BoardState.MODE_LABELS[BoardState.displayMode] },
                        next = { BoardState.setDisplayMode((BoardState.displayMode + 1) % 5) },
                        prev = { BoardState.setDisplayMode((BoardState.displayMode + 4) % 5) }),
                    Cycle("layout", { t("Layout", "Disposition") }, Desc(
                        "List: one row per Pokémon. Grid: tiles side by side, more compact in height.",
                        "Liste : une ligne par Pokémon. Grille : des cases côte à côte, plus compactes en hauteur."),
                        value = { if (ModConfig.gridLayout) t("Grid", "Grille") else t("List", "Liste") },
                        next = { ModConfig.toggleGridLayout() }),
                    Cycle("models", { t("Pokémon", "Pokémon") }, Desc(
                        "Draws the board's Pokémon as animated 3D models, or as 2D sprites that are lighter to render.",
                        "Représente les Pokémon du tableau par leur modèle 3D animé, ou par un sprite 2D plus léger à afficher."),
                        value = { if (ModConfig.usePixelArt) t("2D sprites", "Sprites 2D") else t("3D models", "Modèles 3D") },
                        next = { ModConfig.togglePixelArt() }),
                )),
                Section({ t("Size", "Taille") }, listOf(
                    Slider("sizeMerged", { t("Merged panel", "Panneau combiné") }, Desc(
                        "Size of the merged panel. It can also be resized in the position editor, by dragging its corner.",
                        "Taille du panneau combiné. Il se redimensionne aussi dans l'éditeur de position, en tirant son coin."),
                        get = { ModConfig.hudScale() }, min = ModConfig.MIN_HUD_SCALE, max = ModConfig.MAX_HUD_SCALE,
                        set = { ModConfig.setHuntScale(it) }, format = scale, color = { UiKit.accent() })
                        .visibleWhen { ModConfig.mergedHudMode },
                    Slider("sizeHunt", { t("Hunting board", "Tableau de chasse") }, Desc(
                        "Size of the hunting board. It can also be resized in the position editor, by dragging its corner.",
                        "Taille du tableau de chasse. Il se redimensionne aussi dans l'éditeur de position, en tirant son coin."),
                        get = { ModConfig.huntScale() }, min = ModConfig.MIN_HUD_SCALE, max = ModConfig.MAX_HUD_SCALE,
                        set = { ModConfig.setHuntScale(it) }, format = scale, color = { UiKit.accent() })
                        .visibleWhen { !ModConfig.mergedHudMode },
                    Slider("sizeRaid", { t("Raid timer", "Minuteur de raid") }, Desc(
                        "Size of the raid timer. It can also be resized in the position editor, by dragging its corner.",
                        "Taille du minuteur de raid. Il se redimensionne aussi dans l'éditeur de position, en tirant son coin."),
                        get = { ModConfig.raidScale() }, min = ModConfig.MIN_HUD_SCALE, max = ModConfig.MAX_HUD_SCALE,
                        set = { ModConfig.setRaidScale(it) }, format = scale, color = { UiKit.accent() })
                        .visibleWhen { !ModConfig.mergedHudMode },
                    Slider("sizeMiracle", { t("Miracles", "Miracles") }, Desc(
                        "Size of the miracles panel. It can also be resized in the position editor, by dragging its corner.",
                        "Taille du panneau des miracles. Il se redimensionne aussi dans l'éditeur de position, en tirant son coin."),
                        get = { ModConfig.miracleScale() }, min = ModConfig.MIN_HUD_SCALE, max = ModConfig.MAX_HUD_SCALE,
                        set = { ModConfig.setMiracleScale(it) }, format = scale, color = { UiKit.accent() })
                        .visibleWhen { !ModConfig.mergedHudMode },
                    Action("sizeReset", { t("Default sizes", "Tailles par défaut") }, Desc(
                        "Puts every HUD back to its normal size.",
                        "Remet tous les HUD à leur taille normale."),
                        button = { t("Reset", "Rétablir") }, run = { ModConfig.setHudSizePreset(1); ModConfig.resetHudScales() }),
                )),
                Section({ t("Colours", "Couleurs") }, listOf(
                    Swatches("swatches", { t("Accent colour", "Couleur d'accent") }, colorDesc),
                    Slider("red", { t("Red", "Rouge") }, colorDesc,
                        get = { ModConfig.hudColorR.toFloat() }, min = 0f, max = 255f,
                        set = { ModConfig.setColor(it.roundToInt(), ModConfig.hudColorG, ModConfig.hudColorB) },
                        format = { "${it.roundToInt()}" }, color = { 0xFFFF5555.toInt() }),
                    Slider("green", { t("Green", "Vert") }, colorDesc,
                        get = { ModConfig.hudColorG.toFloat() }, min = 0f, max = 255f,
                        set = { ModConfig.setColor(ModConfig.hudColorR, it.roundToInt(), ModConfig.hudColorB) },
                        format = { "${it.roundToInt()}" }, color = { 0xFF55FF55.toInt() }),
                    Slider("blue", { t("Blue", "Bleu") }, colorDesc,
                        get = { ModConfig.hudColorB.toFloat() }, min = 0f, max = 255f,
                        set = { ModConfig.setColor(ModConfig.hudColorR, ModConfig.hudColorG, it.roundToInt()) },
                        format = { "${it.roundToInt()}" }, color = { 0xFF5599FF.toInt() }),
                    HexInput("hex", { t("Hex code", "Code hexadécimal") }, colorDesc),
                )),
                Section({ t("Background", "Fond") }, listOf(
                    Slider("opacity", { t("Opacity", "Opacité") }, Desc(
                        "Opacity of the HUDs' background: 0% makes it invisible, 100% fully opaque.",
                        "Opacité du fond des HUD : 0 % le rend invisible, 100 % totalement opaque."),
                        get = { ModConfig.hudOpacity.toFloat() }, min = 0f, max = 100f,
                        set = { ModConfig.setOpacity(it.roundToInt()) }, format = pct, color = { UiKit.TEXT_MUTED }),
                    Toggle("fullClear", { t("No background", "Sans fond") }, Desc(
                        "Removes the HUDs' background and borders entirely: only their content remains, laid straight over the game.",
                        "Retire complètement le fond et les bordures des HUD : seul leur contenu reste, posé directement sur le jeu."),
                        get = { ModConfig.fullClearMode }, flip = { ModConfig.toggleFullClear() }),
                )),
            )),

            Tab({ t("Hunts", "Chasses") }, listOf(
                Section({ t("Rank", "Grade") }, listOf(
                    Choice("rank", { t("Number of hunts", "Nombre de chasses") }, Desc(
                        "How many hunts to follow: Trainer 3, Super 4, Hyper 5, Master 6. Auto reads your rank from the Tropimon server when you join; picking a rank by hand turns Auto off.",
                        "Nombre de chasses à suivre : Dresseur 3, Super 4, Hyper 5, Master 6. Auto lit votre grade sur le serveur Tropimon à la connexion ; choisir un grade à la main désactive Auto."),
                        options = { listOf(t("Auto", "Auto")) + ModConfig.RANKS.map { Translations.tr(it.first) } },
                        selected = { if (ModConfig.rankAuto) 0 else ModConfig.rank + 1 },
                        select = { i ->
                            if (i == 0) ModConfig.setRankAuto(true) else ModConfig.setRank(i - 1)
                            if (BoardState.displayCount > ModConfig.maxHunts()) BoardState.setDisplayCount(ModConfig.maxHunts())
                        },
                        detected = { if (ModConfig.rankAuto) ModConfig.rank + 1 else null }),
                    Info("rankInfo") {
                        val count = t("${ModConfig.maxHunts()} hunts counted", "${ModConfig.maxHunts()} chasses comptées")
                        if (!ModConfig.rankAuto) count
                        else if (PlayerData.detectedRank != null) "$count · " + t("detected on the server", "détecté sur le serveur")
                        else "$count · " + t("waiting for the server", "en attente du serveur")
                    },
                )),
                Section({ t("Hunting board", "Tableau de chasse") }, listOf(
                    Toggle("autoHide", { t("\"Congrats\" when done", "« Bravo » une fois fini") }, Desc(
                        "Once every hunt of your rank is caught, the list gives way to \"Congrats!\". It comes back with the next board.",
                        "Quand toutes les chasses de votre grade sont capturées, la liste laisse place à « Bravo ! ». Elle revient avec le tableau suivant."),
                        get = { ModConfig.autoHideOnComplete }, flip = { ModConfig.toggleAutoHideOnComplete() }),
                    Toggle("hideCaught", { t("Hide caught Pokémon", "Masquer les capturés") }, Desc(
                        "Removes the Pokémon you have already caught from the board, instead of crossing them out.",
                        "Retire du tableau les Pokémon déjà capturés, au lieu de les barrer."),
                        get = { ModConfig.hideCaughtInHud }, flip = { ModConfig.toggleHideCaughtInHud() }),
                    Toggle("resetTimer", { t("Reset countdown", "Compte à rebours du reset") }, Desc(
                        "Shows at the bottom of the board how long is left before the hunts renew, every Wednesday at 9 pm (Paris time).",
                        "Affiche en bas du tableau le temps restant avant le renouvellement des chasses, chaque mercredi à 21 h (heure de Paris)."),
                        get = { ModConfig.huntResetTimer }, flip = { ModConfig.toggleHuntResetTimer() }),
                )),
                Section({ t("Battles and captures", "Combats et captures") }, listOf(
                    Toggle("battleHunt", { t("Target alert in battle", "Alerte de cible en combat") }, Desc(
                        "During a battle, tells you when the wild Pokémon is one of your hunt targets, and which Ball to use.",
                        "Pendant un combat, vous prévient quand le Pokémon sauvage est l'une de vos cibles de chasse, et indique la Ball à utiliser."),
                        get = { ModConfig.showBattleHuntHud }, flip = { ModConfig.toggleBattleHuntHud() }),
                    Toggle("captureCard", { t("Full capture card", "Carte de capture complète") }, Desc(
                        "Shows Tropimon's capture card on a single page: ability, IV quality and all six IVs together, instead of two pages that swap.",
                        "Affiche la carte de capture de Tropimon sur une seule page : talent, qualité des IV et les six IV ensemble, au lieu de deux pages qui alternent."),
                        get = { ModConfig.combinedCaptureCard }, flip = { ModConfig.toggleCombinedCaptureCard() }),
                )),
            )),

            Tab({ t("Capture filter", "Filtre Auto Capture") }, listOf(
                Section({ t("Filters", "Filtres") }, listOf(
                    Action("cfManage", { t("Your filters", "Vos filtres") }, Desc(
                        "Opens the filter editor. For each Pokémon you choose, set the conditions its captures must meet: total IV %, each IV, shininess, ability, gender, combined with AND, OR and NOT. A capture that fails its filter is released for you. Pokémon without a filter are never touched.",
                        "Ouvre l'éditeur de filtres. Pour chaque Pokémon choisi, réglez les conditions que ses captures doivent remplir : % d'IV total, chaque IV, shiny, talent, sexe, combinés avec ET, OU et NON. Une capture qui ne respecte pas son filtre est relâchée pour vous. Les Pokémon sans filtre ne sont jamais touchés."),
                        button = { t("Manage", "Gérer") }, run = { client?.setScreen(CaptureFilterScreen(this)) }),
                    Info("cfCount") {
                        val all = CaptureFilters.all()
                        val active = all.count { it.isActive() }
                        when {
                            all.isEmpty() -> t("No filter: no capture is touched.", "Aucun filtre : aucune capture n'est touchée.")
                            active == all.size -> t("$active active filter(s)", "$active filtre(s) actif(s)")
                            else -> t("$active active · ${all.size - active} inactive", "$active actif(s) · ${all.size - active} inactif(s)")
                        }
                    },
                )),
                Section({ t("Behaviour", "Fonctionnement") }, listOf(
                    Toggle("cfEnabled", { t("Automatic release", "Relâchement automatique") }, Desc(
                        "Master switch. Off: the filters are kept but nothing is released.",
                        "Interrupteur général. Désactivé : les filtres sont conservés mais rien n'est relâché."),
                        get = { ModConfig.captureFilterEnabled }, flip = { ModConfig.toggleCaptureFilter() }),
                    Cycle("cfDelay", { t("Delay before release", "Délai avant relâchement") }, Desc(
                        "Time between the announcement in chat and the release. During it, the [Cancel] link in the message, or the keep key on Tropimon's capture card, keeps the Pokémon. The release goes through the capture card, which only answers while it is up: a longer delay is cut short just before the card closes (its duration is set in Tropimon's options).",
                        "Temps entre l'annonce dans le chat et le relâchement. Pendant ce temps, le lien [Annuler] du message, ou la touche « garder » de la carte de capture Tropimon, garde le Pokémon. Le relâchement passe par la carte de capture, qui ne répond que tant qu'elle est affichée : un délai plus long est raccourci juste avant qu'elle se ferme (sa durée se règle dans les options Tropimon)."),
                        value = { if (ModConfig.captureFilterDelay == 0) t("Immediate", "Immédiat") else "${ModConfig.captureFilterDelay} s" },
                        next = { ModConfig.cycleCaptureFilterDelay() }, prev = { ModConfig.cycleCaptureFilterDelay(back = true) })
                        .enabledWhen { ModConfig.captureFilterEnabled },
                    Toggle("cfTest", { t("Test mode", "Mode test") }, Desc(
                        "Releases nothing: the chat says which captures would have been released. Handy to check a new filter.",
                        "Ne relâche rien : le chat indique quelles captures auraient été relâchées. Pratique pour vérifier un nouveau filtre."),
                        get = { ModConfig.captureFilterSimulate }, flip = { ModConfig.toggleCaptureFilterSimulate() })
                        .enabledWhen { ModConfig.captureFilterEnabled },
                    Toggle("cfKept", { t("Message when kept", "Message quand gardé") }, Desc(
                        "Also says in chat when a capture meets its filter and is kept, not only when one is released.",
                        "Indique aussi dans le chat quand une capture respecte son filtre et est gardée, pas seulement quand une est relâchée."),
                        get = { ModConfig.captureFilterNotifyKept }, flip = { ModConfig.toggleCaptureFilterNotifyKept() })
                        .enabledWhen { ModConfig.captureFilterEnabled },
                    Toggle("cfAnnounce", { t("Announce releases", "Annoncer les relâchements") }, Desc(
                        "One short chat line per automatic release, with a [Cancel] link while it is pending. Off: releases happen without a word.",
                        "Une courte ligne dans le chat par relâchement automatique, avec un lien [Annuler] tant qu'il est en attente. Désactivé : les relâchements se font sans message."),
                        get = { ModConfig.captureFilterAnnounce }, flip = { ModConfig.toggleCaptureFilterAnnounce() })
                        .enabledWhen { ModConfig.captureFilterEnabled },
                )),
                Section({ t("Good to know", "Bon à savoir") }, listOf(
                    Info("cfParty") { t("Releases press Release [N] on the capture card: party and PC alike.", "Le relâchement appuie sur Relâcher [N] de la carte de capture : équipe comme PC.") },
                    Info("cfPc") { t("Shinies, legendaries, mythicals and over 90 % IV are always kept.", "Shiny, légendaires, fabuleux et plus de 90 % d'IV sont toujours gardés.") },
                    Info("cfCmd") { t("/hbfilter opens the filters from anywhere.", "/hbfilter ouvre les filtres depuis n'importe où.") },
                )),
            )),

            Tab({ "PvP" }, listOf(
                Section({ t("Battles", "Combats") }, listOf(
                    Toggle("pvpOverlay", { t("Team panels", "Panneaux d'équipes") }, Desc(
                        "In a battle against a player, shows your team and your opponent's, with the HP and statuses revealed as the battle goes. The panels can be moved with the mouse.",
                        "Dans un combat contre un joueur, affiche votre équipe et celle de l'adversaire, avec les PV et statuts révélés au fil du combat. Les panneaux se déplacent à la souris."),
                        get = { ModConfig.pvpOverlayEnabled }, flip = { ModConfig.togglePvpOverlay() }),
                )),
                Section({ "Replays" }, listOf(
                    Toggle("replay", { t("Record replays", "Enregistrer les replays") }, Desc(
                        "Saves every battle between players as a file that plays in the Pokémon Showdown viewer. A clickable link appears in chat when the match ends.",
                        "Enregistre chaque combat entre joueurs dans un fichier lisible avec le lecteur Pokémon Showdown. Un lien cliquable apparaît dans le chat à la fin du match."),
                        get = { ModConfig.pvpReplayEnabled }, flip = { ModConfig.togglePvpReplay() }),
                    Toggle("replayMuted", { t("Start muted", "Démarrer sans son") }, Desc(
                        "New replays open without sound; the viewer's music button stays available.",
                        "Les nouveaux replays s'ouvrent sans son ; le bouton de musique du lecteur reste disponible."),
                        get = { ModConfig.replayMuted }, flip = { ModConfig.toggleReplayMuted() })
                        .enabledWhen { ModConfig.pvpReplayEnabled },
                    Action("replayFolder", { t("Replays folder", "Dossier des replays") }, Desc(
                        "Opens the folder where replays are saved (hunterboard/replays).",
                        "Ouvre le dossier où sont rangés les replays (hunterboard/replays)."),
                        button = { t("Open", "Ouvrir") }, run = {
                            try {
                                val dir = com.hunterboard.replay.ReplayWriter.replayDir()
                                java.nio.file.Files.createDirectories(dir)
                                Util.getOperatingSystem().open(dir.toUri())
                            } catch (_: Exception) {}
                        }),
                )),
            )),

            Tab({ t("Radar bar", "Barre radar") }, listOf(
                Section({ t("Locator bar", "Barre de localisation") }, listOf(
                    Toggle("lbEnabled", { t("Wide radar bar", "Barre radar large") }, Desc(
                        "Shows a wide compass-like bar at the top of the screen, with the same markers as Tropimon's radar.",
                        "Affiche en haut de l'écran une large barre façon boussole, avec les mêmes repères que le radar de Tropimon."),
                        get = { ModConfig.locatorBarEnabled }, flip = { ModConfig.toggleLocatorBar() }),
                    Toggle("lbHideOriginal", { t("Hide Tropimon's bar", "Masquer la barre de Tropimon") }, Desc(
                        "Hides Tropimon's small radar bar above the XP bar, so the markers are not shown twice. The XP bar and your level stay visible.",
                        "Cache la petite barre radar de Tropimon au-dessus de la barre d'XP, pour ne pas voir les repères en double. La barre d'XP et votre niveau restent affichés."),
                        get = { ModConfig.locatorBarHideOriginal }, flip = { ModConfig.toggleLocatorHideOriginal() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                    Toggle("lbAlways", { t("Always visible", "Toujours visible") }, Desc(
                        "Keeps the bar on screen even when the radar detects nothing. Off: it only appears while the radar is active.",
                        "Garde la barre à l'écran même quand le radar ne détecte rien. Désactivé : elle n'apparaît que lorsque le radar est actif."),
                        get = { ModConfig.locatorBarShowEmpty }, flip = { ModConfig.toggleLocatorShowEmpty() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                    Toggle("lbArmor", { t("Above the armour bar", "Au-dessus de la barre d'armure") }, Desc(
                        "Places the bar just above your armour and health, over the hotbar, instead of at the top of the screen.",
                        "Place la barre juste au-dessus de l'armure et de la vie, au-dessus de la barre d'objets, au lieu du haut de l'écran."),
                        get = { ModConfig.locatorBarAboveArmor }, flip = { ModConfig.toggleLocatorAboveArmor() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                    Toggle("lbMenus", { t("Hide in menus", "Masquer dans les menus") }, Desc(
                        "Hides the bar while a menu or an inventory is open.",
                        "Cache la barre quand un menu ou un inventaire est ouvert."),
                        get = { ModConfig.locatorBarHideInMenus }, flip = { ModConfig.toggleLocatorHideInMenus() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                )),
                Section({ t("Dimensions", "Dimensions") }, listOf(
                    Slider("lbLength", { t("Length", "Longueur") }, Desc(
                        "Width of the bar, without changing its thickness.",
                        "Largeur de la barre, sans changer son épaisseur."),
                        get = { ModConfig.locatorBarLength }, min = 1f, max = 4f,
                        set = { ModConfig.setLocatorBarLength(it) }, format = { "%.1fx".format(it) }, color = { UiKit.accent() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                    Slider("lbSize", { t("Size", "Taille") }, Desc(
                        "Overall size of the bar and its icons.",
                        "Taille de la barre et de ses icônes."),
                        get = { ModConfig.locatorBarScale }, min = 0.5f, max = 6f,
                        set = { ModConfig.setLocatorBarScale(it) }, format = { "%.1fx".format(it) }, color = { UiKit.accent() })
                        .enabledWhen { ModConfig.locatorBarEnabled },
                    Slider("lbY", { t("Height on screen", "Hauteur à l'écran") }, Desc(
                        "Distance between the top of the screen and the bar.",
                        "Distance entre le haut de l'écran et la barre."),
                        get = { ModConfig.locatorBarY.toFloat() }, min = 0f, max = 160f,
                        set = { ModConfig.setLocatorBarY(it.roundToInt()) }, format = { "${it.roundToInt()} px" }, color = { UiKit.accent() })
                        .enabledWhen { ModConfig.locatorBarEnabled && !ModConfig.locatorBarAboveArmor },
                )),
            )),

            Tab({ t("Sounds", "Sons") }, listOf(
                Section({ "Raids" }, listOf(
                    Toggle("raidSound", { t("Raid sounds", "Sons de raid") }, Desc(
                        "Plays a sound when a raid starts, and another one 5 minutes before.",
                        "Joue un son au début de chaque raid, et un autre 5 minutes avant."),
                        get = { ModConfig.raidNotification }, flip = { ModConfig.toggleRaidNotification() }),
                    Cycle("raidRepeat", { t("Repeat", "Répétition") }, Desc(
                        "Repeats the raid sounds: 5 times for the start and 3 times for the warning, instead of once.",
                        "Répète les sons de raid : 5 fois pour le début et 3 fois pour l'alerte, au lieu d'une seule."),
                        value = { if (ModConfig.raidSoundRepeat) t("Repeated", "Répétés") else t("Once", "Une fois") },
                        next = { ModConfig.toggleRaidSoundRepeat() })
                        .enabledWhen { ModConfig.raidNotification },
                    Action("raidStartSound", { t("Start sound", "Son de début") }, Desc(
                        "Sound played when a raid starts. Click to pick another one.",
                        "Son joué au début d'un raid. Cliquez pour en choisir un autre."),
                        button = { soundName(ModConfig.raidStartSound) }, run = {
                            client?.setScreen(SoundPickerScreen(this, ModConfig.raidStartSound) { ModConfig.setRaidStartSound(it) })
                        }).enabledWhen { ModConfig.raidNotification },
                    Action("raidWarnSound", { t("Warning sound", "Son d'alerte") }, Desc(
                        "Sound played 5 minutes before a raid. Click to pick another one.",
                        "Son joué 5 minutes avant un raid. Cliquez pour en choisir un autre."),
                        button = { soundName(ModConfig.raidWarningSound) }, run = {
                            client?.setScreen(SoundPickerScreen(this, ModConfig.raidWarningSound) { ModConfig.setRaidWarningSound(it) })
                        }).enabledWhen { ModConfig.raidNotification },
                )),
                Section({ "Miracles" }, listOf(
                    Toggle("miracleSound", { t("Miracle sound", "Son des miracles") }, Desc(
                        "Plays a sound when a miracle (boost) is activated on the server.",
                        "Joue un son quand un miracle (boost) est activé sur le serveur."),
                        get = { ModConfig.miracleNotification }, flip = { ModConfig.toggleMiracleNotification() }),
                    Action("miraclePick", { t("Sound", "Son") }, Desc(
                        "Sound played when a miracle is activated. Click to pick another one.",
                        "Son joué à l'activation d'un miracle. Cliquez pour en choisir un autre."),
                        button = { soundName(ModConfig.miracleSound) }, run = {
                            client?.setScreen(SoundPickerScreen(this, ModConfig.miracleSound) { ModConfig.setMiracleSound(it) })
                        }).enabledWhen { ModConfig.miracleNotification },
                )),
                Section({ t("Ground clear", "Nettoyage du sol") }, listOf(
                    Toggle("clearSound", { t("Clear warning", "Alerte de nettoyage") }, Desc(
                        "Plays a sound 10 seconds before the items lying on the ground are cleared.",
                        "Joue un son 10 secondes avant la suppression des objets au sol."),
                        get = { ModConfig.clearWarningSound }, flip = { ModConfig.toggleClearWarningSound() }),
                    Action("clearPick", { t("Sound", "Son") }, Desc(
                        "Sound played before the ground is cleared. Click to pick another one.",
                        "Son joué avant le nettoyage du sol. Cliquez pour en choisir un autre."),
                        button = { soundName(ModConfig.clearWarningSoundId) }, run = {
                            client?.setScreen(SoundPickerScreen(this, ModConfig.clearWarningSoundId) { ModConfig.setClearWarningSound(it) })
                        }).enabledWhen { ModConfig.clearWarningSound },
                )),
                Section({ "Volume" }, listOf(
                    Slider("volume", { t("Raids and miracles", "Raids et miracles") }, Desc(
                        "Volume of the raid and miracle sounds.",
                        "Volume des sons de raid et de miracle."),
                        get = { ModConfig.raidNotifVolume.toFloat() }, min = 0f, max = 100f,
                        set = { ModConfig.setRaidNotifVolume(it.roundToInt()) }, format = pct, color = { UiKit.accent() }),
                )),
            )),
        )
    }

    private fun soundName(id: String): String =
        id.substringAfter(":").replace(".", " ").replace("_", " ")
            .split(" ").filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    // ── rendering ───────────────────────────────────────────────────────────────────────

    override fun renderScaled(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        hits.clear(); bars.clear(); rows.clear()
        hexVisible = false
        UiKit.screenDim(context, width, height)

        layout()
        UiKit.panel(context, panelX, panelTop, panelW, panelBottom - panelTop)
        UiKit.header(context, textRenderer, panelX, panelTop, panelW, t("Options", "Options"))
        renderHeaderButtons(context, mouseX, mouseY)
        renderSidebar(context, mouseX, mouseY)
        renderContent(context, mouseX, mouseY)

        UiKit.footer(context, textRenderer, panelX, panelBottom, panelW, fit(
            t("Rest the mouse on an option for 2 s to see what it does · Esc to go back",
              "Laissez la souris 2 s sur une option pour sa description · Échap pour revenir"),
            panelW - 16))

        val version = "HunterBoard " + FabricLoader.getInstance().getModContainer(HunterBoard.MOD_ID)
            .map { it.metadata.version.friendlyString }.orElse("?")
        context.drawText(textRenderer, version, (width - textRenderer.getWidth(version)) / 2, height - 12, UiKit.TEXT_MUTED, true)

        hexField.visible = hexVisible
        if (!hexVisible && hexField.isFocused) hexField.isFocused = false
        if (!hexField.isFocused) {
            val now = currentHex()
            if (hexField.text != now) { updatingHex = true; hexField.text = now; updatingHex = false }
        }
        renderWidgets(context, mouseX, mouseY, delta)

        renderTooltip(context, mouseX, mouseY)
    }

    private fun layout() {
        // Nearly the whole width on small screens, where labels are the first to suffer.
        panelW = (width - 24).coerceIn(300, 560).coerceAtMost(width - 8)
        panelX = (width - panelW) / 2
        panelTop = 20
        panelBottom = height - 20
        val sideW = sidebarWidth()
        contentX = panelX + 8 + sideW + 6
        contentRight = panelX + panelW - 10
        contentTop = panelTop + 22
        contentBottom = panelBottom - 16
        ctrlW = ((contentRight - contentX) * 0.44).toInt().coerceIn(92, 180)
        ctrlX = contentRight - ctrlW - 4
    }

    private fun sidebarWidth(): Int =
        (tabs.maxOf { textRenderer.getWidth(it.title()) } + 20).coerceIn(70, 110)

    private fun renderHeaderButtons(context: DrawContext, mouseX: Int, mouseY: Int) {
        val closeX = panelX + panelW - 12
        val closeY = panelTop + 4
        UiKit.closeButton(context, textRenderer, closeX, closeY, mouseX, mouseY)
        hits += Hit(closeX - 2, closeY - 2, 12, 13) { close() }

        // Reset everything, behind a second click: it cannot be undone.
        val armed = System.currentTimeMillis() < resetArmedUntil
        val label = if (armed) t("Confirm?", "Confirmer ?") else t("Reset all", "Tout réinitialiser")
        val w = textRenderer.getWidth(label) + 10
        val x = closeX - 8 - w
        val y = panelTop + 3
        val hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CONTROL_H
        UiKit.button(context, textRenderer, x, y, w, CONTROL_H, label, hov, active = armed)
        if (armed) UiKit.border(context, x, y, w, CONTROL_H, UiKit.DANGER)
        hits += Hit(x, y, w, CONTROL_H) {
            if (System.currentTimeMillis() < resetArmedUntil) {
                resetArmedUntil = 0L
                ModConfig.resetToDefaults()
                BoardState.setDisplayCount(ModConfig.maxHunts())
                HuntOverlay.loadPosition()
                RaidTimerOverlay.loadPosition()
                MiracleOverlay.loadPosition()
            } else {
                resetArmedUntil = System.currentTimeMillis() + RESET_CONFIRM_MS
            }
        }
    }

    private fun renderSidebar(context: DrawContext, mouseX: Int, mouseY: Int) {
        val x = panelX + 8
        val w = sidebarWidth()
        var y = contentTop + 2
        // Sunken well behind the tabs, so they read as one navigation column.
        context.fill(x - 2, contentTop, x + w + 2, contentBottom, UiKit.SURFACE_SUNKEN)
        for ((i, tb) in tabs.withIndex()) {
            val active = i == tab
            val hov = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + TAB_H
            if (active) UiKit.rowHighlight(context, x, y, w, TAB_H)
            else if (hov) context.fill(x, y, x + w, y + TAB_H, UiKit.SURFACE_HOVER)
            val color = when { active -> UiKit.accent(); hov -> UiKit.TEXT; else -> UiKit.TEXT_MUTED }
            context.drawText(textRenderer, fit(tb.title(), w - 10), x + 7, y + 5, color, true)
            hits += Hit(x, y, w, TAB_H) { switchTab(i) }
            y += TAB_H + 2
        }
    }

    private fun switchTab(i: Int) {
        if (i == tab) return
        lastScroll[tab] = scroll
        tab = i
        lastTab = i
        scroll = lastScroll[i] ?: 0
        hoverId = null
        hexField.isFocused = false
    }

    private fun measure(): Int {
        var h = 0
        for (section in tabs[tab].sections) {
            val shown = section.opts.filter { it.visible() }
            if (shown.isEmpty()) continue
            h += SECTION_H + shown.sumOf { heightOf(it) } + SECTION_GAP
        }
        return h + 8
    }

    private fun renderContent(context: DrawContext, mouseX: Int, mouseY: Int) {
        val viewH = contentBottom - contentTop
        contentHeight = measure()
        scroll = scroll.coerceIn(0, maxOf(0, contentHeight - viewH))
        lastScroll[tab] = scroll

        scissor(context, contentX - 2, contentTop, contentRight + 1, contentBottom)
        val startY = contentTop + 4 - scroll
        var y = startY
        val inView = mouseY >= contentTop && mouseY < contentBottom
        for (section in tabs[tab].sections) {
            val shown = section.opts.filter { it.visible() }
            if (shown.isEmpty()) continue
            UiKit.sectionTitle(context, textRenderer, section.title(), contentX, y + 3)
            UiKit.dots(context, contentX, y + 13, contentRight - contentX)
            y += SECTION_H
            for (opt in shown) {
                val h = heightOf(opt)
                val hovered = inView && opt !is Info && mouseX >= contentX && mouseX < contentRight &&
                    mouseY >= y && mouseY < y + h
                if (hovered && opt.enabled()) UiKit.rowHighlight(context, contentX, y, contentRight - contentX, h)
                renderOpt(context, opt, y, h, mouseX, mouseY, inView)
                rows += RowRect(opt, y, h)
                y += h
            }
            y += SECTION_GAP
        }
        context.disableScissor()

        // Scrollbar, only when the tab overflows.
        val sb = UiKit.scrollbar(context, panelX + panelW - 6, contentTop, contentBottom, contentHeight, scroll)
        if (sb != null) { sbThumbY = sb.first; sbThumbH = sb.second } else sbThumbH = 0
    }

    private fun heightOf(opt: Opt): Int = when (opt) {
        is Choice -> WIDE_ROW_H
        is Info -> INFO_H
        else -> ROW_H
    }

    private fun renderOpt(context: DrawContext, opt: Opt, y: Int, h: Int, mouseX: Int, mouseY: Int, inView: Boolean) {
        val live = opt.enabled()
        val labelColor = if (live) UiKit.TEXT else UiKit.TEXT_FAINT
        val cy = y + (ROW_H - CONTROL_H) / 2
        val ctrlHov = { x: Int, w: Int, top: Int, hh: Int ->
            live && inView && mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + hh
        }

        when (opt) {
            is Info -> {
                context.drawText(textRenderer, fit(opt.text(), contentRight - contentX - 10), contentX + 8, y + 2, UiKit.TEXT_FAINT, true)
            }

            is Toggle -> {
                val on = opt.get()
                val sw = 24
                val sx = ctrlX + ctrlW - sw
                val stateText = if (on) t("On", "Activé") else t("Off", "Désactivé")
                // Measured on the longer word, so the label does not shift as the state flips.
                val stateW = maxOf(textRenderer.getWidth(t("On", "Activé")), textRenderer.getWidth(t("Off", "Désactivé")))
                drawLabel(context, opt, y, labelColor, sx - 6 - stateW - 8)
                val stateColor = if (!live) UiKit.TEXT_FAINT else if (on) UiKit.accent() else UiKit.TEXT_MUTED
                context.drawText(textRenderer, stateText, sx - 6 - textRenderer.getWidth(stateText), y + 5, stateColor, true)
                drawSwitch(context, sx, y + 3, on, live, ctrlHov(contentX, contentRight - contentX, y, h))
                // The whole row toggles: aiming for a 24-pixel switch is needless work.
                if (live) hits += Hit(contentX, y, contentRight - contentX, h, inList = true) { opt.flip() }
            }

            is Cycle -> {
                drawLabel(context, opt, y, labelColor)
                val hov = ctrlHov(ctrlX, ctrlW, cy, CONTROL_H)
                UiKit.button(context, textRenderer, ctrlX, cy, ctrlW, CONTROL_H, "", hov)
                val text = fit(opt.value(), ctrlW - 22)
                val tc = if (!live) UiKit.TEXT_FAINT else if (hov) UiKit.accent() else UiKit.TEXT
                context.drawText(textRenderer, text, ctrlX + (ctrlW - textRenderer.getWidth(text)) / 2, cy + 3, tc, true)
                val arrows = if (live) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT
                context.drawText(textRenderer, "‹", ctrlX + 4, cy + 3, arrows, true)
                context.drawText(textRenderer, "›", ctrlX + ctrlW - 4 - textRenderer.getWidth("›"), cy + 3, arrows, true)
                if (live) hits += Hit(contentX, y, contentRight - contentX, h, inList = true, rightClick = true) { button ->
                    if (button == 1 && opt.prev != null) opt.prev.invoke() else opt.next()
                }
            }

            is Choice -> {
                drawLabel(context, opt, y, labelColor)
                val labels = opt.options()
                val x0 = contentX + 6
                val total = contentRight - 4 - x0
                val segW = total / labels.size
                val sy = y + ROW_H - 2
                val selected = opt.selected()
                val detected = opt.detected()
                for ((i, label) in labels.withIndex()) {
                    val sx = x0 + i * segW
                    val w = if (i == labels.size - 1) total - i * segW else segW - 2
                    val sel = i == selected
                    val det = detected == i && !sel
                    val hov = ctrlHov(sx, w, sy, CONTROL_H)
                    context.fill(sx, sy, sx + w, sy + CONTROL_H, when {
                        sel -> UiKit.withAlpha(UiKit.accent(), 0x40)
                        hov -> UiKit.SURFACE_HOVER
                        else -> UiKit.SURFACE
                    })
                    UiKit.border(context, sx, sy, w, CONTROL_H, when {
                        sel -> UiKit.accent(); det -> UiKit.TEXT_MUTED; hov -> UiKit.TEXT_FAINT; else -> UiKit.BORDER
                    })
                    val text = fit(label, w - 4)
                    val tc = when { !live -> UiKit.TEXT_FAINT; sel -> UiKit.accent(); det || hov -> UiKit.TEXT; else -> UiKit.TEXT_MUTED }
                    context.drawText(textRenderer, text, sx + (w - textRenderer.getWidth(text)) / 2, sy + 3, tc, true)
                    if (live) hits += Hit(sx, sy, w, CONTROL_H, inList = true) { opt.select(i) }
                }
            }

            is Slider -> {
                drawLabel(context, opt, y, labelColor)
                val valueW = 38
                val barX = ctrlX + 3
                val barW = ctrlW - valueW - 6
                val ratio = ((opt.get() - opt.min) / (opt.max - opt.min)).coerceIn(0f, 1f)
                val fill = (ratio * barW).roundToInt()
                val barY = y + ROW_H / 2 - 1
                context.fill(barX, barY, barX + barW, barY + 3, UiKit.SURFACE_SUNKEN)
                context.fill(barX, barY, barX + fill, barY + 3, if (live) opt.color() else UiKit.TEXT_FAINT)
                val hov = draggingSlider == opt.id || ctrlHov(barX - 4, barW + 8, y, h)
                val thumbX = barX + fill - 2
                context.fill(thumbX, y + 4, thumbX + 5, y + ROW_H - 4, if (!live) UiKit.TEXT_FAINT else if (hov) UiKit.TEXT else UiKit.TEXT_MUTED)
                val value = opt.format(opt.get())
                context.drawText(textRenderer, value, ctrlX + ctrlW - textRenderer.getWidth(value), y + 5,
                    if (live) UiKit.TEXT_MUTED else UiKit.TEXT_FAINT, true)
                if (live) {
                    bars[opt.id] = Bar(opt, barX, barW)
                    hits += Hit(barX - 4, y, barW + 8, h, inList = true) { _ -> draggingSlider = opt.id }
                }
            }

            is Action -> {
                drawLabel(context, opt, y, labelColor)
                val hov = ctrlHov(ctrlX, ctrlW, cy, CONTROL_H)
                UiKit.button(context, textRenderer, ctrlX, cy, ctrlW, CONTROL_H, fit(opt.button(), ctrlW - 8), hov)
                if (live) hits += Hit(ctrlX, cy, ctrlW, CONTROL_H, inList = true) { opt.run() }
            }

            is Swatches -> {
                drawLabel(context, opt, y, labelColor)
                val size = 11
                val gap = 3
                val n = SWATCHES.size.coerceAtMost((ctrlW + gap) / (size + gap))
                var sx = ctrlX + ctrlW - n * (size + gap) + gap
                val current = ModConfig.accentColor() and 0xFFFFFF
                for (i in 0 until n) {
                    val rgb = SWATCHES[i]
                    val hov = ctrlHov(sx - 1, size + 2, y + 3, size + 2)
                    context.fill(sx, y + 4, sx + size, y + 4 + size, 0xFF000000.toInt() or rgb)
                    UiKit.border(context, sx - 1, y + 3, size + 2, size + 2,
                        if (rgb == current) UiKit.TEXT else if (hov) UiKit.TEXT_MUTED else UiKit.BORDER)
                    hits += Hit(sx - 1, y + 3, size + 2, size + 2, inList = true) {
                        ModConfig.setColor((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                    }
                    sx += size + gap
                }
            }

            is HexInput -> {
                drawLabel(context, opt, y, labelColor)
                val fieldW = 52
                val fx = ctrlX + ctrlW - fieldW - 18
                context.drawText(textRenderer, "#", fx - 8, y + 5, UiKit.TEXT_MUTED, true)
                // Preview of the colour as it is now, beside the code that sets it.
                context.fill(ctrlX + ctrlW - 12, y + 3, ctrlX + ctrlW, y + 15, ModConfig.accentColor())
                UiKit.border(context, ctrlX + ctrlW - 12, y + 3, 12, 12, UiKit.TEXT_FAINT)
                hexField.x = fx
                hexField.y = y + 3
                // The field is a vanilla widget drawn after the scissor is lifted, so it is
                // only shown when it sits wholly inside the visible list.
                hexVisible = y + 3 >= contentTop && y + 15 <= contentBottom
            }
        }
    }

    /** The label, cut to end before [rightEdge] — by default the start of the control column. */
    private fun drawLabel(context: DrawContext, opt: Opt, y: Int, color: Int,
                          rightEdge: Int = if (opt is Choice) contentRight else ctrlX - 6) {
        context.drawText(textRenderer, fit(opt.label(), rightEdge - contentX - 8), contentX + 8, y + 5, color, true)
    }

    private fun drawSwitch(context: DrawContext, x: Int, y: Int, on: Boolean, live: Boolean, hovered: Boolean) {
        val w = 24; val h = 12
        if (UiKit.isModern) {
            UiKit.switch(context, x + 4, y + 1, on, live)
            return
        }
        val accent = UiKit.accent()
        context.fill(x, y, x + w, y + h, if (on && live) UiKit.withAlpha(accent, 0x55) else UiKit.SURFACE_SUNKEN)
        UiKit.border(context, x, y, w, h, when {
            !live -> UiKit.BORDER_DIM
            on -> accent
            hovered -> UiKit.TEXT_FAINT
            else -> UiKit.BORDER
        })
        val kx = if (on) x + w - 10 else x + 2
        context.fill(kx, y + 2, kx + 8, y + h - 2, when {
            !live -> UiKit.TEXT_FAINT
            on -> accent
            else -> UiKit.TEXT_MUTED
        })
    }

    /** Trims [text] to [maxW] pixels, ending with an ellipsis when it had to cut. */
    private fun fit(text: String, maxW: Int): String {
        if (maxW <= 0) return ""
        if (textRenderer.getWidth(text) <= maxW) return text
        val ell = "…"
        return textRenderer.trimToWidth(text, (maxW - textRenderer.getWidth(ell)).coerceAtLeast(0)) + ell
    }

    // ── tooltip ─────────────────────────────────────────────────────────────────────────

    private fun renderTooltip(context: DrawContext, mouseX: Int, mouseY: Int) {
        val inView = mouseY >= contentTop && mouseY < contentBottom && mouseX >= contentX && mouseX < contentRight
        val hovered = if (!inView || draggingSlider != null) null
            else rows.firstOrNull { mouseY >= it.y && mouseY < it.y + it.h && it.opt.desc != null }
        val id = hovered?.opt?.id
        if (id != hoverId) { hoverId = id; hoverSince = System.currentTimeMillis() }
        val opt = hovered?.opt ?: return
        val desc = opt.desc ?: return
        if (System.currentTimeMillis() - hoverSince < HOVER_DELAY_MS) return

        val maxW = (width - 20).coerceAtMost(230)
        val lines = textRenderer.wrapLines(Text.literal(if (Translations.isFrench()) desc.fr else desc.en), maxW)
        val title = opt.label()
        val w = maxOf(textRenderer.getWidth(title), lines.maxOfOrNull { textRenderer.getWidth(it) } ?: 0) + 12
        val h = 18 + lines.size * 10 + 4
        var x = mouseX + 12
        var y = mouseY + 10
        if (x + w > width - 4) x = (mouseX - 12 - w).coerceAtLeast(4)
        if (y + h > height - 4) y = (mouseY - 6 - h).coerceAtLeast(4)

        context.matrices.push()
        context.matrices.translate(0f, 0f, 400f)
        context.fill(x, y, x + w, y + h, UiKit.withAlpha(UiKit.PANEL_BOTTOM, 0xF4))
        UiKit.border(context, x, y, w, h, UiKit.withAlpha(UiKit.accent(), 0xCC))
        context.drawText(textRenderer, title, x + 6, y + 5, UiKit.accent(), true)
        context.fill(x + 6, y + 15, x + w - 6, y + 16, UiKit.BORDER_DIM)
        var ly = y + 19
        for (line in lines) {
            context.drawText(textRenderer, line, x + 6, ly, UiKit.TEXT, true)
            ly += 10
        }
        context.matrices.pop()
    }

    // ── input ───────────────────────────────────────────────────────────────────────────

    private fun applyBar(id: String, mouseX: Double) {
        val bar = bars[id] ?: return
        val ratio = ((mouseX - bar.x) / bar.w.toDouble()).coerceIn(0.0, 1.0).toFloat()
        bar.opt.set(bar.opt.min + (bar.opt.max - bar.opt.min) * ratio)
    }

    override fun mouseClickedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // The hex field is a vanilla widget: let it take its own clicks, and lose focus on
        // any click elsewhere.
        if (hexField.visible && hexField.isMouseOver(mouseX, mouseY)) {
            return super.mouseClickedScaled(mouseX, mouseY, button)
        }
        hexField.isFocused = false
        if (button != 0 && button != 1) return super.mouseClickedScaled(mouseX, mouseY, button)

        // Scrollbar
        val sbX = panelX + panelW - 6
        if (button == 0 && sbThumbH > 0 && mouseX >= sbX - 1 && mouseX < sbX + 5 &&
            mouseY >= contentTop && mouseY < contentBottom) {
            if (mouseY >= sbThumbY && mouseY < sbThumbY + sbThumbH) {
                sbDragging = true
                sbDragStartY = mouseY.toInt()
                sbDragStartScroll = scroll
            } else {
                val viewH = contentBottom - contentTop
                scroll = (((mouseY - contentTop) / viewH) * (contentHeight - viewH)).toInt()
            }
            return true
        }

        val inList = mouseX >= contentX - 2 && mouseX < contentRight + 1 &&
            mouseY >= contentTop && mouseY < contentBottom
        for (hit in hits) {
            if (!hit.contains(mouseX, mouseY) || (hit.inList && !inList) || (button == 1 && !hit.rightClick)) continue
            hit.onClick(button)
            // A slider hit only starts the drag; the value comes from where the click landed.
            draggingSlider?.let { applyBar(it, mouseX) }
            return true
        }
        return super.mouseClickedScaled(mouseX, mouseY, button)
    }

    override fun mouseDraggedScaled(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        if (button == 0 && sbDragging) {
            val viewH = contentBottom - contentTop
            val range = viewH - sbThumbH
            if (range > 0) {
                scroll = sbDragStartScroll + ((mouseY - sbDragStartY) / range * (contentHeight - viewH)).toInt()
            }
            return true
        }
        val id = draggingSlider
        if (button == 0 && id != null) {
            applyBar(id, mouseX)
            return true
        }
        return super.mouseDraggedScaled(mouseX, mouseY, button, deltaX, deltaY)
    }

    override fun mouseReleasedScaled(mouseX: Double, mouseY: Double, button: Int): Boolean {
        if (button == 0 && (sbDragging || draggingSlider != null)) {
            sbDragging = false
            draggingSlider = null
            return true
        }
        return super.mouseReleasedScaled(mouseX, mouseY, button)
    }

    override fun mouseScrolledScaled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val viewH = contentBottom - contentTop
        scroll = (scroll - (verticalAmount * 20).toInt()).coerceIn(0, maxOf(0, contentHeight - viewH))
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) { close(); return true }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun close() {
        lastScroll[tab] = scroll
        client?.setScreen(parent)
    }

    /** Localized display name of a theme id. */
    private fun themeDisplayName(id: String): String = Translations.tr(when (id) {
        "cobblemon" -> "Cobblemon Theme"
        "polaris" -> "Polaris Theme"
        "rebutsurmer" -> "Rebut-sur-mer Theme"
        "rhode" -> "Rhode Theme"
        "niavarane" -> "Niavarane Theme"
        "tropimon" -> "Tropimon Theme"
        else -> "HunterBoard Theme"
    })
}
