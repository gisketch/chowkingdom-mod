package dev.gisketch.chowkingdom.tech

import com.google.gson.annotations.SerializedName
import dev.gisketch.chowkingdom.ChowKingdomMod
import dev.gisketch.chowkingdom.config.TomlConfigIO
import dev.gisketch.chowkingdom.npc.NpcMissionDefinition
import net.neoforged.fml.loading.FMLPaths
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

object TechLicenseConfig {
    private var data: TechLicensesConfigData = TechLicensesConfigData.defaults().normalized()
    private var loaded = false

    private val file: Path
        get() = FMLPaths.CONFIGDIR.get().resolve(ChowKingdomMod.MOD_ID).resolve("tech_licenses").resolve("licenses.toml")

    fun load() {
        file.parent.createDirectories()
        if (!file.exists()) {
            data = TechLicensesConfigData.defaults().normalized()
            TomlConfigIO.write(file, data)
            loaded = true
            return
        }
        data = TomlConfigIO.read(file, TechLicensesConfigData::class.java, { TechLicensesConfigData.defaults() }).normalized()
        loaded = true
    }

    fun settings(): TechLicensesConfigData {
        ensureLoaded()
        return data
    }

    fun enabled(): Boolean {
        ensureLoaded()
        return data.enabled
    }

    fun all(): List<TechLicenseDefinition> {
        ensureLoaded()
        return data.licenses
    }

    fun get(id: String): TechLicenseDefinition? = all().firstOrNull { license -> license.id == normalizeId(id) }

    fun forNpc(npcId: String): TechLicenseDefinition? = all().firstOrNull { license -> license.npcId.equals(npcId, ignoreCase = true) }

    fun dailyMissionsForNpc(npcId: String): List<NpcMissionDefinition> {
        val license = forNpc(npcId) ?: return emptyList()
        return license.dailyMissions
            .map { mission ->
                mission.normalized().apply {
                    requiredTechLicense = license.id
                    ignoreDailyCap = true
                    dailyCapGroup = "tech"
                }
            }
            .filter { mission -> mission.id.isNotBlank() }
    }

    fun normalizeId(value: String): String = value.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_')

    private fun ensureLoaded() {
        if (!loaded) load()
    }
}

