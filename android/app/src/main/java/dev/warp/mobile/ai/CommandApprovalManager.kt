package dev.warp.mobile.ai

import android.content.Context
import dev.warp.mobile.AiUsageTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ApprovalStatus {
    IDLE,
    WAITING_FOR_APPROVAL,
    APPROVED,
    REJECTED,
    AUTO_ALLOWED
}

data class PendingApproval(
    val id: String,
    val command: String,
    val riskLevel: RiskLevel,
    val model: String,
    val callback: (Boolean) -> Unit
)

object CommandApprovalManager {
    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()

    private val _status = MutableStateFlow(ApprovalStatus.IDLE)
    val status: StateFlow<ApprovalStatus> = _status.asStateFlow()

    fun requestApproval(
        context: Context,
        command: String,
        model: String = "claude-3-5-sonnet",
        onDecision: (Boolean) -> Unit
    ) {
        val risk = CommandRiskEvaluator.evaluate(command)
        if (risk == RiskLevel.LOW) {
            _status.value = ApprovalStatus.AUTO_ALLOWED
            AiUsageTracker.recordAudit(
                context = context,
                model = model,
                inputTokens = 0,
                outputTokens = 0,
                latencyMs = 0L,
                commandString = command,
                approvalState = "AUTO_ALLOWED"
            )
            onDecision(true)
            _status.value = ApprovalStatus.IDLE
            return
        }

        val approvalId = "appr_${System.currentTimeMillis()}"
        val pending = PendingApproval(
            id = approvalId,
            command = command,
            riskLevel = risk,
            model = model,
            callback = onDecision
        )

        _status.value = ApprovalStatus.WAITING_FOR_APPROVAL
        _pendingApproval.value = pending
    }

    fun submitDecision(context: Context, approved: Boolean) {
        val current = _pendingApproval.value ?: return
        _pendingApproval.value = null
        val stateStr = if (approved) "APPROVED" else "REJECTED"
        _status.value = if (approved) ApprovalStatus.APPROVED else ApprovalStatus.REJECTED

        AiUsageTracker.recordAudit(
            context = context,
            model = current.model,
            inputTokens = 0,
            outputTokens = 0,
            latencyMs = 0L,
            commandString = current.command,
            approvalState = stateStr
        )

        current.callback(approved)
        _status.value = ApprovalStatus.IDLE
    }
}
