package com.scanpesa.app.automation

import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target

/** A pending auto-fill job. Contains only the numbers to type; never an amount or PIN. */
data class Job(
    val id: Long,
    val type: PaymentType,
    val number: String,
    val account: String?,
    val target: Target,
    val packageName: String,
    val createdAt: Long,
)

/**
 * In-memory hand-off between the app and the accessibility service (same process).
 * Deliberately not persisted: if the process dies the job is dropped, which is the safe outcome.
 */
object JobStore {
    @Volatile var current: Job? = null
        private set

    fun start(job: Job) { current = job }

    fun clear(id: Long? = null) {
        if (id == null || current?.id == id) current = null
    }
}