class TechLicensesConfigData(
    var enabled: Boolean = true,
    @SerializedName("retry_pending_spawn_interval_ticks")
    var retryPendingSpawnIntervalTicks: Int = 200,
    var licenses: MutableList<TechLicenseDefinition> = mutableListOf(),
) {
    fun normalized(): TechLicensesConfigData = apply {
        retryPendingSpawnIntervalTicks = retryPendingSpawnIntervalTicks.coerceIn(20, 20 * 60 * 30)
        licenses = licenses.map { license -> license.normalized() }
            .filter { license -> license.id.isNotBlank() && license.npcId.isNotBlank() }
            .distinctBy { license -> license.id }
            .toMutableList()
    }

    companion object {
        fun defaults(): TechLicensesConfigData = TechLicensesConfigData(
            licenses = mutableListOf(
                TechLicenseDefinition(
                    id = "create",
                    displayName = "Create License",
                    buttonLabel = "CREATE",
                    npcId = "bulma",
                    thresholdChowcoins = 100000,
                    iconItem = "create:goggles",
                    feeChowcoins = 10000,
                    gateNamespaces = mutableListOf("create", "createaddition", "createdeco", "create_dragons_plus", "create_enchantment_industry"),
                    allowedWithoutLicense = mutableListOf("create:goggles"),
                    quest = TechLicenseQuestDefinition(
                        title = "Capsule Workshop Certification",
                        introMessage = "Bulma can certify you for Create machinery once her workshop is settled.",
                        unlockTitle = "Create Engineer",
                        announcement = "{player} earned the Create License from Bulma.",
                        steps = mutableListOf(
                            TechLicenseQuestStepDefinition(kind = "dialogue", title = "Safety Brief", objective = "Hear Bulma's machinery rules.", startMessage = "No loose sleeves near shafts, no surprise boilers, no pretending redstone is a personality. Say you understand, {player}."),
                            TechLicenseQuestStepDefinition(kind = "fetch", title = "Workshop Stock", objective = "Bring {qty} copper ingots.", item = "minecraft:copper_ingot", qty = 24, startMessage = "Bring me {qty} {item}. If this town wants machines, we start with clean conductors.", completeMessage = "Good copper. Not Capsule Corp clean, but we can work with it."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Basic Mechanics", objective = "Craft {goal} pistons.", event = "minecraft:item_crafted", item = "minecraft:piston", goal = 6, startMessage = "Craft {goal} pistons after taking this step. I want proof you understand motion before I let you near rotational force.", completeMessage = "Those pistons move straight and complain less than most engineers."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Site Survey", objective = "Travel {goal} blocks on foot.", event = "minecraft:travel_on_foot", goal = 800, filters = mutableMapOf("mode" to "on_foot"), startMessage = "Walk the town perimeter and scout a safe place for workshops. Progress: {progress}/{goal}.", completeMessage = "Good. Machines need space, not vibes."),
                            TechLicenseQuestStepDefinition(kind = "payment", title = "License Fee", objective = "Pay {cost} Chowcoins.", startMessage = "Certification paperwork costs {cost} Chowcoins. Cheap for not losing an arm.", completeMessage = "Paid. Now you are officially less dangerous."),
                            TechLicenseQuestStepDefinition(kind = "grant", title = "Create License", objective = "Receive the Create License.", startMessage = "Done. Create tech is unlocked for you."),
                        ),
                    ),
                    dailyMissions = mutableListOf(
                        techMission("create_pistons", "minecraft:item_crafted", "Craft {goal} Pistons", "Craft pistons for Bulma's machine bench.", "minecraft:piston", 4, 140, 100),
                        techMission("create_smelt_copper", "minecraft:item_smelted", "Smelt {goal} Copper Ingots", "Smelt copper for clean workshop wiring.", "minecraft:copper_ingot", 16, 140, 100),
                        techMission("create_site_survey", "minecraft:travel_on_foot", "Travel {goal} Blocks On Foot", "Survey safe factory routes on foot.", "", 1200, 160, 120, mutableMapOf("mode" to "on_foot")),
                    ),
                ),
                TechLicenseDefinition(
                    id = "ars",
                    displayName = "Ars Nouveau License",
                    buttonLabel = "ARS",
                    npcId = "howl",
                    thresholdChowcoins = 150000,
                    iconItem = "ars_nouveau:worn_notebook",
                    feeChowcoins = 10000,
                    gateNamespaces = mutableListOf("ars_nouveau"),
                    quest = TechLicenseQuestDefinition(
                        title = "Moving Castle Source Permit",
                        introMessage = "Howl can certify you for Ars Nouveau once his study is settled.",
                        unlockTitle = "Source Apprentice",
                        announcement = "{player} earned the Ars Nouveau License from Howl.",
                        steps = mutableListOf(
                            TechLicenseQuestStepDefinition(kind = "dialogue", title = "Source Etiquette", objective = "Hear Howl's rules for magic in town.", startMessage = "Magic is not the problem, {player}. Unattractive magic is. Promise me you will keep source work graceful and contained."),
                            TechLicenseQuestStepDefinition(kind = "fetch", title = "Quiet Materials", objective = "Bring {qty} lapis lazuli.", item = "minecraft:lapis_lazuli", qty = 24, startMessage = "Bring {qty} {item}. Blue pigment, blue focus, blue consequences if you waste it.", completeMessage = "A useful color. Almost elegant."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Ender Control", objective = "Defeat {goal} Endermen.", event = "minecraft:entity_killed", goal = 3, filters = mutableMapOf("entity" to "minecraft:enderman"), startMessage = "Defeat {goal} Endermen. Spatial magic requires manners around things that teleport.", completeMessage = "Good. You looked terror in the eye and did not blink too much."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Notebook Prep", objective = "Craft {goal} books.", event = "minecraft:item_crafted", item = "minecraft:book", goal = 6, startMessage = "Craft {goal} books. A wizard without notes is just a disaster with sleeves.", completeMessage = "Readable. Barely. I will accept it."),
                            TechLicenseQuestStepDefinition(kind = "payment", title = "License Fee", objective = "Pay {cost} Chowcoins.", startMessage = "The Ars license fee is {cost} Chowcoins. It covers paperwork, ink, and my patience.", completeMessage = "Paid. Try not to become a cautionary ballad."),
                            TechLicenseQuestStepDefinition(kind = "grant", title = "Ars Nouveau License", objective = "Receive the Ars Nouveau License.", startMessage = "Done. Ars Nouveau is unlocked for you."),
                        ),
                    ),
                    dailyMissions = mutableListOf(
                        techMission("ars_lapis", "minecraft:item_crafted", "Craft {goal} Books", "Prepare clean notes for Howl's study.", "minecraft:book", 4, 130, 90),
                        techMission("ars_endermen", "minecraft:entity_killed", "Defeat {goal} Endermen", "Prove your spatial discipline for Howl.", "", 2, 180, 130, mutableMapOf("entity" to "minecraft:enderman")),
                        techFetchMission("ars_amethyst", "Bring {goal} Amethyst Shards", "Bring amethyst shards for source calibration.", "minecraft:amethyst_shard", 8, 140, 100),
                    ),
                ),
                TechLicenseDefinition(
                    id = "oritech",
                    displayName = "Oritech License",
                    buttonLabel = "ORITECH",
                    npcId = "rick_sanchez",
                    thresholdChowcoins = 200000,
                    iconItem = "oritech:wrench",
                    feeChowcoins = 10000,
                    gateNamespaces = mutableListOf("oritech"),
                    quest = TechLicenseQuestDefinition(
                        title = "Containment Engineering Permit",
                        introMessage = "Rick can certify you for Oritech once his lab is settled.",
                        unlockTitle = "Oritech Handler",
                        announcement = "{player} earned the Oritech License from Rick Sanchez.",
                        steps = mutableListOf(
                            TechLicenseQuestStepDefinition(kind = "dialogue", title = "Containment Talk", objective = "Hear Rick's lab rules.", startMessage = "Listen, {player}. Oritech is power with paperwork. Touch the wrong thing and the town becomes a crater with opinions."),
                            TechLicenseQuestStepDefinition(kind = "fetch", title = "Lab Stock", objective = "Bring {qty} redstone dust.", item = "minecraft:redstone", qty = 32, startMessage = "Bring {qty} {item}. If you cannot source redstone, you cannot source responsibility.", completeMessage = "Fine. Usable. Do not look proud."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Circuit Proof", objective = "Craft {goal} comparators.", event = "minecraft:item_crafted", item = "minecraft:comparator", goal = 4, startMessage = "Craft {goal} comparators. Show me you can count signals before you ask for machines that bite.", completeMessage = "Comparators. Incredible. Civilization limps forward."),
                            TechLicenseQuestStepDefinition(kind = "task", title = "Material Processing", objective = "Smelt {goal} iron ingots.", event = "minecraft:item_smelted", item = "minecraft:iron_ingot", goal = 24, startMessage = "Smelt {goal} iron ingots. Industrial work starts with not wasting ore.", completeMessage = "Acceptable metallurgy. Barely embarrassing."),
                            TechLicenseQuestStepDefinition(kind = "payment", title = "License Fee", objective = "Pay {cost} Chowcoins.", startMessage = "Oritech license fee: {cost} Chowcoins. Cheap, considering I am not billing therapy hours.", completeMessage = "Paid. If you explode, explode somewhere educational."),
                            TechLicenseQuestStepDefinition(kind = "grant", title = "Oritech License", objective = "Receive the Oritech License.", startMessage = "Done. Oritech is unlocked for you."),
                        ),
                    ),
                    dailyMissions = mutableListOf(
                        techMission("oritech_comparators", "minecraft:item_crafted", "Craft {goal} Comparators", "Build comparators for Rick's signal tests.", "minecraft:comparator", 3, 170, 120),
                        techMission("oritech_smelt_iron", "minecraft:item_smelted", "Smelt {goal} Iron Ingots", "Process iron for Rick's lab stock.", "minecraft:iron_ingot", 24, 160, 120),
                        techMission("oritech_creepers", "minecraft:entity_killed", "Defeat {goal} Creepers", "Collect field data from volatile mobs.", "", 4, 190, 140, mutableMapOf("entity" to "minecraft:creeper")),
                    ),
                ),
            ),
        )

        private fun techMission(
            id: String,
            event: String,
            eventDesc: String,
            questText: String,
            item: String,
            goal: Int,
            xp: Int,
            chowcoins: Long,
            filters: MutableMap<String, String> = mutableMapOf(),
        ): NpcMissionDefinition = NpcMissionDefinition(
            id = id,
            category = "task",
            event = event,
            eventDesc = eventDesc,
            questText = questText,
            passId = "cozy",
            xp = xp,
            chowcoins = chowcoins,
            goal = goal,
            filters = (filters + if (item.isNotBlank()) mapOf("item" to item, "item.namespace" to item.substringBefore(':', "")) else emptyMap()).toMutableMap(),
            weight = 10,
        )

        private fun techFetchMission(id: String, eventDesc: String, questText: String, item: String, qty: Int, xp: Int, chowcoins: Long): NpcMissionDefinition =
            NpcMissionDefinition(
                id = id,
                category = "fetch",
                eventDesc = eventDesc,
                questText = questText,
                passId = "cozy",
                xp = xp,
                chowcoins = chowcoins,
                goal = qty,
                fetchItem = item,
                fetchCount = qty,
                weight = 10,
            )
    }
}

class TechLicenseDefinition(
    var id: String = "",
    @SerializedName(value = "display_name", alternate = ["displayName"])
    var displayName: String = "",
    @SerializedName(value = "button_label", alternate = ["buttonLabel"])
    var buttonLabel: String = "",
    @SerializedName(value = "npc_id", alternate = ["npcId"])
    var npcId: String = "",
    @SerializedName(value = "threshold_chowcoins", alternate = ["thresholdChowcoins"])
    var thresholdChowcoins: Long = 0L,
    @SerializedName(value = "icon_item", alternate = ["iconItem"])
    var iconItem: String = "minecraft:paper",
    @SerializedName(value = "fee_chowcoins", alternate = ["feeChowcoins"])
    var feeChowcoins: Long = 10000L,
    @SerializedName(value = "gate_namespaces", alternate = ["gateNamespaces"])
    var gateNamespaces: MutableList<String> = mutableListOf(),
    @SerializedName(value = "allowed_without_license", alternate = ["allowedWithoutLicense"])
    var allowedWithoutLicense: MutableList<String> = mutableListOf(),
    @SerializedName(value = "allowed_blocks_without_license", alternate = ["allowedBlocksWithoutLicense"])
    var allowedBlocksWithoutLicense: MutableList<String> = mutableListOf(),
    @SerializedName(value = "always_banned_items", alternate = ["alwaysBannedItems"])
    var alwaysBannedItems: MutableList<String> = mutableListOf(),
    @SerializedName(value = "always_banned_blocks", alternate = ["alwaysBannedBlocks"])
    var alwaysBannedBlocks: MutableList<String> = mutableListOf(),
    var quest: TechLicenseQuestDefinition = TechLicenseQuestDefinition(),
    @SerializedName(value = "daily_missions", alternate = ["dailyMissions"])
    var dailyMissions: MutableList<NpcMissionDefinition> = mutableListOf(),
) {
    fun normalized(): TechLicenseDefinition = apply {
        id = TechLicenseConfig.normalizeId(id)
        displayName = displayName.trim().ifBlank { id.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) } + " License" }
        buttonLabel = buttonLabel.trim().ifBlank { id.uppercase(Locale.ROOT) }.take(16)
        npcId = npcId.trim().lowercase(Locale.ROOT)
        thresholdChowcoins = thresholdChowcoins.coerceAtLeast(0L)
        iconItem = iconItem.trim().lowercase(Locale.ROOT).ifBlank { "minecraft:paper" }
        feeChowcoins = feeChowcoins.coerceAtLeast(0L)
        gateNamespaces = gateNamespaces.map { value -> value.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().toMutableList()
        allowedWithoutLicense = cleanPatterns(allowedWithoutLicense)
        allowedBlocksWithoutLicense = cleanPatterns(allowedBlocksWithoutLicense)
        alwaysBannedItems = cleanPatterns(alwaysBannedItems)
        alwaysBannedBlocks = cleanPatterns(alwaysBannedBlocks)
        quest = quest.normalized()
        dailyMissions = dailyMissions.map { mission -> mission.normalized() }.filter { mission -> mission.id.isNotBlank() }.toMutableList()
    }

    private fun cleanPatterns(values: List<String>): MutableList<String> =
        values.map { value -> value.trim().lowercase(Locale.ROOT) }.filter(String::isNotBlank).distinct().toMutableList()
}

