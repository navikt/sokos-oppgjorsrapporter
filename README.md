# sokos-oppgjorsrapporter

Backend-applikasjon for å

1. motta grunnlag for oppgjørsrapportene K27, T14 og T12,
2. generere dem i forskjellige formater,
3. varsle når nye rapporter ligger tilgjengelig, og
4. tilgjengeliggjøre dem for nedlasting (via API)

APIet brukes bl.a. av [sokos-oppgjorsrapporter-selvbetjening](https://github.com/navikt/sokos-oppgjorsrapporter-selvbetjening).

## Workflows

1. [Deploy application](.github/workflows/deploy.yaml) -> For å bygge/teste prosjektet, bygge/pushe Docker image og deploy til dev og prod
    1. Denne workflow trigges når kode pushes i `main` branch
2. [Build/test PR](.github/workflows/build-pr.yaml) -> For å bygge og teste alle PR som blir opprettet og gjør en sjekk på branch prefix og title
    1. Denne workflow kjøres kun når det opprettes pull requester
3. [Security](.github/workflows/security.yaml) -> For å skanne kode og docker image for sårbarheter. Kjøres hver morgen kl 06:00
    1. Denne kjøres når [Deploy application](.github/workflows/deploy.yaml) har kjørt ferdig
4. [Deploy application manual](.github/workflows/manual-deploy.yaml) -> For å deploye applikasjonen manuelt til ulike miljøer
    1. Denne workflow trigges manuelt basert på branch og miljø
5. [DB migration lint check](.github/workflows/db-migrationcheck.yml) -> Bruker [squawk](https://squawkhq.com/) til å gi tips om feil/forbedringer i nye database-migreringer
    1. Trigges ved opprettelse/oppdatering av pull requests med target-branch `main`

## Bygge og kjøre prosjekt

1. Bygg prosjektet ved å kjøre `./gradlew installDist`
2. Start appen lokalt ved å kjøre main metoden i ***Application.kt***
3. Siden en del av testene benytter [testcontainers](https://testcontainers.com/), trenger man et fungerende Docker-kompatibelt "container runtime" på maskinen man skal kjøre på; se
   [under](#oppsett-av-container-runtime-på-utviklingsmaskin).
4. For å kjøre tester i IntelliJ IDEA trenger du [Kotest IntelliJ Plugin](https://plugins.jetbrains.com/plugin/14080-kotest)

### Oppsett av container runtime på utviklingsmaskin

Det finnes flere alternativer til [testcontainer-kompatible container runtimes](https://java.testcontainers.org/supported_docker_environment/).
På en Mac kan man nokså kjapt få et Docker-oppsett som er testcontainers-kompatibelt slik:

1. Første gangs oppsett av Colima:
   * Kjør `brew install docker colima`
   * Kjør `./setupColimaOnMac.sh` for å få konfigurert opp Colima med en virtuell maskin det kan kjøres containere med forskjellige CPU-arkitekturer i
   * Oppdater environment-variable (som skrevet ut av scriptet) i f.eks. ~/.zshrc, og restart tingene som trenger å restartes (kanskje til og med logge ut og inn av Macen din for å få kjørt .zshrc på ny)
2. Hvis colima har stoppet (etter reboot e.l., sjekk med `colima status`) kan den startes igjen med `colima start`
   * Hvis `colima start` av en eller annen grunn ikke klarer å starte colima, er antakelig den enkleste løsningen å kverke den (`colima delete`) og så starte fra punkt 1 over.

## Henvendelser

- Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på github.
- Interne henvendelser kan sendes via Slack i kanalen [#utbetaling](https://nav-it.slack.com/archives/CKZADNFBP)

```
Alt under her skal beholdes som en standard dokumentasjon som må fylles ut av utviklere.
```

# Prosjektnavn

# Innholdsoversikt

* [1. Funksjonelle krav](#1-funksjonelle-krav)
* [2. Utviklingsmiljø](#2-utviklingsmiljø)
* [3. Programvarearkitektur](#3-programvarearkitektur)
* [4. Deployment](#4-deployment)
* [5. Autentisering](#5-autentisering)
* [6. Drift og støtte](#6-drift-og-støtte)
* [7. Swagger](#7-swagger)
* [8. Henvendelser](#8-henvendelser-og-tilgang)

---

# 1. Funksjonelle Krav

Hva er oppgaven til denne applikasjonen

# 2. Utviklingsmiljø

### Forutsetninger

* Java 21
* [Gradle >= 9.0](https://gradle.org/)
* [Kotest IntelliJ Plugin](https://plugins.jetbrains.com/plugin/14080-kotest)

### Bygge prosjekt

Hvordan bygger jeg prosjektet.

### Lokal utvikling

Hvordan kan jeg kjøre lokalt og hva trenger jeg?

# 3. Programvarearkitektur

Legg ved skissediagram for hvordan arkitekturen er bygget

# 4. Deployment

Distribusjon av tjenesten er gjort med bruk av Github Actions.
[sokos-oppgjorsrapporter CI / CD](https://github.com/navikt/sokos-oppgjorsrapporter/actions)

Push/merge til main branche vil teste, bygge og deploye til produksjonsmiljø og testmiljø.

# 5. Autentisering

Applikasjonen bruker
 * [AzureAD](https://docs.nais.io/security/auth/azure-ad/) for autentisering av interne brukere,
 * [TokenX vekslet fra ID-porten](https://docs.nais.io/auth/tokenx/) for autentisering av eksterne personer, og
 * [Altinn systembruker](https://docs.altinn.studio/nb/authorization/guides/resource-owner/system-user/) for maskinell API-aksess.

## Oppsett for å teste systembruker

For å kunne late som om man aksesserer applikasjonens API som et lønns- og personalsystem (LPS) i dev må det gjøres en del oppsett (jf
[Altinns guide](https://docs.altinn.studio/nb/authorization/guides/system-vendor/system-user/)):

  1. Velg en passende syntetisk organisasjon i [Tenor](https://testdata.skatteetaten.no/web/testnorge/soek/brreg-er-fr) som skal representere LPS-leverandøren.
     Gå til organisasjonens hovedenhet (i "Relasjoner"-fliken).
  2. Husk orgnr for hovedenheten (eksempel: [KOSTBAR LYSEBLÅ LEOPARD AS](https://testdata.skatteetaten.no/web/testnorge/avansert/brreg-er-fr?kql=tenorMetadata.id:211085452) har orgnr `211085452`)
  3. På hovedenhetens "Kildedata"-flik, søk etter "Daglig leder" for å finne fnr for daglig leder; husk dette (eksempel: `55826500726`)
  4. Logg inn "Med syntetisk organisasjon" på [Digdirs selvbetjeningsportal](https://sjolvbetjening.test.samarbeid.digdir.no/login) - med din personlige ID-Porten-innlogging
  5. Bytt til den syntetiske hovedenheten du fant i punkt 2 (oppe i høyre hjørne)
  6. Send mail til [Altinn](mailto:servicedesk@altinn.no) for å be om at nødvendige systembruker-scopes blir lagt til den syntetiske organisasjonen:
     ```
     altinn:authentication/systemregister.read
     altinn:authentication/systemregister.write
     altinn:authentication/systemuser.request.read
     altinn:authentication/systemuser.request.write
     altinn:events.subscribe
     ```
  7. Lag en Maskinporten-klient for organisasjonen
  8. Opprett et nøkkelpar for Maskinporten-klienten; ta vare på private key-en som du da blir gitt - gjerne ved å lage en Nais console-secret (som kan brukes under) med følgende med nøkkelnavn:
     * `MASKINPORTEN_PKEY` - privatnøkkel-blokka
     * `MASKINPORTEN_INTEGRATION_ID` - "Klient ID"-UUIDen du kan finne på klientens "Detaljer"-flik
     * `MASKINPORTEN_KID` - UUIDen for nøkkelparet
  9. Legg til scopes på klienten:
     * scopes for systembruker (når dette blir mulig basert på mailen du sendte i punkt 6):
       ```
       altinn:authentication/systemregister.write
       altinn:authentication/systemuser.request.read
       altinn:authentication/systemuser.request.write
       altinn:events.subscribe
       ```
     * scopes for APIene systembrukere tilknyttet denne klienten skal kunne aksessere:
       ```
       nav:utbetaling/oppgjorsrapporter
       digdir:dialogporten
       ```
 10. Lag en fork av [hag-token-tjeneste](https://github.com/navikt/hag-token-tjeneste), juster navn/scope/etc. til å matche ditt behov, og deploy denne
 11. Kopier Bruno-collection fra [hag-ulv](https://github.com/navikt/hag-ulv/tree/main/kataloger/tigersys) og juster til å matche ditt behov; for denne applikasjonen så finnes resultatet
     [her](kataloger/liksom-lps)
 12. Importer Bruno-collectionen i [Bruno](https://www.usebruno.com/)
 13. Registrer et "system" i Altinns systemregister for LPSet ved å kjøre `registrer nytt system`-requesten i Bruno.
     (Senere API-kall identifiserer dette systemet med `systemId`-en som er definert som en folder-variabel, slik at man ikke trenger å huske UUIDen man får tilbake i API-responsen.)
 14. Velg en passende syntetisk organisasjon i [Tenor](https://testdata.skatteetaten.no/web/testnorge/soek/brreg-er-fr) som skal representere LPS-kunden; for "refusjon arbeidsgiver" (aka K27)
     vil det typisk være kundens hovedenhet rapporten sendes til.  Husk orgnr og fnr til organisasjonens "Daglig leder" e.l.
     (eksempel: For [SKRAVLETE ALTETENDE TIGER AS](https://testdata.skatteetaten.no/web/testnorge/avansert/brreg-er-fr?kql=tenorMetadata.id:313552381) er hovedenhet-orgnr `313552381`, og
     "Styrets leder" har fnr `10926899126`)
 15. Legg valgt kunde-orgnr inn som collection-variablene `kundeSystembrukerOrgnr` og `kundeUnderenhetOrgnr` i Bruno
 16. Start prosessen med å lage en "systembruker" i Altinn for denne kunden (knyttet til LPSets registrerte system) ved å kjøre `opprett systembruker-forespørsel`-requesten i Bruno.
     I responsen finnes det en `confirmUrl` som skal brukes i neste punkt.
 17. Gå til `confirmUrl` (som er i tt02).  Velg innloggingsmetode `TestID: Lag din egen testbruker` og logg inn med fnr til kunde-organisasjonens "Daglig leder" (fra punkt 14).
     Godkjenn systembrukeren.
 18. Kjør `hent rapport`-requesten i Bruno, og se at den gir forventet respons

Hvis man skal gjøre slike tester flere ganger, for eksempel for å teste forskjellige variasjoner av systembruker-rettigheter, er det enklest å slette systembrukeren før man lager en ny.
Det gjøres [herfra i TT02](https://am.ui.tt02.altinn.no/accessmanagement/ui/systemuser/overview) - logg inn som LPS-kunden og sørg for at du representerer riktig org.
Den siden kommer forhåpentligvis å bli lettere å finne i Altinn-GUIet etterhvert, men for nå finnes den i alle fall her.

# 6. Drift og støtte

### Logging

Feilmeldinger og infomeldinger som ikke innheholder sensitive data logges til [Grafana Loki](https://docs.nais.io/observability/logging/#grafana-loki).
Sensitive meldinger logges til [Team Logs](https://doc.nais.io/observability/logging/how-to/team-logs/).

### Kubectl

For dev-gcp:

```shell script
kubectl config use-context dev-gcp
kubectl get pods -n okonomi | grep sokos-oppgjorsrapporter
kubectl logs -f sokos-oppgjorsrapporter-<POD-ID> --namespace okonomi -c sokos-oppgjorsrapporter
```

For prod-gcp:

```shell script
kubectl config use-context prod-gcp
kubectl get pods -n okonomi | grep sokos-oppgjorsrapporter
kubectl logs -f sokos-oppgjorsrapporter-<POD-ID> --namespace okonomi -c sokos-oppgjorsrapporter
```

### Alarmer

Applikasjonen bruker [Grafana Alerting](https://grafana.nav.cloud.nais.io/alerting/) for overvåkning og varsling.

Alarmene overvåker metrics som:

- HTTP-feilrater
- JVM-metrikker

### Grafana

- [appavn](url)

---

# 7. Swagger

Hva er url til Lokal, dev og prod?

# 8. Henvendelser og tilgang

Spørsmål knyttet til koden eller prosjektet kan stilles som issues her på Github.
Interne henvendelser kan sendes via Slack i kanalen [#utbetaling](https://nav-it.slack.com/archives/CKZADNFBP)
