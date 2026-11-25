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

/** One line of text read from a screenshot (OCR), with its centre in screen pixels. */
data class SeenLine(val text: String, val x: Int, val y: Int)

/**
 * What ScanPesa can see beyond the (possibly stale) accessibility tree on this tick.
 * @param lines OCR lines from a screenshot taken after the last action (the app's "eyes").
 * @param focusedInput the field that currently has input focus (fresh query, not cached).
 * @param tapAt taps a screen position like a finger.
 * @param screenHeight for ignoring the title/status area at the top of the screen.
 */
class Sight(
    val lines: List<SeenLine> = emptyList(),
    val focusedInput: UiNode? = null,
    val tapAt: ((Int, Int) -> Boolean)? = null,
    val screenHeight: Int = 0,
    /** Presses the system Back button. */
    val goBack: (() -> Boolean)? = null,
)

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
    private val initialSettleMs: Long = 0,
) {
    var index = 0
        private set
    private var stepStartedAt = startedAt
    /** Wait for the target app's opening animation before the first action. */
    private var settleUntil = startedAt + initialSettleMs
    private var lastScrollAt = 0L
    private var scrolls = 0
    private var backs = 0
    private var typedSomething = false
    private var actedOnce = false
    /** When the last tap/typing happened; screenshots older than this are ignored. */
    var lastActionAt = 0L
        private set
    /** How the last action was done (tree / text search / eyes), for the log. */
    var lastHow = ""
        private set
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

    /** Milliseconds the current step has been waiting (the service turns on the eyes after a while). */
    fun stepAge(now: Long) = now - stepStartedAt

    private fun advance(now: Long, settleMs: Long = 900) {
        lastActionAt = now
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
    fun tick(root: UiNode?, now: Long, noRootReason: String? = null, sight: Sight = Sight()): Outcome {
        val step = steps.getOrNull(index)
        val stepName = step?.name ?: "finish"
        if (now > jobDeadline) {
            return Outcome.Failed(stepName, "Timed out after ${(jobDeadline - startedAt) / 1000} seconds")
        }
        if (now < settleUntil) return Outcome.Working
        if (root == null) return stepTimeout(step, now, noRootReason ?: "The app screen could not be read")

        val screen = Screen.of(root)
        val seenPin = sight.lines.any { Labels.best(it.text, PIN_WORDS) > 0 }
        val seenAmount = sight.lines.any { Labels.best(it.text, config.amountLabels) > 0 }

        // A PIN / password field is on screen (in the tree, or read by the eyes): never touch it.
        if (screen.passwordSeen || seenPin) {
            if (!actedOnce) { // e.g. the M-PESA app's login PIN; wait for the user to unlock
                stepStartedAt = now
                return Outcome.Working
            }
            if (allInputsDone) return Outcome.Done(DONE_MESSAGE)
            return Outcome.Failed(stepName, "A PIN screen appeared before all numbers were entered")
        }

        val amountOnScreen = amountVisible(screen) || seenAmount
        if (allInputsDone && amountOnScreen) return Outcome.Done(DONE_MESSAGE)
        if (step == null) return Outcome.Done(DONE_MESSAGE)

        // Optional step whose following step already matches this screen: skip it.
        if (step.optional && matchesStep(steps.getOrNull(index + 1), screen, root, sight)) {
            index++
            stepStartedAt = now
            return tick(root, now, noRootReason, sight)
        }

        val acted = when (step.action) {
            StepAction.CLICK -> doClick(step, screen, now, root, sight, amountOnScreen)
            StepAction.INPUT -> doInput(step, screen, root, sight)
            StepAction.PRESS -> {
                if (amountOnScreen && allInputsDone) return Outcome.Done(DONE_MESSAGE)
                doPress(step, screen, root, sight, amountOnScreen)
            }
            StepAction.AWAIT_AMOUNT -> false // only finishes via amountVisible above
            StepAction.BACK_UNTIL -> {
                if (atMenu(step, screen, root, sight)) {
                    index++; stepStartedAt = now; backs = 0
                    lastHow = "already there"
                    return tick(root, now, noRootReason, sight)
                }
                val back = sight.goBack
                if (back != null && backs < 4 && !typedSomething) {
                    backs++
                    back()
                    lastHow = "pressed Back"
                    settleUntil = now + 1000
                    return Outcome.Working
                }
                false
            }
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
            StepAction.BACK_UNTIL -> "Could not get back to the menu with \"${texts(step).joinToString("\" / \"")}\""
        }
        return Outcome.Failed(step.name, reason)
    }

    private fun matchesStep(step: FlowStep?, screen: Screen, root: UiNode, sight: Sight): Boolean = when (step?.action) {
        null -> false
        StepAction.CLICK, StepAction.PRESS -> {
            val isButton = step.action == StepAction.PRESS
            findClickable(texts(step), screen, isButton) != null ||
                searchByText(texts(step), root, isButton) != null ||
                seenLine(texts(step), sight, isButton) != null
        }
        StepAction.INPUT -> findField(step, screen) != null || focusedFieldFor(step, root, sight) != null
        StepAction.AWAIT_AMOUNT -> amountVisible(screen) || sight.lines.any { Labels.best(it.text, config.amountLabels) > 0 }
        StepAction.BACK_UNTIL -> atMenu(step, screen, root, sight)
    }

    /** An item exactly matching the step's texts is on screen (e.g. the main menu's "M-PESA"). */
    private fun atMenu(step: FlowStep, screen: Screen, root: UiNode, sight: Sight): Boolean {
        val p = texts(step)
        return findClickable(p, screen, isButton = false, minScore = 3) != null ||
            searchByText(p, root, isButton = false, minScore = 3) != null ||
            seenLine(p, sight, isButton = false, minScore = 3) != null
    }

    /** Fresh text search in the app (bypasses a stale node cache). */
    private fun searchByText(patterns: List<String>, root: UiNode, isButton: Boolean, minScore: Int = MENU_MIN_SCORE): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (p in patterns) for (n in root.findByText(p)) {
            if (n.isPassword || n.isEditable || isTitle(n)) continue
            val label = listOfNotNull(n.text, n.contentDescription)
            val match = label.maxOfOrNull { Labels.best(it, patterns) } ?: 0
            if (match < minScore) continue
            if ((isButton || typedSomething) && label.any { isForbidden(it) }) continue
            if (match > bestScore) { best = n; bestScore = match }
        }
        return best
    }

    /** Best OCR line for [patterns], ignoring the title/status area and ScanPesa's own strip. */
    private fun seenLine(patterns: List<String>, sight: Sight, isButton: Boolean, minScore: Int = MENU_MIN_SCORE): SeenLine? {
        // Top 20% = status bar, app bar and screen title: never tapped.
        val topCut = (sight.screenHeight * 0.20).toInt()
        return sight.lines
            .filter { it.y > topCut && !it.text.contains("scanpesa", ignoreCase = true) }
            .filter { !((isButton || typedSomething) && isForbidden(it.text)) }
            .map { it to Labels.best(it.text, patterns) }
            .filter { it.second >= minScore }
            .maxByOrNull { it.second }?.first
    }

    /** The focused input, if this screen is the step's input screen (its label is on screen). */
    private fun focusedFieldFor(step: FlowStep, root: UiNode, sight: Sight): UiNode? {
        val f = sight.focusedInput ?: return null
        if (f.isPassword || !f.isEditable || isAmountField(f)) return null
        val patterns = texts(step)
        val labelSeen = sight.lines.any { Labels.best(it.text, patterns) > 0 } ||
            patterns.any { p -> root.findByText(p).any { !it.isEditable && Labels.best(it.text, patterns) > 0 } }
        if (!labelSeen) return null
        // Never type into a field when the screen says "amount".
        if (sight.lines.any { Labels.best(it.text, config.amountLabels) > 0 }) return null
        return f
    }

    /** Best clickable node whose text/description matches; exact matches beat partial ones. */
    /** Screen/app-bar titles are never tapped as menu items. */
    private fun isTitle(n: UiNode): Boolean {
        val id = n.viewId?.lowercase() ?: return false
        return "title" in id || "action_bar" in id || "toolbar" in id
    }

    /**
     * @param requireClickable only accept nodes that are (inside) a real clickable; used for
     *   optional steps so they never tap plain text like a screen title.
     */
    private fun findClickable(
        patterns: List<String>, screen: Screen, isButton: Boolean, requireClickable: Boolean = false, minScore: Int = MENU_MIN_SCORE,
    ): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (f in screen.nodes) {
            // Some SIM Toolkit menus don't mark their rows clickable; then the text node itself is
            // the target and the Android side taps its on-screen position instead.
            if (f.clickTarget == null && (requireClickable || isTitle(f.node))) continue
            val target = f.clickTarget ?: f.node
            if (target.isEditable || f.node.isEditable) continue
            val label = listOfNotNull(f.node.text, f.node.contentDescription)
            val match = label.maxOfOrNull { Labels.best(it, patterns) } ?: 0
            if (match < minScore) continue
            // Safety: once a number has been typed (or for any button), never hit Send/Pay/Confirm.
            if ((isButton || typedSomething) && label.any { isForbidden(it) }) continue
            // Exact beats partial; at equal match, a real clickable beats plain text (e.g. a title).
            // ...and anything visible beats nodes reported hidden.
            val s = match * 4 + (if (f.visible) 2 else 0) + (if (f.clickTarget != null) 1 else 0)
            if (s > bestScore) { best = target; bestScore = s }
        }
        return best
    }

    private fun doClick(step: FlowStep, screen: Screen, now: Long, root: UiNode, sight: Sight, amountOnScreen: Boolean): Boolean {
        if (typedSomething && amountOnScreen) return false
        val node = findClickable(texts(step), screen, isButton = false, requireClickable = step.optional)
        if (node != null) { lastHow = "tree"; return node.click() }
        // Optional steps never use the fallbacks (they could hit a screen title).
        if (!step.optional) {
            searchByText(texts(step), root, isButton = false)?.let { lastHow = "text search"; return it.click() }
            seenLine(texts(step), sight, isButton = false)?.let { line ->
                val tap = sight.tapAt ?: return false
                lastHow = "eyes: \"${line.text}\" at ${line.x},${line.y}"
                return tap(line.x, line.y)
            }
        }
        // Long menus (SIM Toolkit) may need scrolling to reveal the item.
        if (scrolls < 3 && now - lastScrollAt > 1200) {
            screen.nodes.firstOrNull { it.node.isScrollable }?.let {
                lastScrollAt = now; scrolls++; it.node.scrollForward()
            }
        }
        return false
    }

    private fun doPress(step: FlowStep, screen: Screen, root: UiNode, sight: Sight, amountOnScreen: Boolean): Boolean {
        if (amountOnScreen && allInputsDone) return false
        findClickable(texts(step), screen, isButton = true)?.let { lastHow = "tree"; return it.click() }
        searchByText(texts(step), root, isButton = true)?.let { lastHow = "text search"; return it.click() }
        val line = seenLine(texts(step), sight, isButton = true) ?: return false
        val tap = sight.tapAt ?: return false
        lastHow = "eyes: \"${line.text}\" at ${line.x},${line.y}"
        return tap(line.x, line.y)
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

    private fun doInput(step: FlowStep, screen: Screen, root: UiNode, sight: Sight): Boolean {
        val value = when (step.value) {
            InputValue.NUMBER -> values.number
            InputValue.ACCOUNT -> values.account ?: return false
            null -> return false
        }
        val field = findField(step, screen)?.also { lastHow = "tree" }
            ?: focusedFieldFor(step, root, sight)?.also { lastHow = "focused field" }
            ?: return false
        if (field.isPassword || isAmountField(field)) return false // belt and braces
        val ok = field.setText(value)
        if (ok) typedSomething = true
        return ok
    }

    companion object {
        const val DONE_MESSAGE = "Enter amount and PIN"
        /** Menu items and buttons need an exact (3) or starts-with (2) match, never a partial one. */
        const val MENU_MIN_SCORE = 2
        /** Words that mean a PIN prompt is on screen (read by the eyes). */
        val PIN_WORDS = listOf("pin", "m-pesa pin", "mpesa pin", "enter pin")
    }
}
