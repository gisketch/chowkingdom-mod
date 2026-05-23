package dev.gisketch.chowkingdom.trading

import java.util.UUID

object TradingClientState {
    private val states: MutableMap<UUID, TradeStatePayload> = linkedMapOf()

    fun apply(payload: TradeStatePayload) {
        states[payload.sessionId] = payload
    }

    fun get(sessionId: UUID): TradeStatePayload? = states[sessionId]

    fun clear(sessionId: UUID) {
        states.remove(sessionId)
    }
}
