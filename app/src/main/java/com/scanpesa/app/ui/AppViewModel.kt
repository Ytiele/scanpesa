package com.scanpesa.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.scanpesa.app.automation.FlowRepository
import com.scanpesa.app.automation.Job
import com.scanpesa.app.automation.JobStore
import com.scanpesa.app.automation.ScanPesaAccessibilityService
import com.scanpesa.app.automation.TargetLauncher
import com.scanpesa.app.data.AppDatabase
import com.scanpesa.app.data.Prefs
import com.scanpesa.app.data.ScanEntity
import com.scanpesa.app.model.Candidate
import com.scanpesa.app.model.PaymentType
import com.scanpesa.app.model.Target
import com.scanpesa.app.parser.PaymentValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Values shown on the confirmation screen. */
data class Draft(
    val type: PaymentType,
    val number: String,
    val account: String,
    /** false = only a phone was found; ask "Send Money or Pochi la Biashara?" */
    val typeConfirmed: Boolean,
)

sealed interface GoResult {
    data object Launched : GoResult
    data class Error(val message: String) : GoResult
    /**
     * Accessibility service is off (or switched on but killed by the phone); the UI offers to fix
     * it or open the app without auto-fill.
     */
    data class NeedsService(val enabledButNotRunning: Boolean) : GoResult
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    private val dao = AppDatabase.get(app).scans()

    val history: StateFlow<List<ScanEntity>> =
        dao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** The current payment, decided automatically from the photo (or reused from History). */
    val draft = MutableStateFlow<Draft?>(null)
    /** The photo it was read from (null when reused from History) and the boxes to highlight. */
    val photo = MutableStateFlow<PhotoScan?>(null)
    val highlight = MutableStateFlow<List<com.scanpesa.app.parser.Box>>(emptyList())

    /** A photo was read: decide the payment with no user choice. */
    fun onPhotoRead(p: PhotoScan) {
        photo.value = p
        val pick = com.scanpesa.app.parser.PhotoTokens.autoPick(p.tokens, p.candidates, p.text)
        draft.value = pick?.candidate?.toDraft()
        highlight.value = listOfNotNull(pick?.numberBox, pick?.accountBox)
    }

    fun reuse(e: ScanEntity) {
        photo.value = null
        highlight.value = emptyList()
        draft.value = Draft(PaymentType.fromName(e.type) ?: PaymentType.SEND_MONEY, e.number, e.account.orEmpty(), true)
    }

    fun clearHistory() = viewModelScope.launch { dao.clear() }

    private fun Candidate.toDraft() = Draft(type, number, account.orEmpty(), typeConfirmed)

    /**
     * Validates, saves to history, hands the job to the accessibility service and opens the target.
     * @param autofill false = just open the app (used when the service is off and the user accepts that).
     */
    fun go(type: PaymentType, rawNumber: String, rawAccount: String, target: Target, autofill: Boolean = true): GoResult {
        val ctx = getApplication<Application>()
        val account = if (type == PaymentType.PAYBILL) PaymentValidator.cleanAccount(rawAccount) else null
        PaymentValidator.validate(type, rawNumber, account)?.let { return GoResult.Error(it) }
        val number = PaymentValidator.cleanNumber(type, rawNumber)

        val pkg = TargetLauncher.resolvePackage(ctx, target, prefs.simSlot)
        if (pkg == null) {
            val tried = FlowRepository.get(ctx).targets[target]?.let { it.packages + it.packagesSim2 }?.distinct()?.joinToString()
            return GoResult.Error("${target.label} was not found on this phone (looked for: $tried). Check the package name in flows.json.")
        }
        if (autofill) {
            val enabled = TargetLauncher.isServiceEnabled(ctx)
            if (!enabled || ScanPesaAccessibilityService.instance == null) {
                return GoResult.NeedsService(enabledButNotRunning = enabled)
            }
        }

        prefs.lastTarget = target
        val now = System.currentTimeMillis()
        viewModelScope.launch {
            dao.insertAndTrim(ScanEntity(type = type.name, number = number, account = account, target = target.name, createdAt = now))
        }
        if (autofill) JobStore.start(Job(now, type, number, account, target, pkg, now)) else JobStore.clear()
        if (!TargetLauncher.launch(ctx, target, pkg)) {
            JobStore.clear()
            return GoResult.Error("Could not open ${target.label} ($pkg)")
        }
        if (autofill) ScanPesaAccessibilityService.instance?.onJobStarted()
        return GoResult.Launched
    }
}
