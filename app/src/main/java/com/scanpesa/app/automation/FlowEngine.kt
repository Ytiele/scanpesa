package com.scanpesa.app.automation

/** Result of one engine tick. */
sealed interface Outcome {
    data object Working : Outcome
    /** All numbers are entered; the user must now type the amount and PIN. */
    data class Done(val message: String) : Outcome
    data class Failed(val step: String, val reason: String) : Outcome
}

/** The values a job may type. There is intentionally no amount and no PIN here. */
data class JobValues(val number: String, val account: String?)

/**
 * Drives one flow (list of steps) against successive screen snapshots.
 *
 * Safety rules enforced here (and unit-tested):
 *  - Password/PIN nodes are never read, typed into or clicked (they are not even in [Screen]).
 *  - Only [JobValues.number] / [JobValues.account] are ever typed, never into an amount field.
 *  - Once any number has been typed, no button in `forbiddenButtons` is ever pressed, and no
 *    button at all is pressed while an amount field or PIN field is visible.
 *  - The job ends after `jobTimeoutSeconds`, or when a step can't be matched for `stepTimeoutSeconds`.
 */
class FlowEngine(
    private val steps: List<FlowStep>,
    private val values: JobValues,
    private val config: FlowConfig,
    private val startedAt: Long,
    private val placeholders: Map<String, String> = emptyMap(),
    /** Extra time allowed before the first step, e.g. while the user unlocks the M-PESA app. */
    private val loginWaitMs: Long = 0,
) {
    var index = 0
        private set
    private var stepStartedAt = startedAt
    private var settleUntil = 0L
    private var lastScrollAt = 0L
    private var scrolls = 0
    private var typedSomething = false
    private var actedOnce = false
    private val lastInputIndex = steps.indexOfLast { it.action == StepAction.INPUT }

    val currentStepName: String get() = steps.getOrNull(index)?.name ?: "finish"

    /** Short progress line for the on-screen status strip, e.g. "Step 2/7: M-PESA". */
    val progress: String get() = "Step ${(index + 1).coerceAtMost(steps.size)}/${steps.size}: $currentStepName"

    /** True once the engine has tapped or typed anything. */
    val hasActed: Boolean get() = actedOnce

    private val jobDeadline get() = startedAt + config.jobTimeoutSeconds * 1000L + loginWaitMs
    private val stepTimeoutMs get() = config.stepTimeoutSeconds * 1000L + (if (actedOnce) 0L else loginWaitMs)

    private val allInputsDone get() = index > lastInputIndex

    private fun texts(step: FlowStep) = step.texts.map { t ->
        placeholders.entries.fold(t) { acc, (k, v) -> acc.replace("{$k}", v) }
    }

    private fun advance(now: Long, settleMs: Long = 700) {
        index++
        stepStartedAt = now
        settleUntil = now + settleMs
        scrolls = 0
        actedOnce = true
    }

    private fun isForbidden(label: String?): Boolean {
        val l = Labels.norm(label)
        return l.isNotEmpty() && config.forbiddenButtons.any { Labels.norm(it) == l }
    }

    private fun editables(screen: Screen) = screen.nodes.filter { it.node.isEditable }

    private fun isAmountField(n: UiNode) = Labels.best(Labels.fieldLabel(n), config.amountLabels) > 0

    /** True when an amount field (own label, or a label text near an empty editable) is on screen. */
    fun amountVisible(screen: Screen): Boolean {
        val eds = editables(screen)
        if (eds.any { isAmountField(it.node) }) return true
        if (eds.isEmpty()) return false
        return screen.nodes.any { !it.node.isEditable && Labels.best(it.node.text ?: it.node.contentDescription, config.amountLabels) > 0 }
    }

    /** @param noRootReason why [root] is null (shown to the user if the step times out). */
    fun tick(root: UiNode?, now: Long, noRootReason: String? = null): Outcome {
        val step = steps.getOrNull(index)
        val stepName = step?.name ?: "finish"
        if (now > jobDeadline) {
            return Outcome.Failed(stepName, "Timed out after ${(jobDeadline - startedAt) / 1000} seconds")
        }
        if (now < settleUntil) return Outcome.Working
        if (root == null) return stepTimeout(step, now, noRootReason ?: "The app screen could not be read")

        val screen = Screen.of(root)

        // A PIN / password field is on screen: never touch it.
        if (screen.passwordSeen) {
            if (!actedOnce) { // e.g. the M-PESA app's login PIN; wait for the user to unlock
                stepStartedAt = now
                return Outcome.Working
            }
            if (allInputsDone) return Outcome.Done(DONE_MESSAGE)
            return Outcome.Failed(stepName, "A PIN screen appeared before all numbers were entered")
        }

        if (allInputsDone && amountVisible(screen)) return Outcome.Done(DONE_MESSAGE)
        if (step == null) return Outcome.Done(DONE_MESSAGE)

        // Optional step whose following step already matches this screen: skip it.
        if (step.optional && matchesStep(steps.getOrNull(index + 1), screen)) {
            index++
            stepStartedAt = now
            return tick(root, now)
        }

        val acted = when (step.action) {
            StepAction.CLICK -> doClick(step, screen, now)
            StepAction.INPUT -> doInput(step, screen)
            StepAction.PRESS -> {
                if (amountVisible(screen) && allInputsDone) return Outcome.Done(DONE_MESSAGE)
                doPress(step, screen)
            }
            StepAction.AWAIT_AMOUNT -> false // only finishes via amountVisible above
        }
        if (acted) {
            advance(now)
            return Outcome.Working
        }
        if (step.optional && now - stepStartedAt > stepTimeoutMs / 2) {
            index++ // optional step never showed up: move on instead of failing
            stepStartedAt = now
            return Outcome.Working
        }
        return stepTimeout(step, now, null)
    }

    private fun stepTimeout(step: FlowStep?, now: Long, why: String?): Outcome {
        if (now - stepStartedAt <= stepTimeoutMs) return Outcome.Working
        if (step == null) return Outcome.Failed("finish", why ?: "Screen did not change")
        val reason = why ?: when (step.action) {
            StepAction.CLICK -> "Could not find \"${texts(step).joinToString("\" / \"")}\" on screen"
            StepAction.INPUT -> "Could not find the input field \"${texts(step).joinToString("\" / \"")}\""
            StepAction.PRESS -> "Could not find the button \"${texts(step).joinToString("\" / \"")}\""
            StepAction.AWAIT_AMOUNT -> "The amount screen did not appear"
        }
        return Outcome.Failed(step.name, reason)
    }

    private fun matchesStep(step: FlowStep?, screen: Screen): Boolean = when (step?.action) {
        null -> false
        StepAction.CLICK, StepAction.PRESS -> findClickable(texts(step), screen, step.action == StepAction.PRESS) != null
        StepAction.INPUT -> findField(step, screen) != null
        StepAction.AWAIT_AMOUNT -> amountVisible(screen)
    }

    /** Best clickable node whose text/description matches; exact matches beat partial ones. */
    private fun findClickable(patterns: List<String>, screen: Screen, isButton: Boolean): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (f in screen.nodes) {
            // Some SIM Toolkit menus don't mark their rows clickable; then the text node itself is
            // the target and the Android side taps its on-screen position instead.
            val target = f.clickTarget ?: f.node
            if (target.isEditable || f.node.isEditable) continue
            val label = listOfNotNull(f.node.text, f.node.contentDescription)
            val match = label.maxOfOrNull { Labels.best(it, patterns) } ?: 0
            if (match == 0) continue
            // Safety: once a number has been typed (or for any button), never hit Send/Pay/Confirm.
            if ((isButton || typedSomething) && label.any { isForbidden(it) }) continue
            // Exact beats partial; at equal match, a real clickable beats plain text (e.g. a title).
            val s = match * 2 + (if (f.clickTarget != null) 1 else 0)
            if (s > bestScore) { best = target; bestScore = s }
        }
        return best
    }

    private fun doClick(step: FlowStep, screen: Screen, now: Long): Boolean {
        if (typedSomething && amountVisible(screen)) return false
        val node = findClickable(texts(step), screen, isButton = false)
        if (node != null) return node.click()
        // Long menus (SIM Toolkit) may need scrolling to reveal the item.
        if (scrolls < 3 && now - lastScrollAt > 1200) {
            screen.nodes.firstOrNull { it.node.isScrollable }?.let {
                lastScrollAt = now; scrolls++; it.node.scrollForward()
            }
        }
        return false
    }

    private fun doPress(step: FlowStep, screen: Screen): Boolean {
        if (amountVisible(screen) && allInputsDone) return false
        val node = findClickable(texts(step), screen, isButton = true) ?: return false
        return node.click()
    }

    /** Field for an INPUT step: own label match first, else first free editable after a matching label. */
    private fun findField(step: FlowStep, screen: Screen): UiNode? {
        val patterns = texts(step)
        val eds = editables(screen).filter { !isAmountField(it.node) }
        eds.maxByOrNull { Labels.best(Labels.fieldLabel(it.node), patterns) }
            ?.takeIf { Labels.best(Labels.fieldLabel(it.node), patterns) > 0 }
            ?.let { return it.node }
        val label = screen.nodes.firstOrNull { !it.node.isEditable && Labels.best(it.node.text ?: it.node.contentDescription, patterns) > 0 }
            ?: return null
        // First editable after the label that isn't an amount field and isn't preceded by an amount label.
        val amountLabelOrder = screen.nodes.firstOrNull {
            it.order > label.order && !it.node.isEditable && Labels.best(it.node.text, config.amountLabels) > 0
        }?.order ?: Int.MAX_VALUE
        return eds.firstOrNull { it.order > label.order && it.order < amountLabelOrder }?.node
    }

    private fun doInput(step: FlowStep, screen: Screen): Boolean {
        val value = when (step.value) {
            InputValue.NUMBER -> values.number
            InputValue.ACCOUNT -> values.account ?: return false
            null -> return false
        }
        val field = findField(step, screen) ?: return false
        if (field.isPassword || isAmountField(field)) return false // belt and braces
        val ok = field.setText(value)
        if (ok) typedSomething = true
        return ok
    }

    companion object {
        const val DONE_MESSAGE = "Enter amount and PIN"
    }
}
