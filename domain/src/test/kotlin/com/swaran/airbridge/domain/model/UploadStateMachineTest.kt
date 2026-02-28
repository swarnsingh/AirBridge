package com.swaran.airbridge.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for UploadState machine transitions.
 *
 * Tests verify that state transitions follow the defined rules:
 * - Invalid transitions return false
 * - Valid transitions return true
 * - Terminal states have no outgoing transitions
 */
class UploadStateMachineTest {

    @Test
    fun `queued can transition to uploading or cancelled`() {
        assertTrue(UploadState.QUEUED.canTransitionTo(UploadState.UPLOADING))
        assertTrue(UploadState.QUEUED.canTransitionTo(UploadState.CANCELLED))
        assertFalse(UploadState.QUEUED.canTransitionTo(UploadState.PAUSED))
    }

    @Test
    fun `uploading can transition to multiple states`() {
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.PAUSING))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.COMPLETED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.ERROR_RETRYABLE))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.ERROR_PERMANENT))
        
        assertFalse(UploadState.UPLOADING.canTransitionTo(UploadState.QUEUED))
        assertFalse(UploadState.UPLOADING.canTransitionTo(UploadState.UPLOADING))
    }

    @Test
    fun `pausing can transition to paused or cancelled`() {
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.PAUSED))
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.ERROR_RETRYABLE))
        
        assertFalse(UploadState.PAUSING.canTransitionTo(UploadState.UPLOADING))
    }

    @Test
    fun `paused can transition to resuming or cancelled`() {
        assertTrue(UploadState.PAUSED.canTransitionTo(UploadState.RESUMING))
        assertTrue(UploadState.PAUSED.canTransitionTo(UploadState.CANCELLED))
        
        assertFalse(UploadState.PAUSED.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.PAUSED.canTransitionTo(UploadState.PAUSING))
    }

    @Test
    fun `resuming can transition to uploading or error`() {
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.UPLOADING))
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.ERROR_RETRYABLE))
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.CANCELLED))
        
        assertFalse(UploadState.RESUMING.canTransitionTo(UploadState.PAUSED))
    }

    @Test
    fun `completed is terminal - no transitions allowed`() {
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.PAUSED))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.CANCELLED))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.ERROR_RETRYABLE))
        
        assertTrue(UploadState.COMPLETED.isTerminal())
    }

    @Test
    fun `cancelled is terminal - no transitions allowed`() {
        assertFalse(UploadState.CANCELLED.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.CANCELLED.canTransitionTo(UploadState.PAUSED))
        
        assertTrue(UploadState.CANCELLED.isTerminal())
    }

    @Test
    fun `error_permanent is terminal - no transitions allowed`() {
        assertFalse(UploadState.ERROR_PERMANENT.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.ERROR_PERMANENT.canTransitionTo(UploadState.QUEUED))
        
        assertTrue(UploadState.ERROR_PERMANENT.isTerminal())
    }

    @Test
    fun `error_retryable can be retried via queued`() {
        assertTrue(UploadState.ERROR_RETRYABLE.canTransitionTo(UploadState.QUEUED))
        assertTrue(UploadState.ERROR_RETRYABLE.canTransitionTo(UploadState.CANCELLED))
        
        assertFalse(UploadState.ERROR_RETRYABLE.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.ERROR_RETRYABLE.isTerminal())
    }

    @Test
    fun `full upload lifecycle transitions`() {
        // Simulate a successful upload lifecycle
        var state = UploadState.QUEUED
        
        // QUEUED -> UPLOADING
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
        state = UploadState.UPLOADING
        
        // UPLOADING -> COMPLETED
        assertTrue(state.canTransitionTo(UploadState.COMPLETED))
        state = UploadState.COMPLETED
        
        // Terminal - no more transitions
        assertTrue(state.isTerminal())
    }

    @Test
    fun `pause resume lifecycle transitions`() {
        var state = UploadState.UPLOADING
        
        // UPLOADING -> PAUSING
        assertTrue(state.canTransitionTo(UploadState.PAUSING))
        state = UploadState.PAUSING
        
        // PAUSING -> PAUSED
        assertTrue(state.canTransitionTo(UploadState.PAUSED))
        state = UploadState.PAUSED
        
        // PAUSED -> RESUMING
        assertTrue(state.canTransitionTo(UploadState.RESUMING))
        state = UploadState.RESUMING
        
        // RESUMING -> UPLOADING
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
        state = UploadState.UPLOADING
        
        // UPLOADING -> COMPLETED
        assertTrue(state.canTransitionTo(UploadState.COMPLETED))
    }

    @Test
    fun `error recovery lifecycle`() {
        var state = UploadState.UPLOADING
        
        // UPLOADING encounters error
        assertTrue(state.canTransitionTo(UploadState.ERROR_RETRYABLE))
        state = UploadState.ERROR_RETRYABLE
        
        // Retry: ERROR_RETRYABLE -> QUEUED
        assertTrue(state.canTransitionTo(UploadState.QUEUED))
        state = UploadState.QUEUED
        
        // Start again
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
    }

    @Test
    fun `all terminal states are correctly identified`() {
        val terminalStates = listOf(
            UploadState.COMPLETED,
            UploadState.CANCELLED,
            UploadState.ERROR_PERMANENT
        )
        
        val nonTerminalStates = listOf(
            UploadState.QUEUED,
            UploadState.UPLOADING,
            UploadState.PAUSING,
            UploadState.PAUSED,
            UploadState.RESUMING,
            UploadState.ERROR_RETRYABLE
        )
        
        terminalStates.forEach { state ->
            assertTrue("$state should be terminal", state.isTerminal())
        }
        
        nonTerminalStates.forEach { state ->
            assertFalse("$state should not be terminal", state.isTerminal())
        }
    }
}

/**
 * Extension function for terminal state check (mirrors UploadStatus implementation)
 */
private fun UploadState.isTerminal(): Boolean {
    return this in setOf(UploadState.COMPLETED, UploadState.CANCELLED, UploadState.ERROR_PERMANENT)
}