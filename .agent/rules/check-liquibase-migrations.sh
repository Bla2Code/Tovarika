#!/usr/bin/env bash
set -euo pipefail

repository_root="$(git rev-parse --show-toplevel)"
cd "$repository_root"

migrations_directory="src/main/resources/db/changelog/changes"
master_changelog="src/main/resources/db/changelog/db.changelog-master.yaml"

changed_published="$(git diff HEAD --name-status --diff-filter=MDR -- "$migrations_directory")"
if [[ -n "$changed_published" ]]; then
    echo "ERROR: migration files already present in HEAD are immutable:" >&2
    echo "$changed_published" >&2
    echo "Create a new numbered changeSet instead of changing an existing migration." >&2
    exit 1
fi

new_migrations="$(
    {
        git diff HEAD --name-only --diff-filter=A -- "$migrations_directory"
        git ls-files --others --exclude-standard -- "$migrations_directory"
    } | sort -u
)"

while IFS= read -r migration; do
    [[ -z "$migration" ]] && continue
    changelog_path="${migration#src/main/resources/}"
    if ! rg --fixed-strings --quiet "file: $changelog_path" "$master_changelog"; then
        echo "ERROR: new migration is not included in $master_changelog: $migration" >&2
        exit 1
    fi
done <<< "$new_migrations"

duplicate_ids="$(
    rg --no-filename '^\s+id:\s+\S+' "$migrations_directory" \
        | sed -E 's/^\s*id:\s*//' \
        | sort \
        | uniq -d
)"
if [[ -n "$duplicate_ids" ]]; then
    echo "ERROR: duplicate Liquibase changeSet IDs:" >&2
    echo "$duplicate_ids" >&2
    exit 1
fi

echo "Liquibase migration rules: OK"
