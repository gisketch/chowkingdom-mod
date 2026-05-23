package dev.gisketch.chowkingdom.tech

import dev.gisketch.chowkingdom.ChowKingdomMod
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ArmorItem
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.server.ServerLifecycleHooks
import java.util.Locale

object TechLicenseNetwork {
    fun register(modBus: IEventBus) {
        modBus.addListener(::registerPayloads)
        NeoForge.EVENT_BUS.addListener(::onPlayerLoggedIn)
    }

    fun syncTo(player: ServerPlayer) {
        PacketDistributor.sendToPlayer(player, createPayload(player))
    }

    fun syncAllPlayers() {
        val server = ServerLifecycleHooks.getCurrentServer() ?: return
        server.playerList.players.forEach(::syncTo)
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        registrar.playToClient(TechLicenseSyncPayload.TYPE, TechLicenseSyncPayload.STREAM_CODEC, ::handleSync)
    }

    private fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        val player = event.entity as? ServerPlayer ?: return
        syncTo(player)
    }

    private fun createPayload(player: ServerPlayer): TechLicenseSyncPayload =
        TechLicenseSyncPayload(
            licenses = TechLicenseConfig.all().map { license ->
                TechLicenseClientLicensePayload(
                    id = license.id,
                    displayName = license.displayName,
                    iconItem = license.iconItem,
                    gateNamespaces = license.gateNamespaces.toList(),
                    allowedWithoutLicense = license.allowedWithoutLicense.toList(),
                    alwaysBannedItems = license.alwaysBannedItems.toList(),
                )
            },
            grantedLicenseIds = TechLicenseStore.playerLicenseIds(player).toList(),
        )

    private fun handleSync(payload: TechLicenseSyncPayload, context: IPayloadContext) {
        TechLicenseClientState.apply(payload)
    }
}

object TechLicenseClientState {
    private var licenses: List<TechLicenseClientLicensePayload> = emptyList()
    private var grantedLicenseIds: Set<String> = emptySet()

    fun apply(payload: TechLicenseSyncPayload) {
        licenses = payload.licenses
        grantedLicenseIds = payload.grantedLicenseIds.map { it.lowercase(Locale.ROOT) }.toSet()
    }

    fun hasLicense(licenseId: String): Boolean =
        licenseId.trim().lowercase(Locale.ROOT) in grantedLicenseIds

    fun lockInfo(stack: ItemStack): TechLicenseItemLockInfo? {
        return lockInfo(stack, allowConfiguredExemptions = stack.item !is ArmorItem)
    }

    fun lockInfo(stack: ItemStack, allowConfiguredExemptions: Boolean): TechLicenseItemLockInfo? {
        if (stack.isEmpty) return null
        val id = BuiltInRegistries.ITEM.getKey(stack.item).toString()
        val license = licenses.firstOrNull { entry -> entry.gateNamespaces.any { namespace -> id.substringBefore(':') == namespace } } ?: return null
        if (license.id in grantedLicenseIds) return null
        val hardBanned = license.alwaysBannedItems.any { pattern -> matchesPattern(id, pattern) }
        if (!hardBanned && allowConfiguredExemptions && license.allowedWithoutLicense.any { pattern -> matchesPattern(id, pattern) }) return null
        return TechLicenseItemLockInfo(
            licenseId = license.id,
            displayName = license.displayName,
            iconItem = license.iconItem,
            message = if (hardBanned) "Item is disabled by server config." else "${license.displayName} required.",
        )
    }

    private fun matchesPattern(id: String, pattern: String): Boolean {
        val clean = pattern.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return false
        if (clean.endsWith(":*")) return id.substringBefore(':') == clean.substringBefore(':')
        if ('*' !in clean) return id == clean
        val regex = clean.split('*').joinToString(".*") { part -> Regex.escape(part) }.toRegex()
        return regex.matches(id)
    }
}

data class TechLicenseItemLockInfo(
    val licenseId: String,
    val displayName: String,
    val iconItem: String,
    val message: String,
)

data class TechLicenseClientLicensePayload(
    val id: String,
    val displayName: String,
    val iconItem: String,
    val gateNamespaces: List<String>,
    val allowedWithoutLicense: List<String>,
    val alwaysBannedItems: List<String>,
)

data class TechLicenseSyncPayload(
    val licenses: List<TechLicenseClientLicensePayload>,
    val grantedLicenseIds: List<String>,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TechLicenseSyncPayload> = TYPE

    companion object {
        val TYPE: CustomPacketPayload.Type<TechLicenseSyncPayload> = CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(ChowKingdomMod.MOD_ID, "tech_licenses/sync"))
        val STREAM_CODEC: StreamCodec<RegistryFriendlyByteBuf, TechLicenseSyncPayload> = object : StreamCodec<RegistryFriendlyByteBuf, TechLicenseSyncPayload> {
            override fun decode(buffer: RegistryFriendlyByteBuf): TechLicenseSyncPayload {
                val licenses = readList(buffer) {
                    TechLicenseClientLicensePayload(
                        id = buffer.readUtf(),
                        displayName = buffer.readUtf(),
                        iconItem = buffer.readUtf(),
                        gateNamespaces = readStringList(buffer),
                        allowedWithoutLicense = readStringList(buffer),
                        alwaysBannedItems = readStringList(buffer),
                    )
                }
                return TechLicenseSyncPayload(licenses, readStringList(buffer))
            }

            override fun encode(buffer: RegistryFriendlyByteBuf, value: TechLicenseSyncPayload) {
                writeList(buffer, value.licenses) { license ->
                    buffer.writeUtf(license.id)
                    buffer.writeUtf(license.displayName)
                    buffer.writeUtf(license.iconItem)
                    writeStringList(buffer, license.gateNamespaces)
                    writeStringList(buffer, license.allowedWithoutLicense)
                    writeStringList(buffer, license.alwaysBannedItems)
                }
                writeStringList(buffer, value.grantedLicenseIds)
            }

            private fun <T> readList(buffer: RegistryFriendlyByteBuf, reader: () -> T): List<T> {
                val size = buffer.readVarInt().coerceIn(0, 256)
                return List(size) { reader() }
            }

            private fun <T> writeList(buffer: RegistryFriendlyByteBuf, values: List<T>, writer: (T) -> Unit) {
                buffer.writeVarInt(values.size.coerceAtMost(256))
                values.take(256).forEach(writer)
            }

            private fun readStringList(buffer: RegistryFriendlyByteBuf): List<String> = readList(buffer) { buffer.readUtf() }

            private fun writeStringList(buffer: RegistryFriendlyByteBuf, values: List<String>) {
                writeList(buffer, values) { value -> buffer.writeUtf(value) }
            }
        }
    }
}
