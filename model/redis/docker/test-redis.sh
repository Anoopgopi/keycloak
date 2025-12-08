#!/bin/bash

# Test Redis connectivity and inspect Keycloak data
# Usage: ./test-redis.sh [command]
# Commands:
#   ping     - Test connectivity
#   keys     - List all Keycloak keys
#   sessions - Show user session count
#   auth     - Show authentication session count
#   monitor  - Monitor Redis commands in real-time
#   flush    - Clear all Keycloak data (DANGEROUS!)

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

cmd="${1:-ping}"

case "$cmd" in
    ping)
        echo "Testing Redis connectivity..."
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING
        ;;
    
    keys)
        echo "Keycloak keys in Redis:"
        echo "========================"
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:*" | sort
        ;;
    
    sessions)
        echo "Session counts:"
        echo "==============="
        echo -n "User sessions: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:sessions:*" | wc -l
        echo -n "Offline sessions: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:offlineSessions:*" | wc -l
        echo -n "Client sessions: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:clientSessions:*" | wc -l
        echo -n "Offline client sessions: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:offlineClientSessions:*" | wc -l
        ;;
    
    auth)
        echo "Authentication session count:"
        echo "============================="
        echo -n "Auth sessions: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:authenticationSessions:*" | wc -l
        echo -n "Action tokens: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:actionTokens:*" | wc -l
        ;;
    
    failures)
        echo "Login failure count:"
        echo "===================="
        echo -n "Login failures: "
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:loginFailures:*" | wc -l
        ;;
    
    stats)
        echo "Redis statistics:"
        echo "================="
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" INFO stats
        ;;
    
    memory)
        echo "Redis memory:"
        echo "============="
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" INFO memory
        ;;
    
    monitor)
        echo "Monitoring Redis commands (Ctrl+C to stop)..."
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" MONITOR
        ;;
    
    get)
        if [ -z "$2" ]; then
            echo "Usage: $0 get <key>"
            exit 1
        fi
        echo "Getting key: $2"
        redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" GET "$2"
        ;;
    
    flush)
        echo "WARNING: This will delete ALL Keycloak data from Redis!"
        read -p "Are you sure? (type 'yes' to confirm): " confirm
        if [ "$confirm" = "yes" ]; then
            echo "Flushing Keycloak keys..."
            redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" KEYS "kc:*" | xargs -r redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" DEL
            echo "Done."
        else
            echo "Cancelled."
        fi
        ;;
    
    all)
        echo "=== Redis Health Check ==="
        $0 ping
        echo ""
        $0 sessions
        echo ""
        $0 auth
        echo ""
        $0 failures
        ;;
    
    *)
        echo "Usage: $0 {ping|keys|sessions|auth|failures|stats|memory|monitor|get|flush|all}"
        exit 1
        ;;
esac
