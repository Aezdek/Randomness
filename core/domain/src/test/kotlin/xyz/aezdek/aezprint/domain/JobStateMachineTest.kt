package xyz.aezdek.aezprint.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.aezdek.aezprint.domain.job.IllegalJobTransition
import xyz.aezdek.aezprint.domain.job.JobEvent
import xyz.aezdek.aezprint.domain.job.JobStateMachine
import xyz.aezdek.aezprint.domain.model.JobState

class JobStateMachineTest {

    @Test
    fun `manual duplex job walks through the flip step`() {
        val states = listOf(JobEvent.Start, JobEvent.FirstPassDone, JobEvent.Continue, JobEvent.Finish)
            .runningFold(JobState.QUEUED, JobStateMachine::reduce)
        assertEquals(
            listOf(JobState.QUEUED, JobState.PROCESSING, JobState.AWAITING_FLIP, JobState.PROCESSING, JobState.COMPLETED),
            states,
        )
    }

    @Test
    fun `simplex job completes without flipping`() {
        val end = listOf(JobEvent.Start, JobEvent.Finish).fold(JobState.QUEUED, JobStateMachine::reduce)
        assertEquals(JobState.COMPLETED, end)
    }

    @Test
    fun `cancel is allowed from every non terminal state`() {
        for (state in listOf(JobState.QUEUED, JobState.PROCESSING, JobState.AWAITING_FLIP)) {
            assertEquals(JobState.CANCELED, JobStateMachine.reduce(state, JobEvent.Cancel))
            assertTrue(JobStateMachine.canCancel(state))
        }
    }

    @Test
    fun `terminal states accept no events`() {
        for (state in JobState.entries.filter { it.isTerminal }) {
            assertFalse(JobStateMachine.canCancel(state))
            for (event in listOf(JobEvent.Start, JobEvent.Cancel, JobEvent.Continue, JobEvent.Fail("x"))) {
                assertThrows(state, event)
            }
        }
    }

    @Test
    fun `continue is only valid while awaiting a flip`() {
        assertTrue(JobStateMachine.canContinue(JobState.AWAITING_FLIP))
        assertFalse(JobStateMachine.canContinue(JobState.PROCESSING))
        assertThrows(JobState.PROCESSING, JobEvent.Continue)
        assertThrows(JobState.QUEUED, JobEvent.FirstPassDone)
    }

    @Test
    fun `stale polls never move a job backwards`() {
        assertEquals(JobState.COMPLETED, JobStateMachine.merge(JobState.COMPLETED, JobState.PROCESSING))
        assertEquals(JobState.PROCESSING, JobStateMachine.merge(JobState.PROCESSING, JobState.QUEUED))
        assertEquals(JobState.AWAITING_FLIP, JobStateMachine.merge(JobState.PROCESSING, JobState.AWAITING_FLIP))
        assertEquals(JobState.QUEUED, JobStateMachine.merge(null, JobState.QUEUED))
    }

    @Test
    fun `api values round trip`() {
        for (state in JobState.entries) assertEquals(state, JobState.fromApi(state.apiValue))
        assertEquals(null, JobState.fromApi("exploded"))
    }

    private fun assertThrows(state: JobState, event: JobEvent) {
        try {
            JobStateMachine.reduce(state, event)
            throw AssertionError("Expected $event to be rejected in $state")
        } catch (expected: IllegalJobTransition) {
            assertEquals(state, expected.state)
        }
    }
}
