package no.nav.sokos.oppgjorsrapporter.blackjack

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.util.getValue

fun Route.blackjackApi(blackjackService: BlackjackService = BlackjackService()) {
    route("/api/blackjack/v1") {
        post {
            call.respond(HttpStatusCode.Created, blackjackService.nyttSpill())
        }

        get("/{id}") {
            val id: String by call.parameters
            val spill = blackjackService.hentSpill(id)
            if (spill == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(spill)
            }
        }

        post("/{id}/trekk") {
            val id: String by call.parameters
            val spill = blackjackService.trekk(id)
            if (spill == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(spill)
            }
        }

        post("/{id}/staa") {
            val id: String by call.parameters
            val spill = blackjackService.staa(id)
            if (spill == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respond(spill)
            }
        }
    }
}
