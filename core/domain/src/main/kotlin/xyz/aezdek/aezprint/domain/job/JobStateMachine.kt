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
     * Merges a polled state into what we already know. Polls can skip states (a short job goes from
     * queued to completed between two polls) or arrive late on a flaky connection, so anything moves a
     * job forward except going back to queued or leaving a terminal state.
     */
    fun merge(current: JobState?, observed: JobState): JobState = when {
        current == null -> observed
        current.isTerminal -> current
        observed == QUEUED && current != QUEUED -> current
        else -> observed
    }
}

class IllegalJobTransition(val state: JobState, val event: JobEvent) :
    IllegalStateException("Cannot apply $event to a job that is ${state.apiValue}")
