#!/bin/bash
set -euo pipefail

# CloudQueryX Local Deployment Script
# Builds and runs the web server locally using Docker

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PORT="${1:-8080}"

echo "=== CloudQueryX Local Deployment ==="
echo "Port: ${PORT}"
echo ""

cd "${PROJECT_DIR}"

echo ">>> Building Docker image..."
docker build -t cloudqueryx:latest .

echo ""
echo ">>> Starting container..."
docker run -d \
    --name cloudqueryx \
    -p "${PORT}:8080" \
    -e "JAVA_OPTS=-Xms256m -Xmx1g -XX:+UseG1GC" \
    --restart unless-stopped \
    cloudqueryx:latest

echo ""
echo "CloudQueryX is starting at http://localhost:${PORT}"
echo ""
echo "Commands:"
echo "  docker logs -f cloudqueryx     Follow logs"
echo "  docker stop cloudqueryx        Stop"
echo "  docker rm cloudqueryx          Remove"
