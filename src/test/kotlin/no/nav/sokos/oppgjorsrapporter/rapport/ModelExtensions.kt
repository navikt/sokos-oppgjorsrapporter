package no.nav.sokos.oppgjorsrapporter.rapport

fun Rapport.medId(id: Long) = copy(id = Rapport.Id(id))

fun Rapport.medOrgNr(orgnr: OrgNr) = copy(orgnr = orgnr)
