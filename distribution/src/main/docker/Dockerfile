FROM eclipse-temurin:21-jdk

ARG langsPkg

RUN set -ex \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    gettext-base \
    procps \
    curl \
    ${langsPkg} \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

COPY maven /usr/share/fscrawler
ENTRYPOINT [ "/usr/share/fscrawler/bin/fscrawler" ]
