FROM maven:3.9.8-eclipse-temurin-17

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 bash \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
COPY . /workspace

ENV MAVEN_REPO_LOCAL=/workspace/.m2/repository

RUN mkdir -p "${MAVEN_REPO_LOCAL}"

CMD ["/bin/bash"]
