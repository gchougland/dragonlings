# Dragonlings Mod

A Hytale mod that adds small, bipedal, tameable dragons as pets with unique behaviors and abilities.

## Features

### Dragonling Variants

The mod includes four unique dragonling variants, each with distinct appearances and abilities:

- **Green Dragonling** - Nature-focused, spawns in Zone 1 forest biomes
- **Blue Dragonling** - Water-focused, spawns on beaches
- **Red Dragonling** - Fire-focused, spawns in Zone 4 Wastes (lava areas)
- **Purple Dragonling** - Void-focused, spawns anywhere at night

### Taming System

Dragonlings can be tamed by right-clicking them with specific essence items:
- **Green Dragonling**: `Ingredient_Life_Essence`
- **Blue Dragonling**: `Ingredient_Ice_Essence`
- **Red Dragonling**: `Ingredient_Fire_Essence`
- **Purple Dragonling**: `Ingredient_Void_Essence`

Taming is chance-based - not every attempt will succeed. When successful, the dragonling will become your pet and follow you around, teleporting nearby if you get too far away.

### Leashing System

All dragonlings can be leashed to a specific location using the **Dragon Whistle** tool:
1. Right-click a tamed dragonling with the Dragon Whistle to enter "leash mode"
2. Right-click a block to set the leash position
3. The dragonling will wander around that spot within a defined area

### Variant-Specific Behaviors (When Leashed)

Each dragonling variant has unique behaviors when leashed:

#### Green Dragonling
- **Crop Harvesting**: Automatically harvests crops within its wander range
- **Chest Storage**: Deposits harvested crops into a chest if the leashed block is a chest
- Only harvests if leashed to a chest block

#### Blue Dragonling
- **Crop Watering**: Uses its `Blow` animation to spray water particles
- Automatically waters crops within its wander radius
- Helps maintain your farm's hydration

#### Red Dragonling
- **Furnace Boosting**: Uses its `Blow` animation to blow fire particles at furnaces
- Speeds up furnace processing (2x speed)
- Automatically targets furnaces within its wander radius

#### Purple Dragonling
- **Combat Assistance**: Helps fight hostile entities you attack
- Uses its `Blow` animation to spawn damaging void projectiles
- Automatically targets enemies you're fighting
- Works both when leashed and when following

### Additional Features

- **Capture Crates**: Dragonlings can be captured using Capture Crates
- **Following Behavior**: Tamed dragonlings follow their owner and teleport if too far away
- **Particle Effects**: Unique particle effects for each variant's abilities
- **Animations**: Custom idle, walk, run, and blow animations

## Requirements

- Hytale Server
- Java 25+
- Gradle (for building)

## Installation

1. Build the mod using Gradle:
   ```bash
   ./gradlew build
   ```

2. The built mod will be in `build/libs/`

3. Place the mod JAR file in your Hytale server's mods directory

4. Restart the server

## Building

```bash
# Clean build
./gradlew clean build

# Run server (for testing)
./gradlew runServer
```

## Usage

### Obtaining Essences

You'll need the appropriate essence items to tame dragonlings:
- `Ingredient_Life_Essence` - For Green Dragonlings
- `Ingredient_Ice_Essence` - For Blue Dragonlings
- `Ingredient_Fire_Essence` - For Red Dragonlings
- `Ingredient_Void_Essence` - For Purple Dragonlings

### Obtaining the Dragon Whistle

The Dragon Whistle is a custom item used to leash dragonlings. Obtain it through creative mode or add it to your inventory using server commands.

### Taming Process

1. Find a wild dragonling in its natural habitat
2. Hold the appropriate essence item in your hand
3. Right-click the dragonling
4. If successful, you'll see a chat message confirming the taming
5. The dragonling will now follow you

### Setting Up Leashed Behaviors

1. Tame a dragonling
2. Right-click it with the Dragon Whistle (enters leash mode)
3. Right-click the target block (chest for Green, any block for others)
4. The dragonling will now perform its variant-specific behavior in that area

## Technical Details

### Spawning Locations

- **Green Dragonling**: Zone 1 forest biomes (e.g., `Forest_Flower`)
- **Blue Dragonling**: Any beach biome (`Env_Zone1_Shores`)
- **Red Dragonling**: Zone 4 Wastes lava areas (`Env_Zone4_Wastes`)
- **Purple Dragonling**: Anywhere at night (like other void mobs)

### Custom Components

The mod uses custom entity components to track:
- Taming status
- Owner UUID
- Leash position and radius
- Leash block type

### Debug Logging

The mod includes extensive debug logging for:
- Taming attempts and results
- Leash mode activation
- Behavior detection (crops, furnaces, enemies)
- Animation and particle spawning

Check server logs for detailed information about dragonling behavior.

## Development

### Project Structure

```
src/main/
├── java/com/hexvane/dragonlings/
│   ├── DragonlingsPlugin.java          # Main plugin class
│   ├── DragonlingTamingHandler.java    # Taming logic
│   ├── DragonlingLeashHandler.java     # Leashing logic
│   ├── DragonlingInteractionSimulationHandler.java  # Custom interactions
│   └── behaviors/                      # Variant-specific behaviors
│       ├── GreenDragonlingHarvestBehavior.java
│       ├── BlueDragonlingWaterBehavior.java
│       ├── RedDragonlingFurnaceBehavior.java
│       └── PurpleDragonlingCombatBehavior.java
└── resources/
    ├── Server/
    │   ├── NPC/Roles/                  # NPC role definitions
    │   ├── NPC/Spawn/                  # Spawn configurations
    │   ├── Models/                     # Model definitions
    │   ├── Particles/                  # Particle systems
    │   ├── ProjectileConfigs/          # Projectile configurations
    │   └── Item/Items/                 # Item definitions
    └── Common/
        └── NPC/Pets/Dragonling/        # Model and animation assets
```

## License

All rights reserved.

This project is proprietary. No permission is granted to use, copy, modify, or distribute
this code or its assets without explicit written permission from the author.

## Credits

- Created for Hytale modding
- Uses Hytale's NPC system, particle system, and interaction framework
