package com.hunterboard

import com.cobblemon.mod.common.CobblemonEntities
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity
import com.cobblemon.mod.common.pokemon.Pokemon
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.particle.ParticleTypes
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import net.minecraft.util.math.Vec3d
import kotlin.math.cos
import kotlin.math.sin

object AprilFoolsPrank {

    /** Delay before the fake legendary encounter (in minutes). */
    private const val DELAY_MINUTES = 20L

    private const val DELAY_TICKS = DELAY_MINUTES * 60 * 20 // 20 ticks/sec
    private const val MESSAGE_DELAY_TICKS = 30 * 20L         // 30 seconds after sound
    private const val REVEAL_DELAY_TICKS = 10 * 20L          // 10 seconds after magikarp spawn
    private const val MAGIKARP_COUNT = 8                      // Number of Magikarps in the circle

    private var tickCounter = 0L
    private var soundPlayed = false
    private var messageSent = false
    private var revealSent = false
    private var active = false
    private val fakeEntityIds = mutableListOf<Int>()
    private val fakeEntityPositions = mutableListOf<Vec3d>()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!active) {
                // Activate when joining a world on April 1st
                if (client.world != null && client.player != null) {
                    val now = java.time.LocalDate.now()
                    if (now.monthValue == 4 && now.dayOfMonth == 1) {
                        active = true
                        tickCounter = 0
                        soundPlayed = false
                        messageSent = false
                        revealSent = false
                        fakeEntityIds.clear()
                        fakeEntityPositions.clear()
                    }
                }
                return@register
            }

            // Reset if player leaves
            if (client.world == null || client.player == null) {
                active = false
                return@register
            }

            tickCounter++

            if (!soundPlayed && tickCounter >= DELAY_TICKS) {
                soundPlayed = true
                try {
                    val id = Identifier.of("tropifurnitures", "zapdos_encounter")
                    val soundEvent = SoundEvent.of(id)
                    client.player?.playSound(soundEvent, 1.0f, 1.0f)
                } catch (_: Exception) {}
            }

            if (soundPlayed && !messageSent && tickCounter >= DELAY_TICKS + MESSAGE_DELAY_TICKS) {
                messageSent = true
                try {
                    // Chat message: only "Électhor" is gold+bold, rest is normal
                    val msg = Text.empty()
                        .append(Text.literal("\u00C9lecthor").styled {
                            it.withColor(0xFFAA00).withBold(true)
                        })
                        .append(Text.literal(" est apparu devant vous!"))
                    client.player?.sendMessage(msg, false)

                    // Spawn Magikarps in a circle around the player
                    spawnMagikarpCircle(client)
                } catch (_: Exception) {}
            }

            // Reveal message 10 seconds after spawn
            if (messageSent && !revealSent && tickCounter >= DELAY_TICKS + MESSAGE_DELAY_TICKS + REVEAL_DELAY_TICKS) {
                revealSent = true
                try {
                    client.player?.sendMessage(
                        Text.literal("Fooled by Popi <3").styled {
                            it.withBold(true)
                        },
                        false
                    )

                    // Firework particles at each Magikarp position before removing
                    val world = client.world
                    if (world != null) {
                        for (pos in fakeEntityPositions) {
                            for (j in 0 until 50) {
                                val vx = (Math.random() - 0.5) * 0.4
                                val vy = Math.random() * 0.25 + 0.05
                                val vz = (Math.random() - 0.5) * 0.4
                                world.addParticle(
                                    ParticleTypes.FIREWORK,
                                    pos.x + (Math.random() - 0.5) * 0.5,
                                    pos.y + Math.random() * 0.8,
                                    pos.z + (Math.random() - 0.5) * 0.5,
                                    vx, vy, vz
                                )
                            }
                        }
                    }

                    // Remove all fake entities
                    for (id in fakeEntityIds) {
                        client.world?.removeEntity(id, net.minecraft.entity.Entity.RemovalReason.DISCARDED)
                    }
                    fakeEntityIds.clear()
                    fakeEntityPositions.clear()
                } catch (_: Exception) {}
            }
        }
    }

    private fun spawnMagikarpCircle(client: MinecraftClient) {
        try {
            val world = client.world ?: return
            val player = client.player ?: return
            val species = PokemonSpecies.getByName("magikarp") ?: return
            val center = player.pos
            val radius = 4.0

            // Thunder sound at player position
            player.playSound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 1.0f, 1.0f)
            player.playSound(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 1.0f, 1.0f)

            for (i in 0 until MAGIKARP_COUNT) {
                val angle = (2.0 * Math.PI * i) / MAGIKARP_COUNT
                val spawnPos = center.add(Vec3d(cos(angle) * radius, 0.0, sin(angle) * radius))

                // Face toward the player
                val dx = center.x - spawnPos.x
                val dz = center.z - spawnPos.z
                val facingYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()

                val pokemon = Pokemon().apply {
                    this.species = species
                    this.level = 1
                    this.nickname = Text.literal("April fool =)").styled {
                        it.withColor(0xFF5555).withBold(true)
                    }
                }

                val entity = PokemonEntity(world, pokemon, CobblemonEntities.POKEMON)
                entity.setPosition(spawnPos)
                entity.yaw = facingYaw

                // Apply GLOWING status effect (renders outline on client)
                try {
                    entity.addStatusEffect(
                        net.minecraft.entity.effect.StatusEffectInstance(
                            net.minecraft.entity.effect.StatusEffects.GLOWING,
                            20 * 60, 0, false, false
                        )
                    )
                } catch (_: Exception) {}

                fakeEntityIds.add(entity.id)
                fakeEntityPositions.add(spawnPos)
                world.addEntity(entity)
            }
        } catch (e: Exception) {
            HunterBoard.LOGGER.debug("April Fools prank entity failed: ${e.message}")
        }
    }
}
