#!/usr/bin/env bash
set -euo pipefail

SKIP_BUILD=false
NO_LAUNCH=false
NO_SYNC_DEPS=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-build)
            SKIP_BUILD=true
            ;;
        --no-launch)
            NO_LAUNCH=true
            ;;
        --no-sync-deps)
            NO_SYNC_DEPS=true
            ;;
        -h|--help)
            echo "Usage: $0 [--skip-build] [--no-launch] [--no-sync-deps]"
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            echo "Usage: $0 [--skip-build] [--no-launch] [--no-sync-deps]" >&2
            exit 2
            ;;
    esac
    shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

LAUNCHER_ROOT="/Users/arnelglennjimenez/Library/Application Support/dev.gisketch.modsync/launchers/prismlauncher-cracked/11.0.2-1"
LAUNCHER_DATA="$LAUNCHER_ROOT/data"
SOURCE_INSTANCE_NAME="modsync-ckdm-2026"
INSTANCE_NAME="modsync-chowbblemon"
SOURCE_MINECRAFT="$LAUNCHER_DATA/instances/$SOURCE_INSTANCE_NAME/minecraft"
INSTANCE_MINECRAFT="$LAUNCHER_DATA/instances/$INSTANCE_NAME/minecraft"
SOURCE_MODS_DIR="$SOURCE_MINECRAFT/mods"
MODS_DIR="$INSTANCE_MINECRAFT/mods"
PRISM_APP="$LAUNCHER_ROOT/Prism Launcher.app"
BUNDLED_JAVA_HOME="$LAUNCHER_DATA/java/java-runtime-delta/jre.bundle/Contents/Home"

# Minimal CKDM dev runtime: required CKDM deps + Cobblemon/RCTAPI for core gameplay tests.
# Excludes optional compat/content packs: RPG classes, relics, mega addons, JEI/Jade, shaders, furniture, etc.
DEV_MODS=(
    "architectury-13.0.8-neoforge.jar"
    "bettercombat-neoforge-2.3.2+1.21.1.jar"
    "cloth-config-15.0.140-neoforge.jar"
    "Cobblemon-neoforge-1.7.3+1.21.1.jar"
    "forgified-fabric-api-0.116.7+2.2.4+1.21.1.jar"
    "geckolib-neoforge-1.21.1-4.8.4.jar"
    "kotlinforforge-5.11.0-all.jar"
    "mobplayeranimator-neoforge-1.21.1-1.4.0-ckdm.jar"
    "owo-lib-neoforge-0.12.15.5-beta.1+1.21.jar"
    "Pehkui-3.8.3+1.21-neoforge.jar"
    "player-animation-lib-forge-2.0.4+1.21.1.jar"
    "rctapi-neoforge-1.21.1-0.15.2-beta.jar"
    "SmartBrainLib-neoforge-1.21.1-1.16.11.jar"
)

if [[ -z "${JAVA_HOME:-}" && -x "$BUNDLED_JAVA_HOME/bin/java" ]]; then
    export JAVA_HOME="$BUNDLED_JAVA_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ ! -x "$ROOT/gradlew" ]]; then
    echo "Missing Gradle wrapper: $ROOT/gradlew" >&2
    exit 1
fi
if [[ ! -d "$MODS_DIR" ]]; then
    echo "Missing Prism instance mods folder: $MODS_DIR" >&2
    exit 1
fi
if [[ ! -d "$PRISM_APP" ]]; then
    echo "Missing Prism launcher app: $PRISM_APP" >&2
    exit 1
fi

if [[ "$SKIP_BUILD" == false ]]; then
    ./gradlew build
fi

if [[ "$NO_SYNC_DEPS" == false ]]; then
    if [[ ! -d "$SOURCE_MODS_DIR" ]]; then
        echo "Missing source instance mods folder: $SOURCE_MODS_DIR" >&2
        exit 1
    fi

    find "$MODS_DIR" -maxdepth 1 -type f -name '*.jar' -delete
    for mod in "${DEV_MODS[@]}"; do
        if [[ ! -f "$SOURCE_MODS_DIR/$mod" ]]; then
            echo "Missing source mod: $SOURCE_MODS_DIR/$mod" >&2
            exit 1
        fi
        cp -f "$SOURCE_MODS_DIR/$mod" "$MODS_DIR/$mod"
    done

    if [[ -d "$SOURCE_MINECRAFT/config/gisketchs_chowkingdom_mod" ]]; then
        mkdir -p "$INSTANCE_MINECRAFT/config"
        rm -rf "$INSTANCE_MINECRAFT/config/gisketchs_chowkingdom_mod"
        cp -R "$SOURCE_MINECRAFT/config/gisketchs_chowkingdom_mod" "$INSTANCE_MINECRAFT/config/gisketchs_chowkingdom_mod"
    fi

    echo "Synced minimal dev deps: ${#DEV_MODS[@]} mods"
fi

JAR="$(find "$ROOT/build/libs" -maxdepth 1 -type f -name 'gisketchs_chowkingdom_*.jar' ! -name '*-sources.jar' -print0 \
    | xargs -0 ls -t 2>/dev/null \
    | head -n 1 || true)"

if [[ -z "$JAR" ]]; then
    echo "No built CKDM jar found in build/libs" >&2
    exit 1
fi

find "$MODS_DIR" -maxdepth 1 -type f -name 'gisketchs_chowkingdom_*' -delete

TARGET_JAR="$MODS_DIR/$(basename "$JAR")"
cp -f "$JAR" "$TARGET_JAR"
echo "Installed CKDM jar: $TARGET_JAR"

if [[ "$NO_LAUNCH" == false ]]; then
    open -n "$PRISM_APP" --args --dir "$LAUNCHER_DATA" --launch "$INSTANCE_NAME"
    echo "Launched Prism instance: $INSTANCE_NAME"
fi
