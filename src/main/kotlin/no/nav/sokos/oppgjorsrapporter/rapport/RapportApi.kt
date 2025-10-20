package no.nav.sokos.oppgjorsrapporter.rapport

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.put

@Resource(path = "/api")
class ApiPaths {
    @Resource("rapport/v1")
    class Rapporter(val parent: ApiPaths = ApiPaths(), val inkluderArkiverte: Boolean = false, val orgnr: String? = null) {
        @Resource("{id}")
        class Id(val parent: Rapporter = Rapporter(), val id: Long) {
            @Resource("innhold") class Innhold(val parent: Id)

            @Resource("arkiver") class Arkiver(var parent: Id, val arkivert: Boolean? = true)
        }
    }
}

fun Route.rapportApi() {
    val rapportService: RapportService by application.dependencies

    get<ApiPaths.Rapporter> { rapporter ->
        // TODO: Listen med tilgjengelige rapporter kan bli lang; trenger vi å lage noe slags paging?  La klient angi hvilken tidsperiode de
        // er interesserte i?
        if (rapporter.orgnr != null) {
            call.respond(rapportService.listForOrg(OrgNr(rapporter.orgnr)))
        } else {
            // TODO: Finne orgnr brukeren har rettigheter til fra MinID-token, liste for alle dem
            call.respond(HttpStatusCode.Companion.BadRequest, "Mangler orgnr")
        }
    }

    get<ApiPaths.Rapporter.Id> { rapport ->
        val rapport = rapportService.findById(Rapport.Id(rapport.id))
        if (rapport == null) {
            return@get call.respond(HttpStatusCode.NotFound)
        }
        call.respond(rapport)
    }

    get<ApiPaths.Rapporter.Id.Innhold> { innhold ->
        call.respondText("innhold fra variant av $innhold")
        TODO()
    }

    put<ApiPaths.Rapporter.Id.Arkiver> { arkiver ->
        call.respondText("sett arkivert: $arkiver")
        TODO()
    }
}
