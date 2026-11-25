package com.scanpesa.app.automation

import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fake node that records what the engine did to it. */
class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val hint: String? = null,
    override val viewId: String? = null,
    override val className: String? = "android.widget.TextView",
    override val isPassword: Boolean = false,
    override val isEditable: Boolean = false,
    override val isClickable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isVisible: Boolean = true,
    override val children: List<UiNode> = emptyList(),
) : UiNode {
    var clicks = 0
    var typed: String? = null
    var textReads = 0
    override fun click(): Boolean { clicks++; return true }
    override fun setText(value: String): Boolean { typed = value; return true }
    override fun scrollForward(): Boolean = true
}

private fun item(t: String) = FakeNode(text = t, isClickable = true)
private fun button(t: String) = FakeNode(text = t, isClickable = true, className = "android.widget.Button")
private fun field(hint: String) = FakeNode(hint = hint, isEditable = true, className = "android.widget.EditText")
private fun label(t: String) = FakeNode(text = t)
private fun screen(vararg n: UiNode) = FakeNode(className = "android.widget.FrameLayout", children = n.toList())

class FlowEngineTest {

    private val config = FlowConfig(
        version = 1, jobTimeoutSeconds = 20, stepTimeoutSeconds = 8,
        amountLabels = listOf("amount", "kiasi"),
        forbiddenButtons = listOf("send", "pay", "confirm", "pay now", "buy"),
        targets = emptyMap(),
    )

    private val stkSendMoney = listOf(
        FlowStep("Pick SIM", StepAction.CLICK, listOf("safaricom", "sim {SIM}"), optional = true),
        FlowStep("M-PESA menu", StepAction.CLICK, listOf("m-pesa", "mpesa")),
        FlowStep("Send Money", StepAction.CLICK, listOf("send money")),
        FlowStep("Phone number", StepAction.INPUT, listOf("phone", "enter phone no"), value = InputValue.NUMBER),
        FlowStep("OK", StepAction.PRESS, listOf("ok", "next", "continue", "send")),
        FlowStep("Amount", StepAction.AWAIT_AMOUNT),
    )

    @Test fun walksStkSendMoneyAndStopsAtAmount() {
        val e = FlowEngine(stkSendMoney, JobValues("0712345678", null), config, startedAt = 0, placeholders = mapOf("SIM" to "1"))
        var t = 1000L
        fun next() = 1000L.also { t += it }

        val mpesa = item("M-PESA")
        assertEquals(Outcome.Working, e.tick(screen(label("SIM Toolkit"), mpesa, item("Safaricom+")), t))
        assertEquals(1, mpesa.clicks) // optional SIM picker skipped because M-PESA was visible

        next(); val send = item("2. Send Money")
        e.tick(screen(item("1. Lipa na M-PESA"), send), t)
        assertEquals(1, send.clicks)

        next(); val phone = field("")
        e.tick(screen(label("Enter phone no."), phone, button("OK")), t)
        assertEquals("0712345678", phone.typed)

        next(); val ok = button("OK")
        e.tick(screen(label("Enter phone no."), FakeNode(text = "0712345678", isEditable = true), ok), t)
        assertEquals(1, ok.clicks)

        next(); val amount = field("")
        val sendBtn = button("Send")
        val out = e.tick(screen(label("Enter Amount"), amount, sendBtn), t)
        assertEquals(Outcome.Done(FlowEngine.DONE_MESSAGE), out)
        assertNull("must never type an amount", amount.typed)
        assertEquals("must never press send", 0, sendBtn.clicks)
    }

    @Test fun neverTouchesPasswordNodes() {
        val pin = FakeNode(text = "1234", isPassword = true, isEditable = true, isClickable = true, hint = "phone")
        val steps = listOf(FlowStep("Phone", StepAction.INPUT, listOf("phone"), value = InputValue.NUMBER))
        val e = FlowEngine(steps, JobValues("0712345678", null), config, 0)
        e.tick(screen(label("Phone"), pin), 1000)
        assertNull(pin.typed)
        assertEquals(0, pin.clicks)
        // and password nodes are not in the dump
        assertTrue(NodeDumper.dump(screen(label("x"), pin)).none { it.contains("1234") || it.contains("phone") })
    }

