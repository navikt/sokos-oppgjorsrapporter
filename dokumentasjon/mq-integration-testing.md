# MQ Integration Testing with IBM MQ Test Container

## Overview

This document describes the complete MQ integration testing setup for `sokos-oppgjorsrapporter`, including how the test infrastructure works, how messages flow through the system, and the challenges encountered during implementation.

## Architecture

### Components

The MQ integration testing involves several key components:

1. **IBM MQ Test Container** - Dockerized IBM MQ instance with pre-configured queues
2. **MqConsumer** - Connects to MQ and consumes messages from a queue
3. **RapportMottak** - Background job that processes messages continuously
4. **Test Configuration** - Overrides production config to point to test container
5. **Integration Tests** - Verify end-to-end message flow

### Message Flow

```
Test → Send Message to Queue → MQ Container → RapportMottak Job → MqConsumer → Process Message → Commit
```

## Test Infrastructure Setup

### 1. Test Container (`TestContainer.kt`)

The test container provides a reusable IBM MQ instance for all tests:

```kotlin
val mq: MQContainer by lazy {
    MQContainer(DockerImageName.parse(mqImage)).acceptLicense().apply {
        withReuse(System.getenv("GITHUB_ACTIONS") == null)
        start()
    }
}
```

**Key Features:**
- **Lazy initialization** - Container starts only when first accessed
- **Container reuse** - In local development, container stays alive between test runs (faster)
- **CI/CD aware** - Reuse disabled in GitHub Actions for clean test runs
- **Pre-configured queues** - Includes `DEV.QUEUE.1` by default

### 2. Test Configuration (`TestUtil.kt`)

The test configuration overrides production settings to connect to the test container:

```kotlin
fun testContainerMqOverrides(container: MQContainer?) =
    MapApplicationConfig().apply {
        val enabled = container != null
        put("mq.enabled", enabled.toString())
        if (enabled) {
            put("mq.host", container.host)
            put("mq.port", container.firstMappedPort.toString())
            put("mq.managerName", container.queueManager)
            put("mq.channel", container.channel)
            put("mq.name", "DEV.QUEUE.1") // IBM MQ test container default queue
            put("mq.username", container.appUser)
            put("mq.password", container.appPassword)
        }
    }
```

**Important:** Configuration keys use **dot-notation** (`mq.host`) not environment-style (`MQ_HOST`) because `MapApplicationConfig` sets HOCON config properties, not environment variables.

### 3. Integration Test Structure (`RapportMottakIntegrationTest.kt`)

Tests verify the complete message flow:

```kotlin
class RapportMottakIntegrationTest : FunSpec({
    context("RapportMottak Integration") {
        test("should consume and parse JSON message from IBM MQ queue") {
            withFullApplication(dbContainer = TestContainer.postgres, mqContainer = TestContainer.mq) {
                // Send test message to queue
                sendMessageToQueue(TestContainer.mq, "DEV.QUEUE.1", testMessage)
                
                // Wait for background job to process
                delay(2000)
                
                // Verify queue is empty (message was consumed)
                val remainingMessages = receiveMessagesFromQueue(TestContainer.mq, "DEV.QUEUE.1")
                remainingMessages shouldHaveSize 0
            }
        }
    }
})
```

## Configuration Deep Dive

### Why Configuration Format Matters

The application uses a layered configuration system with the following precedence:

1. **Environment Variables** (highest priority) - e.g., `MQ_HOST=localhost`
2. **System Properties** - e.g., `-Dmq.host=localhost`
3. **Application Config** (lowest priority) - e.g., `mq { host = "localhost" }`

When testing with `MapApplicationConfig`, we're setting **application config properties**, so we must use the HOCON path structure:

```kotlin
// ❌ Wrong - Environment variable format
put("MQ_HOST", "localhost")

// ✅ Correct - HOCON config path
put("mq.host", "localhost")
```

### Configuration Mapping

| Environment Variable | HOCON Config Path | Kotlin Field | Purpose |
|---------------------|-------------------|--------------|---------|
| `MQ_ENABLED` | `mq.enabled` | `enabled: Boolean` | Enable/disable MQ functionality |
| `MQ_HOST` | `mq.host` | `mqHost: String` | MQ server hostname |
| `MQ_PORT` | `mq.port` | `mqPort: Int` | MQ server port |
| `MQ_MANAGER_NAME` | `mq.managerName` | `mqManagerName: String` | Queue manager name |
| `MQ_NAME` | `mq.name` | `mqQueueName: String` | **Queue name to consume from** |
| `MQ_CHANNEL` | `mq.channel` | `mqChannel: String` | MQ channel |
| `MQ_SERVICE_USERNAME` | `mq.username` | `mqUsername: String` | Authentication username |
| `MQ_SERVICE_PASSWORD` | `mq.password` | `mqPassword: String` | Authentication password |

