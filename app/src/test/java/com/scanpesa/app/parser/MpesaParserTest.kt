package com.scanpesa.app.parser

import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.PaymentType.PAYBILL
import com.scanpesa.app.model.PaymentType.POCHI
import com.scanpesa.app.model.PaymentType.SEND_MONEY
import com.scanpesa.app.model.PaymentType.TILL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaParserTest {

    private fun only(text: String): Candidate {
        val r = MpesaParser.parse(text)
        assertEquals("Expected exactly one candidate for: $text but got $r", 1, r.size)
        return r.single()
    }

    private fun assertCandidate(c: Candidate, type: PaymentType, number: String, account: String? = null) {
        assertEquals(type, c.type)
        assertEquals(number, c.number)
        assertEquals(account, c.account)
    }

    // ---------------------------------------------------------------- normalisation

    @Test fun normalize_fixesConfusablesInsideDigitRuns() {
        assertEquals("paybill 247240", MpesaParser.normalize("PAYBILL 24724O"))
        assertEquals("0712345678", MpesaParser.normalize("O7l2345678"))
        assertEquals("till 585858", MpesaParser.normalize("Till 5B5B5B"))
    }

    @Test fun normalize_leavesWordsAlone() {
        assertEquals("pay bill business no", MpesaParser.normalize("Pay Bill Business No"))
        assertEquals("lipa na m-pesa", MpesaParser.normalize("LIPA NA M-PESA"))
    }

    @Test fun normalize_joinsSplitDigits() {
        assertEquals("0722123456", MpesaParser.normalize("0722 123 456"))
        assertEquals("0722123456", MpesaParser.normalize("0722-123-456"))
        assertEquals("0722123456", MpesaParser.normalize("+254 722 123 456"))
        assertEquals("0110123456", MpesaParser.normalize("254-110-123-456"))
        assertEquals("till 532411", MpesaParser.normalize("till 5 3 2 4 1 1"))
        assertEquals("paybill 522522", MpesaParser.normalize("paybill 522 522"))
    }

    @Test fun normalize_doesNotMergeDatesOrSeparateNumbers() {
        assertEquals("12-05-2024", MpesaParser.normalize("12-05-2024"))
        assertEquals("till 123456 0712345678", MpesaParser.normalize("Till 123456 0712 345 678"))
    }

    // ---------------------------------------------------------------- paybill (realistic signs)

    @Test fun sign01_classicPaybillWithAccount() {
        val c = only("LIPA NA M-PESA\nPAYBILL NO: 247247\nACCOUNT NO: 0712345678")
        assertCandidate(c, PAYBILL, "247247", "0712345678")
    }

    @Test fun sign02_businessNumberAndAlphanumericAccount() {
        val c = only("Business No. 522522\nAccount: SCH-2041A\nThank you")
        assertCandidate(c, PAYBILL, "522522", "SCH-2041A")
    }

    @Test fun sign03_paybillMissingAccount() {
        val c = only("Pay Bill\n888880\nKenya Power Tokens")
        assertCandidate(c, PAYBILL, "888880", null)
    }

    @Test fun sign04_ocrNoisePaybill() {
        // O for 0, l for 1, S for 5 inside the numbers
        val c = only("PAYBlLL: 4OO2OO\nA/C N0: l2S67")
        assertCandidate(c, PAYBILL, "400200", "12567")
    }

    @Test fun sign05_pbAbbreviation() {
        val c = only("M-PESA PB 303030 Acc: JOHNDOE")
        assertCandidate(c, PAYBILL, "303030", "JOHNDOE")
    }

    @Test fun sign06_bizNoWithSpacedDigits() {
        val c = only("BIZ NO 2 2 2 1 1 1   ACC NO 7788")
        assertCandidate(c, PAYBILL, "222111", "7788")
    }

    @Test fun sign07_schoolFeesReceipt() {
        val c = only("ST. MARY'S SCHOOL\nFees payable via M-PESA\nPaybill Number 7654321\nAcc No: ADM1234")
        assertCandidate(c, PAYBILL, "7654321", "ADM1234")
    }

    @Test fun sign08_accountIsPhoneNumberNotTreatedAsSendMoney() {
        val r = MpesaParser.parse("Paybill 400222 account no 0722 333 444")
        assertEquals(1, r.size)
        assertCandidate(r[0], PAYBILL, "400222", "0722333444")
    }

    @Test fun sign09_lipaNaMpesaWithPaybillIsPaybill() {
        val c = only("Lipa na M-Pesa\nPaybill 123123\nAccount 556")
        assertCandidate(c, PAYBILL, "123123", "556")
    }

    // ---------------------------------------------------------------- till

    @Test fun sign10_classicTill() {
        assertCandidate(only("LIPA NA M-PESA\nBUY GOODS\nTILL NO. 5 3 2 4 1 1"), TILL, "532411")
    }

    @Test fun sign11_tillNumberLowercase() {
        assertCandidate(only("till number 987654 mama mboga"), TILL, "987654")
    }

    @Test fun sign12_lipaNaMpesaAloneIsTill() {
        assertCandidate(only("Lipa Na M-PESA 456789"), TILL, "456789")
    }

    @Test fun sign13_tillWithOcrNoise() {
        assertCandidate(only("TlLL N0:  8O1l23"), TILL, "801123")
    }

    @Test fun sign14_buyGoodsSevenDigit() {
        assertCandidate(only("Buy Goods & Services 1234567"), TILL, "1234567")
    }

    @Test fun sign15_tillAndPhoneGiveTwoCandidates() {
        val r = MpesaParser.parse("Till No: 123456\nFor enquiries call 0712 345 678")
        assertEquals(2, r.size)
        assertCandidate(r[0], TILL, "123456")
        assertEquals("0712345678", r[1].number)
        assertFalse(r[1].typeConfirmed)
    }

    // ---------------------------------------------------------------- phone / pochi / send

    @Test fun sign16_barePhoneNeedsTypeChoice() {
        val c = only("Tuma kwa 0722 000 111")
        assertEquals("0722000111", c.number)
        assertFalse(c.typeConfirmed)
    }

    @Test fun sign17_internationalPhone() {
        val c = only("+254 712-345-678")
        assertEquals("0712345678", c.number)
        assertFalse(c.typeConfirmed)
    }

    @Test fun sign18_pochiLaBiashara() {
        val c = only("POCHI LA BIASHARA\n0798 765 432\nMama Wanjiku")
        assertCandidate(c, POCHI, "0798765432")
        assertTrue(c.typeConfirmed)
    }

    @Test fun sign19_sendMoneyKeyword() {
        val c = only("Send Money to 0110 222 333")
        assertCandidate(c, SEND_MONEY, "0110222333")
        assertTrue(c.typeConfirmed)
    }

    @Test fun sign20_safaricom01Prefix254NoPlus() {
        val c = only("Number: 254110987654")
        assertEquals("0110987654", c.number)
    }

    @Test fun sign21_ocrPhoneWithLetterO() {
        assertEquals("0712345678", only("O7l2 345 678").number)
    }

    // ---------------------------------------------------------------- multiple / none

    @Test fun sign22_paybillAndTillOnOneSign() {
        val r = MpesaParser.parse("Paybill 222111 Acc 4511\nOr Till 333444")
        assertEquals(2, r.size)
        assertCandidate(r[0], PAYBILL, "222111", "4511")
        assertCandidate(r[1], TILL, "333444")
    }

    @Test fun sign23_noPaymentDetails() {
        assertTrue(MpesaParser.parse("OPEN 7 DAYS  8AM - 6PM  Tel: none").isEmpty())
        assertTrue(MpesaParser.parse("").isEmpty())
    }

    @Test fun sign24_priceIsNotATill() {
        // "KSh 50 100" must not become a till without the keyword
        assertTrue(MpesaParser.parse("Sukuma KSh 50 100 per bunch").isEmpty())
    }

    @Test fun sign25_acceptWordIsNotAccount() {
        val c = only("We accept M-PESA Paybill 600100")
        assertCandidate(c, PAYBILL, "600100", null)
    }

    @Test fun sign26_garbageAroundOcr() {
        val c = only("~~ |LIPA| na M PESA ** till:  4 4 4-5 5 5 ** asante")
        assertCandidate(c, TILL, "444555")
    }

    @Test fun sign27_duplicateReadsCollapse() {
        val r = MpesaParser.parse("TILL 777888\nTILL 777888")
        assertEquals(1, r.size)
    }

    // ---------------------------------------------------------------- handwriting

    @Test fun hand01_dotsAsSeparators() {
        assertEquals("0722123456", only("0722.123.456").number)
    }

    @Test fun hand02_handwrittenLettersForDigits() {
        // z->2, g->9, q->9, t->7 inside digit runs
        assertEquals("0722193476", only("O7z2 1g3 4t6").number)
    }

    @Test fun hand03_missingLeadingZero() {
        val c = only("Mama Njeri\n722 123 456")
        assertEquals("0722123456", c.number)
        assertFalse(c.typeConfirmed)
    }

    @Test fun hand04_irregularSpacingAndNewWords() {
        assertEquals("0798765432", only("call me 07 98 76 54 32 asante").number)
    }

    @Test fun hand05_wordsWithTandGAreUntouched() {
        assertEquals("till 123456 tag", MpesaParser.normalize("Till 123456 tag"))
    }

    // ---------------------------------------------------------------- business name is never the account

    @Test fun name01_businessNameIgnoredAccountNumberUsed() {
        val c = only("PAYBILL 247247\nBUSINESS NAME: MAMA MBOGA SHOP\nACCOUNT NO: 556677")
        assertCandidate(c, PAYBILL, "247247", "556677")
    }

    @Test fun name02_onlyBusinessNameMeansNoAccount() {
        val c = only("Paybill 400200\nBusiness Name: Kamau Hardware")
        assertCandidate(c, PAYBILL, "400200", null)
    }

    @Test fun name03_accountNameLineIsNotTheAccount() {
        val c = only("Paybill 522522\nAccount Name: JOHN KAMAU\nAccount No: 1234567")
        assertCandidate(c, PAYBILL, "522522", "1234567")
    }

    @Test fun name04_accountThatRepeatsTheBusinessNameIsIgnored() {
        val c = only("Business name: Juja Traders\nPaybill 333222 Account: Juja")
        assertCandidate(c, PAYBILL, "333222", null)
    }

    // ---------------------------------------------------------------- real poster layouts (samples/)

    @Test fun real01_boxedAccountDigitsJoin() {
        val c = only("PAYBILL NUMBER\n625 6 2 5\nACCOUNT NUMBER\n20 1 43 6 1 1\nNATIONAL BANK OF KENYA")
        assertCandidate(c, PAYBILL, "625625", "20143611")
    }

    @Test fun real02_accountLabelUnderTheValue() {
        val c = only("Family Bank\nPAYBILL\n222111\n20252025\nACCOUNT NUMBER\nSAMPLE POSTER\nACCOUNT NAME")
        assertCandidate(c, PAYBILL, "222111", "20252025")
    }

    @Test fun real03_boxedTillDigits() {
        assertCandidate(only("BUY GOODS TILL NUMBER\n4 5 4 9 3 3 2"), TILL, "4549332")
    }

    @Test fun real04_boxEdgesInAccountAreGaps() {
        val c = only("PAYBILL| BUSINESS NUMBER\n400200\nACCOUNT NUMBER\n40 0|7047|7\nBUSINESS NAME\nPOWER SOLAR LTD")
        assertCandidate(c, PAYBILL, "400200", "40070477")
    }

    @Test fun real05_tinyScrapIsNotAnAccount() {
        val c = only("PAYBILL NUMBER\n247247\nACCOUNT NUMBER\no2 47?")
        assertCandidate(c, PAYBILL, "247247", null)
    }

    @Test fun real06_strayCharAfterAccountMeansIncomplete() {
        val c = only("PAYBILL NUMBER\n542542\nACCOUNT NUMBER\n73944 o")
        assertCandidate(c, PAYBILL, "542542", null)
    }

    // ---------------------------------------------------------------- validator

    @Test fun validator_phoneForms() {
        assertEquals("0712345678", PaymentValidator.normalizePhone("+254 712 345 678"))
        assertEquals("0712345678", PaymentValidator.normalizePhone("254712345678"))
        assertEquals("0112345678", PaymentValidator.normalizePhone("0112-345-678"))
        assertEquals("0712345678", PaymentValidator.normalizePhone("712345678"))
        assertNull(PaymentValidator.normalizePhone("0812345678"))
        assertNull(PaymentValidator.normalizePhone("12345"))
    }

    @Test fun validator_rules() {
        assertNull(PaymentValidator.validate(PAYBILL, "247247", "ABC-1"))
        assertTrue(PaymentValidator.validate(PAYBILL, "247247", "") != null)
        assertTrue(PaymentValidator.validate(PAYBILL, "2472", "1") != null)
        assertNull(PaymentValidator.validate(TILL, "123456", null))
        assertTrue(PaymentValidator.validate(TILL, "12345678", null) != null)
        assertNull(PaymentValidator.validate(POCHI, "0712345678", null))
        assertTrue(PaymentValidator.validate(SEND_MONEY, "0812345678", null) != null)
    }
}
