package dev.gisketch.chowkingdom.snackbar

enum class SnackbarType(val id: String) {
    GENERIC("generic"),
    ERROR("error"),
    SUCCESS("success"),
    ;

    companion object {
        val ids: List<String> = entries.map(SnackbarType::id)

        fun fromId(raw: String): SnackbarType = entries.firstOrNull { it.id == raw.lowercase() } ?: GENERIC
    }
}

enum class SnackbarIconKind(val id: String) {
    ITEM("item"),
    PLAYER("player"),
    TEXTURE("texture"),
    NPC("npc"),
    ;

    companion object {
        fun fromId(raw: String): SnackbarIconKind = entries.firstOrNull { it.id == raw.lowercase() } ?: ITEM
    }
}

data class SnackbarNotification(
    val iconKind: SnackbarIconKind,
    val icon: String,
    val title: String,
    val content: String = "",
    val type: SnackbarType = SnackbarType.GENERIC,
    val sound: String = SnackbarSounds.forType(type),
    val progress: SnackbarProgress? = null,
    val durationMs: Long? = null,
) {
    companion object {
        fun item(icon: String, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)): SnackbarNotification =
            SnackbarNotification(SnackbarIconKind.ITEM, icon, title, content, type, sound)

        fun player(playerId: java.util.UUID, playerName: String, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)): SnackbarNotification =
            SnackbarNotification(SnackbarIconKind.PLAYER, "$playerId|$playerName", title, content, type, sound)

        fun texture(texture: String, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)): SnackbarNotification =
            SnackbarNotification(SnackbarIconKind.TEXTURE, texture, title, content, type, sound)

        fun npc(npcId: String, title: String, content: String = "", type: SnackbarType = SnackbarType.GENERIC, sound: String = SnackbarSounds.forType(type)): SnackbarNotification =
            SnackbarNotification(SnackbarIconKind.NPC, npcId, title, content, type, sound)

        fun battlepassXp(title: String, fromXp: Int, toXp: Int, tierSize: Int): SnackbarNotification =
            SnackbarNotification(SnackbarIconKind.ITEM, SnackbarIcons.BATTLEPASS, title, type = SnackbarType.SUCCESS, sound = SnackbarSounds.SUCCESS, progress = SnackbarProgress(fromXp, toXp, tierSize))
    }
}

data class SnackbarProgress(val fromXp: Int, val toXp: Int, val tierSize: Int, val animationMs: Long = 1_400L)

object SnackbarSounds {
    const val GENERIC = "minecraft:block.note_block.pling"
    const val SUCCESS = "minecraft:entity.experience_orb.pickup"
    const val ERROR = "minecraft:block.note_block.bass"
    const val REWARD = "minecraft:entity.player.levelup"
    const val SALE = "minecraft:entity.experience_orb.pickup"
    const val TRADE = "minecraft:block.note_block.chime"
    const val NONE = ""

    fun forType(type: SnackbarType): String = when (type) {
        SnackbarType.ERROR -> ERROR
        SnackbarType.SUCCESS -> SUCCESS
        SnackbarType.GENERIC -> GENERIC
    }
}

object SnackbarIcons {
    const val CHOWCOIN_TEXTURE = "gisketchs_chowkingdom_mod:textures/gui/chowcoin.png"
    const val SHIPPING_BIN = "gisketchs_chowkingdom_mod:shipping_bin"
    const val BATTLEPASS = "minecraft:paper"
    const val TRADE = "minecraft:emerald"
    const val ERROR = "minecraft:barrier"
}
