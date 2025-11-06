# Fix MQ Integration Tests

## Summary

This PR fixes the MQ integration tests by correcting the test configuration format and ensuring the background job starts properly to consume messages.

## Problem

The MQ integration tests were failing with connection errors:
```
JMSWMQ0018: Failed to connect to queue manager 'mq-manager-name' with connection mode 'Client' 
and host name 'mq.example.com(1414)'.
```

Two issues were identified:
1. **Wrong configuration format** - Test configuration used environment-style keys instead of HOCON dot-notation
2. **Background job not starting** - The `RapportMottak` job was never initialized, so messages weren't consumed

## Changes

### 1. Fix Test Configuration (`TestUtil.kt`)

Changed MQ configuration keys from environment-style to dot-notation to match HOCON config structure:

```kotlin
// Before
put("MQ_HOST", container.host)
put("MQ_PORT", container.port.toString())

// After  
put("mq.host", container.host)
put("mq.port", container.firstMappedPort.toString())
```

**Added missing queue name configuration:**
```kotlin
put("mq.name", "DEV.QUEUE.1") // Default queue in IBM MQ testcontainer
```

This ensures the consumer connects to the correct queue in the test container.

### 2. Add Queue Name to Config Model (`PropertiesConfig.kt`)

Added `mqQueueName` field to `MqProperties`:
```kotlin
data class MqProperties(
    // ...existing fields...
    val mqQueueName: String,
    // ...
)
```

### 3. Fix Queue Resolution (`MqConsumer.kt`)

Changed from using manager name to queue name when creating the queue:
```kotlin
// Before
val queue = nonJmsQueue(config.mqManagerName)

// After
val queue = nonJmsQueue(config.mqQueueName)
```

### 4. Start Background Job (`Application.kt`)

Moved `RapportMottak` initialization outside the DI provider to ensure it starts immediately:

```kotlin
// Before: Job defined as lazy DI provider (never started)
provide("mqJob") {
    // ...coroutine scope with RapportMottak
}

// After: Job launched directly when MQ is enabled
if (config.mqConfiguration.enabled) {
    CoroutineScope(...).launch {
        val mqConsumer = dependencies.resolve<MqConsumer>()
        RapportMottak(applicationState, mqConsumer).start()
    }
}
```

### 5. Add Queue Name to Config (`application.conf`)

Added `name` field to MQ configuration:
```hocon
mq {
  // ...existing config...
  name = "mq-name"
  name = ${?MQ_NAME}
}
```

### 6. Enable Container Reuse (`TestContainer.kt`)

Added container reuse for local development (faster test execution):
```kotlin
val mq: MQContainer by lazy {
    MQContainer(DockerImageName.parse(mqImage)).acceptLicense().apply {
        withReuse(System.getenv("GITHUB_ACTIONS") == null)
        start()
    }
}
```

### 7. Enable Debug Logging (`logback.xml`)

Changed log level to DEBUG for easier troubleshooting during development.

## Result

All integration tests now pass:
```
✅ RapportMottakIntegrationTest > should consume and parse JSON message from IBM MQ queue
✅ RapportMottakIntegrationTest > should handle multiple messages in queue  
✅ RapportMottakIntegrationTest > should handle JSON with valid structure
```

## Why These Changes Were Needed

**Configuration Format**: `MapApplicationConfig` sets config properties (not environment variables), so keys must use dot-notation (`mq.host`) not environment-style (`MQ_HOST`).

**Background Job**: DI providers are lazy by default. The job must be explicitly started to begin consuming messages from the queue.

**Queue vs Manager**: The consumer needs the queue name ("DEV.QUEUE.1"), not the queue manager name, to connect to the correct queue.

