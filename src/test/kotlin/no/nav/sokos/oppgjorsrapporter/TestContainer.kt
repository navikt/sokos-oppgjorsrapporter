package no.nav.sokos.oppgjorsrapporter

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

object TestContainer {
    private val dockerImageName = "postgres:17"
    val container: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse(dockerImageName)).apply {
            withReuse(false)
            withUsername("test-admin")
            waitingFor(Wait.defaultWaitStrategy())
            start()
        }
    }
}
