package com.scanpesa.app.model

/** The four M-PESA payment kinds ScanPesa understands. */
enum class PaymentType(val label: String) {
    SEND_MONEY("Send Money"),
    POCHI("Pochi la Biashara"),
    PAYBILL("Paybill"),
    TILL("Till (Buy Goods)");

    companion object {
        fun fromName(name: String?): PaymentType? = entries.firstOrNull { it.name == name }
    }
}

/** Where ScanPesa takes the user to finish the payment. */
enum class Target(val label: String, val subtitle: String, val enabled: Boolean) {
    // Disabled targets keep their code and flows.json entries; flip `enabled` to bring them back.
    MPESA_APP("M-PESA app", "Safaricom M-PESA app", enabled = false),
    SIM_TOOLKIT("SIM Toolkit", "Classic M-PESA menu on the SIM", enabled = true),
    ONE_APP("Safaricom One App", "Its M-PESA section", enabled = false);

    companion object {
        /** Targets shown in the app. */
        val available: List<Target> get() = entries.filter { it.enabled }

        /** "MY_SAFARICOM" is the old name of ONE_APP (kept so saved settings/history still load). */
        fun fromName(name: String?): Target? =
            if (name == "MY_SAFARICOM") ONE_APP else entries.firstOrNull { it.name == name }
    }
}

/**
 * One payment found in the scanned text.
 *
 * @param typeConfirmed false when only a bare phone number was found and the user still has to
 *   choose between Send Money and Pochi la Biashara.
 */
data class Candidate(
    val type: PaymentType,
    val number: String,
    val account: String? = null,
    val typeConfirmed: Boolean = true,
) {
    /** Stable key used to check the same result was seen in consecutive camera frames. */
    val key: String get() = "${type.name}|$number|${account.orEmpty()}|$typeConfirmed"
}
