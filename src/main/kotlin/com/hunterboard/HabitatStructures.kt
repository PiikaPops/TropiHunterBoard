package com.hunterboard

import net.minecraft.block.BlockState
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtSizeTracker
import net.minecraft.registry.Registries
import net.minecraft.util.Identifier
import java.nio.file.Files
import java.nio.file.Path

/** One block of a structure preview, already positioned. */
data class PreviewBlock(val x: Int, val y: Int, val z: Int, val state: BlockState)

/** A parsed structure variant, ready to draw. */
data class StructurePreview(
    val name: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blocks: List<PreviewBlock>,
    /** Blocks dropped because they are fully enclosed, or because of the cap. */
    val skipped: Int
)

/**
 * Loads habitat structure pieces so the wiki can show roughly what one looks like.
 *
 * A habitat is not a single structure: Cobblemon assembles it with a jigsaw from a
 * template pool, and ships 584 nbt pieces for 54 habitats — start anchors, numbered
 * variants, connectors and trees. We only load the numbered variants, which are
 * self-contained, and show one at a time. It is an impression of the habitat, not
 * the exact thing the generator will build.
 */
object HabitatStructures {

    private const val FOLDER = "structure/habitats"
    /** Above this, a piece is a jigsaw mega-chunk (one is 48x40x48) and not worth drawing. */
    private const val MAX_BLOCKS = 12000

