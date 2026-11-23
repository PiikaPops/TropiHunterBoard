package com.hunterboard

import net.fabricmc.api.ClientModInitializer

/**
 * The development tools, a mod of their own that only `gradlew runClient` loads: the
 * released jar is built from `src/main` alone and carries none of this.
 *
 *  - [UiTour] photographs every screen (`-Puitour=<folder>`);
 *  - [WikiAudit] reads every wiki page for unfinished text (`-Puitour.audit=files|api`).
 *
 * They reach the mod's private state through [DevAccess], so the mod keeps no hook for them.
 */
object DevTools : ClientModInitializer {
    override fun onInitializeClient() {
        UiTour.register()
    }
}

/** Reflection into the mod's objects, for the states a tour picture needs. */
object DevAccess {

    private fun field(target: Any, name: String): java.lang.reflect.Field {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try { return c.getDeclaredField(name).apply { isAccessible = true } } catch (_: NoSuchFieldException) {}
            c = c.superclass
        }
        throw NoSuchFieldException("${target.javaClass.simpleName}.$name")
    }

    private fun receiver(f: java.lang.reflect.Field, target: Any): Any? =
        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else target

    fun set(target: Any, name: String, value: Any?) {
        val f = field(target, name)
        f.set(receiver(f, target), value)
    }

    fun get(target: Any, name: String): Any? {
        val f = field(target, name)
        return f.get(receiver(f, target))
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val m = target.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        m.isAccessible = true
        return m.invoke(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else target, *args)
    }

    /** Switches the look for one picture, without saving. */
    fun previewLook(theme: String?, modern: Boolean) {
        if (theme != null && theme in ModConfig.UI_THEMES) set(ModConfig, "uiTheme", theme)
        set(ModConfig, "modernUi", modern)
    }

    /** The raid card as if this boss had just been announced; null = boss not seen yet. */
    fun previewRaid(speciesName: String?, megaRaid: Boolean) {
        try {
            call(RaidCard, "reset")
            set(RaidCard, "mega", megaRaid)
            set(RaidCard, "startsAt", System.currentTimeMillis() + 5 * 60_000L - 28_000L)
            set(RaidCard, "slot", 0L)   // never matches a real raid: nothing is asked or reported
            set(RaidCard, "boss", speciesName?.let { call(RaidCard, "bossOf", it, emptySet<String>(), 3) })
            set(RaidCard, "lastRaid", get(RaidCard, "raidId"))   // not a new raid for the tick
        } catch (e: Exception) {
            HunterBoard.LOGGER.warn("DevAccess: raid card preview failed: $e")
        }
    }
}