### The Critical Missing Piece: Queue Name

Initially, the test configuration was missing the queue name:

```kotlin
// ❌ Before - No queue name configured
put("mq.host", container.host)
put("mq.port", container.firstMappedPort.toString())
// Queue name missing!

// ✅ After - Queue name explicitly set
put("mq.name", "DEV.QUEUE.1")
```

Without this, the `MqConsumer` didn't know which queue to connect to, causing the tests to fail.

## Background Job Lifecycle

### The Problem: Lazy DI Initialization

Initially, the `RapportMottak` job was defined as a DI provider:

```kotlin
// ❌ This never runs!
provide("mqJob") {
    CoroutineScope(...).launch {
        RapportMottak(applicationState, resolve()).start()
    }
}
```

**Why it didn't work:** DI providers are lazy by default. The coroutine scope was never created because nothing explicitly resolved `"mqJob"`.

### The Solution: Eager Initialization

The job must be started immediately when MQ is enabled:

```kotlin
// ✅ Starts immediately
if (config.mqConfiguration.enabled) {
    CoroutineScope(Dispatchers.IO + exceptionHandler + MDCContext() + SupervisorJob()).launch {
        val mqConsumer = dependencies.resolve<MqConsumer>()
        RapportMottak(applicationState, mqConsumer).start()
    }
}
```

### How RapportMottak Works

The `RapportMottak.start()` method runs an infinite loop:

```kotlin
suspend fun start() {
    hentRapporter { rapporter ->
        logger.info { "Hentet rapporter fra ur: $rapporter" }
        // Process rapporter...
    }
}

private suspend fun hentRapporter(block: suspend (List<RefusjonsRapportBestilling>) -> Unit) {
    do {
        val mqRapporter = mqConsumer.receiveMessages(maxMessages = 1000)
        if (mqRapporter.isNotEmpty()) {
            val rapporter = mqRapporter.map { decodeFromString<RefusjonsRapportBestilling>(it) }
            block(rapporter)
            mqConsumer.commit()
        } else {
            delay(500L)
        }
    } while (applicationState.alive)
}
```

**Key Points:**
- Polls queue continuously while `applicationState.alive == true`
- Processes up to 1000 messages per batch
- Uses transactional sessions (commit/rollback)
- Sleeps 500ms when queue is empty

## Testing Patterns

### Test Helper Functions

**Sending Messages:**
```kotlin
private fun sendMessageToQueue(mqContainer: MQContainer, queueName: String, messageText: String) {
    val connectionFactory = MQConnectionFactory().apply {
        transportType = WMQConstants.WMQ_CM_CLIENT
        hostName = mqContainer.host
        port = mqContainer.firstMappedPort
        channel = mqContainer.channel
        queueManager = mqContainer.queueManager
    }
    
    connectionFactory.createConnection(mqContainer.appUser, mqContainer.appPassword).use { connection ->
        connection.createSession(false, Session.AUTO_ACKNOWLEDGE).use { session ->
            val queue = session.createQueue(queueName)
            session.createProducer(queue).use { producer ->
                producer.send(session.createTextMessage(messageText))
            }
        }
    }
}
```

**Receiving Messages:**
```kotlin
private fun receiveMessagesFromQueue(mqContainer: MQContainer, queueName: String): List<String> {
    // Similar setup to sending...
    val messages = mutableListOf<String>()
    session.createConsumer(queue).use { consumer ->
        var message = consumer.receive(100)
        while (message != null) {
            messages.add((message as TextMessage).text)
            message = consumer.receive(100)
        }
    }
    return messages
}
```

### Test Timing

Tests need to account for asynchronous message processing:

```kotlin
// Send message
sendMessageToQueue(...)

// Wait for background job to process (adjust as needed)
delay(2000) // 2 seconds

// Verify queue is empty
val remaining = receiveMessagesFromQueue(...)
remaining shouldHaveSize 0
```

## Common Issues and Solutions

### Issue 1: Connection Failures

**Symptom:**
```
JMSWMQ0018: Failed to connect to queue manager with host name 'mq.example.com(1414)'
```

**Cause:** Test configuration not applied correctly (wrong key format or missing container reference)

**Solution:** Ensure all config keys use dot-notation and point to container properties

### Issue 2: Messages Not Consumed

**Symptom:** Test messages remain in queue after test completes

**Cause:** Background job not started

**Solution:** Ensure job is launched outside DI provider block

