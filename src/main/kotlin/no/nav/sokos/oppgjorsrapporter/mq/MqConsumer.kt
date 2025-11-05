package no.nav.sokos.oppgjorsrapporter.mq

import com.ibm.mq.jms.MQConnectionFactory
import com.ibm.mq.jms.MQQueue
import com.ibm.msg.client.wmq.WMQConstants
import javax.jms.Connection
import javax.jms.MessageConsumer
import javax.jms.Session
import javax.jms.TextMessage
import kotlin.time.Duration.Companion.seconds
import mu.KotlinLogging
import no.nav.sokos.oppgjorsrapporter.config.PropertiesConfig

class MqConsumer(private val config: PropertiesConfig.MqProperties, private val queueName: String) {
    private val logger = KotlinLogging.logger {}

    private lateinit var session: Session
    private lateinit var mqConsumer: MessageConsumer
    private var connected: Boolean = false

    init {
        connect()
    }

    private fun PropertiesConfig.MqProperties.connect(): Connection =
        MQConnectionFactory()
            .apply {
                this.transportType = WMQConstants.WMQ_CM_CLIENT
                this.hostName = config.host
                this.port = config.port
                this.channel = config.channel
                this.queueManager = config.managerName
                this.targetClientMatching = true
                this.userAuthenticationMQCSP = true
            }
            .createConnection(config.username, config.password)

    fun connect() {
        val connection = config.connect()
        session = connection.createSession(Session.SESSION_TRANSACTED)

        logger.info("Connecting to MQ queue $queueName")
        val queue = nonJmsQueue(queueName)
        mqConsumer = session.createConsumer(queue)

        connection.start()
        connected = true
    }

    private fun nonJmsQueue(queueName: String) =
        (session.createQueue(queueName) as MQQueue).apply { targetClient = WMQConstants.WMQ_CLIENT_NONJMS_MQ }

    fun commit() = session.commit()

    fun rollback() = session.rollback()

    fun receive(): String? {
        try {
            if (!connected) connect()
            return when (val message = mqConsumer.receive(0.5.seconds.inWholeMilliseconds)) {
                is TextMessage ->
                    message.text.also {
                        //   Metrics.orderCounter.inc()
                    }
                else -> null
            }
        } catch (ex: Exception) {
            connected = false
            throw ex
        }
    }
}
