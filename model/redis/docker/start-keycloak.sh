#!/bin/bash

# Start Keycloak with Redis providers
# Run this after: docker-compose up -d

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_ROOT="${SCRIPT_DIR}/../../.."

# Build Keycloak if not already built
if [ ! -f "${KEYCLOAK_ROOT}/quarkus/server/target/keycloak-999.0.0-SNAPSHOT.jar" ]; then
    echo "Building Keycloak (this may take a while)..."
    cd "${KEYCLOAK_ROOT}"
    ./mvnw clean install -DskipTests -pl quarkus/server -am
fi

cd "${KEYCLOAK_ROOT}/quarkus/server"

# Run Keycloak with Redis configuration
java -jar target/quarkus-run.jar \
    start-dev \
    --db=postgres \
    --db-url=jdbc:postgresql://localhost:5432/keycloak \
    --db-username=keycloak \
    --db-password=keycloak \
    --spi-redis-connection-default-url=redis://localhost:6379 \
    --spi-user-session-provider=redis \
    --spi-single-use-object-provider=redis \
    --spi-authentication-session-provider=redis \
    --spi-user-login-failure-provider=redis \
    --spi-cluster-provider=redis \
    --http-port=8080

# Alternative: Use environment variables
# export KC_DB=postgres
# export KC_DB_URL=jdbc:postgresql://localhost:5432/keycloak
# export KC_DB_USERNAME=keycloak
# export KC_DB_PASSWORD=keycloak
# export KC_SPI_REDIS_CONNECTION_DEFAULT_URL=redis://localhost:6379
# export KC_SPI_USER_SESSION_PROVIDER=redis
# export KC_SPI_SINGLE_USE_OBJECT_PROVIDER=redis
# export KC_SPI_AUTHENTICATION_SESSION_PROVIDER=redis
# export KC_SPI_USER_LOGIN_FAILURE_PROVIDER=redis
# export KC_SPI_CLUSTER_PROVIDER=redis
# java -jar target/quarkus-run.jar start-dev
