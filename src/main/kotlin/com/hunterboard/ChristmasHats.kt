package com.hunterboard

import com.cobblemon.mod.common.client.render.models.blockbench.PosableModel
import com.cobblemon.mod.common.client.render.models.blockbench.repository.RenderContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.resource.ResourceManagerHelper
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener
import net.minecraft.client.MinecraftClient
import net.minecraft.client.model.ModelPart
import net.minecraft.client.render.RenderLayer
import net.minecraft.client.render.VertexConsumerProvider
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.resource.ResourceManager
import net.minecraft.resource.ResourceType
import net.minecraft.util.Identifier
import org.joml.Matrix4f
import org.joml.Vector3f
import java.time.LocalDate
import java.time.Month
import java.util.WeakHashMap

/**
 * Christmas easter egg: a Santa hat on every Pokémon's head, wild or sent out, from the 20th
 * to the 25th of December. Multi-headed Pokémon get one hat per head.
 *
 * ## Where it hooks
 *
 * At the end of `PosableModel.render` (see `PosableModelHatMixin`), not in a feature
 * renderer. Cobblemon resets every bone to its rest pose straight after rendering
 * (`PosableEntityModel.render` calls `setDefault()`), so anything drawn later would see a
 * frozen head that ignores the animation. At the end of `render` the bones still hold this
 * frame's pose, and the matrix stack is the one the model was drawn with — replaying
 * `ModelPart.rotate` from the root down to the skull lands exactly where Cobblemon drew it,
 * since its `Bone.render`/`Bone.transform` are vanilla `ModelPart.render`/`rotate`.
 *
 * Only Pokémon *in the world* get a hat: the hook requires the layer buffer Cobblemon sets
 * through `setLayerContext`, which only world renderers do, and a species in the render
 * context, which rules out NPCs, Poké Balls and other bedrock models sharing the class.
 *
 * ## Finding heads — checked offline against all 1177 Cobblemon models
 *
 * 1. A per-species table, `christmas/heads.json`, for the rigs no rule can read
 *    (Magneton's three bodies, Hydreigon's hand-heads, Weezing, Falinks…).
 * 2. Top-level bones named like a head (`head`, `head2`, `head_left`, `secondary_head`…);
 *    a candidate nested inside another is a sub-part, not a second head. 943 models.
 * 3. Otherwise each `locator_head*` marks a head: the hat goes on the part it hangs from
 *    (Dugtrio ×3, Exeggcute ×6). 146 models.
 * 4. Otherwise the whole model — Pokémon whose body *is* the head (Voltorb, Lotad). 88.
 *
 * Within a head, the skull is the largest box, preferring the head bone and its structural
 * wrappers (`head_angle`, `head_ai`…) over ears, manes and horns. The hat then rises onto
 * anything wide enough stacked over the skull's centre — a mane or a tuft, never a horn —
 * so it is not buried under Pyroar's mane. Its size follows the skull's width.
 */
object ChristmasHats {

    // ── activation ──────────────────────────────────────────────────────────────────────

    enum class Mode { AUTO, ON, OFF }

    /** Session-only override, set by `/hbdebug christmas` (developer mode). AUTO follows the calendar. */
    var mode: Mode = Mode.AUTO

    private var activeCache = false
    private var activeCheckedAt = 0L

    /** Whether today is in the window, 20–25 December inclusive, on the player's own clock. */
    fun inSeason(date: LocalDate = LocalDate.now()): Boolean =
        date.month == Month.DECEMBER && date.dayOfMonth in 20..25

    fun isActive(): Boolean = when (mode) {
        Mode.ON -> true
        Mode.OFF -> false
        Mode.AUTO -> {
            // Called for every Pokémon every frame; the date only needs a look once a second.
            val now = System.currentTimeMillis()
            if (now - activeCheckedAt > 1_000L) {
                activeCheckedAt = now
                activeCache = inSeason()
            }
            activeCache
        }
    }

    // ── tuning, matched to the offline previews ─────────────────────────────────────────

    /** Brim width relative to the skull's width. */
    private const val FIT = 1.0f
    /** How far the brim sinks into the skull, in hat pixels, so it sits rather than floats. */
    private const val SINK = 1.0f
    /** Something stacked on the skull must be this wide, relative to it, to lift the hat. */
    private const val RAISE_MIN_WIDTH = 0.4f
    /** The lift is capped at this many skull heights, so a tall crest cannot launch it. */
    private const val RAISE_MAX = 1.5f