class TechLicenseQuestDefinition(
    var title: String = "",
    @SerializedName(value = "intro_message", alternate = ["introMessage"])
    var introMessage: String = "",
    @SerializedName(value = "unlock_title", alternate = ["unlockTitle"])
    var unlockTitle: String = "",
    var announcement: String = "",
    var steps: MutableList<TechLicenseQuestStepDefinition> = mutableListOf(),
) {
    fun normalized(): TechLicenseQuestDefinition = apply {
        title = title.trim()
        introMessage = introMessage.trim()
        unlockTitle = unlockTitle.trim()
        announcement = announcement.trim()
        steps = steps.mapIndexed { index, step -> step.normalized(index) }.toMutableList()
    }
}

class TechLicenseQuestStepDefinition(
    var id: String = "",
    var kind: String = "",
    var title: String = "",
    var objective: String = "",
    var event: String = "",
    var item: String = "",
    var qty: Int = 1,
    var goal: Int = 1,
    @SerializedName(value = "time_window_seconds", alternate = ["timeWindowSeconds", "window_seconds", "seconds"])
    var timeWindowSeconds: Int = 0,
    var filters: MutableMap<String, String> = mutableMapOf(),
    @SerializedName(value = "start_message", alternate = ["startMessage"])
    var startMessage: String = "",
    @SerializedName(value = "complete_message", alternate = ["completeMessage"])
    var completeMessage: String = "",
) {
    fun normalized(index: Int): TechLicenseQuestStepDefinition = apply {
        id = TechLicenseConfig.normalizeId(id).ifBlank { "step_${index + 1}" }
        kind = normalizeKind(kind)
        title = title.trim()
        objective = objective.trim()
        event = event.trim()
        item = item.trim().lowercase(Locale.ROOT)
        qty = qty.coerceIn(1, 1000000)
        goal = goal.coerceIn(1, 1000000)
        timeWindowSeconds = if (kind == "timed") (timeWindowSeconds.takeIf { it > 0 } ?: 20).coerceIn(1, 3600) else timeWindowSeconds.coerceIn(0, 3600)
        filters = filters.mapKeys { (key, _) -> key.trim() }.mapValues { (_, value) -> value.trim() }.filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }.toMutableMap()
        startMessage = startMessage.trim()
        completeMessage = completeMessage.trim()
    }

    fun goalValue(): Int = if (kind == "fetch") qty else goal

    companion object {
        fun normalizeKind(value: String): String = when (value.trim().lowercase(Locale.ROOT).replace('-', '_')) {
            "vow", "dialog", "dialogue_intro", "intro" -> "dialogue"
            "offering", "fetch_item" -> "fetch"
            "discipline", "trial", "field_trial", "signature_trial", "event" -> "task"
            "timed", "timed_task", "timed_kill", "time_trial" -> "timed"
            "license", "pay", "fee" -> "payment"
            "unlock", "grant" -> "grant"
            else -> value.trim().lowercase(Locale.ROOT).replace('-', '_').ifBlank { "dialogue" }
        }
    }
}
