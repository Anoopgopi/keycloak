#!/bin/bash

# Keycloak Startup Script for Redis Cache Testing
# 
# Usage:
#   ./start-keycloak.sh site1    # Start Site 1 on port 8080
#   ./start-keycloak.sh site2    # Start Site 2 on port 8180
#   ./start-keycloak.sh both     # Start both sites in background

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEYCLOAK_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
KEYCLOAK_DIST="$KEYCLOAK_DIR/quarkus/dist/target/keycloak-999.0.0-SNAPSHOT"

echo "=== Keycloak Redis Cache Test Startup ==="
echo "Keycloak Distribution: $KEYCLOAK_DIST"

# Check if distribution exists
if [ ! -d "$KEYCLOAK_DIST" ]; then
    echo "ERROR: Keycloak distribution not found at $KEYCLOAK_DIST"
    echo "Please build Keycloak first: ./mvnw clean install -DskipTests"
    exit 1
fi

# Check if Redis is running
echo "Checking Redis connectivity..."
if ! redis-cli -p 6379 ping > /dev/null 2>&1; then
    echo "WARNING: Redis on port 6379 is not responding"
    echo "Start Redis: cd $SCRIPT_DIR && docker-compose up -d"
fi

if ! redis-cli -p 6380 ping > /dev/null 2>&1; then
    echo "WARNING: Redis on port 6380 is not responding"
    echo "Start Redis: cd $SCRIPT_DIR && docker-compose up -d"
fi

start_site1() {
    echo ""
    echo "Starting Keycloak Site 1 (port 8080, Redis 6379)..."
    echo "Admin Console: http://localhost:8080"
    echo ""
    
    cd "$KEYCLOAK_DIST"
    ./bin/kc.sh start-dev \
        --http-port=8080 \
        --spi-connections-redis-default-host=localhost \
        --spi-connections-redis-default-port=6379 \
        --spi-user-cache-provider=redis \
        --spi-user-cache-redis-cross-dc-enabled=true \
        --spi-user-cache-redis-cross-dc-site-name=site1 \
        --spi-user-cache-redis-cross-dc-remote-sites=site2:localhost:6380 \
        --log-level=INFO,org.keycloak.models.cache.redis:DEBUG,org.keycloak.connections.redis:DEBUG
}

start_site2() {
    echo ""
    echo "Starting Keycloak Site 2 (port 8180, Redis 6380)..."
    echo "Admin Console: http://localhost:8180"
    echo ""
    
    cd "$KEYCLOAK_DIST"
    ./bin/kc.sh start-dev \
        --http-port=8180 \
        --spi-connections-redis-default-host=localhost \
        --spi-connections-redis-default-port=6380 \
        --spi-user-cache-provider=redis \
        --spi-user-cache-redis-cross-dc-enabled=true \
        --spi-user-cache-redis-cross-dc-site-name=site2 \
        --spi-user-cache-redis-cross-dc-remote-sites=site1:localhost:6379 \
        --log-level=INFO,org.keycloak.models.cache.redis:DEBUG,org.keycloak.connections.redis:DEBUG
}

case "$1" in
    site1)
        start_site1
        ;;
    site2)
        start_site2
        ;;
    both)
        echo "Starting both sites in background..."
        echo ""
        
        # Start Site 1 in background
        cd "$KEYCLOAK_DIST"
        nohup ./bin/kc.sh start-dev \
            --http-port=8080 \
            --spi-connections-redis-default-host=localhost \
            --spi-connections-redis-default-port=6379 \
            --spi-user-cache-provider=redis \
            --spi-user-cache-redis-cross-dc-enabled=true \
            --spi-user-cache-redis-cross-dc-site-name=site1 \
            --spi-user-cache-redis-cross-dc-remote-sites=site2:localhost:6380 \
            > "$SCRIPT_DIR/site1.log" 2>&1 &
        SITE1_PID=$!
        echo "Site 1 started (PID: $SITE1_PID) - Log: $SCRIPT_DIR/site1.log"
        
        # Wait a bit for site1 to initialize
        sleep 10
        
        # Start Site 2 in background
        nohup ./bin/kc.sh start-dev \
            --http-port=8180 \
            --spi-connections-redis-default-host=localhost \
            --spi-connections-redis-default-port=6380 \
            --spi-user-cache-provider=redis \
            --spi-user-cache-redis-cross-dc-enabled=true \
            --spi-user-cache-redis-cross-dc-site-name=site2 \
            --spi-user-cache-redis-cross-dc-remote-sites=site1:localhost:6379 \
            > "$SCRIPT_DIR/site2.log" 2>&1 &
        SITE2_PID=$!
        echo "Site 2 started (PID: $SITE2_PID) - Log: $SCRIPT_DIR/site2.log"
        
        echo ""
        echo "Both sites starting..."
        echo "  Site 1: http://localhost:8080 (PID: $SITE1_PID)"
        echo "  Site 2: http://localhost:8180 (PID: $SITE2_PID)"
        echo ""
        echo "View logs:"
        echo "  tail -f $SCRIPT_DIR/site1.log"
        echo "  tail -f $SCRIPT_DIR/site2.log"
        echo ""
        echo "Stop with: pkill -f 'kc.sh'"
        ;;
    *)
        echo "Usage: $0 {site1|site2|both}"
        echo ""
        echo "  site1  - Start Keycloak Site 1 (port 8080, Redis 6379)"
        echo "  site2  - Start Keycloak Site 2 (port 8180, Redis 6380)"
        echo "  both   - Start both sites in background"
        echo ""
        echo "Prerequisites:"
        echo "  1. Build Keycloak: ./mvnw clean install -DskipTests"
        echo "  2. Start Redis: cd $SCRIPT_DIR && docker-compose up -d"
        exit 1
        ;;
esac
