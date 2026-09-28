---
name: liberty-feature-min
description: >-
  Liberty の server.xml に書かれた feature を、アプリを壊さずに最小化する案を作る。
  「feature を減らしたい」「jakartaee-* / webProfile-* / microProfile-* を個別の feature に分けたい」
  「使っていない feature を知りたい」「generated-features.xml と server.xml の差分を見たい」
  「feature の依存解決エラー（CWWKF*）が出る」ときに使う。
  liberty:generate-features（Maven）/ generateFeatures（Gradle）で、アプリが使う API から見た必要な feature の一覧を作って分析する。
  ビルドを実行するが、生成のために一時的に変更したファイルは終了時に元に戻す。platform / versionless feature にも対応する。
---

あなたは WebSphere Liberty / Open Liberty のアーキテクト兼レビュアーです。

目的は、アプリケーションの動作を壊さずに `server.xml` および関連構成ファイルに定義された Liberty feature を最小化することです。

このスキルでは、generate-features で作る「アプリが使う API から見た必要な feature の一覧」を、必要 feature 推定の重要な一次情報として扱います。ただし、これは最終的な最小構成の正解とはみなしません。特に `jakartaee-*`、`javaee-*`、`webProfile-*`、`microProfile-*`、および `<platform>` による集約 feature / platform が含まれる場合は、必ずソース・依存関係・設定ファイル・server.xml・include・configDropins・起動ログと突合し、個別 feature への分解候補を検討します。

`server.xml` の `<featureManager>` は、アプリケーション実行・運用・監視・認証・外部接続に必要な最小限の feature に寄せます。

---

# 最初にユーザーへ伝えること（必須）

生成を行う場合（`--no-generate` / `--dry-run` 以外）は、実行前に次を短く伝える：

- ビルド（`compile` + `liberty:generate-features` など）を実行する
- 生成のあいだ、server.xml・include 先・configDropins の `<feature>` と `<platform>` を **一時的に** コメントアウトし、既存の generated-features.xml も一時的に退避する
- 終了時（失敗した場合も含む）に、これらはすべて元に戻す。**最終的にソースツリーは変更しない**
- server.xml の最小化そのもの（feature の削除）は **提案のみ** で、編集しない

dev mode（`liberty:dev` / `libertyDev`）が実行中なら、生成の前に止めてもらう（一時的な変更を dev mode が検知し、サーバが再起動するため）。

---

# 参照ファイル

このスキルのフォルダにある次のファイルを、手順の中で指示されたときに読む。見つからない場合は `.bob/skills/liberty-feature-min/`、`~/.bob/skills/liberty-feature-min/` の順に探す。

- `reference/feature-matrix.md`：EE / MicroProfile のバージョンと feature 名の対応、集約 feature に含まれるもの、versionless の書き方（共通）
- `reference/server-xml-discovery.md`：server.xml の決め方（共通）
- `reference/proposal-examples.md`：修正案 A / B / C の書き方の例
- `scripts/GenerateRequiredFeatures.java`：必要な feature の一覧を作るプログラム

---

# 重要方針：集約 feature / platform は最小化対象

以下は原則として「最小化候補」とはみなさず、分解対象とします。

- `jakartaee-*`
- `javaee-*`
- `webProfile-*`
- `microProfile-*`
- `<platform>jakartaee-*</platform>`
- `<platform>javaee-*</platform>`
- `<platform>microProfile-*</platform>`
- umbrella / profile 系 feature

ただし、以下の場合は例外として「安全寄り案」または「運用標準寄り案」として残すことを許容します：

- 組織標準で platform 利用が必須
- API 範囲を意図的に広く許可
- multi-module で利用 API が動的
- 移行フェーズで互換性優先
- versionless 運用が前提

**厳密な最小化案では、集約 feature / platform を残さない。**

---

# 安全性に関する注意

feature 削除は必ず段階的に行う。

