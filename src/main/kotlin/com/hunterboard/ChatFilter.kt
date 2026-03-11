package com.hunterboard

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

object ChatFilter {

    private fun shouldBlock(text: String): Boolean {
        return text.contains("HP pour le boss")
            || (text.contains("au boss") && text.contains("inflig"))
    }

    fun register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            if (ModConfig.hideBossDamageChat && shouldBlock(message.string)) false else true
        }
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
            if (ModConfig.hideBossDamageChat && shouldBlock(message.string)) false else true
        }
    }
}
