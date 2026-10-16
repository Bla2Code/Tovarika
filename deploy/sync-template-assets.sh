#!/bin/sh

# Runs inside the one-off template-assets-init Compose service.
# Reference objects are immutable: a changed file must use a new object key and
# a new Liquibase migration that points the template at that key.
set -eu

: "${MINIO_ENDPOINT:?MINIO_ENDPOINT is required}"
: "${MINIO_ACCESS_KEY:?MINIO_ACCESS_KEY is required}"
: "${MINIO_SECRET_KEY:?MINIO_SECRET_KEY is required}"
: "${MINIO_BUCKET:?MINIO_BUCKET is required}"

readonly minio_alias="template-storage"

mc alias set "$minio_alias" "$MINIO_ENDPOINT" "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
mc mb --ignore-existing "$minio_alias/$MINIO_BUCKET" >/dev/null

count=0
for source in /template-assets/*/*.webp; do
  [ -f "$source" ] || continue

  relative_path="${source#/template-assets/}"
  object="$minio_alias/$MINIO_BUCKET/templates/$relative_path"
  set -- $(sha256sum "$source")
  source_hash="$1"

  if mc stat "$object" >/dev/null 2>&1; then
    downloaded="$(mktemp /tmp/template-asset.XXXXXX)"
    rm -f "$downloaded"
    if ! mc cp "$object" "$downloaded" >/dev/null; then
      rm -f "$downloaded"
      printf 'Cannot read existing template object: %s\n' "$object" >&2
      exit 1
    fi
    set -- $(sha256sum "$downloaded")
    remote_hash="$1"
    rm -f "$downloaded"

    if [ "$remote_hash" != "$source_hash" ]; then
      printf 'Template object differs from the committed reference: %s\n' "$object" >&2
      printf 'Keep published objects immutable: use a new WebP name and a new Liquibase migration.\n' >&2
      exit 1
    fi

    printf 'Verified existing template object: %s\n' "$object"
  else
    mc cp --attr "Content-Type=image/webp" "$source" "$object"
    printf 'Uploaded template object: %s\n' "$object"
  fi

  count=$((count + 1))
done

if [ "$count" -eq 0 ]; then
  printf 'No WebP template references found in /template-assets.\n' >&2
  exit 1
fi

printf 'Template asset sync completed: %s object(s).\n' "$count"
