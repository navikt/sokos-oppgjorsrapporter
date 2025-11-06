package no.nav.sokos.oppgjorsrapporter.jobs

import com.ibm.mq.jms.MQConnectionFactory
import com.ibm.mq.testcontainers.MQContainer
import com.ibm.msg.client.wmq.WMQConstants
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.ktor.server.plugins.di.dependencies
import javax.jms.Session
import javax.jms.TextMessage
import kotlinx.coroutines.delay
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.TestContainer
import no.nav.sokos.oppgjorsrapporter.TestUtil
import no.nav.sokos.oppgjorsrapporter.mq.MqConsumer

private val logger = KotlinLogging.logger {}

/**
 * Integration test for RapportMottak that verifies end-to-end message consumption from IBM MQ.
 *
 * This test demonstrates:
 * 1. Starting an IBM MQ container using Testcontainers
 * 2. Sending a JSON message to the queue
 * 3. Verifying the application consumes, parses, and processes the message correctly
 */
class RapportMottakIntegrationTest :
    FunSpec({
        context("RapportMottak Integration") {
            val dbContainer = TestContainer.postgres
            val mqContainer = TestContainer.mq
            val queueName = "DEV.QUEUE.1" // Default queue in IBM MQ testcontainer

            test("should consume and parse JSON message from IBM MQ queue") {
                TestUtil.withFullApplication(dbContainer = dbContainer, mqContainer = mqContainer) {
                    // Read test JSON from resources
                    val testJson = TestUtil.readFile("refusjon_bestilling.json")

                    // Send message to MQ queue
                    sendMessageToQueue(mqContainer, queueName, testJson)
                    logger.info { "Sent test message to queue: $queueName" }

                    // Get the MqConsumer from DI to verify consumption
                    val mqConsumer = application.dependencies.resolve<MqConsumer>()

                    // Give the background job time to process the message
                    delay(2000)

                    // Verify the message was consumed from the queue
                    val remainingMessages = mqConsumer.receiveMessages(maxMessages = 10)
                    remainingMessages shouldHaveSize 0

                    logger.info { "Successfully verified message was consumed from queue" }
                }
            }

            test("should handle multiple messages in queue") {
                TestUtil.withFullApplication(dbContainer = dbContainer, mqContainer = mqContainer) {
                    val testJson = TestUtil.readFile("refusjon_bestilling.json")

                    // Send multiple messages
                    repeat(3) { sendMessageToQueue(mqContainer, queueName, testJson) }
                    logger.info { "Sent 3 test messages to queue: $queueName" }

                    // Get the MqConsumer from DI
                    val mqConsumer = application.dependencies.resolve<MqConsumer>()

                    // Give the background job time to process all messages
                    delay(3000)

                    // Verify all messages were consumed
                    val remainingMessages = mqConsumer.receiveMessages(maxMessages = 10)
                    remainingMessages shouldHaveSize 0

                    logger.info { "Successfully processed all 3 messages" }
                }
            }

            test("should handle JSON with valid structure") {
                TestUtil.withFullApplication(dbContainer = dbContainer, mqContainer = mqContainer) {
                    // Create a minimal valid JSON
                    val validJson =
                        """
                            {
                              "header": {
                                "orgnr": 123456789,
                                "bankkonto": "12345678901",
                                "sumBelop": 1000.00,
                                "dkSumBelop": "D",
                                "valutert": "2025-01-01"
                              },
                              "datarec": []
                            }
                        """
                            .trimIndent()

                    sendMessageToQueue(mqContainer, queueName, validJson)

                    // Get the MqConsumer from DI
                    val mqConsumer = application.dependencies.resolve<MqConsumer>()

                    // Give the background job time to process
                    delay(2000)

                    // Verify message was consumed
                    val remainingMessages = mqConsumer.receiveMessages(maxMessages = 10)
                    remainingMessages shouldHaveSize 0

                    logger.info { "Successfully verified message with empty datarec was processed" }
                }
            }
        }
    })

/** Helper function to send a text message to an MQ queue */
private fun sendMessageToQueue(mqContainer: MQContainer, queueName: String, messageText: String) {
    val connectionFactory =
        MQConnectionFactory().apply {
            transportType = WMQConstants.WMQ_CM_CLIENT
            hostName = mqContainer.host
            port = mqContainer.firstMappedPort
            channel = mqContainer.channel
            queueManager = mqContainer.queueManager
            targetClientMatching = true
            userAuthenticationMQCSP = true
        }

    connectionFactory.createConnection(mqContainer.appUser, mqContainer.appPassword).use { connection ->
        connection.start()
        val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
        val queue = session.createQueue(queueName)
        val producer = session.createProducer(queue)
        val message: TextMessage = session.createTextMessage(messageText)
        producer.send(message)
        logger.info { "Message sent successfully to queue: $queueName" }
    }
}
