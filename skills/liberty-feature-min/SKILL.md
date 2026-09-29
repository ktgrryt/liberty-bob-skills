---
name: liberty-feature-min
description: >-
  Liberty の server.xml に書かれた feature を、アプリを壊さずに最小化する案を作る。
  「feature を減らしたい」「jakartaee-* / webProfile-* / microProfile-* を個別の feature に分けたい」
  「使っていない feature を知りたい」「generated-features.xml と server.xml の差分を見たい」
  「feature の依存解決エラー（CWWKF*）が出る」ときに使う。
  liberty:generate-features（Maven）/ generateFeatures（Gradle）で、アプリが使う API から見た必要な feature の一覧を作って分析する。
  ビルドを実行するが、生成は構成ファイルのコピーで行い、元のファイルは変更しない。platform / versionless feature にも対応する。
---

あなたは WebSphere Liberty / Open Liberty のアーキテクト兼レビュアーです。

目的は、アプリケーションの動作を壊さずに `server.xml` および関連構成ファイルに定義された Liberty feature を最小化することです。

このスキルでは、generate-features で作る「アプリが使う API から見た必要な feature の一覧」を、必要 feature 推定の重要な一次情報として扱います。ただし、これは最終的な最小構成の正解とはみなしません。特に `jakartaee-*`、`javaee-*`、`webProfile-*`、`microProfile-*`、および `<platform>` による集約 feature / platform が含まれる場合は、必ずソース・依存関係・設定ファイル・server.xml・include・configDropins・起動ログと突合し、個別 feature への分解候補を検討します。

`server.xml` の `<featureManager>` は、アプリケーション実行・運用・監視・認証・外部接続に必要な最小限の feature に寄せます。

---

# 最初にユーザーへ伝えること（必須）

生成を行う場合（`--no-generate` / `--dry-run` 以外）は、実行前に次を短く伝える：

- ビルド（`compile` + `liberty:generate-features` など）を実行する
- 生成は構成ファイルの **コピー** で行う（コピーの `<feature>` と `<platform>` をすべてコメントアウトして生成する）。**元の server.xml・include 先・configDropins・generated-features.xml は変更しない**
  - Maven：構成ディレクトリだけをコピーする。ビルドは元のプロジェクトで行うので、`target/` はふだんのビルドと同じように更新される
  - Gradle、または pom.xml で構成の場所を指定している Maven：プロジェクトをまるごとコピーし、コピーの中でビルドする（コピーとフルビルドの分だけ時間とディスクを使う。コピーは終了時に削除する）
- server.xml の最小化そのもの（feature の削除）は **提案のみ** で、編集しない

dev mode（`liberty:dev` / `libertyDev`）が実行中なら、生成の前に止めてもらう（Maven では元のプロジェクトでビルドするので、dev mode のビルドと `target/` を取り合うため）。

---

# 参照ファイル

このスキルのフォルダにある次のファイルを、手順の中で指示されたときに読む。見つからない場合は `.bob/skills/liberty-feature-min/`、`~/.bob/skills/liberty-feature-min/` の順に探す。

- `reference/feature-matrix.md`：EE / MicroProfile のバージョンと feature 名の対応、集約 feature に含まれるもの、versionless の書き方（共通）
- `reference/server-xml-discovery.md`：server.xml の決め方（共通）
- `reference/proposal-examples.md`：修正案 A / B / C の書き方の例
- `scripts/GenerateRequiredFeatures.java`：必要な feature の一覧を作るプログラム

---

# 重要方針：集約 feature / platform は最小化対象

以下は原則として「そのまま残す候補」とはみなさず、個別の feature への分解対象とします。

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

指定が無い場合、前回の生成結果が `src/` 配下・`pom.xml`・`build.gradle(.kts)`・server.xml などの構成ファイルのどれよりも新しければ、生成せずにそれを使う（使ったことと、その日時を出力に書く）。スクリプトは元のファイルを変更しないので、更新日時で判断してよい。

確かめ方の例（何も表示されなければ再利用できる。存在しないパスのエラーは無視してよい）：

```bash
find src pom.xml build.gradle build.gradle.kts <server.xml のディレクトリ> -type f \
    -newer target/liberty-feature-min/generated-features.required.xml 2>/dev/null | head -1
```

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

