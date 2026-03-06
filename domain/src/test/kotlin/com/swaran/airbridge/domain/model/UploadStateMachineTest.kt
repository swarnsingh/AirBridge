package com.swaran.airbridge.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for UploadState state machine transitions - Protocol v2.1
 *
 * Tests verify that state transitions follow the defined rules:
 * - Invalid transitions return false
 * - Valid transitions return true
 * - Terminal states have no outgoing transitions (COMPLETED, CANCELLED, ERROR)
 * - All 9 states are correctly defined with their string values
 */
class UploadStateMachineTest {

    @Test
    fun `state values are correctly defined`() {
        assertEquals("none", UploadState.NONE.value)
        assertEquals("queued", UploadState.QUEUED.value)
        assertEquals("resuming", UploadState.RESUMING.value)
        assertEquals("uploading", UploadState.UPLOADING.value)
        assertEquals("pausing", UploadState.PAUSING.value)
        assertEquals("paused", UploadState.PAUSED.value)
        assertEquals("completed", UploadState.COMPLETED.value)
        assertEquals("cancelled", UploadState.CANCELLED.value)
        assertEquals("error", UploadState.ERROR.value)
    }

    @Test
    fun `none can transition to queued, uploading, or cancelled`() {
        assertTrue(UploadState.NONE.canTransitionTo(UploadState.QUEUED))
        assertTrue(UploadState.NONE.canTransitionTo(UploadState.UPLOADING))
        assertTrue(UploadState.NONE.canTransitionTo(UploadState.CANCELLED))
        assertFalse(UploadState.NONE.canTransitionTo(UploadState.PAUSED))
    }

    @Test
    fun `queued can transition to resuming, uploading, or cancelled`() {
        assertTrue(UploadState.QUEUED.canTransitionTo(UploadState.RESUMING))
        assertTrue(UploadState.QUEUED.canTransitionTo(UploadState.UPLOADING))
        assertTrue(UploadState.QUEUED.canTransitionTo(UploadState.CANCELLED))
        assertFalse(UploadState.QUEUED.canTransitionTo(UploadState.PAUSED))
        assertFalse(UploadState.QUEUED.canTransitionTo(UploadState.COMPLETED))
    }