    private val HEAD_RE = Regex(
        """^(head\d*|head_(left|right|middle|center|mid)\d*|(left|right|middle|center)_head\d*|secondary_head\d*)$""",
        RegexOption.IGNORE_CASE
    )
    private val LOCATOR_RE = Regex("""^locator_head(\d+|_left|_right|_center|_middle)?$""", RegexOption.IGNORE_CASE)
    private val STRUCT_RE = Regex(
        """^(head|face|skull)\d*(_(left|right))?(_(angle|ai|correction|rotation|rot|tilt|main|base|inner|inside|upper|scale|hidden|pivot|look|joint)\d*(_(left|right))?)*$""",
        RegexOption.IGNORE_CASE
    )
    private val DECOR_RE = Regex(
        "(ear|horn|hair|mane|antenna|crest|fin|ruff|fur|leaf|leaves|feather|spike|whisker|flame|fire|crown|hat|bow|" +
            "ribbon|tuft|plume|frill|petal|flower|quill|tentacle|beard|mustache|tail|wing|jaw|mouth|tongue|teeth|tooth|" +
            "eye|nose|cheek|brow|blade|gem|jewel|beak|tusk|fang|lip|pupil|lid)",
        RegexOption.IGNORE_CASE
    )

    // ── resources: hat model, texture, per-species table ────────────────────────────────

    private val HAT_MODEL = Identifier.of("hunterboard", "christmas/hat.json")
    private val HEADS_TABLE = Identifier.of("hunterboard", "christmas/heads.json")

    /** One face of the hat, in hat pixels (Y up, brim centred on x = z = 8). */
    private class Quad(val pos: FloatArray, val uv: FloatArray, val normal: Vector3f)

    private class Hat(val quads: List<Quad>, val brimWidth: Float, val texture: Identifier)

    private var hat: Hat? = null
    private var hatLoaded = false
    private var table: Map<String, List<List<String>>>? = null

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register() {
        // A resource reload swaps both the hat files and Cobblemon's models, so everything
        // derived from either is dropped and rebuilt on next sight.
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(
            object : SimpleSynchronousResourceReloadListener {
                override fun getFabricId(): Identifier = Identifier.of("hunterboard", "christmas_hats")
                override fun reload(manager: ResourceManager) {
                    hat = null; hatLoaded = false; table = null
                    anchors.clear()
                }
            }
        )
    }

    private fun readJson(id: Identifier): JsonObject? = try {
        MinecraftClient.getInstance().resourceManager.getResource(id).orElse(null)
            ?.inputStream?.use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
    } catch (e: Exception) {
        HunterBoard.LOGGER.warn("[Christmas] could not read $id: ${e.message}")
        null
    }

    private fun hat(): Hat? {
        if (!hatLoaded) {
            hatLoaded = true
            hat = readJson(HAT_MODEL)?.let { parseHat(it) }
            if (hat == null) HunterBoard.LOGGER.warn("[Christmas] no usable hat model at $HAT_MODEL")
        }
        return hat
    }

    private fun table(): Map<String, List<List<String>>> {
        table?.let { return it }
        val parsed = readJson(HEADS_TABLE)?.entries
            ?.filter { !it.key.startsWith("_") }
            ?.associate { (species, heads) ->
                species to heads.jsonArray.map { alts -> alts.jsonArray.map { it.jsonPrimitive.content } }
            }
            ?: emptyMap()
        table = parsed
        return parsed
    }

