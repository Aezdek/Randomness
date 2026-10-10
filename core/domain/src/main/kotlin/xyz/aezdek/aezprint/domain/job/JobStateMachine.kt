package xyz.aezdek.aezprint.domain.job

import xyz.aezdek.aezprint.domain.model.JobState
import xyz.aezdek.aezprint.domain.model.JobState.AWAITING_FLIP
import xyz.aezdek.aezprint.domain.model.JobState.CANCELED
import xyz.aezdek.aezprint.domain.model.JobState.COMPLETED
import xyz.aezdek.aezprint.domain.model.JobState.FAILED
import xyz.aezdek.aezprint.domain.model.JobState.PROCESSING
import xyz.aezdek.aezprint.domain.model.JobState.QUEUED

sealed interface JobEvent {
    data object Start : JobEvent
    data object FirstPassDone : JobEvent
    data object Continue : JobEvent
    data object Finish : JobEvent
    data object Cancel : JobEvent
    data class Fail(val reason: String) : JobEvent
}

/**
 * The lifecycle of a manual-duplex job:
 * queued → processing → (awaiting_flip → processing)? → completed, with cancel/fail allowed from any
 * non-terminal state.
 */
object JobStateMachine {

    private val transitions: Map<JobState, Set<JobState>> = mapOf(
        QUEUED to setOf(PROCESSING, CANCELED, FAILED),
        PROCESSING to setOf(AWAITING_FLIP, COMPLETED, CANCELED, FAILED),
        AWAITING_FLIP to setOf(PROCESSING, CANCELED, FAILED),
        COMPLETED to emptySet(),
        CANCELED to emptySet(),
        FAILED to emptySet(),
    )

    fun canTransition(from: JobState, to: JobState): Boolean = from == to || to in transitions.getValue(from)

    fun reduce(state: JobState, event: JobEvent): JobState {
        val next = when (event) {
            JobEvent.Start -> PROCESSING.takeIf { state == QUEUED }
            JobEvent.FirstPassDone -> AWAITING_FLIP.takeIf { state == PROCESSING }
            JobEvent.Continue -> PROCESSING.takeIf { state == AWAITING_FLIP }
            JobEvent.Finish -> COMPLETED.takeIf { state == PROCESSING }
            JobEvent.Cancel -> CANCELED.takeIf { !state.isTerminal }
            is JobEvent.Fail -> FAILED.takeIf { !state.isTerminal }
        }
        return next ?: throw IllegalJobTransition(state, event)
    }

    fun canCancel(state: JobState): Boolean = !state.isTerminal

    fun canContinue(state: JobState): Boolean = state == AWAITING_FLIP

    /**
     * Polls can arrive out of order on a flaky connection; a stale response must never move a job
     * backwards, so only forward transitions are accepted.
     */
    fun merge(current: JobState?, observed: JobState): JobState = when {
        current == null -> observed
        canTransition(current, observed) -> observed
        else -> current
    }
}

class IllegalJobTransition(val state: JobState, val event: JobEvent) :
    IllegalStateException("Cannot apply $event to a job that is ${state.apiValue}")
