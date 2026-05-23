package dev.gisketch.chowkingdom.npc

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Entity.RemovalReason
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import java.util.UUID
import java.util.function.Consumer

object NpcPokemonCompanions {
    private const val POKEMON_ENTITY_CLASS = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity"
    private const val POKEMON_PROPERTIES_CLASS = "com.cobblemon.mod.common.api.pokemon.PokemonProperties"
    private const val CAPTURE_CONTEXT_CLASS = "com.cobblemon.mod.common.api.pokeball.catching.CaptureContext"
    private const val COMPANION_TAG = "CkdmNpcCompanion"
    private const val NPC_ID_TAG = "NpcId"
    private const val SPECIES_TAG = "MainPokemon"
    private const val FOLLOW_SPEED = 1.05
    private const val FOLLOW_START_DISTANCE_SQR = 4.5 * 4.5
    private const val FOLLOW_STOP_DISTANCE_SQR = 2.25 * 2.25
    private const val TELEPORT_DISTANCE_SQR = 36.0 * 36.0
    private const val COMPANION_TICK_INTERVAL = 5
    private const val COMPANION_CLEANUP_INTERVAL = 20 * 10

    private var eventsRegistered = false
    private val pokemonEntityClass: Class<*>? by lazy { runCatching { Class.forName(POKEMON_ENTITY_CLASS) }.getOrNull() }
    private val pokemonPropertiesClass: Class<*>? by lazy { runCatching { Class.forName(POKEMON_PROPERTIES_CLASS) }.getOrNull() }
    private val captureContextClass: Class<*>? by lazy { runCatching { Class.forName(CAPTURE_CONTEXT_CLASS) }.getOrNull() }
    private val hideLabelAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("HIDE_LABEL") }
    private val unbattleableAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("UNBATTLEABLE") }
    private val shouldRenderNameAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("SHOULD_RENDER_NAME") }
    private val countsTowardsSpawnCapAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("COUNTS_TOWARDS_SPAWN_CAP") }
    private val labelLevelAccessor: Any? by lazy { pokemonEntityClass?.staticAccessor("LABEL_LEVEL") }
    private val pokemonSleepingMemory: Any? by lazy { cobblemonStaticField("com.cobblemon.mod.common.CobblemonMemories", "POKEMON_SLEEPING") }
    private val pokemonDrowsyMemory: Any? by lazy { cobblemonStaticField("com.cobblemon.mod.common.CobblemonMemories", "POKEMON_DROWSY") }
    private val pokemonSleepingActivity: Any? by lazy { cobblemonStaticField("com.cobblemon.mod.common.CobblemonActivities", "POKEMON_SLEEPING_ACTIVITY") }
    private val sleepStatus: Any? by lazy { cobblemonStaticField("com.cobblemon.mod.common.api.pokemon.status.Statuses", "SLEEP") }
    private val persistentStatusContainerClass: Class<*>? by lazy { runCatching { Class.forName("com.cobblemon.mod.common.pokemon.status.PersistentStatusContainer") }.getOrNull() }
    private val battleSuspendedNpcIds: MutableSet<String> = linkedSetOf()
    private val companionReleaseWindows: MutableMap<String, CompanionReleaseWindow> = linkedMapOf()
    private val companionIdsByNpc: MutableMap<String, MutableSet<UUID>> = linkedMapOf()
    private var nextCleanupTick = 0

    fun registerEvents() {
        if (eventsRegistered) return
        eventsRegistered = true
        runCatching {
            val eventsClass = Class.forName("com.cobblemon.mod.common.api.events.CobblemonEvents")
            subscribeRaw(eventsClass, "POKE_BALL_CAPTURE_CALCULATED", ::handleCaptureCalculated)
            subscribeRaw(eventsClass, "BATTLE_STARTED_PRE", ::handleBattleStartedPre)
            ChowKingdomMod.LOGGER.info("Registered NPC Cobblemon companion guards")
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("NPC Cobblemon companion guards unavailable", exception)
        }
    }

    fun tick(server: MinecraftServer) {
        if (pokemonEntityClass == null || pokemonPropertiesClass == null) return
        if (server.tickCount >= nextCleanupTick) {
            cleanupLoadedCompanions(server)
            nextCleanupTick = server.tickCount + COMPANION_CLEANUP_INTERVAL
        }
        if (server.tickCount % COMPANION_TICK_INTERVAL != 0) return
        NpcConfig.all().forEach { definition ->
            if (definition.mainPokemon.isBlank()) return@forEach
            val npc = NpcFeature.existingNpc(server, definition.id)
            if (npc == null || !npc.isAlive || NpcStore.isDead(definition.id)) {
                removeCachedForNpc(server, definition.id, discard = true)
                return@forEach
            }
            if (cleanNpcId(definition.id) in battleSuspendedNpcIds) {
                removeCachedForNpc(server, definition.id, discard = true)
                return@forEach
            }
            if (!shouldReleaseCompanion(npc, definition)) {
                recallCachedCompanions(server, definition.id)
                return@forEach
            }
            tickNpcCompanion(npc, definition)
        }
    }

    fun ensureFor(npc: ChowNpcEntity, definition: NpcDefinition) {
        if (definition.mainPokemon.isBlank()) return
        if (pokemonEntityClass == null || pokemonPropertiesClass == null) return
        if (!shouldReleaseCompanion(npc, definition)) {
            (npc.level() as? ServerLevel)?.server?.let { server -> recallCompanions(server, definition.id) }
            return
        }
        tickNpcCompanion(npc, definition)
    }

    fun removeForNpc(server: MinecraftServer, npcId: String) {
        companionReleaseWindows.remove(cleanNpcId(npcId))
        companions(server, npcId, discover = true).forEach { companion -> companion.discard() }
        companionIdsByNpc.remove(cleanNpcId(npcId))
    }

    private fun recallCompanions(server: MinecraftServer, npcId: String) {
        companions(server, npcId, discover = true).forEach { companion -> recallWithEffects(companion) }
        companionIdsByNpc.remove(cleanNpcId(npcId))
    }

    private fun removeCachedForNpc(server: MinecraftServer, npcId: String, discard: Boolean) {
        companionReleaseWindows.remove(cleanNpcId(npcId))
        companions(server, npcId, discover = false).forEach { companion ->
            if (discard) companion.discard() else recallWithEffects(companion)
        }
        companionIdsByNpc.remove(cleanNpcId(npcId))
    }

    private fun recallCachedCompanions(server: MinecraftServer, npcId: String) {
        removeCachedForNpc(server, npcId, discard = false)
    }

    fun suspendForBattle(npcId: String, server: MinecraftServer) {
        battleSuspendedNpcIds.add(cleanNpcId(npcId))
        companions(server, npcId, discover = true).forEach { companion -> recallForBattle(companion) }
        companionIdsByNpc.remove(cleanNpcId(npcId))
    }

    fun resumeAfterBattle(npcId: String) {
        battleSuspendedNpcIds.remove(cleanNpcId(npcId))
    }

    fun isCompanion(entity: Entity?): Boolean = entity?.persistentData?.getCompound(COMPANION_TAG)?.getBoolean(COMPANION_TAG) == true

    fun npcId(entity: Entity?): String = entity?.persistentData?.getCompound(COMPANION_TAG)?.getString(NPC_ID_TAG).orEmpty()

    fun speciesLabel(definition: NpcDefinition): String {
        val species = definition.mainPokemon.trim()
        if (species.isBlank()) return "none"
        return species.substringAfter(':').replace('_', ' ').replace('-', ' ').split(' ')
            .filter(String::isNotBlank)
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
            .ifBlank { species }
    }

    fun llmSummary(definition: NpcDefinition): String {
        if (definition.mainPokemon.isBlank()) return "- Pokemon companion: none"
        return "- Pokemon companion: ${speciesLabel(definition)} (${definition.mainPokemon}); this is your NPC-owned companion. You may mention them naturally sometimes, but not in every reply."
    }

    private fun tickNpcCompanion(npc: ChowNpcEntity, definition: NpcDefinition) {
        val level = npc.level() as? ServerLevel ?: return
        val companions = companions(level.server, definition.id, discover = false)
        val matching = companions.filter { companion -> companion.persistentData.getCompound(COMPANION_TAG).getString(SPECIES_TAG) == definition.mainPokemon }
        val active = matching.firstOrNull { companion -> companion.level() == level && companion.isAlive } ?: spawn(level, npc, definition)
        companions.filter { companion -> companion != active }.forEach { companion -> companion.discard() }
        if (active != null) {
            applyCompanionState(active, definition)
            syncSleeping(active, npc)
            if (npc.isSleeping) (active as? Mob)?.navigation?.stop() else follow(active, npc)
        }
    }

    private fun spawn(level: ServerLevel, npc: ChowNpcEntity, definition: NpcDefinition): Entity? {
        val propertiesClass = pokemonPropertiesClass ?: return null
        return runCatching {
            val properties = propertiesClass.getConstructor().newInstance()
            propertiesClass.getMethod("setSpecies", String::class.java).invoke(properties, definition.mainPokemon.substringAfter(':'))
            propertiesClass.getMethod("setLevel", Integer::class.java).invoke(properties, Integer.valueOf(10))
            val entity = propertiesClass.getMethod("createEntity", net.minecraft.world.level.Level::class.java).invoke(properties, level) as? Entity
                ?: return@runCatching null
            entity.moveTo(npc.x + 1.2, npc.y, npc.z + 1.2, level.random.nextFloat() * 360.0f, 0.0f)
            tagCompanion(entity, definition)
            applyCompanionState(entity, definition)
            level.addFreshEntity(entity)
            rememberCompanion(entity, definition.id)
            releaseEffects(entity)
            entity
        }.onFailure { exception ->
            ChowKingdomMod.LOGGER.debug("Failed to spawn NPC Pokemon companion npc={} pokemon={}", definition.id, definition.mainPokemon, exception)
        }.getOrNull()
    }

    private fun shouldReleaseCompanion(npc: ChowNpcEntity, definition: NpcDefinition): Boolean {
        val level = npc.level() as? ServerLevel ?: return false
        val settings = NpcConfig.settings().pokemonCompanions
        if (!settings.eventWindows) return true
        val activity = NpcFeature.activityFor(npc, definition)
        if (settings.recallDuringSleep && (npc.isSleeping || activity == "sleep")) return false
        if (settings.recallDuringHome && activity == "home") return false
        if (settings.recallDuringWork && activity == "work") return false
        val releaseChance = when {
            activity == "pokemon_roam" -> settings.pokemonRoamReleaseChance
            activity == NpcScheduleDefinition.MEETUP_ACTIVITY -> settings.meetupReleaseChance
            NpcFeature.ambientFocus(npc)?.let { focus -> focus.topic == "pokemon" || "pokemon" in focus.goal } == true -> settings.ambientPokemonReleaseChance
            else -> return false
        }
        return releaseWindowAllows(level, definition.id, settings, releaseChance)
    }

    private fun releaseWindowAllows(level: ServerLevel, npcId: String, settings: NpcPokemonCompanionSettingsDefinition, releaseChance: Int): Boolean {
        val key = cleanNpcId(npcId)
        companionReleaseWindows[key]?.let { window ->
            if (level.gameTime < window.untilTick) return window.released
        }
        val released = releaseChance >= 100 || releaseChance > 0 && level.random.nextInt(100) < releaseChance
        val seconds = if (released) {
            randomSeconds(level, settings.releaseMinSeconds, settings.releaseMaxSeconds)
        } else {
            randomSeconds(level, settings.recallMinSeconds, settings.recallMaxSeconds)
        }
        companionReleaseWindows[key] = CompanionReleaseWindow(released, level.gameTime + seconds * 20L)
        return released
    }

    private fun randomSeconds(level: ServerLevel, minSeconds: Int, maxSeconds: Int): Int =
        if (minSeconds == maxSeconds) minSeconds else minSeconds + level.random.nextInt(maxSeconds - minSeconds + 1)

    private fun tagCompanion(entity: Entity, definition: NpcDefinition) {
        entity.persistentData.put(COMPANION_TAG, CompoundTag().also { tag ->
            tag.putBoolean(COMPANION_TAG, true)
            tag.putString(NPC_ID_TAG, definition.id)
            tag.putString(SPECIES_TAG, definition.mainPokemon)
        })
        val pokemon = runCatching { entity.javaClass.getMethod("getPokemon").invoke(entity) }.getOrNull()
        val pokemonTag = runCatching { pokemon?.javaClass?.getMethod("getPersistentData")?.invoke(pokemon) as? CompoundTag }.getOrNull()
        pokemonTag?.put(COMPANION_TAG, entity.persistentData.getCompound(COMPANION_TAG).copy())
    }

    private fun applyCompanionState(entity: Entity, definition: NpcDefinition) {
        entity.setInvulnerable(true)
        entity.isCustomNameVisible = true
        entity.customName = Component.literal(speciesLabel(definition))
        writeEntityDataValue(entity, labelLevelAccessor, 0)
        writeEntityDataBoolean(entity, hideLabelAccessor, false)
        writeEntityDataBoolean(entity, unbattleableAccessor, true)
        writeEntityDataBoolean(entity, shouldRenderNameAccessor, true)
        writeEntityDataBoolean(entity, countsTowardsSpawnCapAccessor, false)
        runCatching { entity.javaClass.getMethod("setPersistenceRequired").invoke(entity) }
        runCatching { entity.javaClass.getMethod("setCountsTowardsSpawnCap", java.lang.Boolean.TYPE).invoke(entity, false) }
    }

    private fun follow(companion: Entity, npc: ChowNpcEntity) {
        if (companion.level() != npc.level()) {
            companion.remove(RemovalReason.DISCARDED)
            return
        }
        if (companion.distanceToSqr(npc) >= TELEPORT_DISTANCE_SQR) {
            companion.teleportTo(npc.x + 1.2, npc.y, npc.z + 1.2)
            return
        }
        val mob = companion as? Mob ?: return
        when {
            companion.distanceToSqr(npc) <= FOLLOW_STOP_DISTANCE_SQR -> mob.navigation.stop()
            companion.distanceToSqr(npc) >= FOLLOW_START_DISTANCE_SQR -> mob.navigation.moveTo(npc.x, npc.y, npc.z, FOLLOW_SPEED)
        }
    }

    private fun syncSleeping(companion: Entity, npc: ChowNpcEntity) {
        if (npc.isSleeping) {
            forceCompanionSleeping(companion)
        } else {
            forceCompanionAwake(companion)
        }
    }

    private fun forceCompanionAwake(entity: Entity) {
        clearPokemonStatusIfSleep(entity)
        pokemonSleepingMemory?.let { memory -> eraseBrainMemory(entity, memory) }
        pokemonDrowsyMemory?.let { memory -> eraseBrainMemory(entity, memory) }
        brain(entity)?.let { brain -> runCatching { brain.javaClass.getMethod("useDefaultActivity").invoke(brain) } }
        runCatching { (entity as? LivingEntity)?.stopSleeping() }
    }

    private fun forceCompanionSleeping(entity: Entity) {
        setPokemonSleepStatus(entity)
        pokemonDrowsyMemory?.let { memory -> eraseBrainMemory(entity, memory) }
        pokemonSleepingMemory?.let { memory -> setBrainMemory(entity, memory, true) }
        pokemonSleepingActivity?.let { activity -> setBrainActivity(entity, activity) }
        (entity as? Mob)?.navigation?.stop()
    }

    private fun clearPokemonStatusIfSleep(entity: Entity) {
        val pokemon = pokemon(entity) ?: return
        val statusContainer = runCatching { pokemon.javaClass.getMethod("getStatus").invoke(pokemon) }.getOrNull() ?: return
        val currentStatus = runCatching { statusContainer.javaClass.getMethod("getStatus").invoke(statusContainer) }.getOrNull()
        if (currentStatus == sleepStatus) {
            runCatching { pokemon.javaClass.getMethod("setStatus", statusContainer.javaClass).invoke(pokemon, null) }
                .recoverCatching { pokemon.javaClass.methods.firstOrNull { method -> method.name == "setStatus" && method.parameterCount == 1 }?.invoke(pokemon, null) }
        }
    }

    private fun setPokemonSleepStatus(entity: Entity) {
        val pokemon = pokemon(entity) ?: return
        val status = sleepStatus ?: return
        val containerClass = persistentStatusContainerClass ?: return
        val currentContainer = runCatching { pokemon.javaClass.getMethod("getStatus").invoke(pokemon) }.getOrNull()
        val currentStatus = runCatching { currentContainer?.javaClass?.getMethod("getStatus")?.invoke(currentContainer) }.getOrNull()
        if (currentStatus == status) return
        val container = runCatching {
            containerClass.constructors.firstOrNull { constructor -> constructor.parameterCount == 2 }
                ?.newInstance(status, 0)
        }.getOrNull() ?: return
        runCatching { pokemon.javaClass.getMethod("setStatus", containerClass).invoke(pokemon, container) }
            .recoverCatching { pokemon.javaClass.methods.firstOrNull { method -> method.name == "setStatus" && method.parameterCount == 1 }?.invoke(pokemon, container) }
    }

    private fun pokemon(entity: Entity): Any? =
        runCatching { entity.javaClass.getMethod("getPokemon").invoke(entity) }.getOrNull()

    private fun brain(entity: Entity): Any? =
        runCatching { entity.javaClass.getMethod("getBrain").invoke(entity) }.getOrNull()

    private fun eraseBrainMemory(entity: Entity, memory: Any) {
        brain(entity)?.let { brain ->
            runCatching {
                brain.javaClass.methods.firstOrNull { method -> method.name == "eraseMemory" && method.parameterCount == 1 }
                    ?.invoke(brain, memory)
            }
        }
    }

    private fun setBrainMemory(entity: Entity, memory: Any, value: Any) {
        brain(entity)?.let { brain ->
            runCatching {
                brain.javaClass.methods.firstOrNull { method -> method.name == "setMemory" && method.parameterCount == 2 }
                    ?.invoke(brain, memory, value)
            }
        }
    }

    private fun setBrainActivity(entity: Entity, activity: Any) {
        brain(entity)?.let { brain ->
            runCatching {
                brain.javaClass.methods.firstOrNull { method -> method.name == "setActiveActivityToFirstValid" && method.parameterCount == 1 }
                    ?.invoke(brain, listOf(activity))
            }
        }
    }

    private fun recallForBattle(entity: Entity) {
        recallWithEffects(entity)
    }

    private fun recallWithEffects(entity: Entity) {
        val level = entity.level() as? ServerLevel
        if (level != null) {
            val y = entity.y + entity.bbHeight * 0.55
            level.sendParticles(ParticleTypes.POOF, entity.x, y, entity.z, 18, 0.35, 0.35, 0.35, 0.02)
            level.sendParticles(ParticleTypes.PORTAL, entity.x, y, entity.z, 12, 0.22, 0.22, 0.22, 0.05)
            level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.55f, 1.65f)
        }
        entity.discard()
    }

    private fun releaseEffects(entity: Entity) {
        val level = entity.level() as? ServerLevel ?: return
        val y = entity.y + entity.bbHeight * 0.55
        level.sendParticles(ParticleTypes.POOF, entity.x, y, entity.z, 12, 0.3, 0.3, 0.3, 0.02)
        level.playSound(null, entity.x, entity.y, entity.z, SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL, 0.45f, 1.35f)
    }

    private fun companions(server: MinecraftServer, npcId: String, discover: Boolean): List<Entity> {
        val key = cleanNpcId(npcId)
        val cached = companionIdsByNpc[key].orEmpty()
            .mapNotNull { id -> server.allLevels.asSequence().mapNotNull { level -> level.getEntity(id) }.firstOrNull() }
            .filter { entity -> entity.isAlive && isPokemonEntity(entity) && isCompanion(entity) && cleanNpcId(npcId(entity)) == key }
        if (cached.isNotEmpty() || !discover) {
            companionIdsByNpc[key] = cached.map { it.uuid }.toMutableSet()
            return cached
        }
        val discovered = server.allLevels.flatMap { level ->
            level.allEntities.filter { entity ->
                isPokemonEntity(entity) && isCompanion(entity) && cleanNpcId(npcId(entity)) == key
            }
        }
        companionIdsByNpc[key] = discovered.map { it.uuid }.toMutableSet()
        return discovered
    }

    private fun cleanupLoadedCompanions(server: MinecraftServer) {
        val configured = NpcConfig.all().associateBy { definition -> cleanNpcId(definition.id) }
        val seen: MutableMap<String, MutableSet<UUID>> = linkedMapOf()
        server.allLevels.forEach { level ->
            level.allEntities.forEach { entity ->
                if (!isPokemonEntity(entity) || !isCompanion(entity)) return@forEach
                val key = cleanNpcId(npcId(entity))
                val definition = configured[key]
                if (definition == null || definition.mainPokemon.isBlank() || key in battleSuspendedNpcIds || NpcStore.isDead(definition.id)) {
                    entity.discard()
                    return@forEach
                }
                seen.getOrPut(key) { linkedSetOf() }.add(entity.uuid)
            }
        }
        companionIdsByNpc.clear()
        companionIdsByNpc.putAll(seen)
    }

    private fun rememberCompanion(entity: Entity, npcId: String) {
        companionIdsByNpc.getOrPut(cleanNpcId(npcId)) { linkedSetOf() }.add(entity.uuid)
    }

    private fun handleCaptureCalculated(event: Any) {
        val entity = runCatching { event.javaClass.getMethod("getPokemonEntity").invoke(event) as? Entity }.getOrNull() ?: return
        if (!isCompanion(entity)) return
        val context = captureContextClass?.getConstructor(Integer.TYPE, java.lang.Boolean.TYPE, java.lang.Boolean.TYPE)
            ?.newInstance(0, false, false) ?: return
        runCatching { event.javaClass.getMethod("setCaptureResult", captureContextClass).invoke(event, context) }
    }

    private fun handleBattleStartedPre(event: Any) {
        if (!battleContainsCompanion(event)) return
        runCatching { event.javaClass.getMethod("cancel").invoke(event) }
        runCatching { event.javaClass.getMethod("setReason", net.minecraft.network.chat.MutableComponent::class.java).invoke(event, Component.literal("NPC companion Pokemon cannot battle.")) }
    }

    private fun battleContainsCompanion(event: Any): Boolean {
        val battle = runCatching { event.javaClass.getMethod("getBattle").invoke(event) }.getOrNull() ?: return false
        val activePokemon = runCatching { battle.javaClass.getMethod("getActivePokemon").invoke(battle) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
        if (activePokemon.any(::activeBattlePokemonIsCompanion)) return true
        val actors = runCatching { battle.javaClass.getMethod("getActors").invoke(battle) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
        return actors.any { actor ->
            val pokemonList = runCatching { actor?.javaClass?.getMethod("getPokemonList")?.invoke(actor) as? Iterable<*> }.getOrNull() ?: emptyList<Any?>()
            pokemonList.any(::battlePokemonIsCompanion)
        }
    }

    private fun activeBattlePokemonIsCompanion(active: Any?): Boolean {
        val battlePokemon = runCatching { active?.javaClass?.getMethod("getBattlePokemon")?.invoke(active) }.getOrNull()
        return battlePokemonIsCompanion(battlePokemon)
    }

    private fun battlePokemonIsCompanion(battlePokemon: Any?): Boolean {
        val entity = runCatching { battlePokemon?.javaClass?.getMethod("getEntity")?.invoke(battlePokemon) as? Entity }.getOrNull()
        if (isCompanion(entity)) return true
        val originalPokemon = runCatching { battlePokemon?.javaClass?.getMethod("getOriginalPokemon")?.invoke(battlePokemon) }.getOrNull()
        val originalEntity = runCatching { originalPokemon?.javaClass?.getMethod("getEntity")?.invoke(originalPokemon) as? Entity }.getOrNull()
        return isCompanion(originalEntity)
    }

    private fun subscribeRaw(eventsClass: Class<*>, fieldName: String, handler: (Any) -> Unit) {
        val observable = eventsClass.getField(fieldName).get(null)
        val subscribe = observable.javaClass.methods.first { method ->
            method.name == "subscribe" && method.parameterTypes.size == 1 && method.parameterTypes[0] == Consumer::class.java
        }
        subscribe.invoke(observable, Consumer<Any> { event -> handler(event) })
    }

    private fun isPokemonEntity(entity: Entity): Boolean = pokemonEntityClass?.isInstance(entity) == true

    private fun cleanNpcId(value: String): String = value.trim().lowercase().replace(' ', '_')

    private data class CompanionReleaseWindow(val released: Boolean, val untilTick: Long)

    private fun Class<*>.staticAccessor(name: String): Any? =
        runCatching { getMethod("get$name").invoke(null) }.getOrNull()
            ?: runCatching { getMethod("access\$get${name}\$cp").invoke(null) }.getOrNull()
            ?: runCatching { getDeclaredField(name).also { it.isAccessible = true }.get(null) }.getOrNull()

    private fun cobblemonStaticField(className: String, fieldName: String): Any? =
        runCatching { Class.forName(className).staticAccessor(fieldName) }.getOrNull()

    private fun writeEntityDataBoolean(entity: Entity, accessor: Any?, value: Boolean) {
        writeEntityDataValue(entity, accessor, value)
    }

    private fun writeEntityDataValue(entity: Entity, accessor: Any?, value: Any?) {
        accessor ?: return
        runCatching {
            val setMethod = entity.entityData.javaClass.methods.firstOrNull { method ->
                method.name == "set" && method.parameterCount == 2 && method.parameterTypes[1] == Any::class.java
            } ?: entity.entityData.javaClass.methods.firstOrNull { method -> method.name == "set" && method.parameterCount == 2 }
            setMethod?.invoke(entity.entityData, accessor, value)
        }
    }
}