### Issue 3: Wrong Queue

**Symptom:** Consumer connects but doesn't find messages

**Cause:** `MqConsumer` connecting to wrong queue (was using manager name instead of queue name)

**Solution:** 
```kotlin
// Before
val queue = nonJmsQueue(config.mqManagerName) // ❌

// After  
val queue = nonJmsQueue(config.mqQueueName) // ✅
```

### Issue 4: Port Mismatch

**Symptom:** Connection refused or timeout

**Cause:** Using `container.port` instead of `container.firstMappedPort`

**Solution:**
```kotlin
// Use the mapped port (accessible from host)
put("mq.port", container.firstMappedPort.toString())
```

## Best Practices

### 1. Container Reuse in Local Development

Reusing containers speeds up local test runs:

```kotlin
withReuse(System.getenv("GITHUB_ACTIONS") == null)
```

This keeps the container alive between test runs, but only in local environments.

### 2. Explicit Queue Configuration

Always explicitly configure the queue name in tests:

```kotlin
put("mq.name", "DEV.QUEUE.1")
```

Don't rely on defaults from `application.conf`.

### 3. Transaction Management

Use transactional sessions for reliability:

```kotlin
session = connection.createSession(Session.SESSION_TRANSACTED)
// ... process messages ...
mqConsumer.commit() // or rollback() on error
```

### 4. Proper Cleanup

Ensure resources are properly closed:

```kotlin
connection.use { conn ->
    session.use { sess ->
        // Use resources
    }
}
```

### 5. Adequate Wait Times

Allow sufficient time for asynchronous processing:

```kotlin
delay(2000) // Adjust based on message processing complexity
```

## Testing Locally

### Running Tests

```bash
# Run all MQ integration tests
./gradlew test --tests "*RapportMottakIntegrationTest"

# Run specific test
./gradlew test --tests "*.should consume and parse JSON message*"
```

### Debugging

Enable debug logging in `logback.xml`:

```xml
<logger name="no.nav.sokos.oppgjorsrapporter" level="DEBUG"/>
```

Check container logs:

```bash
docker logs <container-id>
```

### Container Management

List running containers:
```bash
docker ps | grep ibm-messaging
```

Stop reused containers:
```bash
docker stop <container-id>
```

## CI/CD Considerations

### GitHub Actions

- Container reuse is **disabled** (clean state for each run)
- Tests run in isolated environments
- Containers are automatically cleaned up

### Performance

- First test run: ~30s (container startup)
- Subsequent runs (with reuse): ~5s
- CI/CD runs: ~30s per run (no reuse)

## Future Improvements

### Potential Enhancements

1. **Parallel Test Execution** - Use different queues per test for parallel runs
2. **Message Builders** - Helper functions to create test messages easily
3. **Queue Inspection Tools** - Better utilities to inspect queue state
4. **Error Scenarios** - Tests for malformed messages, connection failures, etc.
5. **Performance Tests** - Load testing with high message volumes

### Refactoring Opportunities

1. Extract common MQ connection logic into test utilities
2. Create test fixtures for common message patterns
3. Add assertions for message metadata (timestamps, correlation IDs, etc.)

## Related Files

### Production Code
- `src/main/kotlin/no/nav/sokos/oppgjorsrapporter/Application.kt` - Job initialization
- `src/main/kotlin/no/nav/sokos/oppgjorsrapporter/mq/MqConsumer.kt` - Message consumption
- `src/main/kotlin/no/nav/sokos/oppgjorsrapporter/jobs/RapportMottak.kt` - Message processing
- `src/main/kotlin/no/nav/sokos/oppgjorsrapporter/config/PropertiesConfig.kt` - Configuration model
- `src/main/resources/application.conf` - Default configuration

### Test Code
- `src/test/kotlin/no/nav/sokos/oppgjorsrapporter/TestContainer.kt` - Container setup
- `src/test/kotlin/no/nav/sokos/oppgjorsrapporter/TestUtil.kt` - Test configuration
- `src/test/kotlin/no/nav/sokos/oppgjorsrapporter/jobs/RapportMottakIntegrationTest.kt` - Integration tests
- `src/test/kotlin/no/nav/sokos/oppgjorsrapporter/jobs/RapportMottakUnitTest.kt` - Unit tests (mocked MQ)

## References

- [IBM MQ Test Container Documentation](https://github.com/ibm-messaging/mq-container)
- [Ktor DI Plugin Documentation](https://ktor.io/docs/dependency-injection.html)
- [Testcontainers Documentation](https://www.testcontainers.org/)

---

**Last Updated:** November 6, 2025

