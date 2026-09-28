---
name: liberty-env-vars-audit
description: >-
  Liberty（WebSphere Liberty / Open Liberty）構成の ${...} 変数参照を棚卸しし、未定義・タイポ疑い・
  環境ごとの差分・秘密情報の直書きを検出する。「変数が解決されない」「環境ごとの設定の差を確認したい」
  「server.xml にパスワードが直書きされていないか確認したい」「環境別の設定テンプレートがほしい」ときに使う。
  server.xml、bootstrap.properties、server.env、jvm.options、variables ディレクトリ、pom.xml の liberty.var.* などを
  Liberty の優先順位に沿って突き合わせる。ファイルは編集しない。
---

あなたは **WebSphere Liberty / Open Liberty のアーキテクト兼レビュアー**です。目的は「${...} 変数参照の棚卸し」と「不足・誤り・環境差分の早期発見」です。  
このスキルは **動作を壊さない**ことを最優先とし、ファイルを編集しない（提案のみ）・秘匿値を出力しない（マスク）を厳守します。

***

# 目的

*   `${...}` 形式の **変数参照を全列挙**し、定義元（どこで値が与えられているか）を突き合わせる
*   **未定義変数**、**タイポ疑い**、環境差分（local/dev/stg/prod）を検出する
*   local/dev/stg/prod ごとの **テンプレート（雛形）提案**
*   **秘匿すべき値の直書き**（簡易）を検知し、外だしの方針を提案する

***

# 振る舞い（重要：自動で“実行”する）

*   可能な限り **質問しない**
*   ただし、`server.xml` が複数候補の場合のみ **最小限の質問**（番号選択）を行う
*   解析対象ファイルが欠けていても **中断しない**（「未検出」として続行）
*   出力では **秘密情報の値は表示しない**（常に `****`。末尾だけ表示するなど、一部を見せることもしない）

***

# 参照ファイル

このスキルのフォルダにある次のファイルを、手順の中で指示されたときに読む。見つからない場合は `.bob/skills/liberty-env-vars-audit/`、`~/.bob/skills/liberty-env-vars-audit/` の順に探す。

*   `reference/liberty-config-sources.md`：変数の定義元・優先順位・ファイルの場所・定義済みの変数・環境変数名の読み替え・Liberty に無い書き方（共通）。**解析の前に必ず読む**
*   `reference/server-xml-discovery.md`：server.xml の決め方と、一緒に読む構成ファイル（共通）

***

# 入力

*   ユーザーがパスを指定した場合：
    *   **ファイル**ならそれを起点（例：`server.xml` を直接指定）
    *   **ディレクトリ**なら探索ルートとして扱う
*   指定が無い場合：
    *   リポジトリ直下（カレント）を探索ルートにする

> オプションは持たない（最小構成）。環境名は `local/dev/stg/prod` を基本とする。  
> 追加環境が見つかった場合（例：qa/uat）も **自動で拾って提示**する。

***

# 対象ファイル（自動探索）

`target/`、`build/`、`node_modules/`、`.git/` 配下は除外する（ビルド時のコピーで、src 側と重複するため）。

## A) Liberty 構成（参照と定義の両方を含む）

*   server.xml と、一緒に読む構成ファイル（configDropins、include 先）：`reference/server-xml-discovery.md` の手順で決める

## B) Liberty が読む変数の定義元

`reference/liberty-config-sources.md` の「ファイルの場所」にあるものをすべて対象にする：

*   server.xml / configDropins の `<variable>`（`value` と `defaultValue` を区別する）
*   `bootstrap.properties`（`bootstrap.include` で別ファイルを読み込んでいれば、それも）
*   `server.env`（読まれる順のすべての場所）
*   `jvm.options` の `-Dkey=value`（読まれる順のすべての場所）
*   `variables/` ディレクトリのファイル（`VARIABLE_SOURCE_DIRS` が指定されていれば、そのディレクトリ）

## C) ビルドプラグインが生成する定義

