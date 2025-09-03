package no.nav.sokos.oppgjorsrapporter.config

import io.kotest.core.listeners.TestListener
import io.kotest.core.spec.Spec
import io.kotest.extensions.testcontainers.toDataSource
import no.nav.sokos.oppgjorsrapporter.TestContainer

object PostgresListener : TestListener {
    val container = TestContainer.postgres
    val dataSource = container.toDataSource()

    override suspend fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        if (!container.isRunning) container.start()
        DatabaseMigrator(dataSource, container.username, ApplicationState())
    }
}