    /**
     * Reads the subset of the Java block-model format a hat needs: elements with `from`/`to`,
     * an optional single-axis `rotation`, and faces with `uv` (0–16 whatever the texture's
     * size, as in vanilla) and an optional `rotation`. Any Blockbench Java model fits, so a
     * resource pack can swap in another hat.
     */
    private fun parseHat(root: JsonObject): Hat? {
        val textures = root["textures"]?.jsonObject
        val texRef = textures?.get("0")?.jsonPrimitive?.content
            ?: textures?.values?.firstOrNull()?.jsonPrimitive?.content
            ?: return null
        val texture = textureId(texRef)

        val quads = mutableListOf<Quad>()
        var namedBrim: Float? = null
        var widest = 0f
        for (el in root["elements"]?.jsonArray ?: return null) {
            val e = el.jsonObject
            val from = e["from"]!!.jsonArray.map { it.jsonPrimitive.float }
            val to = e["to"]!!.jsonArray.map { it.jsonPrimitive.float }
            val w = to[0] - from[0]
            widest = maxOf(widest, w)
            if (e["name"]?.jsonPrimitive?.content == "white_brim") namedBrim = w

            val rotation = e["rotation"]?.jsonObject?.let { r ->
                val angle = Math.toRadians(r["angle"]!!.jsonPrimitive.float.toDouble()).toFloat()
                val o = r["origin"]!!.jsonArray.map { it.jsonPrimitive.float }
                val m = Matrix4f().translate(o[0], o[1], o[2])
                when (r["axis"]!!.jsonPrimitive.content) {
                    "x" -> m.rotateX(angle)
                    "y" -> m.rotateY(angle)
                    else -> m.rotateZ(angle)
                }
                m.translate(-o[0], -o[1], -o[2])
            } ?: Matrix4f()

            val faces = e["faces"]?.jsonObject ?: continue
            for ((dir, faceEl) in faces) {
                val face = faceEl.jsonObject
                val corners = FACE_CORNERS[dir] ?: continue
                val uvArr = face["uv"]?.jsonArray?.map { it.jsonPrimitive.float / 16f }
                    ?: listOf(0f, 0f, 1f, 1f)
                // Corners are listed top-left, bottom-left, bottom-right, top-right as seen
                // from outside, which is how a face's uv rectangle maps onto them.
                val uvCorners = listOf(
                    uvArr[0] to uvArr[1], uvArr[0] to uvArr[3], uvArr[2] to uvArr[3], uvArr[2] to uvArr[1]
                )
                val turn = ((face["rotation"]?.jsonPrimitive?.int ?: 0) / 90).mod(4)
                val pos = FloatArray(12)
                val uv = FloatArray(8)
                for (i in 0 until 4) {
                    val c = corners[i]
                    val v = Vector3f(
                        if (c[0] == 0) from[0] else to[0],
                        if (c[1] == 0) from[1] else to[1],
                        if (c[2] == 0) from[2] else to[2]
                    )
                    rotation.transformPosition(v)
                    pos[i * 3] = v.x; pos[i * 3 + 1] = v.y; pos[i * 3 + 2] = v.z
                    val (u, vv) = uvCorners[(i + turn) % 4]
                    uv[i * 2] = u; uv[i * 2 + 1] = vv
                }
                val n = Vector3f(FACE_NORMALS[dir]!!)
                rotation.transformDirection(n)
                quads += Quad(pos, uv, n)
            }
        }
        if (quads.isEmpty()) return null
        return Hat(quads, namedBrim ?: widest, texture)
    }

    /** `hunterboard:textures/x.png` as-is; vanilla-style `block/hat` → `minecraft:textures/block/hat.png`. */
    private fun textureId(ref: String): Identifier {
        val id = Identifier.of(ref)
        if (id.path.startsWith("textures/") && id.path.endsWith(".png")) return id
        return Identifier.of(id.namespace, "textures/${id.path}.png")
    }

    private val FACE_CORNERS: Map<String, List<IntArray>> = mapOf(
        "north" to listOf(intArrayOf(1, 1, 0), intArrayOf(1, 0, 0), intArrayOf(0, 0, 0), intArrayOf(0, 1, 0)),
        "south" to listOf(intArrayOf(0, 1, 1), intArrayOf(0, 0, 1), intArrayOf(1, 0, 1), intArrayOf(1, 1, 1)),
        "east" to listOf(intArrayOf(1, 1, 1), intArrayOf(1, 0, 1), intArrayOf(1, 0, 0), intArrayOf(1, 1, 0)),
        "west" to listOf(intArrayOf(0, 1, 0), intArrayOf(0, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 1, 1)),
        "up" to listOf(intArrayOf(0, 1, 0), intArrayOf(0, 1, 1), intArrayOf(1, 1, 1), intArrayOf(1, 1, 0)),
        "down" to listOf(intArrayOf(0, 0, 1), intArrayOf(0, 0, 0), intArrayOf(1, 0, 0), intArrayOf(1, 0, 1)),
    )
    private val FACE_NORMALS: Map<String, Vector3f> = mapOf(
        "north" to Vector3f(0f, 0f, -1f), "south" to Vector3f(0f, 0f, 1f),
        "east" to Vector3f(1f, 0f, 0f), "west" to Vector3f(-1f, 0f, 0f),
        "up" to Vector3f(0f, 1f, 0f), "down" to Vector3f(0f, -1f, 0f),
    )

    // ── anchors: where the hats go on one model ─────────────────────────────────────────

    /**
     * One hat. [chain] runs from the root to the part owning the skull box; replaying each
     * one's `rotate` reaches the skull's frame. The position is in that frame, in model
     * pixels — where, as in every vanilla entity model, Y points *down*.
     */
    private class Anchor(val chain: Array<ModelPart>, val x: Float, val top: Float, val z: Float, val width: Float)