    @Test fun pinScreenAfterInputsMeansDone() {
        val steps = listOf(
            FlowStep("Till", StepAction.INPUT, listOf("till"), value = InputValue.NUMBER),
            FlowStep("Amount", StepAction.AWAIT_AMOUNT),
        )
        val e = FlowEngine(steps, JobValues("123456", null), config, 0)
        e.tick(screen(field("Till number")), 1000)
        val out = e.tick(screen(label("Enter PIN"), FakeNode(isPassword = true, isEditable = true)), 3000)
        assertEquals(Outcome.Done(FlowEngine.DONE_MESSAGE), out)
    }

    @Test fun waitsWhileLoginPinShownBeforeAnyAction() {
        val steps = listOf(FlowStep("Pay tab", StepAction.CLICK, listOf("pay")))
        val e = FlowEngine(steps, JobValues("123456", null), config, 0)
        assertEquals(Outcome.Working, e.tick(screen(FakeNode(isPassword = true, isEditable = true)), 1000))
        assertEquals(Outcome.Working, e.tick(screen(FakeNode(isPassword = true, isEditable = true)), 12000))
    }

    @Test fun singleScreenPaybillFillsBothFieldsButNotAmount() {
        val steps = listOf(
            FlowStep("Business", StepAction.INPUT, listOf("business number", "paybill"), value = InputValue.NUMBER),
            FlowStep("Account", StepAction.INPUT, listOf("account"), value = InputValue.ACCOUNT),
            FlowStep("Continue", StepAction.PRESS, listOf("continue")),
            FlowStep("Amount", StepAction.AWAIT_AMOUNT),
        )
        val biz = field("Business number"); val acc = field("Account number"); val amt = field("Amount")
        val cont = button("Continue")
        val e = FlowEngine(steps, JobValues("247247", "ABC1"), config, 0)
        val s = screen(biz, acc, amt, cont)
        e.tick(s, 1000); e.tick(s, 2000)
        val out = e.tick(s, 3000)
        assertEquals("247247", biz.typed)
        assertEquals("ABC1", acc.typed)
        assertNull(amt.typed)
        assertEquals("must stop before pressing continue when amount is visible", 0, cont.clicks)
        assertEquals(Outcome.Done(FlowEngine.DONE_MESSAGE), out)
    }

    @Test fun neverPressesForbiddenButtonEvenIfListed() {
        val steps = listOf(
            FlowStep("Number", StepAction.INPUT, listOf("phone"), value = InputValue.NUMBER),
            FlowStep("Next", StepAction.PRESS, listOf("send", "pay")),
        )
        val sendBtn = button("Send")
        val e = FlowEngine(steps, JobValues("0712345678", null), config, 0)
        e.tick(screen(field("Phone"), sendBtn), 1000)
        e.tick(screen(field("Phone"), sendBtn), 2000)
        assertEquals(0, sendBtn.clicks)
        val out = e.tick(screen(field("Phone"), sendBtn), 20000)
        assertTrue(out is Outcome.Failed)
    }

    @Test fun failsWithStepNameWhenScreenDoesNotMatch() {
        val e = FlowEngine(stkSendMoney.drop(1), JobValues("0712345678", null), config, 0)
        val s = screen(item("Something else"))
        assertEquals(Outcome.Working, e.tick(s, 3000))
        val out = e.tick(s, 9000)
        assertTrue(out is Outcome.Failed)
        assertEquals("M-PESA menu", (out as Outcome.Failed).step)
    }

    @Test fun jobTimesOutAfter20Seconds() {
        val e = FlowEngine(stkSendMoney, JobValues("0712345678", null), config, 0)
        assertTrue(e.tick(screen(), 20_001) is Outcome.Failed)
    }

    @Test fun amountFieldIsNeverUsedForNumber() {
        val steps = listOf(FlowStep("Number", StepAction.INPUT, listOf("enter"), value = InputValue.NUMBER))
        val amt = field("Enter amount")
        val e = FlowEngine(steps, JobValues("0712345678", null), config, 0)
        e.tick(screen(label("Enter"), amt), 1000)
        assertNull(amt.typed)
    }

    @Test fun bundledFlowsJsonParsesAndCoversEveryTargetAndType() {
        val f = listOf(File("src/main/assets/flows.json"), File("app/src/main/assets/flows.json")).first { it.exists() }
        val cfg = FlowConfig.parse(f.readText())
        assertEquals(40, cfg.jobTimeoutSeconds)
        for (t in Target.entries) {
            val tc = cfg.targets[t]!!
            for (p in PaymentType.entries) {
                val steps = tc.flows[p]
                assertTrue("$t/$p missing", !steps.isNullOrEmpty())
                assertEquals("$t/$p must end with await_amount", StepAction.AWAIT_AMOUNT, steps!!.last().action)
                if (p == PaymentType.PAYBILL) assertTrue(steps.any { it.value == InputValue.ACCOUNT })
            }
        }
        assertTrue("com.android.stk" in cfg.allPackages)
        assertFalse(cfg.forbiddenButtons.isEmpty())
    }

