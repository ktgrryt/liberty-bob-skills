#!/usr/bin/env bash
# skills/liberty-datasource-create/scripts/JdbcPing.java のテスト。
# H2（インメモリ / ファイル DB）と PostgreSQL のドライバーを Maven で取得して使う。DB サーバーは不要。
set -uo pipefail
. "$(dirname "$0")/lib.sh"

PING="$ROOT/skills/liberty-datasource-create/scripts/JdbcPing.java"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

section "準備（ドライバーの取得）"
fetch_jar com.h2database:h2:2.3.232 "$WORK/drv" && fetch_jar org.postgresql:postgresql:42.7.4 "$WORK/drv" \
  && pass "H2 と PostgreSQL のドライバーを取得" || { fail "ドライバーを取得できない"; finish; exit 1; }
CP="$WORK/drv/*"

run() { out=$(java -cp "$CP" "$PING" "$@" 2>&1); code=$?; }

section "引数と環境変数"
run
expect_exit "引数なしは終了コード 2" $code 2
expect_match "使い方を表示する" "$out" '^Usage: JdbcPing'
export PW_OK=secret123
run "jdbc:h2:mem:t" sa PW_OK abc
expect_exit "タイムアウトが数値でなければ終了コード 2" $code 2
run "jdbc:h2:mem:t" sa NO_SUCH_ENV_FOR_TEST
expect_exit "環境変数が未設定なら終了コード 2" $code 2
expect_line "PASSWORD_ENV_NOT_SET を返す" "$out" "CATEGORY=PASSWORD_ENV_NOT_SET"

section "成功"
run "jdbc:h2:mem:t" sa PW_OK
expect_exit "接続できれば終了コード 0" $code 0
expect_line "RESULT=OK" "$out" "RESULT=OK"
expect_match "製品名を表示する" "$out" '^PRODUCT=H2 '

section "失敗の分類"
java -cp "$CP" "$PING" "jdbc:h2:$WORK/authdb" sa PW_OK > /dev/null 2>&1
export PW_BAD=wrongpass
run "jdbc:h2:$WORK/authdb" sa PW_BAD
expect_exit "失敗は終了コード 1" $code 1
expect_line "パスワード違いは AUTH" "$out" "CATEGORY=AUTH"
run "jdbc:foo://x/y" sa PW_OK
expect_line "URL に合うドライバーが無ければ DRIVER" "$out" "CATEGORY=DRIVER"
run "jdbc:postgresql://localhost:1/db" app PW_OK 3
expect_line "接続拒否は NETWORK" "$out" "CATEGORY=NETWORK"
run "jdbc:postgresql://nonexistent.invalid:5432/db" app PW_OK 3
expect_line "ホスト名を解決できなければ DNS" "$out" "CATEGORY=DNS"

section "--env-file（server.env の形式）から読む"
ENV_PW='pa$$w0rd&x'
# この値のパスワードで H2 のファイル DB を作っておく
PW_ENVFILE_INIT="$ENV_PW" java -cp "$CP" "$PING" "jdbc:h2:$WORK/envdb" sa PW_ENVFILE_INIT > /dev/null 2>&1
printf '# comment\r\nOTHER=1\r\nDB_PW_IN_FILE=old\r\nDB_PW_IN_FILE=%s\r\n' "$ENV_PW" > "$WORK/server.env"
run --env-file "$WORK/server.env" "jdbc:h2:$WORK/envdb" sa DB_PW_IN_FILE
expect_line "\$ や & を含む値をそのまま使う（CRLF、コメント行、同じ名前は後のもの）" "$out" "RESULT=OK"
expect_line "どこから読んだかを示す" "$out" "PASSWORD_SOURCE=$WORK/server.env"
expect_no_match "パスワードを表示しない" "$out" 'pa\$\$w0rd'
run --env-file "$WORK/server.env" "jdbc:h2:mem:t" sa PW_OK
expect_line "環境変数があればそちらを使う" "$out" "PASSWORD_SOURCE=environment"
run --env-file "$WORK/server.env" "jdbc:h2:mem:t" sa NOT_IN_ENV_OR_FILE
expect_line "ファイルにも無ければ PASSWORD_ENV_NOT_SET" "$out" "CATEGORY=PASSWORD_ENV_NOT_SET"
run --env-file "$WORK/no-such.env" "jdbc:h2:mem:t" sa NOT_IN_ENV_OR_FILE
expect_line "ファイルを読めなければ ENV_FILE_NOT_READABLE" "$out" "CATEGORY=ENV_FILE_NOT_READABLE"

section "パスワードを表示しない"
export PW_LEAK=LeakyPw987
run "jdbc:h2:file:$WORK/LeakyPw987/db;IFEXISTS=TRUE" sa PW_LEAK
expect_no_match "メッセージにパスワードを含めない" "$out" 'LeakyPw987'
expect_match "パスワードと同じ文字列を **** に置き換える" "$out" '/\*\*\*\*/db'

section "Java 8 向けのコンパイル"
if javac --release 8 -Xlint:-options -d "$WORK/classes" "$PING" 2>/dev/null; then
  pass "--release 8 でコンパイルできる"
  out=$(java -cp "$WORK/classes:$CP" JdbcPing "jdbc:h2:mem:t" sa PW_OK 2>&1)
  expect_line "コンパイルしたクラスで動く" "$out" "RESULT=OK"
else
  fail "--release 8 でコンパイルできない"
fi

finish