    /**
     * Pool ids and structure file names disagree for nine habitats. Cobblemon spells
     * some of them differently ("wildfire" pool vs "wildlife" files), routes several
     * pools at one set of pieces, or splits one pool across several sets.
     */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "abandonedvillagehouse" to listOf(
            "villagedesert", "villageplain", "villagesavanna", "villagesnowy", "villagetaiga"
        ),
        "birchwildfirescar" to listOf("birchwildlifescar"),
        "oakwildfirescar" to listOf("oakwildlifescar"),
        "sprucewildfirescar" to listOf("sprucewildlifescar"),
        "crimsonfungaldwelling" to listOf("strangefungaldwelling"),
        "warpedfungaldwelling" to listOf("strangefungaldwelling"),
        "lostcaveruin" to listOf("lostruin"),
        "naturallightningrod" to listOf("naturallightningrodlarge", "naturallightningrodsmall"),
        "reclaimeddesertmonument" to listOf("reclaimeddesertedmonument")
    )

    private var variantIndex: Map<String, List<Path>>? = null
    private val phaseOrderCache = HashMap<String, String?>()
    private val previewCache = HashMap<String, StructurePreview?>()

    fun invalidate() {
        variantIndex = null
        previewCache.clear()
        phaseOrderCache.clear()
    }

    private fun normalize(id: String): String = id.substringAfter(":")
        .substringAfterLast("/")
        .trimEnd { it.isDigit() }
        .replace("_", "")
        .removeSuffix("s")

    /** habitat key -> its numbered variant files, in name order. */
    private fun index(): Map<String, List<Path>> {
        variantIndex?.let { return it }
        val found = mutableMapOf<String, MutableList<Path>>()
        try {
            CobblemonResources.forEachFile(FOLDER, ".nbt") { file ->
                val base = file.fileName.toString().removeSuffix(".nbt")
                // Numbered variants only. Start anchors have no digit; connectors DO end
                // in one ("birch_wildlife_scar_connector1") but are jigsaw glue, not a
                // habitat, so they have to be excluded by name.
                if (base.isEmpty() || !base.last().isDigit()) return@forEachFile
                if (base.contains("_connector")) return@forEachFile
                found.getOrPut(normalize(base)) { mutableListOf() }.add(file)
            }
        } catch (_: Exception) {}
        val sorted = found.mapValues { (_, v) -> v.sortedBy { it.fileName.toString() } }
        variantIndex = sorted
        return sorted
    }

    /** Every variant file for a habitat, following the alias table when needed. */
    private fun filesFor(habitatId: String): List<Path> {
        val index = index()
        val key = normalize(habitatId)
        index[key]?.let { if (it.isNotEmpty()) return it }
        val aliases = ALIASES[key] ?: return emptyList()
        return aliases.flatMap { index[it].orEmpty() }
    }

    /** Normalised file keys for a habitat: the direct one, or its aliases. */
    fun fileKeysFor(habitatId: String): List<String> {
        val key = normalize(habitatId)
        return listOf(key) + (ALIASES[key] ?: emptyList())
    }

    /** How many variants exist for this habitat. */
    fun variantCount(habitatId: String): Int = filesFor(habitatId).size

    /**
     * The PhaseOrder authored into the habitat block of this structure.
     *
     * This matters on a server: `HabitatBlockEntity.toInitialChunkDataNbt` sends only
     * MimicId and DisplaySpecies, so a client's copy of the block entity keeps the
     * constructor default (SIMPLE) instead of the real value — every habitat Cobblemon
     * ships is actually FULL_RANDOM. Reading it from the structure file gives the
     * authored value without needing the server to tell us.
     */
    fun phaseOrderFor(habitatId: String): String? {
        val key = normalize(habitatId)
        if (phaseOrderCache.containsKey(key)) return phaseOrderCache[key]

        var result: String? = null
        for (file in filesFor(habitatId)) {
            result = try { readPhaseOrder(file) } catch (_: Exception) { null }
            if (result != null) break
        }
        phaseOrderCache[key] = result
        return result
    }

    private fun readPhaseOrder(file: Path): String? {
        val root = Files.newInputStream(file).use { stream ->
            NbtIo.readCompressed(stream, NbtSizeTracker.ofUnlimitedBytes())
        } ?: return null
        val blocks = root.getList("blocks", NbtElement.COMPOUND_TYPE.toInt())
        for (i in 0 until blocks.size) {
            try {
                val data = blocks.getCompound(i).getCompound("nbt")
                val order = data.getString("PhaseOrder")
                if (order.isNotEmpty()) return order
            } catch (_: Exception) {}
        }
        return null
    }

    /** Parse one variant, or null when there is nothing usable. */
    fun preview(habitatId: String, variant: Int): StructurePreview? {
        val key = "${normalize(habitatId)}#$variant"
        if (previewCache.containsKey(key)) return previewCache[key]

        val files = filesFor(habitatId)
        if (files.isEmpty()) return null.also { previewCache[key] = null }
        val file = files[variant.mod(files.size)]

        val result = try { parse(file) } catch (_: Exception) { null }
        previewCache[key] = result
        return result
    }

    private fun parse(file: Path): StructurePreview? {
        val root: NbtCompound = Files.newInputStream(file).use { stream ->
            NbtIo.readCompressed(stream, NbtSizeTracker.ofUnlimitedBytes())
        } ?: return null

        val size = root.getList("size", NbtElement.INT_TYPE.toInt())
        if (size.size < 3) return null
        val sx = size.getInt(0)
        val sy = size.getInt(1)
        val sz = size.getInt(2)

        // palette index -> block state (default state: properties are not worth the
        // extra API surface for a thumbnail, and unknown blocks must not break parsing)
        val palette = root.getList("palette", NbtElement.COMPOUND_TYPE.toInt())
        val states = arrayOfNulls<BlockState>(palette.size)
        for (i in 0 until palette.size) {
            states[i] = try {
                val name = palette.getCompound(i).getString("Name")
                val block = Registries.BLOCK.get(Identifier.of(name))
                block.defaultState
            } catch (_: Exception) { null }
        }

        val blockList = root.getList("blocks", NbtElement.COMPOUND_TYPE.toInt())
        if (blockList.size > MAX_BLOCKS) return null

        // occupancy grid so we can drop blocks that nothing can see
        val occupied = HashSet<Int>(blockList.size * 2)
        fun key(x: Int, y: Int, z: Int) = (x + 64) * 512 * 512 + (y + 64) * 512 + (z + 64)

        data class Raw(val x: Int, val y: Int, val z: Int, val state: BlockState)
        val raws = ArrayList<Raw>(blockList.size)

        for (i in 0 until blockList.size) {
            try {
                val entry = blockList.getCompound(i)
                val pos = entry.getList("pos", NbtElement.INT_TYPE.toInt())
                if (pos.size < 3) continue
                val state = states.getOrNull(entry.getInt("state")) ?: continue
                if (state.isAir) continue
                val x = pos.getInt(0); val y = pos.getInt(1); val z = pos.getInt(2)
                raws.add(Raw(x, y, z, state))
                occupied.add(key(x, y, z))
            } catch (_: Exception) {}
        }

        var skipped = 0
        val visible = ArrayList<PreviewBlock>(raws.size)
        for (raw in raws) {
            val enclosed =
                occupied.contains(key(raw.x + 1, raw.y, raw.z)) &&
                occupied.contains(key(raw.x - 1, raw.y, raw.z)) &&
                occupied.contains(key(raw.x, raw.y + 1, raw.z)) &&
                occupied.contains(key(raw.x, raw.y - 1, raw.z)) &&
                occupied.contains(key(raw.x, raw.y, raw.z + 1)) &&
                occupied.contains(key(raw.x, raw.y, raw.z - 1))
            if (enclosed) skipped++ else visible.add(PreviewBlock(raw.x, raw.y, raw.z, raw.state))
        }

        return StructurePreview(
            name = file.fileName.toString().removeSuffix(".nbt"),
            sizeX = sx, sizeY = sy, sizeZ = sz,
            blocks = visible,
            skipped = skipped
        )
    }
}