    /** Keyed by the model's root part: one entry per species variant, dropped with the model. */
    private val anchors = WeakHashMap<ModelPart, List<Anchor>>()

    private class Node(val name: String, val part: ModelPart, val parent: Node?) {
        val kids = mutableListOf<Node>()
        lateinit var matrix: Matrix4f
    }

    private fun computeAnchors(root: ModelPart, species: String): List<Anchor> {
        // Flatten the tree, recording each part's matrix relative to the root. The pose is
        // whatever this first frame holds; it only serves to compare boxes *within* a head,
        // which move together, so a mid-animation pose does not skew the result.
        val nodes = mutableListOf<Node>()
        val stack = MatrixStack()
        fun walk(node: Node) {
            stack.push()
            node.part.rotate(stack)
            node.matrix = Matrix4f(stack.peek().positionMatrix)
            nodes += node
            for ((name, child) in node.part.children) {
                val kid = Node(name, child, node)
                node.kids += kid
                walk(kid)
            }
            stack.pop()
        }
        val rootNode = Node("", root, null)
        walk(rootNode)
        val byName = nodes.groupBy { it.name }

        fun subtree(n: Node, stop: Set<Node>): List<Node> {
            val out = mutableListOf(n)
            for (k in n.kids) if (k !in stop) out += subtree(k, stop)
            return out
        }
        fun ancestors(n: Node) = generateSequence(n.parent) { it.parent }
        fun hasGeometry(n: Node) = subtree(n, emptySet()).any { it.part.cuboids.isNotEmpty() }

        // Which parts are heads
        val heads: List<Node> = run {
            table()[species]?.let { entries ->
                val found = entries.mapNotNull { alts -> alts.firstNotNullOfOrNull { byName[it]?.firstOrNull() } }.distinct()
                if (found.isNotEmpty()) return@run found
            }
            val named = nodes.filter { HEAD_RE.matches(it.name) }.toSet()
            val tops = named.filter { n -> ancestors(n).none { it in named } }
            if (tops.isNotEmpty()) return@run tops
            val fromLocators = nodes.filter { LOCATOR_RE.matches(it.name) }.mapNotNull { loc ->
                // A locator often hangs off an empty helper bone: climb to real geometry.
                var p = loc.parent
                while (p != null && !hasGeometry(p)) p = p.parent
                p
            }.distinct()
            if (fromLocators.isNotEmpty()) return@run fromLocators
            // The root part is synthetic; a bedrock model's real top bone is usually its only
            // child, and starting there lets that bone's own boxes count as the "head".
            listOf(rootNode.kids.singleOrNull() ?: rootNode)
        }

        val headSet = heads.toSet()
        val out = mutableListOf<Anchor>()
        for (head in heads) {
            val region = subtree(head, headSet - head)
            val pool = region.flatMap { n -> n.part.cuboids.map { n to it } }
            if (pool.isEmpty()) continue
            val structural = pool.filter { (n, _) -> n === head || STRUCT_RE.matches(n.name) }
            val plain = pool.filter { (n, _) -> n === head || !DECOR_RE.containsMatchIn(n.name) }
            val (owner, skull) = (structural.ifEmpty { plain.ifEmpty { pool } }).maxBy { (_, c) ->
                (c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ)
            }

            val cx = (skull.minX + skull.maxX) / 2f
            val cz = (skull.minZ + skull.maxZ) / 2f
            val width = skull.maxX - skull.minX
            val height = skull.maxY - skull.minY
            val skullTop = skull.minY  // Y down: the top is the smallest y

            // Lift onto anything wide enough stacked over the skull's centre (a mane, a tuft).
            val toSkull = Matrix4f(owner.matrix).invert()
            var top = skullTop
            val p = Vector3f()
            for ((n, c) in pool) {
                if (c === skull) continue
                val toLocal = Matrix4f(toSkull).mul(n.matrix)
                var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE
                var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
                for (i in 0 until 8) {
                    // Cuboid bounds are in pixels, part matrices in blocks.
                    p.set(
                        (if (i and 1 == 0) c.minX else c.maxX) / 16f,
                        (if (i and 2 == 0) c.minY else c.maxY) / 16f,
                        (if (i and 4 == 0) c.minZ else c.maxZ) / 16f
                    )
                    toLocal.transformPosition(p)
                    p.mul(16f)
                    x0 = minOf(x0, p.x); x1 = maxOf(x1, p.x)
                    y0 = minOf(y0, p.y); y1 = maxOf(y1, p.y)
                    z0 = minOf(z0, p.z); z1 = maxOf(z1, p.z)
                }
                if (cx < x0 || cx > x1 || cz < z0 || cz > z1) continue
                if (x1 - x0 < RAISE_MIN_WIDTH * width) continue
                // Must rest on the skull's top, not hover above it or sit below it.
                if (y1 < skullTop - 0.01f || y0 >= skullTop) continue
                top = minOf(top, y0)
            }
            top = maxOf(top, skullTop - RAISE_MAX * height)

            val chain = generateSequence(owner) { it.parent }.toList().asReversed().map { it.part }.toTypedArray()
            out += Anchor(chain, cx, top, cz, width)
        }
        return out
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────

    // ── diagnostics, shown by `/hbdebug christmas` ──────────────────────────────────────
    //
    // A hat that fails to appear has three very different causes — the mixin never applied,
    // the hook runs but every model is filtered out, or drawing throws — and from the
    // outside all three look identical. These counters tell them apart in game, without a
    // log: no calls means the hook is not in place; calls but no hats means the filter or
    // the placement; an error is logged once with its stack.

    /** Times the render hook was reached, active or not. */
    var hookCalls = 0L
        private set

    /** Hats drawn this session. */
    var hatsDrawn = 0L
        private set

    /** Last failure, for the status line; the full stack goes to the log once. */
    var lastError: String? = null
        private set

    private var loggedFirstHat = false

    /** Entry point for the mixin. Never throws: this runs inside Cobblemon's render. */
    @JvmStatic
    fun render(
        model: PosableModel, context: RenderContext, matrices: MatrixStack,
        light: Int, overlay: Int, color: Int, buffers: VertexConsumerProvider?
    ) {
        hookCalls++
        try {
            draw(model, context, matrices, light, overlay, color, buffers)
        } catch (t: Throwable) {
            if (lastError == null) HunterBoard.LOGGER.error("[Christmas] hat rendering failed", t)
            lastError = "${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun draw(
        model: PosableModel, context: RenderContext, matrices: MatrixStack,
        light: Int, overlay: Int, color: Int, buffers: VertexConsumerProvider?
    ) {
        if (buffers == null || !isActive()) return
        val species = context.request(RenderContext.SPECIES) ?: return
        val state = context.request(RenderContext.RENDER_STATE)
        if (state != null && state != RenderContext.RenderState.WORLD) return
        val root = model.rootPart as? ModelPart ?: return
        val hat = hat() ?: return

        val list = anchors.getOrPut(root) {
            try {
                computeAnchors(root, species.path)
            } catch (e: Exception) {
                HunterBoard.LOGGER.warn("[Christmas] no hat placement for $species: ${e.message}")
                emptyList()
            }
        }
        if (list.isEmpty()) return

        val vc = buffers.getBuffer(RenderLayer.getEntityCutoutNoCull(hat.texture))
        for (a in list) {
            matrices.push()
            var shown = true
            for (part in a.chain) {
                // Same rule as ModelPart.render: an invisible part hides its whole subtree.
                if (!part.visible) { shown = false; break }
                part.rotate(matrices)
            }
            // `hidden` only hides a part's own boxes — and the skull is one of them.
            if (shown && !a.chain.last().hidden) {
                val k = a.width / hat.brimWidth * FIT
                matrices.translate(a.x / 16f, (a.top + SINK * k) / 16f, a.z / 16f)
                // Hat pixels → model blocks. Flipping X and Y together is a half-turn about Z:
                // the hat comes upright in this Y-down frame without mirroring, so faces keep
                // their winding and the tip still falls toward +Z — the back, since Cobblemon
                // models face −Z (true for 866 of the 884 models with a readable face).
                matrices.scale(-k / 16f, -k / 16f, k / 16f)
                matrices.translate(-8f, 0f, -8f)
                val entry = matrices.peek()
                for (q in hat.quads) {
                    for (i in 0 until 4) {
                        vc.vertex(entry, q.pos[i * 3], q.pos[i * 3 + 1], q.pos[i * 3 + 2])
                            .color(color)
                            .texture(q.uv[i * 2], q.uv[i * 2 + 1])
                            .overlay(overlay)
                            .light(light)
                            .normal(entry, q.normal.x, q.normal.y, q.normal.z)
                    }
                }
                hatsDrawn++
                if (!loggedFirstHat) {
                    loggedFirstHat = true
                    HunterBoard.LOGGER.info("[Christmas] hats are drawing (first: $species, ${list.size} hat(s))")
                }
            }
            matrices.pop()
        }
    }
}
