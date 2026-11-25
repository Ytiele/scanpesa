package com.scanpesa.app.parser

import com.scanpesa.app.model.PaymentType

/** Validation and cleanup for numbers the user confirms or types by hand. */
object PaymentValidator {

    /** Normalises "+254 712 345 678", "254712345678", "0712-345-678" to "0712345678"; null if invalid. */
    fun normalizePhone(input: String): String? {
        val d = input.filter { it.isDigit() }
        val local = when {
            d.length == 12 && d.startsWith("254") -> "0" + d.substring(3)
            d.length == 10 && d.startsWith("0") -> d
            d.length == 9 && (d.startsWith("7") || d.startsWith("1")) -> "0$d"
            else -> return null
        }
        return if (local[1] == '7' || local[1] == '1') local else null
    }

    fun isBusinessNumber(input: String): Boolean = input.length in 5..7 && input.all { it.isDigit() }

    fun cleanAccount(input: String): String = input.trim().replace(Regex("\\s+"), "").uppercase()

    fun isValidAccount(input: String): Boolean {
        val a = cleanAccount(input)
        return a.isNotEmpty() && a.length <= 24 && a.all { it.isLetterOrDigit() || it == '-' }
    }

    /** Returns an error message for the user, or null if the values are fine. */
    fun validate(type: PaymentType, number: String, account: String?): String? = when (type) {
        PaymentType.SEND_MONEY, PaymentType.POCHI ->
            if (normalizePhone(number) == null) "Enter a phone number like 0712 345 678" else null
        PaymentType.PAYBILL -> when {
            !isBusinessNumber(number.filter { !it.isWhitespace() }) -> "Business number must be 5–7 digits"
            account.isNullOrBlank() -> "Enter the account number"
            !isValidAccount(account) -> "Account can only contain letters, digits and '-'"
            else -> null
        }
        PaymentType.TILL ->
            if (!isBusinessNumber(number.filter { !it.isWhitespace() })) "Till number must be 5–7 digits" else null
    }

    /** The number exactly as it will be typed into M-PESA. */
    fun cleanNumber(type: PaymentType, number: String): String = when (type) {
        PaymentType.SEND_MONEY, PaymentType.POCHI -> normalizePhone(number) ?: number.filter { it.isDigit() }
        else -> number.filter { it.isDigit() }
    }
}