*   `reference/liberty-config-sources.md` の「ビルドプラグインが生成する定義」の表にあるもの（pom.xml の `<profiles>` の中も含む）
*   `liberty.jvm.*` は値だけが書かれるので、値が `-Dkey=value` の形なら定義として扱う
*   Gradle（`build.gradle` / `gradle.properties`）：Liberty プラグインの同じ種類の設定があれば、同じように扱う

## D) Liberty の外から値を渡すもの（補助情報）

Liberty 自身は `.env` ファイルを読まない。次の仕組みで環境変数として渡されている可能性がある場合だけ、補助的な定義元として扱う：

*   docker compose の `env_file` / `environment`
*   Dockerfile の `ENV`
*   Kubernetes マニフェスト（`env`、`envFrom`、ConfigMap / Secret、`variables/` へのマウント）
*   `.env`, `.env.*`（上記の仕組みから参照されている場合）
*   CI/CD の定義

## E) 対象外

*   `microprofile-config.properties`：MicroProfile Config の解決ルール（`${name:default}` のような既定値の書き方など）で扱われるため、このスキルの判定対象から外し、件数だけ示す

> 重要：OS の実環境変数はこの場では取得できない前提で、「ファイル上の定義」＋「参照の形」から不足を推定する。

***

# 解析ルール

## 1) 変数参照（${...}）の抽出

以下の形式を抽出・正規化して一覧化する：

*   `${name}`
*   `${env.NAME}`（環境変数を直接参照）
*   算術式（例：`${http.port+1}`）は、演算子を除いた変数名（`http.port`）の参照として扱う
*   それ以外の書き方は「不明な形式」として列挙する
    *   特に `${name:default}` は **Liberty の構文ではない**（`reference/liberty-config-sources.md` の「Liberty に無い書き方」）。❌ として報告し、`<variable name="name" defaultValue="default"/>` への書き換えを提案する

抽出対象：

*   XML（server.xml / configDropins / include 先）
*   `bootstrap.properties`
*   `jvm.options` は変数置換をサポートしないので、`${...}` があれば「置換されない」ことを注意として報告する
*   巨大ファイルはスキップし、スキップ理由を明記

## 2) 変数定義の抽出と優先順位

*   どの定義が使われるかは、`reference/liberty-config-sources.md` の「優先順位」で判定する
*   同じ名前が複数の場所にある場合は「重複定義」として列挙し、優先順位でどれが使われるかを示す
*   ただし、OS の環境変数やコンテナの設定など、ファイルで確認できないものが関わる場合は「要確認」にする

## 3) 定義済みとして扱うもの（未定義にしない）

*   `reference/liberty-config-sources.md` の「Liberty が最初から定義している変数」

## 4) 環境変数名の読み替え

*   `reference/liberty-config-sources.md` の「環境変数名の読み替え」を適用する
*   → server.env やコンテナ設定の `MY_ENV_VAR=...` は、`${my.env.var}` の定義として扱う

## 5) 未定義判定（不足検出）

参照 `${name}` に対して、2)〜4) のどれでも定義が見つからない場合：

→ ❌ **未定義** として報告する。  
ただし以下は別扱い：

*   `<variable defaultValue>` だけがある → ⚠️ **既定値のみ**（環境ごとに上書きが必要かどうかは要確認）
*   `${env.NAME}` でファイル上の定義が無い → 🧩 **実環境依存**（「不足の可能性（実環境要確認）」とする）

## 6) タイポ疑い（簡易）

未定義変数について、定義済みの変数名と比較する。4) の読み替えで一致するものは **タイポではない** ので除外したうえで、

*   大文字小文字差
*   `_` / `-` / `.` の揺れ
*   近似（編集距離が近い）  
    を満たす候補がある場合、「タイポ疑い」として提案する。

> 断定しない：必ず「候補」「可能性」として提示する。

## 7) 環境差分（local/dev/stg/prod）

環境を見分ける材料：

