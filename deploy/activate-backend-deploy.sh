#!/usr/bin/env bash

# Shared final deployment step for local deploy-local.sh and GitHub Actions.
# It publishes immutable template references before starting the Spring app,
# whose Liquibase migrations make those references available in the catalog.
set -Eeuo pipefail

DEPLOY_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.prod.yml"
ENV_FILE="$DEPLOY_DIR/.env"
IMAGE_ARCHIVE="$DEPLOY_DIR/tovarika-backend.tar.gz"
TEMPLATE_ARCHIVE="$DEPLOY_DIR/template-assets.tar.gz"
TEMPLATE_DIR="$DEPLOY_DIR/template-assets"
SYNC_SCRIPT="$DEPLOY_DIR/sync-template-assets.sh"

[[ -s "$ENV_FILE" ]] || { echo "Missing $ENV_FILE" >&2; exit 1; }
[[ -s "$DEPLOY_DIR/secrets/openai_api_key" ]] || { echo "Missing $DEPLOY_DIR/secrets/openai_api_key" >&2; exit 1; }
[[ -s "$COMPOSE_FILE" ]] || { echo "Missing $COMPOSE_FILE" >&2; exit 1; }
[[ -s "$IMAGE_ARCHIVE" ]] || { echo "Missing $IMAGE_ARCHIVE" >&2; exit 1; }
[[ -s "$TEMPLATE_ARCHIVE" ]] || { echo "Missing $TEMPLATE_ARCHIVE" >&2; exit 1; }
[[ -s "$SYNC_SCRIPT" ]] || { echo "Missing $SYNC_SCRIPT" >&2; exit 1; }

stage_dir="$(mktemp -d "$DEPLOY_DIR/template-assets.stage.XXXXXX")"
cleanup() {
  rm -rf -- "$stage_dir"
}
trap cleanup EXIT

tar -xzf "$TEMPLATE_ARCHIVE" -C "$stage_dir"
[[ -d "$stage_dir/templates" ]] || { echo "Template archive does not contain templates/" >&2; exit 1; }

rm -rf -- "$TEMPLATE_DIR"
mv "$stage_dir/templates" "$TEMPLATE_DIR"

chmod 600 "$DEPLOY_DIR/secrets/openai_api_key"
chmod 755 "$SYNC_SCRIPT"

APP_UID="$(id -u)"
APP_GID="$(id -g)"
export APP_UID APP_GID

cd "$DEPLOY_DIR"
gunzip -c "$IMAGE_ARCHIVE" | docker load

if ! docker network inspect tovarika-edge >/dev/null 2>&1; then
  docker network create tovarika-edge >/dev/null 2>&1 || docker network inspect tovarika-edge >/dev/null 2>&1
fi

docker compose --env-file .env -f docker-compose.prod.yml up -d minio
docker compose --env-file .env -f docker-compose.prod.yml --profile template-sync run --rm template-assets-init
docker compose --env-file .env -f docker-compose.prod.yml up -d --remove-orphans

docker image prune -f
docker compose --env-file .env -f docker-compose.prod.yml ps
rm -f -- "$IMAGE_ARCHIVE" "$TEMPLATE_ARCHIVE"