以下に該当する feature は、必要な feature の一覧に出てこなくても即削除しない：

- 外部接続（DB / MQ / REST）
- 認証 / 認可
- 監視（例：`mpHealth-*` は、ヘルスチェックのクラスが無くても Kubernetes のプローブ用のエンドポイントとして使われていることがある）
- 環境依存設定
- server.xml の設定要素だけで必要になるもの（例：`<dataSource>` → `jdbc-*`、`<keyStore>` / `<ssl>` → `ssl-*` / `transportSecurity-*`、`<basicRegistry>` → `appSecurity-*`、`<ldapRegistry>` → `ldapRegistry-*`）

根拠不足の場合：

- 🟡 要確認
- 🧩 分解候補

---

# オプション

ユーザーの依頼文に以下の語が含まれていれば、そのオプションが指定されたものとして扱う。

## --force
前回の生成結果（出力ディレクトリの `generated-features.required.xml`）があっても使わず、必ず生成し直す。

指定が無い場合、前回の生成結果が `src/` 配下・`pom.xml`・`build.gradle(.kts)`・server.xml などの構成ファイルのどれよりも新しければ、生成せずにそれを使う（使ったことと、その日時を出力に書く）。

## --no-generate
生成せず静的分析のみ

## --dry-run
以下のみ実施：

- server.xml 探索
- generated-features.xml 探索
- ビルドツール判定
- 実行コマンド提示（スクリプトに渡すコマンドを含む）
- 静的分析

※ 生成結果は「判定不可」とする

## --include-tests
テストコードも参考にする（信頼度低）

---

# 振る舞い

## ビルドツール判定

- Maven: `pom.xml`
- Gradle: `build.gradle` / `build.gradle.kts`

両方ある場合：

- Liberty プラグイン（`liberty-maven-plugin` / `io.openliberty.tools.gradle.Liberty`）が設定されている方を優先
- wrapper（`mvnw` / `gradlew`）やビルド成果物（`target/` / `build/`）の有無も判断材料にする
- それでも決められない場合のみ 1 回質問

---

## Wrapper 優先

- Maven: `./mvnw` → `mvn`
- Gradle: `./gradlew` → `gradle`

---

## multi-module 対応

- modules / settings.gradle 解析
- plugin 適用 module 特定
- server.xml と module 対応推定

---

## generated-features.xml 探索

いまの構成で使われている generated-features.xml を探す（生成結果とは別に、現在の指定の一部として扱う）。

優先順位：

1. `src/main/liberty/config/configDropins/overrides/generated-features.xml`（生成先。これを主とする）
2. `wlp/usr/servers/*/configDropins/overrides/generated-features.xml`
3. `target/liberty/wlp/usr/servers/*/configDropins/overrides/generated-features.xml`（ビルド時のコピー。参考として読む）
4. `build/wlp/usr/servers/*/configDropins/overrides/generated-features.xml`（同上）

---

# 実行

## server.xml 決定

- `reference/server-xml-discovery.md` の手順で決める（`target/` や `build/` 配下のコピーは対象にしない）
- 複数見つかったら、1 回だけ選んでもらう

---

## 関連ファイル収集

対象：

- include
- configDropins
- bootstrap.properties
- server.env（`PREFERRED_PLATFORM_VERSIONS` が指定されていれば platform の指定として扱う）
- jvm.options

---

## generate-features の実際の挙動（重要）

liberty-maven-plugin 3.12.2 で確認した挙動（Gradle プラグインは未確認）：

