package com.hexvane.dragonlings;

/**
 * AI state for dragonlings - determines their current behavior.
 */
public enum DragonlingAIState {
    WANDER,           // Default: wandering around (leashed or not)
    FOLLOW_OWNER,     // Following the owner (tamed, not leashed)
    SEEK_FURNACE,     // Red: seeking a furnace to boost
    HARVEST_CROPS,    // Green: seeking crops to harvest
    WATER_CROPS,      // Blue: seeking crops to water
    COMBAT            // Purple: in combat mode
}
