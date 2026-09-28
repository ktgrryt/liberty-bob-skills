#!/usr/bin/env bash
# skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java のテスト。
#
# 使い方:
#   tests/scripts/test-generate-required-features.sh               偽のビルドコマンドでテストする（Maven は不要）
#   tests/scripts/test-generate-required-features.sh --with-maven  tests/fixtures/sample-app で本物の generate-features も試す
set -uo pipefail
. "$(dirname "$0")/lib.sh"

WITH_MAVEN=0
[ "${1:-}" = "--with-maven" ] && WITH_MAVEN=1

GRF="$ROOT/skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java"
WORK=$(mktemp -d)
trap 'touch "$WORK/stop"; rm -rf "$WORK"' EXIT

# 偽のプロジェクト：CRLF のファイル、権限 640 のファイル、include 先、configDropins、コメント内の feature を含む
P="$WORK/project"
C="$P/src/main/liberty/config"
mkdir -p "$C/configDropins/defaults" "$C/configDropins/overrides"
cat > "$C/server.xml" <<'EOF'
<server>
  <featureManager>
    <feature>jakartaee-10.0</feature>
    <feature>microProfile-7.0</feature>
    <feature>mpHealth-4.0</feature>
    <!-- <feature>webProfile-10.0</feature> -->
  </featureManager>
  <include location="extra.xml"/>
  <include location="${shared.config.dir}/x.xml" optional="true"/>
</server>
EOF
chmod 640 "$C/server.xml"
printf '<server>\r\n  <featureManager>\r\n    <platform>jakartaee-10.0</platform>\r\n  </featureManager>\r\n</server>\r\n' > "$C/extra.xml"
echo '<server><featureManager><feature>webProfile-10.0</feature></featureManager></server>' > "$C/configDropins/defaults/a.xml"
echo '<server><featureManager><feature>OLD-1.0</feature></featureManager></server>' > "$C/configDropins/overrides/generated-features.xml"

# 偽のビルドコマンド：一時的な変更を確かめてから generated-features.xml を書く。
# FAKE_EXIT で終了コード、FAKE_WAIT=1 で stop ファイルができるまで待つ（シグナルのテスト用）
cat > "$P/fake-build.sh" <<EOF
#!/bin/sh
C=src/main/liberty/config
echo "marks=\$(grep -c 'liberty-feature-min:' \$C/server.xml)"
echo "nested=\$(grep -c '<!-- <!--' \$C/server.xml)"
test -e \$C/configDropins/overrides/generated-features.xml && { echo "generated file was not moved aside"; exit 9; }
touch "$WORK/started"
if [ -n "\${FAKE_WAIT:-}" ]; then
  i=0; while [ ! -f "$WORK/stop" ] && [ \$i -lt 300 ]; do sleep 0.1; i=\$((i + 1)); done
fi
mkdir -p \$C/configDropins/overrides
echo '<server><featureManager><feature>restfulWS-3.1</feature><feature>cdi-4.0</feature></featureManager></server>' > \$C/configDropins/overrides/generated-features.xml
exit \${FAKE_EXIT:-0}
EOF
chmod +x "$P/fake-build.sh"

run() { out=$(cd "$P" && java "$GRF" "$@" 2>&1); code=$?; }
run_build() { run --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./fake-build.sh; }
wait_started() { for _ in $(seq 1 150); do [ -f "$WORK/started" ] && return 0; sleep 0.1; done; return 1; }

# 更新日時を古くしておき、元に戻したときに更新日時も戻るかを確かめる（ref より新しいファイルがあれば戻っていない）
find "$P/src" -type f -exec touch -t 202001010000 {} +
touch -t 202001010100 "$WORK/ref"

BEFORE=$(snapshot "$P/src")

section "正常に生成する"
run_build
expect_exit "終了コード 0" $code 0
expect_line "RESULT=OK" "$out" "RESULT=OK"
expect_match "server.xml の feature を外す" "$out" '^COMMENTED_OUT=.*server.xml: mpHealth-4.0$'
expect_match "include 先の platform を外す" "$out" '^COMMENTED_OUT=.*extra.xml: platform jakartaee-10.0$'
expect_match "configDropins の feature を外す" "$out" '^COMMENTED_OUT=.*a.xml: webProfile-10.0$'
expect_match "変数を使った include 先は対象外" "$out" '^SKIPPED_INCLUDE=\$\{shared.config.dir\}/x.xml'
expect_line "既存の generated-features.xml の中身を示す" "$out" "GENERATED_BEFORE_FEATURE=OLD-1.0"
expect_line "生成結果を示す" "$out" "REQUIRED_FEATURE=cdi-4.0"
LOG=$(cat "$P/target/lfm/build.log")
expect_line "ビルド中は server.xml の 3 つの feature が外れている" "$LOG" "marks=3"
expect_line "コメント内の feature には手を付けない（コメントが入れ子にならない）" "$LOG" "nested=0"
expect_same_tree "ソースツリーが元どおり（CRLF・権限を含む）" "$BEFORE" "$P/src"
newer=$(find "$P/src" -type f -newer "$WORK/ref")
[ -z "$newer" ] && pass "更新日時も元どおり（前回の結果を再利用できる）" || fail "更新日時が変わった" "$newer"
[ ! -d "$P/target/lfm/backup" ] && pass "バックアップを削除する" || fail "バックアップが残っている"

