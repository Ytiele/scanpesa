package com.scanpesa.app.automation

import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import org.json.JSONArray
import org.json.JSONObject

/** What a flow step does. */
enum class StepAction {
    /** Tap a menu item / tab / list entry whose text or content description matches [FlowStep.texts]. */
    CLICK,
    /** Find an input field (by hint/label in [FlowStep.texts]) and type [FlowStep.value] with ACTION_SET_TEXT. */
    INPUT,
    /** Tap a Next / OK / Continue button. Never allowed for buttons in [FlowConfig.forbiddenButtons]. */
    PRESS,
    /** Finish: wait until the amount field (or PIN screen) is visible, then hand over to the user. */
    AWAIT_AMOUNT,
}

/** Which job value an INPUT step types. Amount and PIN are deliberately not representable. */
enum class InputValue { NUMBER, ACCOUNT }

data class FlowStep(
    val name: String,
    val action: StepAction,
    val texts: List<String> = emptyList(),
    val value: InputValue? = null,
    /** Optional steps are skipped when the next step already matches the screen. */
    val optional: Boolean = false,
)

data class TargetConfig(
    val target: Target,
    /** Package names for SIM slot 1 (first installed one wins). */
    val packages: List<String>,
    /** Package names tried for SIM slot 2 (SIM Toolkit only). Falls back to [packages]. */
    val packagesSim2: List<String>,
    val clearTask: Boolean,
    val flows: Map<PaymentType, List<FlowStep>>,
)

data class FlowConfig(
    val version: Int,
    val jobTimeoutSeconds: Int,
    val stepTimeoutSeconds: Int,
    val amountLabels: List<String>,
    val forbiddenButtons: List<String>,
    val targets: Map<Target, TargetConfig>,
) {
    /** Every package the accessibility service needs to watch. */
    val allPackages: Set<String>
        get() = targets.values.flatMap { it.packages + it.packagesSim2 }.toSet()

    companion object {
        /** Parses flows.json. Throws [IllegalArgumentException] with a readable message on bad input. */
        fun parse(json: String): FlowConfig {
            val root = try { JSONObject(json) } catch (e: Exception) {
                throw IllegalArgumentException("flows.json is not valid JSON: ${e.message}")
            }
            val targetsObj = root.optJSONObject("targets")
                ?: throw IllegalArgumentException("flows.json has no \"targets\" object")
            val targets = mutableMapOf<Target, TargetConfig>()
            for (key in targetsObj.keys()) {
                val target = Target.fromName(key)
                    ?: throw IllegalArgumentException("Unknown target \"$key\" (use ${Target.entries.joinToString { it.name }})")
                val t = targetsObj.getJSONObject(key)
                val flowsObj = t.optJSONObject("flows") ?: JSONObject()
                val flows = mutableMapOf<PaymentType, List<FlowStep>>()
                for (fk in flowsObj.keys()) {
                    val type = PaymentType.fromName(fk)
                        ?: throw IllegalArgumentException("Unknown payment type \"$fk\" in $key")
                    flows[type] = parseSteps(flowsObj.getJSONArray(fk), "$key.$fk")
                }
                val pk = t.optJSONArray("packages").strings()
                if (pk.isEmpty()) throw IllegalArgumentException("$key needs at least one package name")
                targets[target] = TargetConfig(
                    target = target,
                    packages = pk,
                    packagesSim2 = t.optJSONArray("packagesSim2").strings().ifEmpty { pk },
                    clearTask = t.optBoolean("clearTask", true),
                    flows = flows,
                )
            }
            return FlowConfig(
                version = root.optInt("version", 1),
                jobTimeoutSeconds = root.optInt("jobTimeoutSeconds", 20),
                stepTimeoutSeconds = root.optInt("stepTimeoutSeconds", 8),
                amountLabels = root.optJSONArray("amountLabels").strings().ifEmpty { listOf("amount") },
                forbiddenButtons = root.optJSONArray("forbiddenButtons").strings(),
                targets = targets,
            )
        }

        private fun parseSteps(arr: JSONArray, where: String): List<FlowStep> = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val actionName = o.optString("action").uppercase()
            val action = StepAction.entries.firstOrNull { it.name == actionName }
                ?: throw IllegalArgumentException("$where step ${i + 1}: unknown action \"${o.optString("action")}\"")
            val value = if (action == StepAction.INPUT) {
                when (o.optString("value").lowercase()) {
                    "number" -> InputValue.NUMBER
                    "account" -> InputValue.ACCOUNT
                    else -> throw IllegalArgumentException("$where step ${i + 1}: input value must be \"number\" or \"account\"")
                }
            } else null
            val texts = o.optJSONArray("texts").strings()
            if (action != StepAction.AWAIT_AMOUNT && texts.isEmpty()) {
                throw IllegalArgumentException("$where step ${i + 1}: \"texts\" must list at least one label")
            }
            FlowStep(
                name = o.optString("name").ifBlank { "${action.name.lowercase()} ${texts.firstOrNull().orEmpty()}" },
                action = action,
                texts = texts,
                value = value,
                optional = o.optBoolean("optional", false),
            )
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }.filter { it.isNotBlank() }
    }
}