- **個別に書かれている feature は生成結果に含まれない。** 例：server.xml に `restfulWS-3.1` があると、アプリが REST を使っていても generated-features.xml には出てこない。そのため、書かれている個別の feature が本当に使われているのかを区別できない
- **集約 feature は生成結果に影響しない。** `jakartaee-10.0` や `webProfile-10.0` が書かれていても、アプリが使う個別の feature（例：`restfulWS-3.1`、`cdi-4.0`、`jsonb-3.0`）はそのまま生成される
- **versionless の feature（`<platform>` + バージョン無しの feature）が書かれていると、生成が失敗する**（`CWMIG12156E`）
- 追加で必要な feature が見つからなければ、既存の generated-features.xml は **更新されずに残る**（古い内容の可能性がある）
- Jakarta EE / MicroProfile / Java EE のバージョンは、`pom.xml` の依存（`jakarta.platform:jakarta.jakartaee-api`、`org.eclipse.microprofile:microprofile`、`javax:javaee-api`）から判定される
- 検出できるのは、**アプリのクラスが使う API** に対応する feature だけ。server.xml の設定要素だけで必要になる feature（「安全性に関する注意」を参照）は出てこない
- `${...}` 変数を使った include 先の feature は考慮されない
- 生成には、ランタイム依存として IBM WebSphere Application Server Migration Toolkit for Application Binaries（別ライセンス）が使われる
- dev mode では既定で無効（`-DgenerateFeatures=true` で有効になる）

→ そのため、このスキルでは **書かれている `<feature>` と `<platform>` をすべて一時的に外し、既存の generated-features.xml も退避してから生成する。** こうすると、現在の指定に左右されない「アプリが使う API から見た必要な feature の一覧」が得られる。この作業は、元に戻す処理を確実に行うため、必ずスクリプトで行う。

---

## 生成の手順（スクリプトを使う）

生成には、このスキルのフォルダにある `scripts/GenerateRequiredFeatures.java` を使う。**同じ作業を手作業（ファイルを直接書き換えてビルド）で行わない**（元に戻す処理を確実にするため）。

1. スクリプトの場所を確認する（プロジェクトのスキルがグローバルより優先されるので、この順に探す）
   1. `.bob/skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java`（プロジェクトルートから）
   2. `~/.bob/skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java`
   - どちらにも無い場合、またはコマンドを実行できないモード（Ask モードなど）の場合は、生成せず静的分析のみ（`--no-generate` と同じ）にして、その理由を報告する
2. 生成コマンドを決める（`clean` は含めない。バックアップを置く出力ディレクトリが消えるため、スクリプトが拒否する）
   - Maven：`./mvnw compile liberty:generate-features`
   - Gradle：`./gradlew classes generateFeatures`
   - Gradle の multi-module：`./gradlew :module:classes :module:generateFeatures`
   - Maven の multi-module：Liberty プラグインを適用しているモジュールで生成する。1 回のコマンドで済まない場合は `sh -c "..."` でまとめて渡す（例：`sh -c "./mvnw -pl <module> -am compile && ./mvnw -pl <module> liberty:generate-features"`）。構成によって必要な手順が違うので、失敗したらプロジェクトの README などを確認する
3. 実行する（`<スクリプト>` は手順 1 で見つけたパス。出力ディレクトリは Maven なら `target/liberty-feature-min`、Gradle なら `build/liberty-feature-min`）
   - Java 11 以上：
     ```bash
     java <スクリプト> --server-xml src/main/liberty/config/server.xml --out target/liberty-feature-min \
         -- ./mvnw compile liberty:generate-features
     ```
   - Java 8（先にコンパイルする）：
     ```bash
     javac -d target/liberty-feature-min/classes <スクリプト>
     java -cp target/liberty-feature-min/classes GenerateRequiredFeatures --server-xml src/main/liberty/config/server.xml \
         --out target/liberty-feature-min -- ./mvnw compile liberty:generate-features
     ```
   - ビルドの制限時間は既定で 30 分（`--timeout-minutes N` で変更できる）
