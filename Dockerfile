FROM gcr.io/distroless/java21-debian12
WORKDIR /app
COPY build/install/*/lib /lib

ENV TZ="Europe/Oslo"
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-cp", "/lib/*", "no.nav.sokos.oppgjorsrapporter.ApplicationKt"]
