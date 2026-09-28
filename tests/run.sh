#!/usr/bin/env bash
# 自動の検査とテストをまとめて実行する。CI と同じ内容を手元で確かめるときに使う。
#
# 使い方:
#   tests/run.sh               Skill の形式、shared/ の同期、スクリプトのテスト（偽のビルド）
#   tests/run.sh --with-maven  上に加えて、tests/fixtures/sample-app で本物の generate-features も試す
set -uo pipefail

cd "$(dirname "$0")/.."
status=0

step() {
  echo
  echo "================ $1"
  shift
  if "$@"; then echo "---------------- ok"; else echo "---------------- FAILED"; status=1; fi
}

step "Skill の形式" python3 tools/validate-skills.py
step "shared/ の同期" tools/sync-shared.sh --check
step "JdbcPing" tests/scripts/test-jdbc-ping.sh
step "GenerateRequiredFeatures" tests/scripts/test-generate-required-features.sh "$@"

echo
[ "$status" -eq 0 ] && echo "all passed" || echo "some checks FAILED"
exit "$status"