section "ビルドが失敗する"
FAKE_EXIT=1 run_build
expect_exit "終了コード 1" $code 1
expect_line "RESULT=BUILD_FAILED" "$out" "RESULT=BUILD_FAILED"
expect_line "失敗したビルドの結果は使わない" "$out" "REQUIRED=NONE"
expect_same_tree "ソースツリーが元どおり" "$BEFORE" "$P/src"

section "generated-features.xml と overrides フォルダが元々無い"
rm -rf "$C/configDropins/overrides"
BEFORE2=$(snapshot "$P/src")
run_build
expect_line "RESULT=OK" "$out" "RESULT=OK"
expect_line "元々無かったことを示す" "$out" "GENERATED_BEFORE=NONE"
expect_same_tree "生成されたファイルとフォルダを削除する" "$BEFORE2" "$P/src"
mkdir -p "$C/configDropins/overrides"
echo '<server><featureManager><feature>OLD-1.0</feature></featureManager></server>' > "$C/configDropins/overrides/generated-features.xml"

section "SIGTERM（Ctrl+C 相当）で止める"
rm -f "$WORK/started" "$WORK/stop"
(cd "$P" && FAKE_WAIT=1 exec java "$GRF" --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./fake-build.sh > "$WORK/term.txt" 2>&1) &
JPID=$!
wait_started && kill -TERM $JPID
wait $JPID; code=$?
touch "$WORK/stop"
out=$(cat "$WORK/term.txt")
expect_exit "シグナルで終了する（143）" $code 143
expect_line "RESULT=INTERRUPTED" "$out" "RESULT=INTERRUPTED"
expect_line "止められたときの結果は使わない" "$out" "REQUIRED=NONE"
expect_same_tree "ソースツリーが元どおり" "$BEFORE" "$P/src"

section "sh -c でまとめたビルドを止めたときは、子のプロセスも止める"
rm -f "$WORK/started" "$WORK/stop"
(cd "$P" && FAKE_WAIT=1 exec java "$GRF" --server-xml src/main/liberty/config/server.xml --out target/lfm \
  -- sh -c "./fake-build.sh && true" > "$WORK/sh.txt" 2>&1) &
JPID=$!
wait_started && kill -TERM $JPID
wait $JPID
# 子のプロセスが残っていれば、stop ファイルができた後に generated-features.xml を書く
touch "$WORK/stop"
sleep 1
out=$(cat "$WORK/sh.txt")
expect_line "RESULT=INTERRUPTED" "$out" "RESULT=INTERRUPTED"
expect_same_tree "止めた後に子のプロセスがファイルを書かない" "$BEFORE" "$P/src"

section "SIGKILL（強制終了）の後に --restore で戻す"
rm -f "$WORK/started" "$WORK/stop"
(cd "$P" && FAKE_WAIT=1 exec java "$GRF" --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./fake-build.sh > /dev/null 2>&1) &
JPID=$!
wait_started && kill -KILL $JPID
wait $JPID 2>/dev/null
touch "$WORK/stop"
sleep 0.5
if grep -q 'liberty-feature-min:' "$C/server.xml"; then pass "強制終了すると一時的な変更が残る（前提の確認）"; else fail "一時的な変更が残っていない"; fi
run_build
expect_exit "戻っていない状態での再実行は終了コード 2" $code 2
expect_line "PREVIOUS_RUN_NOT_RESTORED を返す" "$out" "CATEGORY=PREVIOUS_RUN_NOT_RESTORED"
expect_match "戻すためのコマンドにスクリプトの場所を含める" "$out" '^RESTORE_COMMAND=java ".*/GenerateRequiredFeatures.java" --restore '
restore_cmd=$(printf '%s\n' "$out" | sed -n 's/^RESTORE_COMMAND=//p')
out=$(cd "$P" && eval "$restore_cmd" 2>&1)
expect_line "示されたコマンドをそのまま実行して戻せる" "$out" "RESULT=RESTORED"
expect_same_tree "ソースツリーが元どおり" "$BEFORE" "$P/src"
run --restore target/lfm
expect_line "戻すものが無ければ NOTHING_TO_RESTORE" "$out" "RESULT=NOTHING_TO_RESTORE"

