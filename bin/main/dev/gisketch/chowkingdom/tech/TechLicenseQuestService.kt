package dev.gisketch.chowkingdom.tech

import dev.gisketch.chowkingdom.battlepass.BattlepassMissionEventBank
import dev.gisketch.chowkingdom.battlepass.BattlepassMissionSignal
import dev.gisketch.chowkingdom.battlepass.BattlepassNetwork
import dev.gisketch.chowkingdom.battlepass.BattlepassXpEventDefinition
import dev.gisketch.chowkingdom.npc.ChowNpcEntity
import dev.gisketch.chowkingdom.npc.NpcConfig
import dev.gisketch.chowkingdom.npc.NpcDefinition
import dev.gisketch.chowkingdom.npc.NpcDialogTokens
import dev.gisketch.chowkingdom.npc.NpcFeature
import dev.gisketch.chowkingdom.npc.NpcLlmService
import dev.gisketch.chowkingdom.npc.NpcNetwork
import dev.gisketch.chowkingdom.npc.NpcQuestHudEntryPayload
import dev.gisketch.chowkingdom.npc.NpcQuestService
import dev.gisketch.chowkingdom.npc.NpcStore
import dev.gisketch.chowkingdom.snackbar.SnackbarIcons
import dev.gisketch.chowkingdom.snackbar.SnackbarNetwork
import dev.gisketch.chowkingdom.snackbar.SnackbarNotification
import dev.gisketch.chowkingdom.snackbar.SnackbarSounds
import dev.gisketch.chowkingdom.snackbar.SnackbarType
import dev.gisketch.chowkingdom.wallets.ChowcoinNetwork
import dev.gisketch.chowkingdom.wallets.ChowcoinStore
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.Locale

object TechLicenseQuestService {
    fun handle(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, licenseId: String): Boolean {
        val license = TechLicenseConfig.get(licenseId) ?: return false
        if (!license.npcId.equals(definition.id, ignoreCase = true)) return false
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        if (TechLicenseStore.has(player, license.id)) {
            openLicenseDialog(player, npc, definition, license, null, "You already have <b>${license.displayName}</b>. Keep the workshop clean.", "already_unlocked", null, null, "The player already owns this tech license. Reply in character and keep it short.")
            return true
        }
        if (!TechLicenseFeature.thresholdReached(license)) {
            openLicenseDialog(player, npc, definition, license, null, "The server has not shipped enough yet for <b>${license.displayName}</b>. Current total: ${TechLicenseFeature.currentShippingTotal()}/${license.thresholdChowcoins}.", "threshold_locked", null, null, "The player asked too early. Tell them this license is still locked behind server shipping progress.")
            return true
        }

        val steps = license.quest.steps
        if (steps.isEmpty()) {
            openLicenseDialog(player, npc, definition, license, null, "This license has no questline configured yet.", "missing_config", null, null, "The license quest config is missing. Tell the player this cannot be trained yet.")
            return true
        }
        val existing = TechLicenseStore.quest(player, license.id)
        if (existing?.npcId?.isNotBlank() == true && !existing.npcId.equals(definition.id, ignoreCase = true)) {
            val mentorName = NpcConfig.get(existing.npcId)?.name ?: existing.npcId
            openLicenseDialog(player, npc, definition, license, existing, "$mentorName is already handling your <b>${license.displayName}</b>. Finish that path first.", "locked_mentor", null, null, "Another NPC is already handling this license quest. Tell the player who to return to.")
            return true
        }
        val state = existing ?: PlayerTechLicenseQuestState(
            licenseId = license.id,
            npcId = definition.id,
            startedAtTick = player.level().dayTime,
            stepStartedAtTick = player.level().dayTime,
        ).also {
            TechLicenseStore.putQuest(player, it)
            NpcQuestService.syncTo(player)
        }
        if (state.npcId.isBlank()) {
            state.npcId = definition.id
            TechLicenseStore.saveQuest(player, state)
            NpcQuestService.syncTo(player)
        }

        val step = steps.getOrNull(state.stepIndex) ?: return grant(player, npc, definition, license, state, friendship.level)
        return when (step.kind) {
            "dialogue" -> {
                advance(player, state)
                openLicenseDialog(
                    player,
                    npc,
                    definition,
                    license,
                    state,
                    completionTemplate(step.startMessage.ifBlank { step.objective.ifBlank { license.quest.introMessage } }, steps.getOrNull(state.stepIndex)),
                    "step_dialogue",
                    step,
                    steps.getOrNull(state.stepIndex),
                    stepPrompt(player, definition, license, state, step, steps.getOrNull(state.stepIndex), "Start the tech license safety or introduction step, then point to the next certification task."),
                )
                true
            }
            "fetch" -> handleFetch(player, npc, definition, license, state, step, friendship.level)
            "task", "timed" -> handleTask(player, npc, definition, license, state, step, friendship.level)
            "payment" -> handlePayment(player, npc, definition, license, state, step, friendship.level)
            "grant" -> grant(player, npc, definition, license, state, friendship.level)
            else -> {
                openLicenseDialog(player, npc, definition, license, state, step.startMessage.ifBlank { step.objective.ifBlank { "Continue <b>{license}</b> work." } }, "step_unknown", step, null, stepPrompt(player, definition, license, state, step, null, "Explain this tech license step and what the player should do next."))
                true
            }
        }
    }

