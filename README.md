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

## Bygge og kjøre prosjekt

1. Bygg prosjektet ved å kjøre `./gradlew clean build shadowJar`
2. Start appen lokalt ved å kjøre main metoden i ***Application.kt***
3. Siden en del av testene benytter [testcontainers](https://testcontainers.com/), trenger man et fungerende Docker-kompatibelt "container runtime" på maskinen man skal kjøre på; se
   [under](#oppsett-av-container-runtime-på-utviklingsmaskin).
4. For å kjøre tester i IntelliJ IDEA trenger du [Kotest IntelliJ Plugin](https://plugins.jetbrains.com/plugin/14080-kotest)

### Oppsett av container runtime på utviklingsmaskin

Det finnes flere alternativer til [testcontainer-kompatible container runtimes](https://java.testcontainers.org/supported_docker_environment/).
På en Mac kan man nokså kjapt få et Docker-oppsett som er testcontainers-kompatibelt slik:

1. `brew install podman-desktop`
2. Start "Podman Desktop"-appen
3. Gå til "Settings", velg "Preferences", og skru på "Docker Compatibility"-knappen
4. Gå til "Dashboard"
5. Trykk på knappen for å installere Podman, og gjennomfør installasjonen

   Forhåpentligvis vil `brew install podman` også virke etterhvert, men det har vært issues i f.eks. 5.6.0 med at brew-varianten er pakket uten noen ting den trenger, så som `krunkit`...
6. Hvis det er et spørsmål om du ønsker "Mac helper"-dingsen på Dashboard-siden, så må du takke ja til det
7. Hvis det er en "Podman needs to be set up"-boks der, trykker du "Set up" og følger instruksjonene
8. Nå skal det stå "Podman vX.y.z RUNNING" nederst på Dashboardet, og ting bør virke
9. Du kan evt. også dobbeltsjekke i Settings -> Docker Compatibility at "System socket status" viser "podman is listening"

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
* [Gradle >= 8.9](https://gradle.org/)
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

Applikasjonen bruker [AzureAD](https://docs.nais.io/security/auth/azure-ad/) autentisering

# 6. Drift og støtte

### Logging

https://logs.adeo.no.

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
