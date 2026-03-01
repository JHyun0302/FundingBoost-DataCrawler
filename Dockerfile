FROM gradle:8.7.0-jdk17 AS builder

WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY src src

RUN gradle clean bootWar -x test --no-daemon

FROM debian:bookworm-slim

ENV APP_HOME=/opt/funding-crawler
ENV CHROME_BIN=/usr/bin/chromium
ENV CHROMEDRIVER_PATH=/usr/bin/chromedriver

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       openjdk-17-jre-headless \
       chromium \
       chromium-driver \
       fonts-noto-cjk \
       ca-certificates \
       tzdata \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p ${APP_HOME}

WORKDIR ${APP_HOME}

COPY --from=builder /workspace/build/libs/funding-crawler.war funding-crawler.war

EXPOSE 8090

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:-} -jar /opt/funding-crawler/funding-crawler.war --spring.profiles.active=${SPRING_PROFILES_ACTIVE:-prod}"]
