FROM eclipse-temurin:17-jre-alpine

ENV APP_HOME=/opt/funding-crawler

RUN mkdir -p ${APP_HOME}

WORKDIR ${APP_HOME}

COPY funding-crawler.war funding-crawler.war

EXPOSE 8080

# 파일 로그 옵션 제거 (logging.file.name 사용 안 함)
ENTRYPOINT ["sh", "-c", "java -jar /opt/funding-crawler/funding-crawler.war --spring.profiles.active=prod"]