- Maven は `pom.xml` の `<modules>`、Gradle は `settings.gradle(.kts)` の `include` から module を列挙する
- Liberty プラグインを適用している module（通常は war / ear を作る module）を特定し、その module の server.xml を対象にする
- 該当する module が複数あって決められない場合だけ、1 回質問する

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
- Liberty プラグインの設定で構成の場所が変えられていないかを確認する（generate-features はプラグインの設定の場所を読み、そこに generated-features.xml を書く）
  - Maven：liberty-maven-plugin の `<configDirectory>` / `<serverXmlFile>`
  - Gradle：`liberty { server { configDirectory = ... ; serverXmlFile = ... } }`
  - `serverXmlFile` があればそのファイル、無ければ `configDirectory` の中の `server.xml` を対象にする
  - スクリプトは「server.xml のあるディレクトリ」を構成ディレクトリとして扱う。対象の server.xml が `configDirectory`（既定は `src/main/liberty/config`）の直下に無い場合は、generated-features.xml の場所がずれるので、生成せず静的分析のみにして、その理由を報告する

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
- 追加で必要な feature が見つからないと、既存の generated-features.xml は feature の無い内容（`No additional features generated` のコメントだけ）に書き直される。無ければ作られない
- 生成すると、server.xml にも generated-features.xml についてのコメントを書き足す
- Jakarta EE / MicroProfile / Java EE のバージョンは、`pom.xml` の依存（`jakarta.platform:jakarta.jakartaee-api`、`org.eclipse.microprofile:microprofile`、`javax:javaee-api`）から判定される
- 検出できるのは、**アプリのクラスが使う API** に対応する feature だけ。server.xml の設定要素だけで必要になる feature（「安全性に関する注意」を参照）は出てこない
- `${...}` 変数を使った include 先の feature は考慮されない
- 生成には、ランタイム依存として IBM WebSphere Application Server Migration Toolkit for Application Binaries（別ライセンス）が使われる
- dev mode では既定で無効（`-DgenerateFeatures=true` で有効になる）

→ そのため、このスキルでは **構成ファイルのコピーを作り、コピーの `<feature>` と `<platform>` をすべて外してから、コピーを対象に生成する。** こうすると、現在の指定に左右されない「アプリが使う API から見た必要な feature の一覧」が得られる。元のファイルは変更しない（プラグインが書き足すコメントや generated-features.xml も、コピーに書かれる）。コピーの作成と、プラグインが本当にコピーを使ったかの確認のため、必ずスクリプトで行う。

---

## 生成の手順（スクリプトを使う）

生成には、このスキルのフォルダにある `scripts/GenerateRequiredFeatures.java` を使う。**同じ作業を手作業（ファイルを直接書き換えてビルド）で行わない**（元のファイルを変更しないため。スクリプトは、プラグインが本当にコピーを使ったかも確かめる）。

1. スクリプトの場所を確認する（プロジェクトのスキルがグローバルより優先されるので、この順に探す）
   1. `.bob/skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java`（プロジェクトルートから）
   2. `~/.bob/skills/liberty-feature-min/scripts/GenerateRequiredFeatures.java`
   - どちらにも無い場合、またはコマンドを実行できないモード（Ask モードなど）の場合は、生成せず静的分析のみ（`--no-generate` と同じ）にして、その理由を報告する
