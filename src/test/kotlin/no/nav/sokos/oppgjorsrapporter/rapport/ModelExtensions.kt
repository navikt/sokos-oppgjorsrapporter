package no.nav.sokos.oppgjorsrapporter.rapport

import no.nav.sokos.utils.OrgNr

fun Rapport.medId(id: Long) = copy(id = Rapport.Id(id))

fun Rapport.medOrgNr(orgnr: OrgNr) = copy(orgnr = orgnr)
