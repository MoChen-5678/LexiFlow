#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ ! -f "${TENCENT_CSV_PATH:-./SecretKey.csv}" ]; then
    echo "请将 SecretKey.csv 放到部署目录，或设置 TENCENT_CSV_PATH 指向私有密钥文件。" >&2
    exit 1
fi
if [ -f lexiflow-api-1.4.1.tar.gz ]; then
    docker load -i lexiflow-api-1.4.1.tar.gz
    docker compose -p lexiflow up -d --no-build --wait
else
    docker compose -p lexiflow up -d --build --wait
fi
