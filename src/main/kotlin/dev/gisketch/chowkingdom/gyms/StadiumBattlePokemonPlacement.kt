package dev.gisketch.chowkingdom.gyms

import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.npc.NpcPokemonCompanions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.RelativeMovement
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.tick.ServerTickEvent
import java.util.EnumSet
import java.util.UUID
import kotlin.math.atan2

object StadiumBattlePokemonPlacement {
    private const val POKEMON_ENTITY_CLASS = "com.cobblemon.mod.common.entity.pokemon.PokemonEntity"
    private const val POKEMON_OFFSET_BLOCKS = 10.0
    private const val PLACEMENT_SYNC_TICKS = 20L * 20L
    private const val SEARCH_PADDING = 24.0
    private const val HORIZONTAL_ANCHOR_TOLERANCE_SQR = 0.35 * 0.35

    private val pokemonEntityClass: Class<*>? by lazy { runCatching { Class.forName(POKEMON_ENTITY_CLASS) }.getOrNull() }
    private val pendingPlacements: MutableList<PendingStadiumPokemonPlacement> = mutableListOf()
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        NeoForge.EVENT_BUS.addListener(::onServerTick)
    }

    fun queue(player: ServerPlayer, npc: ChowNpcEntity, playerPos: Vec3, trainerPos: Vec3) {
        if (pokemonEntityClass == null) return
        val now = player.server.overworld().gameTime
        val placement = PendingStadiumPokemonPlacement(
            untilTick = now + PLACEMENT_SYNC_TICKS,
            levelDimension = player.level().dimension().location().toString(),
            playerUuid = player.uuid,
            npcUuid = npc.uuid,
            playerPos = playerPos,
            trainerPos = trainerPos,
        )
        pendingPlacements.removeIf { pending -> pending.playerUuid == player.uuid || pending.npcUuid == npc.uuid }
        pendingPlacements += placement
        apply(player.server, placement)
    }

    private fun onServerTick(event: ServerTickEvent.Post) {
        if (pendingPlacements.isEmpty()) return
        val now = event.server.overworld().gameTime
        val iterator = pendingPlacements.iterator()
        while (iterator.hasNext()) {
            val placement = iterator.next()
            if (now > placement.untilTick) {
                iterator.remove()
                continue
            }
            apply(event.server, placement)
        }
    }

    private fun apply(server: MinecraftServer, placement: PendingStadiumPokemonPlacement): Boolean {
        val player = server.playerList.getPlayer(placement.playerUuid) ?: return false
        val npc = server.allLevels.asSequence().mapNotNull { level -> level.getEntity(placement.npcUuid) as? ChowNpcEntity }.firstOrNull() ?: return false
        val level = player.level() as? ServerLevel ?: return false
        if (level.dimension().location().toString() != placement.levelDimension || npc.level() != level) return false

        val playerPokemonPos = battlePokemonPos(placement.playerPos, placement.trainerPos)
        val trainerPokemonPos = battlePokemonPos(placement.trainerPos, placement.playerPos)
        val searchCenter = placement.playerPos.add(placement.trainerPos).scale(0.5)
        val searchRadius = placement.playerPos.distanceTo(placement.trainerPos) + SEARCH_PADDING
        val playerPokemon = findOwnedPokemon(server, placement.playerUuid, player, searchCenter, searchRadius)
        val trainerPokemon = findOwnedPokemon(server, placement.npcUuid, npc, searchCenter, searchRadius)
            ?: findBattlePokemon(level, playerPokemon, trainerPokemonPos, searchRadius)

        playerPokemon?.let { pokemon ->
            val firstPlacement = placement.playerPokemonUuid != pokemon.uuid
            placePokemon(pokemon, level, playerPokemonPos, trainerPokemonPos, firstPlacement)
            placement.playerPokemonUuid = pokemon.uuid
        }
        trainerPokemon?.let { pokemon ->
            val firstPlacement = placement.trainerPokemonUuid != pokemon.uuid
            placePokemon(pokemon, level, trainerPokemonPos, playerPokemonPos, firstPlacement)
            placement.trainerPokemonUuid = pokemon.uuid
        }
        if (playerPokemon != null && trainerPokemon != null) {
            facePokemon(playerPokemon, trainerPokemon.position())
            facePokemon(trainerPokemon, playerPokemon.position())
        }
        return playerPokemon != null && trainerPokemon != null
    }

    private fun battlePokemonPos(actorPos: Vec3, opponentPos: Vec3): Vec3 {
        val direction = horizontalDirection(actorPos, opponentPos)
        return actorPos.add(direction.scale(POKEMON_OFFSET_BLOCKS))
    }

    private fun horizontalDirection(from: Vec3, to: Vec3): Vec3 {
        val delta = Vec3(to.x - from.x, 0.0, to.z - from.z)
        return if (delta.lengthSqr() <= 0.0001) Vec3(0.0, 0.0, 1.0) else delta.normalize()
    }

    private fun findOwnedPokemon(server: MinecraftServer, ownerUuid: UUID, owner: LivingEntity, center: Vec3, radius: Double): Entity? {
        val radiusSqr = radius * radius
        return server.allLevels.asSequence()
            .flatMap { level -> level.allEntities.asSequence() }
            .filter { entity -> isPokemonEntity(entity) && entity.isAlive && !NpcPokemonCompanions.isCompanion(entity) }
            .filter { entity -> pokemonOwnerUuid(entity) == ownerUuid || pokemonOwnerEntityUuid(entity) == owner.uuid || entityOwnerUuid(entity) == owner.uuid || pokemonBelongsTo(entity, owner) }
            .sortedWith(compareByDescending<Entity> { entity -> isInBattle(entity) }.thenBy { entity -> entity.position().distanceToSqr(center).takeIf { distance -> distance <= radiusSqr } ?: Double.MAX_VALUE })
            .firstOrNull()
    }

    private fun findBattlePokemon(level: ServerLevel, excluded: Entity?, targetPos: Vec3, radius: Double): Entity? {
        val radiusSqr = radius * radius
        return level.allEntities.asSequence()
            .filter { entity -> entity.uuid != excluded?.uuid && isPokemonEntity(entity) && entity.isAlive && !NpcPokemonCompanions.isCompanion(entity) && isInBattle(entity) }
            .filter { entity -> entity.position().distanceToSqr(targetPos) <= radiusSqr }
            .minByOrNull { entity -> entity.position().distanceToSqr(targetPos) }
    }

    private fun placePokemon(entity: Entity, level: ServerLevel, pos: Vec3, faceToward: Vec3, firstPlacement: Boolean) {
        val yaw = pokemonYawToward(pos, faceToward)
        entity.setNoGravity(false)
        if (entity.level() == level) {
            val placeY = if (firstPlacement) pos.y else entity.y
            if (firstPlacement || horizontalDistanceSqr(entity.position(), pos) > HORIZONTAL_ANCHOR_TOLERANCE_SQR) {
                entity.moveTo(pos.x, placeY, pos.z, yaw, 0.0f)
            }
        } else if (!teleportAcrossLevels(entity, level, pos, yaw)) {
            ChowKingdomMod.LOGGER.debug("Could not move battle Pokemon {} across dimensions for stadium placement", entity.uuid)
            return
        }
        facePokemon(entity, faceToward)
        (entity as? Mob)?.navigation?.stop()
    }

    private fun horizontalDistanceSqr(first: Vec3, second: Vec3): Double {
        val dx = first.x - second.x
        val dz = first.z - second.z
        return dx * dx + dz * dz
    }

    private fun teleportAcrossLevels(entity: Entity, level: ServerLevel, pos: Vec3, yaw: Float): Boolean {
        val movement = EnumSet.noneOf(RelativeMovement::class.java)
        return runCatching {
            val method = entity.javaClass.methods.firstOrNull { method ->
                method.name == "teleportTo" &&
                    method.parameterCount == 7 &&
                    ServerLevel::class.java.isAssignableFrom(method.parameterTypes[0])
            } ?: return false
            method.invoke(entity, level, pos.x, pos.y, pos.z, movement, yaw, 0.0f)
            true
        }.getOrDefault(false)
    }

    private fun facePokemon(entity: Entity, toward: Vec3) {
        val yaw = pokemonYawToward(entity.position(), toward)
        entity.setYRot(yaw)
        entity.setXRot(0.0f)
        entity.yRotO = yaw
        if (entity is LivingEntity) {
            entity.yHeadRot = yaw
            entity.yHeadRotO = yaw
            entity.yBodyRot = yaw
            entity.yBodyRotO = yaw
        }
    }

    private fun yawToward(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return (atan2(dz, dx) * 180.0 / Math.PI - 90.0).toFloat()
    }

    private fun pokemonYawToward(from: Vec3, to: Vec3): Float = normalizeYaw(yawToward(from, to))

    private fun normalizeYaw(yaw: Float): Float {
        var normalized = yaw % 360.0f
        if (normalized >= 180.0f) normalized -= 360.0f
        if (normalized < -180.0f) normalized += 360.0f
        return normalized
    }

    private fun isPokemonEntity(entity: Entity): Boolean = pokemonEntityClass?.isInstance(entity) == true

    private fun pokemon(entity: Entity): Any? =
        runCatching { entity.javaClass.getMethod("getPokemon").invoke(entity) }.getOrNull()

    private fun pokemonOwnerUuid(entity: Entity): UUID? {
        val pokemon = pokemon(entity) ?: return null
        return runCatching { pokemon.javaClass.getMethod("getOwnerUUID").invoke(pokemon) as? UUID }.getOrNull()
    }

    private fun pokemonOwnerEntityUuid(entity: Entity): UUID? {
        val pokemon = pokemon(entity) ?: return null
        return runCatching { (pokemon.javaClass.getMethod("getOwnerEntity").invoke(pokemon) as? LivingEntity)?.uuid }.getOrNull()
    }

    private fun pokemonBelongsTo(entity: Entity, owner: LivingEntity): Boolean {
        if (owner !is ServerPlayer) return false
        val pokemon = pokemon(entity) ?: return false
        return runCatching { pokemon.javaClass.getMethod("belongsTo", net.minecraft.world.entity.player.Player::class.java).invoke(pokemon, owner) as? Boolean == true }.getOrDefault(false)
    }

    private fun entityOwnerUuid(entity: Entity): UUID? =
        runCatching { (entity.javaClass.getMethod("getOwner").invoke(entity) as? LivingEntity)?.uuid }.getOrNull()

    private fun isInBattle(entity: Entity): Boolean =
        runCatching { entity.javaClass.getMethod("getBattle").invoke(entity) != null }.getOrDefault(false) ||
            runCatching { entity.javaClass.getMethod("getBattleId").invoke(entity) != null }.getOrDefault(false)

    private data class PendingStadiumPokemonPlacement(
        val untilTick: Long,
        val levelDimension: String,
        val playerUuid: UUID,
        val npcUuid: UUID,
        val playerPos: Vec3,
        val trainerPos: Vec3,
        var playerPokemonUuid: UUID? = null,
        var trainerPokemonUuid: UUID? = null,
    )
}
