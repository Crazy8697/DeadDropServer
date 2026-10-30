# Dead Drop server image. Build the server first (./gradlew installDist), then `docker build .`: the image only copies
# the built server in, so building it needs no Gradle inside Docker. The release builds it from the public source.
FROM eclipse-temurin:21-jre-noble@sha256:cd35bb33859641c5374415826a5951845104093eaa401cbe9d3d9023444f1c13
ARG DEADDROP_VERSION=dev
ARG DEADDROP_REVISION=dev
LABEL org.opencontainers.image.title="deaddrop-server"
LABEL org.opencontainers.image.description="Dead Drop Finance sync server: keeps the app and the program in step, on your own hardware"
LABEL org.opencontainers.image.source="https://github.com/Crazy8697/DeadDropServer"
LABEL org.opencontainers.image.licenses="Apache-2.0"
LABEL org.opencontainers.image.version="${DEADDROP_VERSION}"
LABEL org.opencontainers.image.revision="${DEADDROP_REVISION}"
COPY build/install/deaddrop-server /opt/deaddrop-server
COPY LICENSE NOTICE THIRD-PARTY-NOTICES.md /opt/deaddrop-server/
COPY licenses /opt/deaddrop-server/licenses
COPY deploy/entrypoint.sh /usr/local/bin/deaddrop-entrypoint
RUN chmod 755 /usr/local/bin/deaddrop-entrypoint /opt/deaddrop-server/bin/deaddrop-server && ln -s /opt/deaddrop-server/bin/deaddrop-server /usr/local/bin/deaddrop-server
# a small heap; serial GC is the lightest
ENV JAVA_OPTS="-Xmx192m -XX:+UseSerialGC"
# Starts as root only for the entrypoint, which reads the mounted settings file and drops to DEADDROP_RUN_AS (never
# root) with setpriv before the JVM starts. No settings or keys are in the image.
ENTRYPOINT ["/usr/local/bin/deaddrop-entrypoint"]
CMD ["serve"]