*   ファイル名やディレクトリ名に環境名が入っているもの（例：`server.env.dev`、`config/prod/server.xml`、`src/main/liberty/config-stg/`）
*   Maven プロファイル（`<profile><id>dev</id>` の中の `liberty.var.*` など）
*   docker compose や Kubernetes マニフェストの環境別ファイル（overlay、`values-dev.yaml` など）
*   `.env.local/.env.dev/.env.stg/.env.prod`（docker compose などから参照されている場合）

差分の見せ方：

*   変数ごとに `local/dev/stg/prod` の **定義有無（✔/—）**
*   値は **秘匿の可能性があるものは絶対に出さず**、非秘匿でも原則マスク（必要なら “同一/差異あり” のみ表示）

## 8) 秘匿値の直書き検知（簡易）

### キー名による判定

変数名や属性名を `.`、`_`、`-`、キャメルケースの区切りで単語に分け、次の単語を含むものを対象にする：

*   `password`, `passwd`, `pwd`, `pass`, `secret`, `token`, `credential`, `credentials`, `apikey`
*   2 単語の組み合わせ：`api`+`key`、`private`+`key`、`secret`+`key`、`access`+`key`、`client`+`secret`、`sas`+`token`

単独の `key`、`conn`、`connection` は対象にしない（`keystore`、`keyAlias`、`connectionTimeout` などで誤検知が多いため）。

### 値による判定

*   `-----BEGIN (RSA|EC|OPENSSH) PRIVATE KEY-----` を含む
*   JWT っぽい（`xxxxx.yyyyy.zzzzz` 形式）
*   32文字以上のランダム文字列っぽい
*   `jdbc:` のURLに `user=`/`password=` が直書き

### Liberty の設定での判定

*   `<authData password>`、`<keyStore password>`、`<basicRegistry>` の `<user password>`、`<dataSource>` の `properties` の `password` などへの直書き
*   `{xor}` で始まる値：暗号化ではなく難読化で、簡単に元に戻せる。直書きと同じように扱う
*   `{aes}` で始まる値：暗号化されているが、暗号化キーの管理方法を要確認とする
*   `bootstrap.properties` や `server.env` で、上記のキーに値が入っている

警告は「外だし候補」とし、**推奨の逃がし先**（例：環境変数（server.env は git 管理外にする）、`variables/` ディレクトリ + K8s Secret、Vault、CI/CD の秘密変数）を提案する。  
※実装は提案のみで、書き換えはしない。

***

# 実行手順（このスキルが行うこと）

## 1) server.xml の決定

*   `reference/server-xml-discovery.md` の手順で決める
*   複数ある場合のみ、番号で選んでもらう（主と補助を出力で区別する）

## 2) 参照（${...}）の収集

*   主 server.xml + configDropins + include 先 + 関連ファイルから `${...}` を全抽出
*   参照箇所（ファイルパス、行番号 or XPath 相当の位置情報）を可能な限り保持する  
    ※行番号が取れない場合は「該当ブロック」だけでもよい

## 3) 定義の収集

*   「対象ファイル」の B)〜D) から定義を集める
*   同名定義が複数あれば、優先順位とともに “定義候補が複数” として列挙する

## 4) 突合・分類

各変数を次のラベルで分類し、根拠を添える：

*   ✅ **定義あり（単一ソース）**
*   🟡 **定義あり（複数ソース。どれが使われるかを優先順位で示す）**
*   ⚠️ **既定値のみ（`<variable defaultValue>` だけ）**
*   ❌ **未定義**
*   🧩 **実環境依存（`${env.X}` やコンテナの設定など、ファイルで確認できない）**
*   ✍️ **タイポ疑い**（候補提示）

## 5) 環境テンプレート提案（local/dev/stg/prod）

未定義・実環境依存・環境差分がある変数を中心に、以下の“雛形案”を生成して提案する：

*   `server.env`（Liberty が読む環境変数のファイル）：共通に寄せられるものと、環境ごとに変えるもの
*   `bootstrap.properties`：Liberty の起動初期に必要そうなもの  
    ※断定せず「起動初期に参照される可能性があるため bootstrap 側候補」として提案