    fun recordSignal(player: ServerPlayer, signal: BattlepassMissionSignal): Boolean {
        var changed = false
        TechLicenseConfig.all().forEach { license ->
            val state = TechLicenseStore.quest(player, license.id) ?: return@forEach
            val step = license.quest.steps.getOrNull(state.stepIndex) ?: return@forEach
            if (step.kind !in setOf("task", "timed") || state.progress >= step.goalValue()) return@forEach
            if (!matches(signal, step)) return@forEach
            if (step.kind == "timed") {
                val progress = recordTimedEvent(player, state, step, signal.amount)
                changed = true
                if (progress >= step.goalValue()) notifyReady(player, license, state, step)
                return@forEach
            }
            state.progress = (state.progress + signal.amount.coerceAtLeast(1)).coerceAtMost(step.goalValue())
            changed = true
            if (state.progress >= step.goalValue()) notifyReady(player, license, state, step)
        }
        if (changed) {
            TechLicenseStore.saveQuests()
            NpcQuestService.syncTo(player)
        }
        return changed
    }

    fun hudEntriesFor(player: ServerPlayer): List<NpcQuestHudEntryPayload> =
        TechLicenseConfig.all()
            .mapNotNull { license ->
                val state = TechLicenseStore.quest(player, license.id) ?: return@mapNotNull null
                if (TechLicenseStore.has(player, license.id)) return@mapNotNull null
                val step = license.quest.steps.getOrNull(state.stepIndex)
                val definition = NpcConfig.get(state.npcId.ifBlank { license.npcId })
                val npcId = definition?.id ?: state.npcId.ifBlank { license.npcId }
                val npcName = definition?.name ?: license.displayName
                val goal = step?.goalValue() ?: 1
                NpcQuestHudEntryPayload(
                    npcId = npcId,
                    npcName = npcName,
                    description = hudDescription(license, step, npcName, hudProgress(player, state, step), goal),
                    passId = "cozy",
                    xp = 0,
                    chowcoins = 0L,
                    progress = hudProgress(player, state, step).coerceAtMost(goal),
                    goal = goal,
                    acceptedAtTick = state.startedAtTick,
                )
            }
            .sortedBy { entry -> entry.acceptedAtTick }

    private fun handleFetch(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition, friendshipLevel: Int): Boolean {
        if (countRequiredItems(player, step) < step.goalValue()) {
            openLicenseDialog(player, npc, definition, license, state, step.startMessage.ifBlank { "Bring <b>{qty} {item}</b> for {license}." }, "step_progress", step, null, stepPrompt(player, definition, license, state, step, null, "The player has not brought the required items yet. Tell them the exact item and count."))
            return true
        }
        consumeRequiredItems(player, step)
        advance(player, state)
        val nextStep = license.quest.steps.getOrNull(state.stepIndex)
        openLicenseDialog(player, npc, definition, license, state, completionTemplate(step.completeMessage.ifBlank { "Good. This step is done." }, nextStep), "step_complete", step, nextStep, stepPrompt(player, definition, license, state, step, nextStep, "The player completed this item turn-in. Acknowledge it and clearly introduce the next certification step."))
        stepComplete(player, definition, license)
        return true
    }

