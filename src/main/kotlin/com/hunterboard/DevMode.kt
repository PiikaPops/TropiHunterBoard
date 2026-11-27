package com.hunterboard

/**
 * Developer mode, switched on by a JVM argument: `-Dhunterboard.dev=true` (or just
 * `-Dhunterboard.dev`) in the launcher's Java arguments. Only then are the developer commands
 * (`/hbdebug`, see [DebugCommands]) registered: in a normal game they do not exist at all, not
 * even in the command suggestions. `gradlew runClient` always starts in it.
 */
object DevMode {
    @JvmField
    val enabled: Boolean = System.getProperty("hunterboard.dev")?.trim()?.lowercase()
        ?.let { it.isEmpty() || it in setOf("true", "1", "yes", "on", "oui") } ?: false
}
