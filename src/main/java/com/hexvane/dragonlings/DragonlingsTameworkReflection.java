package com.hexvane.dragonlings;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Loads Alec's Tamework! classes from that plugin's {@link com.hypixel.hytale.server.core.plugin.PluginClassLoader}.
 * Dragonlings is often on the dev classpath while Tamework is a separate mods/ JAR, so {@link Class#forName(String)}
 * on this mod's loader frequently fails even when Tamework is running.
 */
final class DragonlingsTameworkReflection {
    static final String CLASS_TAMEWORK = "com.alechilles.alecstamework.Tamework";
    private static final PluginIdentifier TAMEWORK_PLUGIN_ID = new PluginIdentifier("Alechilles", "Alec's Tamework!");

    private DragonlingsTameworkReflection() {}

    static boolean isTameworkPluginLoaded() {
        PluginManager pluginManager = HytaleServer.get().getPluginManager();
        return pluginManager != null && pluginManager.getPlugin(TAMEWORK_PLUGIN_ID) != null;
    }

    @Nonnull
    static Class<?> loadTameworkClass(@Nonnull String name) throws ClassNotFoundException {
        ClassLoader classLoader = tameworkClassLoader();
        if (classLoader == null) {
            throw new ClassNotFoundException(name + " (Alec's Tamework! plugin is not loaded)");
        }
        return Class.forName(name, true, classLoader);
    }

    @Nullable
    static Object tameworkInstance() throws ReflectiveOperationException {
        Class<?> tameworkClass = loadTameworkClass(CLASS_TAMEWORK);
        return tameworkClass.getMethod("getInstance").invoke(null);
    }

    @Nullable
    private static ClassLoader tameworkClassLoader() {
        PluginManager pluginManager = HytaleServer.get().getPluginManager();
        if (pluginManager == null) {
            return null;
        }
        PluginBase plugin = pluginManager.getPlugin(TAMEWORK_PLUGIN_ID);
        if (plugin instanceof JavaPlugin javaPlugin) {
            return javaPlugin.getClassLoader();
        }
        return null;
    }
}
