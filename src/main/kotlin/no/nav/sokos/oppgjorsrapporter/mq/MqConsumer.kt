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
import no.nav.sokos.oppgjorsrapporter.metrics.Metrics

class MqConsumer(private val config: PropertiesConfig.MqProperties, private val queueName: String, private val metrics: Metrics) {
    private val logger = KotlinLogging.logger {}

    private lateinit var session: Session
    private lateinit var mqConsumer: MessageConsumer
    private var connected: Boolean = false

    init {
        connect()
    }

    fun connect() {
        val connection = config.connect()
        session = connection.createSession(Session.SESSION_TRANSACTED)

        logger.info { "Connecting to MQ queue $queueName" }
        val queue = nonJmsQueue(queueName)
        mqConsumer = session.createConsumer(queue)

        connection.start()
        connected = true
    }

    private fun nonJmsQueue(queueName: String) =
        (session.createQueue(queueName) as MQQueue).apply { targetClient = WMQConstants.WMQ_CLIENT_NONJMS_MQ }

    fun commit() = session.commit()

    fun rollback() = session.rollback()

    fun receive(): Melding? {
        try {
            if (!connected) connect()
            return when (val message = mqConsumer.receive(0.5.seconds.inWholeMilliseconds)) {
                is TextMessage -> {
                    message.text?.let {
                        metrics.tellMottak(message, queueName)
                        Melding("MQ manager=${config.managerName} queue=$queueName", it)
                    }
                }
                else -> {
                    message?.let { metrics.tellMottak(it, queueName) }
                    null
                }
            }
        } catch (ex: Exception) {
            connected = false
            throw ex
        }
    }
}

data class Melding(val kilde: String, val data: String)

fun PropertiesConfig.MqProperties.connect(): Connection =
    this.let { cfg ->
        MQConnectionFactory()
            .apply {
                transportType = WMQConstants.WMQ_CM_CLIENT
                hostName = cfg.host
                port = cfg.port
                channel = cfg.channel
                queueManager = cfg.managerName
                targetClientMatching = true
                userAuthenticationMQCSP = true
            }
            .createConnection(username, password)
    }
