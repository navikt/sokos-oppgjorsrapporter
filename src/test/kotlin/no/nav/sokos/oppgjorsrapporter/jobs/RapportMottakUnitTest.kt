package no.nav.sokos.oppgjorsrapporter.jobs

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.config.ApplicationState
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Unit test for RapportMottak using mocked MqConsumer for faster test execution.
 *
 * This test demonstrates:
 * 1. Mocking the MqConsumer to simulate MQ behavior without needing a real container
 * 2. Verifying message parsing logic
 * 3. Testing error handling and rollback scenarios
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RapportMottakUnitTest {

    @Test
    fun `should parse and process messages from mocked MQ consumer`() = runBlocking {
        val mockMqConsumer = mockk<MqConsumer>(relaxed = true)
        val testJson = TestUtil.readFile("refusjon_bestilling.json")

        // Setup mock to return our test message once, then empty list
        every { mockMqConsumer.receiveMessages(any()) } returnsMany listOf(listOf(testJson), emptyList())

        val applicationState = ApplicationState(started = true, alive = true)

        val rapportMottak = RapportMottak(applicationState, mockMqConsumer)

        val job = launch { rapportMottak.start() }

        // Stop after a short delay
        launch {
            delay(500)
            applicationState.alive = false
        }

        withTimeout(2000) { job.join() }

        // Verify MQ operations were called
        verify(atLeast = 1) { mockMqConsumer.receiveMessages(1000) }
        verify { mockMqConsumer.commit() }
    }

    @Test
    fun `should rollback on JSON parsing error`() = runBlocking {
        val mockMqConsumer = mockk<MqConsumer>(relaxed = true)

        // Invalid JSON that will cause parsing to fail
        val invalidJson = """{"invalid": "json without required fields"}"""

        every { mockMqConsumer.receiveMessages(any()) } returnsMany listOf(listOf(invalidJson), emptyList())
        every { mockMqConsumer.messagesInTransaction() } returns 1

        val applicationState = ApplicationState(started = true, alive = true)
        val rapportMottak = RapportMottak(applicationState, mockMqConsumer)

        val job = launch { rapportMottak.start() }

        launch {
            delay(500) // Give it time to attempt processing
            applicationState.alive = false
        }

        withTimeout(2000) { job.join() }

        // Verify rollback was called due to parsing error
        verify(atLeast = 1) { mockMqConsumer.rollback() }
        verify(atLeast = 1) { mockMqConsumer.messagesInTransaction() }
    }

    @Test
    fun `should commit after successful processing`() = runBlocking {
        val mockMqConsumer = mockk<MqConsumer>(relaxed = true)
        val testJson = TestUtil.readFile("refusjon_bestilling.json")

        every { mockMqConsumer.receiveMessages(any()) } returnsMany listOf(listOf(testJson), emptyList())

        val applicationState = ApplicationState(started = true, alive = true)
        val rapportMottak = RapportMottak(applicationState, mockMqConsumer)

        val job = launch { rapportMottak.start() }

        launch {
            delay(500)
            applicationState.alive = false
        }

        withTimeout(2000) { job.join() }

        // Verify commit was called after successful processing
        verify(exactly = 1) { mockMqConsumer.commit() }
        verify(exactly = 0) { mockMqConsumer.rollback() }
    }

    @Test
    fun `should handle empty queue gracefully`() = runBlocking {
        val mockMqConsumer = mockk<MqConsumer>(relaxed = true)

        // Always return empty list
        every { mockMqConsumer.receiveMessages(any()) } returns emptyList()

        val applicationState = ApplicationState(started = true, alive = true)
        val rapportMottak = RapportMottak(applicationState, mockMqConsumer)

        val job = launch { rapportMottak.start() }

        launch {
            delay(200) // Give it time to poll a few times
            applicationState.alive = false
        }

        withTimeout(2000) { job.join() }

        // Should poll but never commit or rollback since no messages
        verify(atLeast = 1) { mockMqConsumer.receiveMessages(1000) }
        verify(exactly = 0) { mockMqConsumer.commit() }
        verify(exactly = 0) { mockMqConsumer.rollback() }
    }
}