2. 生成コマンドを決める
   - スクリプトは **ビルドのルート**（Maven は親の pom.xml、Gradle は settings.gradle(.kts) のあるディレクトリ）で実行する。プロジェクトをコピーするときは、このディレクトリをコピーする
   - `clean` は含めない（コピーを置く出力ディレクトリが消えるため、スクリプトが拒否する）
   - スクリプトに渡すコマンドは 1 つだけにする。`sh -c "a && b"` のように複数のコマンドをまとめない（止めたときにビルドの本体が残ることがある。スクリプトは Java 9 以上なら子のプロセスまで止めるが、Java 8 では止められない）
   - コマンドの中のパスは相対パスにする（プロジェクトをコピーするときは、コピーの中で実行される）
   - `-DconfigDirectory`・`-DserverXmlFile`・`-DgenerateToSrc` はコマンドに書かない（Maven のときはスクリプトが付け足す）
   - Maven：`./mvnw compile liberty:generate-features`
   - Gradle：`./gradlew classes generateFeatures`
   - Maven の multi-module：`./mvnw -pl <module> -am compile io.openliberty.tools:liberty-maven-plugin:<version>:generate-features`
     - `<module>` は Liberty プラグインを適用しているモジュール、`<version>` はそのモジュールが使う liberty-maven-plugin のバージョン（`<module>` の pom.xml か、親の pom.xml の `<pluginManagement>` に書かれている）
     - `liberty:generate-features` と書くと、親の pom.xml が Liberty プラグインを宣言していない場合に失敗する（`No plugin found for prefix 'liberty'`）。バージョンを省くと、プロジェクトの指定ではなく最新版が使われる
     - generate-features は最も下流のモジュール（`<module>`）でだけ実行され、依存するモジュールのクラスも調べる
   - Gradle の multi-module：`./gradlew :module:classes :module:generateFeatures`
     - Gradle の generateFeatures は、`:module`（Liberty プラグインを適用したプロジェクト）自身のクラスだけを調べる。Maven と違い、依存するプロジェクトのクラスは調べない。ear のようにクラスを持たないプロジェクトに Liberty プラグインを適用している場合は生成できない（スクリプトは `NO_CLASSES_SCANNED` を返す）
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
   - コピーの作り方はスクリプトが決める（`MODE`）
     - `CONFIG_COPY`（Maven）：構成ディレクトリだけをコピーし、`-DconfigDirectory` と `-DserverXmlFile` でコピーを指定する。ビルドは元のプロジェクトで行う
     - `PROJECT_COPY`（Gradle、または pom.xml で `<configDirectory>` / `<serverXmlFile>` を指定している Maven。pom.xml の指定は `-D` より優先されるため）：プロジェクトをコピーし、コピーの中でビルドする。`.git` とビルドの出力（`target/`、`build/`、`.gradle/`）はコピーしないので、フルビルドになる
     - どちらも、構成ディレクトリの外にある include 先はコピーに含め、include の場所をコピーを指すように書き換える
   - ビルドの制限時間は既定で 30 分（`--timeout-minutes N` で変更できる）
4. 出力（`KEY=VALUE` 形式）を読む
   - `MODE` / `MODE_REASON`：コピーの作り方と、その理由
   - `DECLARED=<ファイル>: <feature>`：いま書かれている feature と platform（コピーで外したもの。元のファイルは変更していない）
   - `GENERATED_BEFORE_FEATURE=<feature>`：既存の generated-features.xml に書かれていた feature
   - `REQUIRED=<ファイル>` と `REQUIRED_FEATURE=<feature>`：アプリが使う API から見た必要な feature（`generated-features.required.xml` にも保存される）。`REQUIRED=<ファイル>` があって `REQUIRED_FEATURE` が 1 つも無ければ、API から必要と判定された feature は無い。`REQUIRED=NONE` は結果が無いことを示す
   - `CHANGED_ORIGINAL=<ファイル>`：実行のあいだに元のファイルが変わった（スクリプトは書かないので、ビルドかほかのプログラムが書いた）。**必ずユーザーに伝え**、`git diff` などで確かめてもらう
   - `GENERATED_FILE=<ファイル>`：コピー以外の場所に書かれた generated-features.xml。`target/` や `build/` の中ならビルドの出力なので問題ない（Liberty プラグインの次の版は、サーバーのディレクトリにも書く）。それ以外の場所ならユーザーに伝える
   - `RESULT`：
     - `OK`：生成した
     - `BUILD_FAILED`：生成に失敗した。`BUILD_LOG_TAIL` と `build.log` を見て「生成失敗時」に従う
     - `INTERRUPTED`：途中で止められた
     - `ERROR`：生成しなかった、または結果を使えない。`CATEGORY` を見る
       - `NOT_GENERATED_IN_COPY`：ビルドは成功したが、generate-features がコピーを使わなかった（プラグインの設定で generate-features を飛ばしている、ビルドの設定で構成の場所を絶対パスにしている、など）。結果は使わず、`GENERATED_FILE`・`CHANGED_ORIGINAL`・`build.log` から分かる原因を報告し、静的分析のみにする
       - `NO_CLASSES_SCANNED`：generate-features が調べるクラスを見つけられなかった（「追加の feature が無い」ではない）。コンパイルされていない、または Gradle で Liberty プラグインを適用したプロジェクトにクラスが無い（ear など）。結果は使わず、静的分析のみにして、その理由を報告する
       - `NOT_BUILD_ROOT`：ビルドのルートで実行していない。`MESSAGE` のディレクトリに移ってやり直す
       - `BAD_COMMAND`：コマンドにプロジェクトの絶対パスがある。相対パスに直してやり直す
       - `SERVER_XML_OUTSIDE_PROJECT`：プロジェクトをコピーする場合に、server.xml がプロジェクトの外にある。静的分析のみにして、その理由を報告する
       - `LEFTOVER_MARK`：元のファイルに、このスキルの以前の版が一時的にコメントアウトした跡（`liberty-feature-min:`）が残っている。**ユーザーに伝え**、git などで元に戻してもらってからやり直す
       - `IO_ERROR`：ファイルを読み書きできなかった。`MESSAGE` をユーザーに伝える
   - スクリプトが強制終了されても、元のファイルは変わっていない。出力ディレクトリの `work`（コピー）が残るが、次の実行で削除される
