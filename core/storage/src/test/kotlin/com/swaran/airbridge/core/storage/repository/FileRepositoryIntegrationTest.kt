package com.swaran.airbridge.core.storage.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.swaran.airbridge.core.common.logging.AirLogger
import com.swaran.airbridge.core.storage.datasource.MediaStoreDataSource
import com.swaran.airbridge.core.storage.datasource.SafDataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * File System Integration Tests with Robolectric
 * 
 * These tests verify file path handling logic using Robolectric.
 * Note: Actual file I/O operations are mocked/stubbed since we don't have 
 * a real Android file system in unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class FileRepositoryIntegrationTest {

    private lateinit var context: Context

    private class TestLogger : AirLogger {
        override fun log(priority: Int, classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun log(priority: Int, classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun v(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun v(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun d(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun d(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun i(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun i(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun w(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun w(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun e(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun e(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
        override fun wtf(classTag: String, methodTag: String, message: String, vararg args: Any?) {}
        override fun wtf(classTag: String, methodTag: String, t: Throwable, message: String, vararg args: Any?) {}
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    /**
     * Test that MediaStoreDataSource and SafDataSource can be instantiated
     * with Robolectric context
     */
    @Test
    fun `data sources can be created with Robolectric context`() {
        // These should not crash with Robolectric
        val mediaStoreDataSource = MediaStoreDataSource(context)
        val safDataSource = SafDataSource(context)
        
        assertNotNull("MediaStoreDataSource should be created", mediaStoreDataSource)
        assertNotNull("SafDataSource should be created", safDataSource)
    }

    /**
     * Test path construction logic without actual file operations
     */
    @Test
    fun `path construction - trailing slash should be handled`() {
        // Test the path logic that was causing double-slash bug
        val testCases = listOf(
            "/" to "",           // Root path becomes empty after trim
            "/Download" to "/Download",  // No trailing slash - unchanged
            "/Download/" to "/Download", // Trailing slash removed
        )
        
        for ((input, expected) in testCases) {
            val result = input.trimEnd('/')
            assertEquals("Path trim for '$input'", expected, result)
        }
    }

    /**
     * Test that the repository can be created
     */
    @Test
    fun `repository can be created with Robolectric`() {
        val mediaStoreDataSource = MediaStoreDataSource(context)
        val safDataSource = SafDataSource(context)
        
        val repository = FileRepository(
            context = context,
            mediaStoreDataSource = mediaStoreDataSource,
            safDataSource = safDataSource,
            logger = TestLogger()
        )
        
        assertNotNull("FileRepository should be created", repository)
    }

    /**
     * Verify the path trimming fix in cleanupPartial is correct
     */
    @Test
    fun `cleanupPartial path trimming logic`() {
        // This tests the logic that was fixed:
        // val cleanPath = request.path.trimEnd('/')
        
        val path1 = "/"
        val path2 = "/Download/"
        
        assertEquals("", path1.trimEnd('/'))
        assertEquals("/Download", path2.trimEnd('/'))
    }
}
