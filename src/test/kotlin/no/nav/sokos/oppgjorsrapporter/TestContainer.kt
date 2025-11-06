package no.nav.sokos.oppgjorsrapporter

import com.ibm.mq.testcontainers.MQContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

object TestContainer {
    private val postgresImage = "postgres:17"
    val postgres: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>(DockerImageName.parse(postgresImage)).apply {
            // Med 'reuse' påskrudd forblir containeren med databasen levende etter at testene har kjørt.
            // Dette kan være nyttig når man kjører testene lokalt, siden man da kan gjøre ting som:
            //  * peke IDEen sin på databasen (slik at den kan hente ned skjemaet og hjelpe med skriving av spørringer/migreringer),
            //  * bruke f.eks. psql til å se på hvordan dataene ser ut etter at en test har kjørt,
            //  * etc.
            //
            // En liten ulempe er dog at hvis man har endret på en migrering etter at testene har kjørt, vil flyway feile ved neste
            // kjøring (fordi sjekksummen på migreringen er endret).
            // Såfremt det er en ny, ikke-deployet migrering det er snakk om, kan man enkelt blidgjøre flyway ved å manuelt kverke
            // postgres-containeren (i Podman Desktop: Gå til "Containers", trykk på søppelkasse-ikonet til høyre på linja med
            // postgres-containeren) før man kjører testene igjen.  En ny container vil da bli laget - med endret lytte-port, så
            // man må antakelig gjøre en liten konfig-endring i IDE før database-skjema-integrasjonen der virker igjen.
            //
            // Hvis testene kjøres fra GitHub Actions, skal database-containeren aldri forbli levende.
            withReuse(System.getenv("GITHUB_ACTIONS") == null)
            withUsername("test-admin")
            waitingFor(Wait.defaultWaitStrategy())
            start()
        }
    }

    private val mqImage = "icr.io/ibm-messaging/mq:9.4.0.6-r1"
    val mq: MQContainer by lazy {
        MQContainer(DockerImageName.parse(mqImage)).acceptLicense().apply {
            withReuse(System.getenv("GITHUB_ACTIONS") == null)
            start()
        }
    }
}