    private fun bundled(): FlowConfig {
        val f = listOf(File("src/main/assets/flows.json"), File("app/src/main/assets/flows.json")).first { it.exists() }
        return FlowConfig.parse(f.readText())
    }

    /** Replays the real Safaricom SIM menu described by the user (Nov 2026). */
    @Test fun stkPaybillWithSearchContactsScreens() {
        val cfg = bundled()
        val steps = cfg.targets[Target.SIM_TOOLKIT]!!.flows[PaymentType.PAYBILL]!!
        val e = FlowEngine(steps, JobValues("247247", "0712345678"), cfg, 0, mapOf("SIM" to "1"))
        var t = 0L
        fun tick(root: UiNode) { t += 1000; assertEquals(Outcome.Working, e.tick(root, t)) }

        val mpesa = item("M-PESA"); tick(screen(item("Safaricom"), mpesa)); assertEquals(1, mpesa.clicks)
        val lipa = item("Lipa na M-PESA")
        tick(screen(item("Send Money"), item("Withdraw"), item("Buy Airtime"), item("Loans and Savings"), lipa, item("My Account")))
        assertEquals(1, lipa.clicks)
        val payBill = item("Pay Bill"); tick(screen(payBill, item("Buy Goods"), item("Pochi la Biashara"))); assertEquals(1, payBill.clicks)

        val search1 = item("Search contacts"); val enterBiz = item("Enter business number")
        tick(screen(search1, enterBiz))
        assertEquals(1, enterBiz.clicks); assertEquals(0, search1.clicks)

        val bizField = field(""); tick(screen(label("Enter business number"), bizField, button("OK")))
        assertEquals("247247", bizField.typed)
        val ok1 = button("OK"); tick(screen(label("Enter business number"), FakeNode(text = "247247", isEditable = true), ok1))
        assertEquals(1, ok1.clicks)

        val search2 = item("Search contacts"); val accItem = item("Account number")
        tick(screen(search2, accItem))
        assertEquals(1, accItem.clicks); assertEquals(0, search2.clicks)

        val accField = field(""); tick(screen(label("Account number"), accField, button("OK")))
        assertEquals("0712345678", accField.typed)
        val ok2 = button("OK"); tick(screen(label("Account number"), FakeNode(text = "0712345678", isEditable = true), ok2))
        assertEquals(1, ok2.clicks)

        val amount = field("")
        t += 1000
        assertEquals(Outcome.Done(FlowEngine.DONE_MESSAGE), e.tick(screen(label("Enter Amount"), amount, button("OK")), t))
        assertNull(amount.typed)
    }

    @Test fun stkSendMoneyOpensInputDirectly() {
        val cfg = bundled()
        val steps = cfg.targets[Target.SIM_TOOLKIT]!!.flows[PaymentType.SEND_MONEY]!!
        val e = FlowEngine(steps, JobValues("0712345678", null), cfg, 0, mapOf("SIM" to "1"))
        e.tick(screen(item("Safaricom"), item("M-PESA")), 1000)
        val send = item("Send Money"); e.tick(screen(send, item("Withdraw"), item("Lipa na M-PESA")), 2000)
        assertEquals(1, send.clicks)
        val phone = field(""); e.tick(screen(label("Enter phone number"), phone, button("OK")), 3000)
        assertEquals("0712345678", phone.typed)
    }

    @Test fun loginWaitGivesTimeToUnlockButTypesNothing() {
        val steps = listOf(
            FlowStep("Pay", StepAction.CLICK, listOf("pay")),
            FlowStep("Amount", StepAction.AWAIT_AMOUNT),
        )
        val e = FlowEngine(steps, JobValues("123456", null), config, 0, loginWaitMs = 45_000)
        val keypad = screen(label("Enter M-PESA PIN"), button("1"), button("2"))
        assertEquals(Outcome.Working, e.tick(keypad, 15_000)) // past the normal 8 s step timeout
        assertEquals(Outcome.Working, e.tick(keypad, 30_000))
        val pay = item("Pay"); e.tick(screen(pay), 40_000)
        assertEquals(1, pay.clicks)
        assertTrue(e.tick(screen(), 70_000) is Outcome.Failed) // hard stop: 20 s + 45 s
    }

