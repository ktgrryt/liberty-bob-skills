#!/usr/bin/env bash
# shared/ の共通ファイルを、shared/targets.txt に書いた各 Skill の reference/ にコピーする。
#
# 使い方:
#   tools/sync-shared.sh          コピーする（targets.txt から外れた古いコピーは削除する）
#   tools/sync-shared.sh --check  コピーが shared/ とずれていないか確認する（ずれていれば終了コード 1。CI 用）
#
# Skill のフォルダは 1 つずつコピーして使われるので、別のフォルダを参照せず、各 Skill に同じファイルを置いている。
set -euo pipefail

cd "$(dirname "$0")/.."

mode=sync
case "${1:-}" in
  "") ;;
  --check) mode=check ;;
  *) echo "Usage: $0 [--check]" >&2; exit 2 ;;
esac

# コピーしたファイルの先頭に付ける行。古いコピーを見分けるのにも使う
marker="このファイルは shared/ からコピーしたものです"

expected_content() {
  printf '<!-- %s。編集は shared/%s で行い、tools/sync-shared.sh を実行してください。 -->\n\n' "$marker" "$1"
  cat "shared/$1"
}

status=0
listed=$(mktemp)
trap 'rm -f "$listed"' EXIT

while read -r file skills; do
  case "$file" in "" | "#"*) continue ;; esac
  if [ ! -f "shared/$file" ]; then
    echo "missing: shared/$file" >&2
    status=1
    continue
  fi
  for skill in $skills; do
    if [ ! -d "skills/$skill" ]; then
      echo "unknown skill: $skill (shared/targets.txt)" >&2
      status=1
      continue
    fi
    dest="skills/$skill/reference/$file"
    echo "$dest" >> "$listed"
    if [ "$mode" = check ]; then
      if ! expected_content "$file" | cmp -s - "$dest"; then
        echo "out of sync: $dest"
        status=1
      fi
    else
      mkdir -p "skills/$skill/reference"
      expected_content "$file" > "$dest"
      echo "copied: $dest"
    fi
  done
done < shared/targets.txt

# targets.txt から外れた古いコピー
for dest in skills/*/reference/*.md; do
  [ -f "$dest" ] || continue
  grep -q "$marker" "$dest" || continue
  grep -qxF "$dest" "$listed" && continue
  if [ "$mode" = check ]; then
    echo "stale copy: $dest"
    status=1
  else
    rm "$dest"
    echo "removed: $dest"
  fi
done

if [ "$mode" = check ] && [ "$status" -eq 0 ]; then
  echo "shared/ and skills/*/reference/ are in sync"
fi
exit "$status"
