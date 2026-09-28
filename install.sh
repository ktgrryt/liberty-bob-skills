#!/usr/bin/env bash
# liberty-bob-skills の Skill を、IBM Bob の Skills フォルダにコピーする。
#
# 使い方:
#   /path/to/liberty-bob-skills/install.sh                 カレントディレクトリのプロジェクト（./.bob/skills）に入れる
#   /path/to/liberty-bob-skills/install.sh --project DIR   DIR/.bob/skills に入れる
#   /path/to/liberty-bob-skills/install.sh --global        ~/.bob/skills に入れる
#
# オプション:
#   --force        既に入っている Skill を置き換える（その Skill のフォルダを削除してからコピーする）
#   <Skill 名>...  指定した Skill だけを入れる（省略するとすべて）
set -euo pipefail

repo=$(cd "$(dirname "$0")" && pwd)

usage() {
  sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
}

project=$PWD
global=0
force=0
names=""
while [ $# -gt 0 ]; do
  case "$1" in
    --project)
      [ $# -ge 2 ] || { echo "--project にはディレクトリを指定してください" >&2; exit 2; }
      project=$2
      shift 2
      ;;
    --global) global=1; shift ;;
    --force) force=1; shift ;;
    -h | --help) usage; exit 0 ;;
    -*) echo "不明なオプション: $1" >&2; usage >&2; exit 2 ;;
    *) names="$names $1"; shift ;;
  esac
done

if [ "$global" -eq 1 ]; then
  dest="$HOME/.bob/skills"
else
  [ -d "$project" ] || { echo "ディレクトリがありません: $project" >&2; exit 2; }
  project=$(cd "$project" && pwd)
  if [ "$project" = "$repo" ]; then
    echo "このリポジトリ自身には入れられません。--project で対象のプロジェクトを指定するか、--global を使ってください" >&2
    exit 2
  fi
  dest="$project/.bob/skills"
fi

available=""
for d in "$repo"/skills/*/; do
  [ -f "$d/SKILL.md" ] && available="$available $(basename "$d")"
done
[ -n "$names" ] || names=$available
for name in $names; do
  case " $available " in
    *" $name "*) ;;
    *) echo "不明な Skill: ${name}（使えるもの:${available}）" >&2; exit 2 ;;
  esac
done

# 共通の参照資料のコピーが古いまま入らないように確かめる（開発中のリポジトリから入れる場合）
if ! "$repo/tools/sync-shared.sh" --check > /dev/null 2>&1; then
  echo "注意: skills/*/reference/ が shared/ と一致していません。tools/sync-shared.sh を実行してから入れ直すことをおすすめします" >&2
fi

mkdir -p "$dest"
installed=0
skipped=0
for name in $names; do
  target="$dest/$name"
  if [ -e "$target" ]; then
    if [ "$force" -eq 0 ]; then
      echo "skip:      ${target}（既にあります。置き換えるには --force）"
      skipped=$((skipped + 1))
      continue
    fi
    rm -rf "$target"
    echo "replaced:  $target"
  else
    echo "installed: $target"
  fi
  cp -R "$repo/skills/$name" "$target"
  installed=$((installed + 1))
done

echo
echo "$installed 件をコピーしました（${dest}）"
[ "$skipped" -eq 0 ] || echo "$skipped 件は既にあるためスキップしました"