    @Test fun nonClickableMenuRowsAreStillTapped() {
        // Some SIM Toolkit lists don't mark rows clickable: the text node itself must be used.
        val steps = listOf(FlowStep("M-PESA", StepAction.CLICK, listOf("m-pesa")))
        val row = FakeNode(text = "M-PESA") // not clickable, no clickable parent
        val e = FlowEngine(steps, JobValues("0712345678", null), config, 0)
        e.tick(screen(FakeNode(text = "Safaricom"), row), 1000)
        assertEquals(1, row.clicks)
    }

    /** Samsung S22 SIM Toolkit, captured over adb: rows sit in a list reported invisible mid-animation. */
    @Test fun samsungStkRowsInsideHiddenListAreFound() {
        val cfg = bundled()
        val steps = cfg.targets[Target.SIM_TOOLKIT]!!.flows[PaymentType.SEND_MONEY]!!
        val title = FakeNode(text = "Safaricom", viewId = "com.android.stk:id/title_text")
        val mpesaText = FakeNode(text = "M-PESA", viewId = "com.android.stk:id/text")
        val mpesaRow = FakeNode(className = "android.widget.LinearLayout", isClickable = true, children = listOf(mpesaText))
        val plusRow = FakeNode(className = "android.widget.LinearLayout", isClickable = true,
            children = listOf(FakeNode(text = "Safaricom+", viewId = "com.android.stk:id/text")))
        val list = FakeNode(className = "android.widget.ListView", isVisible = false, children = listOf(plusRow, mpesaRow))
        val root = screen(FakeNode(children = listOf(title)), list, FakeNode(text = "Safaricom"))

        val e = FlowEngine(steps, JobValues("0712345678", null), cfg, 0, mapOf("SIM" to "1"), initialSettleMs = 1500)
        assertEquals(Outcome.Working, e.tick(root, 1000)) // still settling: nothing touched
        assertEquals(0, title.clicks + mpesaRow.clicks)
        e.tick(root, 2000)
        assertEquals("M-PESA row tapped", 1, mpesaRow.clicks)
        assertEquals("title never tapped", 0, title.clicks)
    }

    /** SIM Toolkit reopens where it was left (here: the M-PESA submenu). */
    @Test fun stkStartsInSubmenuPressesBackFirst() {
        val cfg = bundled()
        val steps = cfg.targets[Target.SIM_TOOLKIT]!!.flows[PaymentType.SEND_MONEY]!!
        var backs = 0
        val sight = Sight(goBack = { backs++; true })
        val e = FlowEngine(steps, JobValues("0712345678", null), cfg, 0, mapOf("SIM" to "1"))
        val lipa = item("Lipa na M-PESA") // contains "M-PESA" but must not count as the main menu
        val sub = screen(FakeNode(text = "SIM Toolkit", viewId = "com.android.stk:id/title_text"), item("Send Money"), lipa)
        e.tick(sub, 1000, sight = sight)
        assertEquals("pressed Back instead of tapping a partial match", 1, backs)
        assertEquals(0, lipa.clicks)
        val mpesa = item("M-PESA")
        e.tick(screen(item("Safaricom+"), mpesa), 2500, sight = sight)
        assertEquals(1, mpesa.clicks)
        assertEquals(1, backs)
    }

    @Test fun clickableItemBeatsSameTextTitle() {
        val steps = listOf(FlowStep("Lipa", StepAction.CLICK, listOf("lipa na m-pesa")))
        val title = FakeNode(text = "Lipa na M-PESA") // screen title, not clickable
        val itemNode = item("Lipa na M-PESA")
        val e = FlowEngine(steps, JobValues("1", null), config, 0)
        e.tick(screen(title, itemNode), 1000)
        assertEquals(1, itemNode.clicks)
        assertEquals(0, title.clicks)
    }

    @Test fun labelNormalisation() {
        assertEquals("send money", Labels.norm(" 2. Send Money "))
        assertEquals(3, Labels.score("1) Pay Bill", "pay bill"))
        assertEquals(0, Labels.score("Paybill", "pay"))
        assertEquals(1, Labels.score("Lipa na M-PESA", "m pesa"))
    }
}
