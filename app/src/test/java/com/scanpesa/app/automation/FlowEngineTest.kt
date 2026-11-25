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
        assertEquals(20, cfg.jobTimeoutSeconds)
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

    @Test fun labelNormalisation() {
        assertEquals("send money", Labels.norm(" 2. Send Money "))
        assertEquals(3, Labels.score("1) Pay Bill", "pay bill"))
        assertEquals(0, Labels.score("Paybill", "pay"))
        assertEquals(1, Labels.score("Lipa na M-PESA", "m pesa"))
    }
}