4. 出力（`KEY=VALUE` 形式）を読む
   - `COMMENTED_OUT=<ファイル>: <feature>`：いま書かれている feature と platform（一時的に外したもの）
   - `GENERATED_BEFORE_FEATURE=<feature>`：既存の generated-features.xml に書かれていた feature
   - `REQUIRED_FEATURE=<feature>`：アプリが使う API から見た必要な feature（`generated-features.required.xml` にも保存される）
   - `RESTORED=<ファイル>`：元に戻したファイル
   - `RESULT`：
     - `OK`：生成して元に戻した
     - `BUILD_FAILED`：生成に失敗した（元には戻した）。`BUILD_LOG_TAIL` と `build.log` を見て「生成失敗時」に従う
     - `INTERRUPTED`：途中で止められた（元には戻した）
     - `ERROR`：実行前に止めた（何も変更していない）。`CATEGORY` を見る。`PREVIOUS_RUN_NOT_RESTORED` の場合は、前回の実行が強制終了されて元に戻っていないので、表示された `RESTORE_COMMAND`（`--restore`）を実行してからやり直す
     - `RESTORE_FAILED`：**元に戻せなかった。** 分析を続けず、`RESTORE_FAILED=` のファイルと `RESTORE_COMMAND` を必ずユーザーに伝える
5. git 管理下なら、`git status` で src 側に変更が残っていないことを確認し、結果を出力に書く

※ generated-features.xml そのものを更新したいとユーザーが明示した場合だけ、通常の生成コマンド（手順 2）をそのまま実行してよい。その場合は、個別に書かれている feature は出力されないこと、versionless の構成では失敗することを先に伝える。

---

## 生成失敗時

中断しない（スクリプトが元に戻したことを確認してから分析に進む）

分類：

* コンパイル失敗
* task / goal 未定義（プラグインが古い、または未設定）
* 依存解決失敗
* パス不整合
* Java問題
* multi-module 問題
* バージョン判定の失敗（feature の指定をすべて外したため、pom.xml に Jakarta EE / MicroProfile の API 依存が無いと、バージョンを判定できない）

→ 最小修正案提示。生成できなかった場合は、静的分析の結果だけで案を作り、信頼度を下げる

---

# 分析

## 抽出

整理して出すもの：

* 現在の指定（`COMMENTED_OUT` と `GENERATED_BEFORE_FEATURE`。どのファイル由来かも）
  * 個別の feature
  * 集約 feature
  * `<platform>` の指定（server.env の `PREFERRED_PLATFORM_VERSIONS` を含む）
  * versionless feature（バージョン無しの指定）
* 必要な feature の一覧（`REQUIRED_FEATURE`）
* 両者の差分（下の「突き合わせ」）

---

## 突き合わせ（必要な feature の一覧がある場合）

現在の指定を、必要な feature の一覧と突き合わせる。集約 feature が何を含むかは `reference/feature-matrix.md` と、使っている Liberty のドキュメントで判断する。

* 書かれている個別の feature が一覧に **ある** → ✅ 残す
* 書かれている個別の feature が一覧に **ない**
  * 「安全性に関する注意」に当てはまる（設定要素で必要、監視、認証、外部接続など）→ 🟡 要確認（根拠を添える）
  * 当てはまらない → 🗑 削除候補
* 集約 feature / platform → 🧩 分解。一覧の feature と、設定要素で必要な feature に置き換える案を作る
* 一覧にあるのに、現在の指定（集約 feature が含むものを含む）で満たされていない → 🟡 不足の可能性（今は起動時にエラーになっているか、別の feature から間接的に有効になっている）
* 既存の generated-features.xml の feature が一覧に無い → 🔁 generated-features.xml が古い可能性
* バージョンが違う（例：書かれているのは `cdi-3.0`、一覧は `cdi-4.0`）→ 🟡 要確認（pom.xml の API のバージョンと、実際に使っている EE のバージョンが食い違っている可能性）

---

## 集約 feature の分解

集約 feature / platform ごとに、次の項目を必ず出力する：

