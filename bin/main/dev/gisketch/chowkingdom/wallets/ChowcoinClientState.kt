package dev.gisketch.chowkingdom.wallets

object ChowcoinClientState {
    private var balance = 0L
    private var displayedBalance = 0L
    private var animationStartBalance = 0L
    private var animationStartedAt = 0L
    private var deltaAmount = 0L
    private var deltaStartedAt = 0L
    private var initialized = false

    fun apply(payload: ChowcoinSyncPayload) {
        val next = payload.balance.coerceAtLeast(0L)
        if (!initialized) {
            initialized = true
            balance = next
            displayedBalance = next
            animationStartBalance = next
            return
        }
        if (next == balance) return
        val now = System.currentTimeMillis()
        animationStartBalance = displayBalance(now)
        displayedBalance = animationStartBalance
        deltaAmount = next - balance
        deltaStartedAt = now
        balance = next
        animationStartedAt = now
    }

    fun balance(): Long = balance

    fun displayBalance(now: Long = System.currentTimeMillis()): Long {
        if (displayedBalance == balance) return balance
        val age = now - animationStartedAt
        if (age >= BALANCE_ANIMATION_MS) {
            displayedBalance = balance
            return balance
        }
        val step = ((age.coerceAtLeast(0L) * BALANCE_ANIMATION_STEPS) / BALANCE_ANIMATION_MS).coerceIn(0L, BALANCE_ANIMATION_STEPS.toLong())
        displayedBalance = animationStartBalance + ((balance - animationStartBalance) * step) / BALANCE_ANIMATION_STEPS
        return displayedBalance
    }

    fun deltaDisplay(now: Long = System.currentTimeMillis()): DeltaDisplay? {
        if (deltaAmount == 0L) return null
        val age = now - deltaStartedAt
        if (age >= DELTA_DURATION_MS) return null
        val alpha = if (age <= DELTA_SOLID_MS) 1.0f else 1.0f - ((age - DELTA_SOLID_MS) / (DELTA_DURATION_MS - DELTA_SOLID_MS).toFloat())
        return DeltaDisplay(deltaAmount, alpha.coerceIn(0.0f, 1.0f))
    }

    data class DeltaDisplay(val amount: Long, val alpha: Float)

    private const val BALANCE_ANIMATION_MS = 520L
    private const val BALANCE_ANIMATION_STEPS = 20
    private const val DELTA_DURATION_MS = 3_000L
    private const val DELTA_SOLID_MS = 2_350L
}