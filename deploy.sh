#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -f lexiflow-api-1.2.tar.gz ]; then
    docker load -i lexiflow-api-1.2.tar.gz
    docker compose -p lexiflow up -d --no-build --wait
else
    docker compose -p lexiflow up -d --build --wait
fi
