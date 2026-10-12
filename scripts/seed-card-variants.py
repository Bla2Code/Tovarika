#!/usr/bin/env python3
"""Validate authoring data and emit a new, immutable Liquibase data migration."""
import argparse
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]


def nonempty(value, limit):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= limit and "\x00" not in value


def validate(data):
    if (type(data.get("schemaVersion")) is not int or data.get("schemaVersion") != 1 or data.get("kind") != "template-card-variants"
            or data.get("templateId") != "tpl_fashion_hero"):
        raise ValueError("Expected schemaVersion=1, template-card-variants, tpl_fashion_hero")
    if not isinstance(data.get("sharedStyle"), dict) or data.get("referenceFile") != "reference.webp":
        raise ValueError("Missing sharedStyle or referenceFile")
    variants = data.get("variants")
    if not isinstance(variants, list) or len(variants) != 10:
        raise ValueError("Expected exactly 10 variants")
    ids, positions = set(), set()
    for variant in variants:
        vid, pos = variant.get("id"), variant.get("position")
        if not isinstance(vid, str) or not re.fullmatch(r"tpl_fashion_hero_[A-Za-z0-9_]{1,23}", vid) or len(vid) > 40:
            raise ValueError("Invalid variant ID")
        if type(pos) is not int or not 1 <= pos <= 10 or vid in ids or pos in positions:
            raise ValueError("Duplicate ID/position or invalid position")
        ids.add(vid)
        positions.add(pos)
        if not nonempty(variant.get("title"), 200) or not nonempty(variant.get("defaultIdea"), 2000):
            raise ValueError("Invalid title/defaultIdea")
        recipe = variant.get("generationRecipe")
        if not isinstance(recipe, dict) or type(recipe.get("schemaVersion")) is not int or recipe.get("schemaVersion") != 1 or recipe.get("framingMode") not in ("full_product", "detail"):
            raise ValueError("Invalid generationRecipe")
        if not all(nonempty(recipe.get(key), 4000) for key in ("composition", "fallbackComposition")):
            raise ValueError("Invalid composition/fallback")
        conditions = recipe.get("conditions")
        if not isinstance(conditions, list) or not conditions or not all(nonempty(c, 2000) for c in conditions):
            raise ValueError("Invalid conditions")
    return sorted(variants, key=lambda v: v["position"])


def sql_literal(value):
    # Standard-conforming PostgreSQL strings: quotes are doubled; backslashes stay literal.
    return "'" + value.replace("'", "''") + "'"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=pathlib.Path, default=ROOT / "assets/templates/tpl_fashion_hero/card-variants.json")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.source.name.startswith(".env") or args.source.name == "openai_api_key" or "/run/secrets/" in str(args.source.resolve()):
        parser.error("Sensitive source path is forbidden")
    try:
        variants = validate(json.loads(args.source.read_text(encoding="utf-8")))
        if args.check:
            print("Card variants: OK (10)")
            return
        if not args.output:
            parser.error("Use --check or supply a new --output file")
        output = args.output.resolve()
        changes = ROOT / "src/main/resources/db/changelog/changes"
        if output.parent != changes or not re.fullmatch(r"\d{3}-[a-z0-9-]+\.yaml", output.name):
            raise ValueError("Output must be a numbered YAML file in db/changelog/changes")
        relative = output.relative_to(ROOT).as_posix()
        published = subprocess.run(["git", "cat-file", "-e", "HEAD:" + relative], cwd=ROOT,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if published or output.exists():
            raise ValueError("Refusing to overwrite an existing/published migration")
        rows = []
        for v in variants:
            recipe = json.dumps(v["generationRecipe"], ensure_ascii=False, separators=(",", ":"))
            rows.append("(" + ", ".join([sql_literal(v["id"]), "'tpl_fashion_hero'", str(v["position"]),
                                        sql_literal(v["title"]), sql_literal(v["defaultIdea"]),
                                        sql_literal(recipe) + "::jsonb"]) + ")")
        sql = ("SET LOCAL standard_conforming_strings = on;\n"
               "INSERT INTO template_card_variants(id,template_id,position,title,default_idea,generation_recipe) VALUES\n"
               + ",\n".join(rows)
               + "\nON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title, default_idea=EXCLUDED.default_idea,\n"
                 "generation_recipe=EXCLUDED.generation_recipe\n"
                 "WHERE template_card_variants.template_id='tpl_fashion_hero'\n"
                 "AND template_card_variants.position=EXCLUDED.position;\n")
        content = ("databaseChangeLog:\n  - changeSet:\n      id: " + output.stem
                   + "\n      author: codex\n      runInTransaction: true\n      changes:\n"
                     "        - sql:\n            splitStatements: false\n            sql: |\n"
                   + "".join("              " + line + "\n" for line in sql.splitlines()))
        with output.open("x", encoding="utf-8") as target:
            target.write(content)
        print("Created " + relative + "; include it at the end of db.changelog-master.yaml")
    except (ValueError, OSError, TypeError, AttributeError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