    @Test
    fun `resuming can transition to uploading, pausing, paused, or cancelled`() {
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.UPLOADING))
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.PAUSING))
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.PAUSED))
        assertTrue(UploadState.RESUMING.canTransitionTo(UploadState.CANCELLED))
        assertFalse(UploadState.RESUMING.canTransitionTo(UploadState.QUEUED))
        assertFalse(UploadState.RESUMING.canTransitionTo(UploadState.COMPLETED))
    }

    @Test
    fun `uploading can transition to pausing, paused, completed, cancelled, or error`() {
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.PAUSING))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.PAUSED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.COMPLETED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.UPLOADING.canTransitionTo(UploadState.ERROR))
        assertFalse(UploadState.UPLOADING.canTransitionTo(UploadState.QUEUED))
        assertFalse(UploadState.UPLOADING.canTransitionTo(UploadState.RESUMING))
    }

    @Test
    fun `pausing can transition to paused, cancelled, or error`() {
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.PAUSED))
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.PAUSING.canTransitionTo(UploadState.ERROR))
        assertFalse(UploadState.PAUSING.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.PAUSING.canTransitionTo(UploadState.RESUMING))
    }

    @Test
    fun `paused can transition to resuming, uploading, or cancelled`() {
        assertTrue(UploadState.PAUSED.canTransitionTo(UploadState.RESUMING))
        assertTrue(UploadState.PAUSED.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.PAUSED.canTransitionTo(UploadState.UPLOADING)) // Late POST resume
        assertFalse(UploadState.PAUSED.canTransitionTo(UploadState.PAUSING))
        assertFalse(UploadState.PAUSED.canTransitionTo(UploadState.COMPLETED))
    }

    @Test
    fun `completed is terminal - no transitions allowed`() {
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.PAUSED))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.RESUMING))
        assertFalse(UploadState.COMPLETED.canTransitionTo(UploadState.CANCELLED))
        assertTrue(UploadState.COMPLETED.isTerminal)
    }

    @Test
    fun `cancelled is terminal - no transitions allowed`() {
        assertFalse(UploadState.CANCELLED.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.CANCELLED.canTransitionTo(UploadState.PAUSED))
        assertFalse(UploadState.CANCELLED.canTransitionTo(UploadState.QUEUED))
        assertTrue(UploadState.CANCELLED.isTerminal)
    }

    @Test
    fun `error is terminal - no transitions allowed`() {
        assertFalse(UploadState.ERROR.canTransitionTo(UploadState.UPLOADING))
        assertFalse(UploadState.ERROR.canTransitionTo(UploadState.PAUSED))
        assertFalse(UploadState.ERROR.canTransitionTo(UploadState.QUEUED))
        assertTrue(UploadState.ERROR.isTerminal)
    }

    @Test
    fun `same state transition always allowed`() {
        // All states can "transition" to themselves (idempotent)
        UploadState.entries.forEach { state ->
            assertTrue("$state should allow transition to itself", 
                state.canTransitionTo(state))
        }
    }

    @Test
    fun `full upload lifecycle - success`() {
        // Simulate a successful upload lifecycle
        var state = UploadState.NONE
        
        // NONE -> QUEUED
        assertTrue(state.canTransitionTo(UploadState.QUEUED))
        state = UploadState.QUEUED
        
        // QUEUED -> UPLOADING
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
        state = UploadState.UPLOADING
        
        // UPLOADING -> COMPLETED
        assertTrue(state.canTransitionTo(UploadState.COMPLETED))
        state = UploadState.COMPLETED
        
        // Terminal - no more transitions
        assertTrue(state.isTerminal)
    }

    @Test
    fun `full upload lifecycle - with pause and resume`() {
        var state = UploadState.NONE
        
        // Start
        assertTrue(state.canTransitionTo(UploadState.QUEUED))
        state = UploadState.QUEUED
        
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
        state = UploadState.UPLOADING
        
        // Pause
        assertTrue(state.canTransitionTo(UploadState.PAUSING))
        state = UploadState.PAUSING
        
        assertTrue(state.canTransitionTo(UploadState.PAUSED))
        state = UploadState.PAUSED
        
        // Resume
        assertTrue(state.canTransitionTo(UploadState.RESUMING))
        state = UploadState.RESUMING
        
        assertTrue(state.canTransitionTo(UploadState.UPLOADING))
        state = UploadState.UPLOADING
        
        // Complete
        assertTrue(state.canTransitionTo(UploadState.COMPLETED))
        state = UploadState.COMPLETED
        
        assertTrue(state.isTerminal)
    }

    @Test
    fun `upload lifecycle - cancelled`() {
        var state = UploadState.UPLOADING
        
        // Cancel from uploading
        assertTrue(state.canTransitionTo(UploadState.CANCELLED))
        state = UploadState.CANCELLED
        
        assertTrue(state.isTerminal)
    }

    @Test
    fun `upload lifecycle - error`() {
        var state = UploadState.UPLOADING
        
        // Error during upload
        assertTrue(state.canTransitionTo(UploadState.ERROR))
        state = UploadState.ERROR
        
        assertTrue(state.isTerminal)
    }

    @Test
    fun `terminal states list is correct`() {
        val terminalStates = UploadState.entries.filter { it.isTerminal }
        
        assertEquals(3, terminalStates.size)
        assertTrue(terminalStates.contains(UploadState.COMPLETED))
        assertTrue(terminalStates.contains(UploadState.CANCELLED))
        assertTrue(terminalStates.contains(UploadState.ERROR))
    }

    @Test
    fun `non-terminal states list is correct`() {
        val nonTerminalStates = UploadState.entries.filter { !it.isTerminal }
        
        assertEquals(6, nonTerminalStates.size)
        assertTrue(nonTerminalStates.contains(UploadState.NONE))
        assertTrue(nonTerminalStates.contains(UploadState.QUEUED))
        assertTrue(nonTerminalStates.contains(UploadState.RESUMING))
        assertTrue(nonTerminalStates.contains(UploadState.UPLOADING))
        assertTrue(nonTerminalStates.contains(UploadState.PAUSING))
        assertTrue(nonTerminalStates.contains(UploadState.PAUSED))
    }

    @Test
    fun `all states have non-empty string values`() {
        UploadState.entries.forEach { state ->
            assertTrue("$state should have non-empty value", 
                state.value.isNotBlank())
        }
    }
}
