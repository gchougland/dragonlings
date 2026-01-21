package com.hexvane.dragonlings;

import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * System that programmatically merges the base game's Capture_Crate tag set
 * with our dragonling roles at runtime.
 */
public class DragonlingCaptureCrateMerger {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String[] DRAGONLING_ROLES = {
        "Dragonling_Green",
        "Dragonling_Blue",
        "Dragonling_Red",
        "Dragonling_Purple"
    };
    
    public static void register(@Nonnull EventRegistry eventRegistry) {
        eventRegistry.register(LoadedAssetsEvent.class, NPCGroup.class, DragonlingCaptureCrateMerger::onNPCGroupsLoaded);
    }
    
    private static void onNPCGroupsLoaded(@Nonnull LoadedAssetsEvent<String, NPCGroup, ?> event) {
        // Only process on initial load, not on updates
        if (!event.isInitial()) {
            return;
        }
        
        NPCGroup baseCaptureCrate = event.getLoadedAssets().get("Capture_Crate");
        if (baseCaptureCrate == null) {
            LOGGER.atWarning().log("Base game Capture_Crate tag set not found - cannot merge dragonling roles");
            return;
        }
        
        LOGGER.atInfo().log("Merging dragonling roles into Capture_Crate tag set");
        
        // Get existing roles from base Capture_Crate
        String[] baseRoles = baseCaptureCrate.getIncludedTags();
        Set<String> allRoles = new HashSet<>();
        
        // Add all base roles
        if (baseRoles != null) {
            allRoles.addAll(Arrays.asList(baseRoles));
        }
        
        // Add our dragonling roles
        allRoles.addAll(Arrays.asList(DRAGONLING_ROLES));
        
        // Create merged NPCGroup using reflection to set protected fields
        NPCGroup mergedCaptureCrate = new NPCGroup("Capture_Crate");
        try {
            Field includedRolesField = NPCGroup.class.getDeclaredField("includedRoles");
            includedRolesField.setAccessible(true);
            includedRolesField.set(mergedCaptureCrate, allRoles.toArray(new String[0]));
            
            Field includedGroupTagsField = NPCGroup.class.getDeclaredField("includedGroupTags");
            includedGroupTagsField.setAccessible(true);
            includedGroupTagsField.set(mergedCaptureCrate, baseCaptureCrate.getIncludedTagSets());
            
            Field excludedRolesField = NPCGroup.class.getDeclaredField("excludedRoles");
            excludedRolesField.setAccessible(true);
            excludedRolesField.set(mergedCaptureCrate, baseCaptureCrate.getExcludedTags());
            
            Field excludedGroupTagsField = NPCGroup.class.getDeclaredField("excludedGroupTags");
            excludedGroupTagsField.setAccessible(true);
            excludedGroupTagsField.set(mergedCaptureCrate, baseCaptureCrate.getExcludedTagSets());
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e).log("Failed to set fields on merged Capture_Crate NPCGroup");
            return;
        }
        
        // Load the merged asset to replace the base one
        AssetStore<String, NPCGroup, ?> assetStore = NPCGroup.getAssetStore();
        assetStore.loadAssets("hexvane:Dragonlings", List.of(mergedCaptureCrate));
        
        LOGGER.atInfo().log("Successfully merged %d roles (including %d dragonlings) into Capture_Crate tag set", 
            allRoles.size(), DRAGONLING_ROLES.length);
    }
}
