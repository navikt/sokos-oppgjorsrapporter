package no.nav.sokos.oppgjorsrapporter.mq

import com.ibm.mq.jms.MQConnectionFactory
import com.ibm.mq.jms.MQQueue
import com.ibm.msg.client.wmq.WMQConstants
import javax.jms.ConnectionFactory
import javax.jms.MessageConsumer
import javax.jms.Session
import javax.jms.TextMessage
import kotlin.let
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig

class MqConsumer(val config: PropertiesConfig.MqProperties) {
    private val logger = KotlinLogging.logger {}

    lateinit var session: Session
    private lateinit var mqConsumer: MessageConsumer
    var uncomittedMessages: Int = 0
    var connected: Boolean = false

    init {
        connect { session, queue -> mqConsumer = session.createConsumer(queue) }
    }

    fun connect(initBlock: (Session, MQQueue) -> Unit) {
        val connectionFactory: ConnectionFactory =
            MQConnectionFactory().apply {
                transportType = WMQConstants.WMQ_CM_CLIENT
                hostName = config.mqHost
                port = config.mqPort
                channel = config.mqChannel
                queueManager = config.mqName
                targetClientMatching = true
                userAuthenticationMQCSP = true
            }

        val connection = connectionFactory.createConnection(config.mqUsername, config.mqPassword)

        session = connection.createSession(Session.SESSION_TRANSACTED)

        logger.info { "Kobler seg til MQ køen ${config.mqName}" }
        val queue = nonJmsQueue(config.mqName)
        initBlock(session, queue)

        connection.start()
        connected = true
    }

    fun commit() {
        session.commit()
        uncomittedMessages = 0
    }

    fun rollback() {
        session.rollback()
        uncomittedMessages = 0
    }

    fun messagesInTransaction() = uncomittedMessages

    private fun nonJmsQueue(queueName: String) =
        (session.createQueue(queueName) as MQQueue).apply { targetClient = WMQConstants.WMQ_CLIENT_NONJMS_MQ }

    fun receiveMessages(maxMessages: Int): List<String> {
        val messages = mutableListOf<String>()
        repeat(maxMessages) {
            val message =
                try {
                    if (!connected) {
                        connect { session, queue -> mqConsumer = session.createConsumer(queue) }
                    }
                    (mqConsumer.receive(100L) as? TextMessage)?.text
                } catch (ex: Exception) {
                    rollback()
                    connected = false
                    throw ex
                }
            message?.let { messages.add(it) } ?: return@repeat
        }
        uncomittedMessages += messages.size
        return messages
    }
}
