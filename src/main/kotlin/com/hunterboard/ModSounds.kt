package com.hunterboard

import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier

object ModSounds {

    val SOCIAL_CREDIT_SIREN: SoundEvent = register("999_social_credit_siren")
    val ACK: SoundEvent = register("ack")
    val AMONG_US_ROLE_REVEAL: SoundEvent = register("among_us_role_reveal")
    val ANIME_WOW: SoundEvent = register("anime_wow")
    val BONE_CRACK: SoundEvent = register("bone_crack")
    val BOOM: SoundEvent = register("boom")
    val COUCOU: SoundEvent = register("coucou")
    val DRY_FART: SoundEvent = register("dry_fart")
    val GIRL_UWU: SoundEvent = register("girl_uwu")
    val LEGO_BREAKING: SoundEvent = register("lego_breaking")
    val METAL_PIPE: SoundEvent = register("metal_pipe")
    val MIAOU: SoundEvent = register("miaou")
    val NYA: SoundEvent = register("nya")
    val QUACK: SoundEvent = register("quack")
    val RIZZ: SoundEvent = register("rizz")
    val UNDERTAKERS_BELL: SoundEvent = register("undertakers_bell")

    // Easter egg — not shown in sound picker
    val SPECIAL_RICKROLL: SoundEvent = register("special_rickroll")

    private fun register(name: String): SoundEvent {
        val id = Identifier.of(HunterBoard.MOD_ID, name)
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id))
    }

    fun register() {
        // Force static init — all sounds registered above
        HunterBoard.LOGGER.info("Registered HunterBoard sounds")
    }
}