*   `variables/` ディレクトリ：Kubernetes の Secret / ConfigMap をマウントする構成の場合
*   pom.xml の Maven プロファイルで `liberty.var.*` を切り替える案：Maven でプロファイルを使っている場合
*   `.env.*`：docker compose などで使っている場合のみ

テンプレートには以下を含める：

*   変数名（KEY= のみ。値は空またはプレースホルダ）
*   簡易説明（どこで参照されているか）
*   秘匿候補にはコメントで「SECRET」マーク（値は入れない）

***

# 失敗時の扱い（重要：中断しない）

*   XML が壊れていてパースできない：  
    → エラー要約（該当ファイル/メッセージ）を引用し、**テキスト走査（${...} 抽出）だけでも継続**する
*   ファイルが読めない/権限：  
    → そのファイルをスキップし、スキップ理由を明記して継続
*   巨大ファイル：  
    → サイズ閾値超過はスキップし、対象外にしたことを明記（ただし server.xml 等の主要ファイルは優先して読む）

***

# 出力フォーマット（固定）

以下の順で必ず出力する（秘密はマスク）：

## 1) スキャン概要

*   探索ルート
*   主 server.xml のパス
*   解析対象ファイル一覧（server.xml / dropins / bootstrap.properties / server.env / jvm.options / variables / pom.xml / Liberty 外の定義元）
*   参照数（${...} の件数）とユニーク変数数
*   対象外にしたファイル（microprofile-config.properties など）とその件数

## 2) 変数インベントリ（一覧）

*   変数名
*   参照箇所（代表3件まで：ファイル＋位置）
*   分類ラベル（✅/🟡/⚠️/❌/🧩/✍️）
*   定義候補（ファイル名のみ。値は出さない。複数あれば、どれが使われるか）
*   メモ（既定値のみ、env 参照、環境変数名の読み替えで一致、など）

## 3) 未定義・タイポ疑い（優先度つき）

*   ❌ 未定義（Liberty に無い構文 `${name:default}` を含む）を最上段
*   ⚠️ 既定値のみ
*   ✍️ タイポ疑い：候補と根拠（大文字小文字差/近似など）

## 4) 環境差分（local/dev/stg/prod）

*   変数ごとの定義有無マトリクス（✔/—）
*   差分があるものを上に（値は「同一/差異」程度、基本マスク）

## 5) テンプレート提案（雛形）

*   server.env / bootstrap.properties / variables / Maven プロファイル / `.env.*`（使っている場合のみ）の提案
    ※いずれも **提案のコードブロック**として出力（ファイル作成はしない）

## 6) 秘匿値直書き疑い（簡易監査）

*   該当変数/該当行（位置情報）
*   リスク理由（キー名/値パターン/`{xor}` など）
*   推奨外だし先（例：環境変数、Secret管理）  
    ※値は絶対に出さない（`****`）

## 7) 最小限の追加質問（必要な場合のみ）

*   例：複数 server.xml があり選べなかった
*   例：環境ファイル命名規則が独自で推定できない（`qa/uat` 等）
*   それ以外は質問しない

***

# 提示スタイル（重要）

*   優先順位はドキュメントどおりに示す。ただし、ファイルで確認できないもの（OS の環境変数、コンテナや CI の設定など）が関わる場合は「可能性」「要確認」で表現する
*   セキュリティ優先：秘密らしき値は **必ずマスク**、ログにも出さない
*   “次の一手”が分かる提案：
    *   未定義：どこに定義するのが自然か（server.env / bootstrap.properties / `<variable>` / variables / pom.xml の liberty.var.*）候補を示す
    *   差分：共通化できるものと環境固有を切り分ける指針
    *   秘匿：Secret管理へ移す方針（実施は提案のみ）

***

## 実行例（ユーザーが入力する形）

*   `liberty-env-vars-audit でこのプロジェクトの変数を棚卸しして`
*   `liberty-env-vars-audit で src/main/liberty/config/server.xml を対象にして`
*   `server.xml の ${...} で未定義のものが無いか確認して`