5. git 管理下なら、`git status` で src 側に変更が無いことを確認し、結果を出力に書く

※ generated-features.xml そのものを更新したいとユーザーが明示した場合だけ、通常の生成コマンド（手順 2）をそのまま実行してよい。その場合は、個別に書かれている feature は出力されないこと、versionless の構成では失敗することを先に伝える。

---

## 生成失敗時

中断しない（生成できなかった分は、静的分析で補う）

分類：

* コンパイル失敗
* task / goal 未定義（プラグインが古い、または未設定）
* 依存解決失敗
* パス不整合
* Java問題
* multi-module 問題
* コピーの中のビルドの問題（`PROJECT_COPY` のとき。`.git` が無いと動かないプラグイン、プロジェクトの外を参照するビルド（Gradle の `includeBuild("../...")` など））
* バージョン判定の失敗（feature の指定をすべて外したため、pom.xml に Jakarta EE / MicroProfile の API 依存が無いと、バージョンを判定できない）
* 対応していない MicroProfile のバージョン（`The MicroProfile version number ... is not supported for feature generation`）。生成に使う binary-app-scanner 25.0.0.2.1 が対応しているのは MicroProfile 6.0 までで、依存に `org.eclipse.microprofile:microprofile` の 6.1 や 7.x を書いていると失敗する。ビルドファイルは変えず、静的分析のみにして、その理由を報告する

→ 最小修正案提示。生成できなかった場合は、静的分析の結果だけで案を作り、信頼度を下げる

---

# 分析

## 抽出

整理して出すもの：

* 現在の指定（`DECLARED` と `GENERATED_BEFORE_FEATURE`。どのファイル由来かも）
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

現在の指定を、次の 3 つに分けて示す（分類表の「由来」にも書く）：

* platform：`<platform>` と、server.env の `PREFERRED_PLATFORM_VERSIONS`
* versionless feature：バージョン無しの feature（例：`restfulWS`）。どの platform で解決されるかも示す
* バージョン付きの feature（例：`restfulWS-3.1`）

注意：

* 書き方の規則（`<platform>` の数、名前の付け方、`PREFERRED_PLATFORM_VERSIONS`）は `reference/feature-matrix.md` の「versionless feature と platform」に従う
* versionless の構成では、通常の生成コマンドは失敗する（スクリプトはコピーで feature の指定をすべて外すので生成できる）

---

## 生成結果の扱い

* 必要な feature の一覧（`REQUIRED_FEATURE`）は一次情報だが、正解ではない（設定要素だけで必要な feature は含まれない）
* 既存の generated-features.xml は「いまの指定に足りなかった分」でしかない（書かれている feature は含まれないので、使っているかどうかの根拠にはならない）

---

## 段階削減

一度に全部を変えず、次の順に 1 段階ずつ進める案にする。各段階の後に起動し、`CWWKF0012I` と feature 解決エラーの有無、主要なテストを確かめてから次に進む。

1. 重複削除：集約 feature に含まれる個別の指定を消す（案A）
2. 集約分解：集約 feature / platform を、必要な feature の一覧と設定要素で必要な feature に置き換える（案B）
3. 不要削除：🗑 削除候補を消す（🟡 要確認のものは、根拠を確かめるまで残す）
4. 統合テスト：「検証チェック」の項目を本番相当の環境で確かめる
5. 微調整：起動ログやテストで不足が見つかった feature を足す。足した理由を記録する

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
   - コピーの作り方（`MODE`）と、元のファイルが変更されていないことの確認結果（`CHANGED_ORIGINAL` が無いこと、`git status`）
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
