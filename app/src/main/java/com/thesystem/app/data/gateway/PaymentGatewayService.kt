package com.thesystem.app.data.gateway

/**
 * PAYMENT GATEWAY SEAM (v0.24.0, spec §4).
 *
 * The legacy manual peer-to-peer rail (QR → reference → screenshot → human review) has
 * been ripped out — client and server both. This interface is the ONLY
 * payment surface the client knows about, and it is deliberately inert until
 * the official **Razorpay Payment Gateway** SDK wiring lands in a future
 * release.
 *
 * Laws that survive every future implementation:
 *  · No client-side price computation — the server owns amounts and
 *    fulfillment, exactly-once, idempotently.
 *  · No fake transactions, no simulated checkouts, no test money moving.
 *  · If the gateway is not configured, the honest answer is [GatewayResult.Unavailable].
 */
interface PaymentGatewayService {

    /** Provider id, e.g. "razorpay". Purely informational. */
    val providerId: String

    /** True only when real provider credentials are present and checkout can run. */
    fun isAvailable(): Boolean

    /**
     * Begin a checkout for a server-minted order reference. The implementor
     * (Razorpay wiring) will open the provider sheet, and settlement will be
     * confirmed exclusively by server-side webhook verification.
     */
    suspend fun beginCheckout(request: CheckoutRequest): GatewayResult

    data class CheckoutRequest(
        /** Server-minted order reference — fulfillment hangs off this id. */
        val orderReference: String,
        val amountPaise: Long,
        val currency: String = "INR",
        val description: String,
    )

    sealed interface GatewayResult {
        /** Gateway not wired yet. The UI must say so honestly and stop. */
        data object Unavailable : GatewayResult

        /** User cancelled the provider sheet — no state changed anywhere. */
        data object Cancelled : GatewayResult

        /** Provider confirmed the payment sheet; server webhook still owns credit. */
        data class Submitted(val providerPaymentId: String) : GatewayResult

        data class Failure(val message: String) : GatewayResult
    }
}

/**
 * The placeholder that ships today: truthful, inert, and impossible to
 * mistake for a live rail. Replace with a Razorpay-backed implementation
 * behind the same interface when the SDK lands — nothing else in the app
 * should change.
 */
class NoopPaymentGateway : PaymentGatewayService {
    override val providerId: String = "razorpay(pending)"
    override fun isAvailable(): Boolean = false
    override suspend fun beginCheckout(request: PaymentGatewayService.CheckoutRequest): PaymentGatewayService.GatewayResult =
        PaymentGatewayService.GatewayResult.Unavailable
}

/** Access point — swap [current] when the Razorpay-backed service ships. */
object PaymentGateways {
    val current: PaymentGatewayService = NoopPaymentGateway()

    /** One cold sentence, reused by every surface that used to offer manual pay. */
    const val OFFLINE_NOTICE: String =
        "[ NOTICE: SECURE PAYMENT GATEWAY OFFLINE — official Razorpay integration scheduled. " +
            "Manual transfers are not accepted. Your balances and history are untouched. ]"
}