    private fun handleTask(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition, friendshipLevel: Int): Boolean {
        val progress = if (step.kind == "timed") refreshTimedProgress(player, state, step) else state.progress
        if (progress < step.goalValue()) {
            openLicenseDialog(player, npc, definition, license, state, step.startMessage.ifBlank { "{objective} Progress: <b>{progress}/{goal}</b>." }, "step_progress", step, null, stepPrompt(player, definition, license, state, step, null, "The player is still working on this tech certification task. Mention progress and the exact objective."))
            return true
        }
        advance(player, state)
        val nextStep = license.quest.steps.getOrNull(state.stepIndex)
        openLicenseDialog(player, npc, definition, license, state, completionTemplate(step.completeMessage.ifBlank { "Good. This step is done." }, nextStep), "step_complete", step, nextStep, stepPrompt(player, definition, license, state, step, nextStep, "The player completed this field task. Acknowledge it and clearly introduce the next certification step."))
        stepComplete(player, definition, license)
        return true
    }

    private fun handlePayment(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition, friendshipLevel: Int): Boolean {
        val cost = license.feeChowcoins.coerceAtLeast(0L)
        val balance = ChowcoinStore.get(player)
        if (balance < cost) {
            ChowcoinNetwork.syncTo(player)
            SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "TECH LICENSE NEEDED", "Need $cost chowcoins.", SnackbarType.ERROR, SnackbarSounds.ERROR))
            openLicenseDialog(player, npc, definition, license, state, step.startMessage.ifBlank { "The {license} fee is <b>{cost} Chowcoins</b>. You do not have enough yet." }, "payment_missing", step, null, stepPrompt(player, definition, license, state, step, null, "The player reached payment but lacks chowcoins. Tell them the exact cost."))
            return true
        }
        ChowcoinStore.set(player, balance - cost)
        ChowcoinNetwork.syncTo(player)
        state.paid = true
        advance(player, state)
        val nextStep = license.quest.steps.getOrNull(state.stepIndex)
        openLicenseDialog(player, npc, definition, license, state, completionTemplate(step.completeMessage.ifBlank { "License fee paid." }, nextStep), "payment_complete", step, nextStep, stepPrompt(player, definition, license, state, step, nextStep, "The player paid the license fee. Point them to the final grant step."))
        SnackbarNetwork.send(player, SnackbarNotification.texture(SnackbarIcons.CHOWCOIN_TEXTURE, "TECH LICENSE PAID", "$cost chowcoins for ${license.displayName}.", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        return true
    }

    private fun grant(player: ServerPlayer, npc: ChowNpcEntity, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, friendshipLevel: Int): Boolean {
        val changed = TechLicenseStore.grant(player, license.id, "npc:${definition.id}")
        TechLicenseStore.completeQuest(player, license.id)
        val message = license.quest.announcement.ifBlank { "{player} earned the {license} from {npc}." }
            .replace("{player}", player.gameProfile.name)
            .replace("{npc}", definition.name)
            .replace("{license}", license.displayName)
            .replace("{title}", license.quest.unlockTitle.ifBlank { license.displayName })
        player.server.playerList.broadcastSystemMessage(Component.literal(message), false)
        SnackbarNetwork.send(player, SnackbarNotification.item(license.iconItem, "TECH LICENSE UNLOCKED", license.displayName, SnackbarType.SUCCESS, SnackbarSounds.REWARD))
        val grantMessage = license.quest.steps.getOrNull(state.stepIndex)?.startMessage
            ?.ifBlank { "Done. {license} is unlocked." }
            ?: "Done. {license} is unlocked."
        openLicenseDialog(player, npc, definition, license, state, grantMessage.ifBlank { "Done. <b>{license}</b> is unlocked." }, "unlock", null, null, buildUnlockPrompt(player, definition, license))
        NpcStore.recordPlayerMemory(player, "tech_license_unlock", "${player.gameProfile.name} earned ${license.displayName} from ${definition.name}.")
        if (changed) {
            BattlepassMissionEventBank.record(player, TechLicenseFeature.TECH_LICENSE_UNLOCKED_EVENT, 1, mapOf("license" to license.id, "npc" to definition.id))
            BattlepassNetwork.syncAllPlayers()
            TechLicenseNetwork.syncTo(player)
        }
        NpcQuestService.syncTo(player)
        return true
    }

    private fun advance(player: ServerPlayer, state: PlayerTechLicenseQuestState) {
        state.stepIndex += 1
        state.progress = 0
        state.stepStartedAtTick = player.level().dayTime
        state.timedEventTicks.clear()
        TechLicenseStore.saveQuest(player, state)
        NpcQuestService.syncTo(player)
    }

    private fun stepComplete(player: ServerPlayer, definition: NpcDefinition, license: TechLicenseDefinition) {
        SnackbarNetwork.send(player, SnackbarNotification.npc(definition.id, "TECH LICENSE STEP COMPLETE", license.displayName, SnackbarType.SUCCESS, SnackbarSounds.REWARD))
    }

    private fun notifyReady(player: ServerPlayer, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition) {
        SnackbarNetwork.send(player, SnackbarNotification.npc(state.npcId, "TECH LICENSE READY", "${license.displayName}: ${step.title.ifBlank { step.objective }}", SnackbarType.SUCCESS, SnackbarSounds.REWARD))
    }

    private fun openLicenseDialog(
        player: ServerPlayer,
        npc: ChowNpcEntity,
        definition: NpcDefinition,
        license: TechLicenseDefinition,
        state: PlayerTechLicenseQuestState?,
        fallbackTemplate: String,
        recordType: String,
        step: TechLicenseQuestStepDefinition?,
        nextStep: TechLicenseQuestStepDefinition?,
        prompt: String,
    ) {
        npc.startTalkingTo(player, 100)
        val friendship = NpcStore.friendshipSnapshot(definition.id, player)
        val fallback = render(fallbackTemplate, player, definition, license, state, step, nextStep).trim().ifBlank { "Done." }
        val llmEnabled = NpcConfig.settings().llm.enabled && NpcConfig.settings().llmMessageUsage.classTraining
        val responseToken = if (llmEnabled) NpcDialogTokens.next() else 0L
        NpcNetwork.openDialog(player, NpcFeature.dialogPayload(definition, npc, if (llmEnabled) "..." else fallback, false, friendship.level, closeOnly = true, closeLabel = "OKAY", responseToken = responseToken))
        if (llmEnabled) {
            NpcLlmService.event(
                player,
                npc,
                definition,
                fallback,
                techPrompt(prompt),
                inputLabel = "Tech license quest",
                npcRecordType = "npc_tech_license_$recordType",
                responseToken = responseToken,
                messageFilter = { message -> filterTechLicenseReply(message, fallback) },
            )
        } else {
            NpcStore.recordConversation(definition.id, player, definition.name, fallback, "npc_tech_license_$recordType")
            NpcFeature.relayNpcDialogToDiscord(player, definition, fallback)
        }
    }

    private fun render(template: String, player: ServerPlayer, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState?, step: TechLicenseQuestStepDefinition?, nextStep: TechLicenseQuestStepDefinition?): String = template
        .replace("{player}", player.gameProfile.name)
        .replace("{npc}", definition.name)
        .replace("{license}", license.displayName)
        .replace("{license_id}", license.id)
        .replace("{cost}", license.feeChowcoins.toString())
        .replace("{title}", license.quest.unlockTitle.ifBlank { license.displayName })
        .replace("{step}", step?.title.orEmpty())
        .replace("{objective}", step?.objective.orEmpty())
        .replace("{progress}", (state?.progress ?: 0).toString())
        .replace("{goal}", (step?.goalValue() ?: 1).toString())
        .replace("{qty}", (step?.qty ?: 1).toString())
        .replace("{item}", step?.item?.let(::displayName).orEmpty())
        .replace("{next_step}", nextStep?.title.orEmpty())
        .replace("{next_objective}", nextStep?.objective.orEmpty())

    private fun completionTemplate(message: String, nextStep: TechLicenseQuestStepDefinition?): String =
        "${message.trim().ifBlank { "Step complete." }}\n\n${nextStepLine(nextStep)}"

    private fun nextStepLine(nextStep: TechLicenseQuestStepDefinition?): String =
        nextStep?.let { "Next: ${stepSummary(it)}" } ?: "Next: final certification."

    private fun stepSummary(step: TechLicenseQuestStepDefinition): String = when (step.kind) {
        "fetch" -> "<b>${step.title.ifBlank { "Item Turn-In" }}</b>: bring <b>${step.goalValue()} ${displayName(step.item)}</b>."
        "task", "timed" -> "<b>${step.title.ifBlank { "Field Task" }}</b>: ${step.objective.ifBlank { "make progress" }}"
        "payment" -> "<b>${step.title.ifBlank { "License Fee" }}</b>: pay <b>{cost} Chowcoins</b>."
        "grant" -> "<b>${step.title.ifBlank { "License Grant" }}</b>: receive the license."
        else -> "<b>${step.title.ifBlank { "Training" }}</b>: ${step.objective.ifBlank { "continue" }}"
    }

    private fun techPrompt(prompt: String): String =
        """
            $prompt

            Hard tech-license truth rules:
            - Tech licenses are earned only by finishing the configured certification steps and then talking to the tech expert NPC.
            - Never tell the player to right-click a camp block, camping block, workplace block, bed, house, rent contract, or job application for this tech license.
            - Never invent a license application item or claim form.
            - If this is the unlock/grant step, the license is already granted now. There are no extra claim steps after this dialogue.
            - If a mission step is complete, the next real action is only to talk to this NPC, unless the prompt lists a concrete next quest step.
            - Ignore NPC state fields named camp, home bed, workplace, and schedule unless this prompt explicitly asks about NPC housing/work.
        """.trimIndent()

    private fun filterTechLicenseReply(message: String, fallback: String): String {
        val lower = message.lowercase(Locale.ROOT)
        val forbidden = listOf(
            "camp block",
            "camping block",
            "license application",
            "job application",
            "rent contract",
            "right click the camp",
            "right-click the camp",
            "right click a camp",
            "right-click a camp",
            "right click the workplace",
            "right-click the workplace",
            "use it on a bed",
            "click the bed",
            "claim form",
            "application form",
        )
        return if (forbidden.any { phrase -> phrase in lower }) fallback else message
    }

    private fun hudDescription(license: TechLicenseDefinition, step: TechLicenseQuestStepDefinition?, npcName: String, progress: Int, goal: Int): String {
        if (step == null) return "Talk to $npcName to receive ${license.displayName}"
        if (progress >= goal && step.kind in setOf("task", "timed")) return "Talk to $npcName about ${license.displayName}"
        val template = step.objective.ifBlank {
            when (step.kind) {
                "dialogue" -> "Talk to $npcName about ${license.displayName}"
                "fetch" -> "Bring {qty} {item} to $npcName"
                "payment" -> "Pay {cost} Chowcoins for ${license.displayName}"
                "grant" -> "Receive ${license.displayName}"
                else -> "Continue ${license.displayName}"
            }
        }
        return renderHudTemplate(template, license, step, progress, goal)
    }

    private fun renderHudTemplate(template: String, license: TechLicenseDefinition, step: TechLicenseQuestStepDefinition, progress: Int, goal: Int): String =
        template
            .replace("{license}", license.displayName)
            .replace("{cost}", license.feeChowcoins.toString())
            .replace("{title}", license.quest.unlockTitle.ifBlank { license.displayName })
            .replace("{step}", step.title)
            .replace("{objective}", step.objective)
            .replace("{progress}", progress.toString())
            .replace("{goal}", goal.toString())
            .replace("{qty}", step.qty.toString())
            .replace("{item}", step.item.takeIf(String::isNotBlank)?.let(::displayName).orEmpty())
            .replace(Regex("</?b>"), "")

    private fun hudProgress(player: ServerPlayer, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition?): Int = when (step?.kind) {
        "fetch" -> countRequiredItems(player, step)
        "timed" -> refreshTimedProgress(player, state, step)
        "dialogue", "payment", "grant" -> 0
        else -> state.progress
    }

    private fun stepPrompt(player: ServerPlayer, definition: NpcDefinition, license: TechLicenseDefinition, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition, nextStep: TechLicenseQuestStepDefinition?, instruction: String): String {
        val steps = license.quest.steps
        val currentIndex = steps.indexOfFirst { it.id == step.id }.takeIf { it >= 0 } ?: state.stepIndex
        val outline = steps.mapIndexed { index, entry ->
            val window = if (entry.kind == "timed") ", ${entry.timeWindowSeconds.coerceAtLeast(1)}s window" else ""
            "${index + 1}. ${entry.title.ifBlank { entry.objective }} (${entry.kind}, goal ${entry.goalValue()}$window)"
        }.joinToString("\n")
        return """
            $instruction

            Tech license questline context:
            Player: ${player.gameProfile.name}
            NPC expert: ${definition.name}
            License: ${license.displayName}
            Unlock cost: ${license.feeChowcoins} chowcoins
            Server shipping total: ${TechLicenseFeature.currentShippingTotal()}/${license.thresholdChowcoins}
            Current step: ${currentIndex + 1}/${steps.size}
            Current progress: ${state.progress}/${step.goalValue()}
            Current title: ${step.title}
            Current objective: ${step.objective}
            Next step: ${nextStep?.title.orEmpty()} ${nextStep?.objective.orEmpty()}

            Full certification path:
            $outline

            Reply as ${definition.name}. Make this feel like technical certification, training, and trust-building, not a generic quest. Mention the exact item, action, count, or cost when useful. Use <b>...</b> for the key license, item, cost, or next action. Keep it concise.
        """.trimIndent()
    }

    private fun buildUnlockPrompt(player: ServerPlayer, definition: NpcDefinition, license: TechLicenseDefinition): String =
        """
            ${player.gameProfile.name} completed every certification step with ${definition.name}.
            License unlocked: ${license.displayName}
            Title: ${license.quest.unlockTitle.ifBlank { license.displayName }}
            Reply as ${definition.name} with a short in-character certification ceremony line. Name the license and make the moment feel earned. Use <b>...</b> on the license name. The license is already granted now; do not mention any application, claim, camp block, job application, bed, house, or extra step.
        """.trimIndent()

    private fun countRequiredItems(player: ServerPlayer, step: TechLicenseQuestStepDefinition): Int =
        player.inventory.items.sumOf { stack -> if (stackMatchesRequired(stack, step)) stack.count else 0 } +
            player.inventory.offhand.sumOf { stack -> if (stackMatchesRequired(stack, step)) stack.count else 0 }

    private fun consumeRequiredItems(player: ServerPlayer, step: TechLicenseQuestStepDefinition) {
        var remaining = step.goalValue()
        fun consume(stack: ItemStack) {
            if (remaining <= 0 || !stackMatchesRequired(stack, step)) return
            val taken = minOf(stack.count, remaining)
            stack.shrink(taken)
            remaining -= taken
        }
        player.inventory.items.forEach(::consume)
        player.inventory.offhand.forEach(::consume)
    }

    private fun stackMatchesRequired(stack: ItemStack, step: TechLicenseQuestStepDefinition): Boolean =
        !stack.isEmpty && step.item.isNotBlank() && stack.`is`(item(step.item))

    private fun matches(signal: BattlepassMissionSignal, step: TechLicenseQuestStepDefinition): Boolean {
        val event = BattlepassXpEventDefinition().apply {
            this.event = step.event
            filters.putAll(stepFilters(step))
        }
        return event.event.isNotBlank() && BattlepassMissionEventBank.matches(signal, event)
    }

    private fun stepFilters(step: TechLicenseQuestStepDefinition): Map<String, String> = linkedMapOf<String, String>().apply {
        putAll(step.filters)
        if (step.item.isNotBlank()) {
            putIfAbsent("item", step.item)
            putIfAbsent("item.namespace", step.item.substringBefore(':', ""))
        }
    }

    private fun item(itemId: String) = runCatching { ResourceLocation.parse(itemId) }.getOrNull()
        ?.let { id -> BuiltInRegistries.ITEM.getOptional(id).orElse(Items.AIR) }
        ?: Items.AIR

    private fun recordTimedEvent(player: ServerPlayer, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition, amount: Int): Int {
        val now = player.level().gameTime
        repeat(amount.coerceAtLeast(1).coerceAtMost(1000)) {
            state.timedEventTicks.add(now)
        }
        val progress = refreshTimedProgress(player, state, step)
        if (progress >= step.goalValue()) {
            state.progress = step.goalValue()
            state.timedEventTicks.clear()
        }
        return state.progress
    }

    private fun refreshTimedProgress(player: ServerPlayer, state: PlayerTechLicenseQuestState, step: TechLicenseQuestStepDefinition): Int {
        if (state.progress >= step.goalValue()) return step.goalValue()
        val cutoff = player.level().gameTime - step.timeWindowSeconds.coerceAtLeast(1) * 20L
        state.timedEventTicks.removeAll { tick -> tick < cutoff }
        state.progress = state.timedEventTicks.size.coerceAtMost(step.goalValue())
        return state.progress
    }

    private fun displayName(id: String): String = id.substringAfter(':')
        .replace('_', ' ')
        .replaceFirstChar { character -> character.titlecase(Locale.ROOT) }
}
