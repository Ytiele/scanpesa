package com.scanpesa.app.ui

import android.app.Application
import android.graphics.Bitmap
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

    /** Candidates from the last scan (more than one => user picks). */
    val candidates = MutableStateFlow<List<Candidate>>(emptyList())
    val frozenFrame = MutableStateFlow<Bitmap?>(null)
    val draft = MutableStateFlow<Draft?>(null)

    fun onScanned(found: List<Candidate>, frame: Bitmap?) {
        candidates.value = found
        frozenFrame.value = frame
        draft.value = found.singleOrNull()?.toDraft()
    }

    fun pick(c: Candidate) { draft.value = c.toDraft() }

    /** The last photo read (camera shutter or gallery), shown on the "Tap the number" screen. */
    val photo = MutableStateFlow<PhotoScan?>(null)

    fun onPhotoRead(p: PhotoScan) {
        photo.value = p
        candidates.value = emptyList()
        draft.value = null
    }

    /** The user tapped / confirmed a number on the photo. */
    fun pickFromPhoto(c: Candidate) {
        frozenFrame.value = photo.value?.bitmap
        candidates.value = emptyList()
        draft.value = c.toDraft()
    }

    fun startManual() {
        candidates.value = emptyList()
        frozenFrame.value = null
        draft.value = Draft(PaymentType.PAYBILL, "", "", typeConfirmed = true)
    }

    fun reuse(e: ScanEntity) {
        candidates.value = emptyList()
        frozenFrame.value = null
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