section "実行前に止める（何も変更しない）"
run --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./mvnw clean compile liberty:generate-features
expect_line "clean を拒否する" "$out" "CATEGORY=CLEAN_NOT_ALLOWED"
run --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./gradlew :app:clean generateFeatures
expect_line "Gradle のタスク指定の clean も拒否する" "$out" "CATEGORY=CLEAN_NOT_ALLOWED"
run --server-xml src/main/liberty/config/server.xml --out src/main/liberty/config/tmp -- ./fake-build.sh
expect_line "出力先が構成ディレクトリの中なら拒否する" "$out" "CATEGORY=BAD_OUT_DIR"
run --server-xml no/such/server.xml --out target/lfm -- ./fake-build.sh
expect_line "server.xml が無ければ止める" "$out" "CATEGORY=SERVER_XML_NOT_FOUND"
run --server-xml src/main/liberty/config/server.xml --out target/lfm --timeout-minutes 0 -- ./fake-build.sh
expect_exit "制限時間が 1 分未満なら終了コード 2" $code 2
cp "$C/extra.xml" "$WORK/extra.bak"
printf '<!-- liberty-feature-min: leftover -->\n' >> "$C/extra.xml"
run_build
expect_line "前回の一時変更の印が残っていれば止める" "$out" "CATEGORY=LEFTOVER_MARK"
cp "$WORK/extra.bak" "$C/extra.xml"
expect_same_tree "ソースツリーが元どおり" "$BEFORE" "$P/src"

section "Java 8 向けのコンパイル"
if javac --release 8 -Xlint:-options -d "$WORK/classes" "$GRF" 2>/dev/null; then
  pass "--release 8 でコンパイルできる"
  out=$(cd "$P" && java -cp "$WORK/classes" GenerateRequiredFeatures --server-xml src/main/liberty/config/server.xml --out target/lfm -- ./fake-build.sh 2>&1)
  expect_line "コンパイルしたクラスで動く" "$out" "RESULT=OK"
  expect_same_tree "ソースツリーが元どおり" "$BEFORE" "$P/src"
else
  fail "--release 8 でコンパイルできない"
fi

# 権限を変えるので、偽のプロジェクトを使うテストの最後に置く
section "書き換えに失敗する（読み取り専用のファイル）"
chmod 444 "$C/extra.xml"
if [ -w "$C/extra.xml" ]; then
  echo "  skip  root で実行しているので、読み取り専用のファイルに書けてしまう"
else
  BEFORE_RO=$(snapshot "$P/src")
  run_build
  expect_exit "終了コード 2" $code 2
  expect_line "IO_ERROR を返す" "$out" "CATEGORY=IO_ERROR"
  expect_line "RESULT=ERROR（BUILD_FAILED ではない）" "$out" "RESULT=ERROR"
  expect_same_tree "ソースツリーが元どおり" "$BEFORE_RO" "$P/src"
fi

if [ "$WITH_MAVEN" -eq 1 ]; then
  section "本物の generate-features（tests/fixtures/sample-app）"
  cp -R "$ROOT/tests/fixtures/sample-app" "$WORK/sample-app"
  rm -rf "$WORK/sample-app/target"
  BEFORE_APP=$(snapshot "$WORK/sample-app/src")
  out=$(cd "$WORK/sample-app" && java "$GRF" --server-xml src/main/liberty/config/server.xml --out target/liberty-feature-min \
    -- mvn -B compile liberty:generate-features 2>&1)
  code=$?
  expect_exit "終了コード 0" $code 0
  for f in restfulWS-3.1 cdi-4.0 jsonb-3.0 mpRestClient-3.0; do
    expect_line "必要な feature に $f が入る" "$out" "REQUIRED_FEATURE=$f"
  done
  expect_no_match "使っていない mpHealth は入らない" "$out" '^REQUIRED_FEATURE=mpHealth'
  expect_same_tree "ソースツリーが元どおり" "$BEFORE_APP" "$WORK/sample-app/src"
  if [ "$code" -ne 0 ]; then
    tail -30 "$WORK/sample-app/target/liberty-feature-min/build.log" 2>/dev/null
  fi
fi

finish
