# テスト用の共通関数。各テストスクリプトから source して使う。
# macOS（BSD）と Linux（GNU）の両方で動くように、sed -i や stat は使わない。

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TESTS_FAILED=0
TESTS_PASSED=0

section() { echo; echo "## $1"; }

pass() { echo "  ok    $1"; TESTS_PASSED=$((TESTS_PASSED + 1)); }

fail() {
  echo "  FAIL  $1"
  TESTS_FAILED=$((TESTS_FAILED + 1))
  if [ -n "${2:-}" ]; then
    printf '%s\n' "$2" | sed 's/^/        | /'
  fi
}

# expect_line <説明> <出力> <行>：出力に、その行がそのまま含まれること
expect_line() {
  if printf '%s\n' "$2" | grep -qxF -- "$3"; then pass "$1"; else fail "$1 (expected line: $3)" "$2"; fi
}

# expect_match <説明> <出力> <正規表現>：出力のどこかの行が正規表現に一致すること
expect_match() {
  if printf '%s\n' "$2" | grep -qE -- "$3"; then pass "$1"; else fail "$1 (expected: $3)" "$2"; fi
}

# expect_no_match <説明> <出力> <正規表現>：どの行も正規表現に一致しないこと
expect_no_match() {
  if printf '%s\n' "$2" | grep -qE -- "$3"; then fail "$1 (unexpected: $3)" "$2"; else pass "$1"; fi
}

# expect_exit <説明> <実際の終了コード> <期待する終了コード>
expect_exit() {
  if [ "$2" -eq "$3" ]; then pass "$1"; else fail "$1 (exit $2, expected $3)"; fi
}

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}

# snapshot <ディレクトリ>：ファイルの内容・権限と、ディレクトリの構成を 1 つの文字列にする
snapshot() {
  (cd "$1" && find . -print | LC_ALL=C sort | while read -r p; do
    if [ -f "$p" ]; then
      echo "F $(ls -ln "$p" | cut -c1-10) $(sha256 < "$p") $p"
    else
      echo "D $p"
    fi
  done)
}

# expect_same_tree <説明> <前の snapshot> <ディレクトリ>
expect_same_tree() {
  local now
  now=$(snapshot "$3")
  if [ "$2" = "$now" ]; then pass "$1"; else fail "$1" "$(diff <(printf '%s\n' "$2") <(printf '%s\n' "$now"))"; fi
}

# fetch_jar <groupId:artifactId:version> <出力ディレクトリ>：Maven でドライバーなどを取得する
fetch_jar() {
  mvn -q -B dependency:copy -Dartifact="$1" -DoutputDirectory="$2" > /dev/null
}

finish() {
  echo
  echo "passed: $TESTS_PASSED, failed: $TESTS_FAILED"
  [ "$TESTS_FAILED" -eq 0 ]
}