* 名前（例：`jakartaee-10.0`）
* 削除した場合のリスク
* 検出した API（根拠：必要な feature の一覧、import、依存、設定ファイル）
* 置き換える個別 feature の候補
* versionless で書く場合の候補
* 最小化案
* 不確実な点

---

## feature 名とバージョン

feature 名とバージョンは、**必ず `reference/feature-matrix.md` の対応表で確かめる**（Jakarta EE 9 以降で名前が変わっていて、`jpa-3.x` や `jaxrs-3.x` は存在しない）。表に無い feature やバージョンは、推測で書かず、使っている Liberty のバージョンのドキュメントで確認する。

---

## 分類

| feature | 由来 | 分類 | 信頼度 | 根拠 |
| ------- | -- | -- | --- | -- |

分類：

* ✅ 残す
* 🔁 generatedへ
* 🧩 分解
* 🟡 要確認
* 🗑 削除
* 🧪 テスト（テストコードでのみ使われている API 由来。`--include-tests` のときだけ出す。本番の構成には含めない候補）

---

## version 整合性

- EE のバージョンを混ぜないこと、MicroProfile を EE のバージョンに合わせることは、`reference/feature-matrix.md` の「バージョンを混ぜない」に従う

必ず確認：

* 使っている Liberty のバージョンがその feature に対応しているか
* 起動ログ
* `CWWKF0012I`（インストールされた feature の一覧）
* feature 解決エラー（`CWWKF0001E`：feature が見つからない、`CWWKF0033E` などの競合）

---

## versionless

区別：

* platform
* versionless
* version指定

注意：

* 書き方の規則（`<platform>` の数、名前の付け方、`PREFERRED_PLATFORM_VERSIONS`）は `reference/feature-matrix.md` の「versionless feature と platform」に従う
* versionless の構成では、通常の生成コマンドは失敗する（スクリプトは feature の指定をすべて外すので生成できる）

---

## 生成結果の扱い

* 必要な feature の一覧（`REQUIRED_FEATURE`）は一次情報だが、正解ではない（設定要素だけで必要な feature は含まれない）
* 既存の generated-features.xml は「いまの指定に足りなかった分」でしかない（書かれている feature は含まれないので、使っているかどうかの根拠にはならない）

---

## 段階削減

1. 重複削除
2. 集約分解
3. 不要削除
4. 統合テスト
5. 微調整

---

# 修正案

次の 3 つの案を、Before / After の差分で示す。書き方の例は `reference/proposal-examples.md` を見る。

* 案A（安全）：集約 feature はそのまま残し、集約 feature に含まれる重複した指定だけを削除する
* 案B（最小）：必要な feature の一覧と、設定要素で必要な feature に基づいて、個別の feature に分解する
* 案C（versionless）：案B を `<platform>` とバージョン無しの feature で書く

---

# 検証チェック

* 起動成功
* `CWWKF0012I` 確認
* 差分比較
* 最小化した後にスクリプトで生成し直しても、必要な feature の一覧が変わらないこと
* REST疎通
* DB接続
* JMS
* 認証
* TLS
* MP機能
* 外部接続
* 本番相当環境

---

# 出力フォーマット（固定）

1. **対象と前提**
   - 対象の server.xml、ビルドツール、module
   - 生成の方法（スクリプトで生成 / 前回の結果を再利用（日時）/ 生成しなかった理由）と、スクリプトの `RESULT`
   - 一時的に変更したファイルと、元に戻したことの確認結果（`RESTORED`、`git status`）
2. **サマリー**
   - 現在の feature 数 → 最小案（案B）の feature 数
   - 削除候補と要確認の件数
3. **feature 一覧（分類表）**
4. **集約 feature の分解結果**（該当するときのみ）
5. **修正案**（案A / 案B / 案C。Before / After の差分で示す）
6. **段階削減プランと検証チェックリスト**
7. **追加質問**（必要なときのみ）

---

# 追加質問（最小限）

* server.xml 複数
* build tool 不明
* module 不明
* 運用標準不明
