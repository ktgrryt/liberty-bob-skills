# Liberty Bob Skills

WebSphere Liberty / Open Liberty 開発を支援する IBM Bob 用 Skills 集です。

## 概要

このリポジトリは、IBM Bob で WebSphere Liberty / Open Liberty の開発・運用を効率化するための Skills を提供します。コードレビュー、設定管理、トラブルシューティング、移行支援など、日常的な作業を自動化・効率化できます。

## Skills とは？

Skills は、IBM Bob に特定の作業手順や専門知識を追加するための仕組みです。

よく行う作業（レビュー依頼、手順の定型化、チェックリスト実行、構成診断など）を Skill として登録しておくことで、Bob が対象プロジェクトの文脈に合わせて処理を実行できます。

Bob は、依頼内容と各 Skill の `description` を照らし合わせて、使う Skill を自動で選びます。デフォルトでは、Skill を読み込む前に許可を求められます。

## セットアップ

### 1. Skills の配置

このリポジトリの `skills/` 配下にある **各 Skill のフォルダ** を、以下のいずれかにコピーしてください。

```text
.bob/skills/          # プロジェクト固有の Skills（プロジェクトルート直下）
~/.bob/skills/        # グローバル Skills
```

`skills/` フォルダごとコピーすると `.bob/skills/skills/...` と 1 段深くなり、認識されません。配置後は次の形になります。

```text
.bob/skills/
├── liberty-datasource-create/
│   ├── SKILL.md
│   ├── reference/
│   │   ├── databases.md
│   │   └── server-xml-discovery.md
│   └── scripts/
│       └── JdbcPing.java
├── liberty-feature-min/
│   ├── SKILL.md
│   ├── reference/
│   │   └── ...
│   └── scripts/
│       └── GenerateRequiredFeatures.java
└── ...
```

各 Skill は、`SKILL.md` のほかに参照資料（`reference/`）やスクリプト（`scripts/`）を持っています。フォルダの中身をすべてコピーしてください。

#### インストールスクリプトを使う（macOS / Linux）

```bash
# プロジェクト固有として配置（対象のプロジェクトで実行する。--project DIR でも指定できる）
cd /path/to/your-project
/path/to/liberty-bob-skills/install.sh

# グローバルに配置
/path/to/liberty-bob-skills/install.sh --global

# 既に入っている Skill を新しい版に置き換える（その Skill のフォルダを削除してからコピーする）
/path/to/liberty-bob-skills/install.sh --force

# 一部の Skill だけ入れる
/path/to/liberty-bob-skills/install.sh liberty-doctor liberty-feature-min
```

既に入っている Skill は、`--force` を付けない限り上書きしません。

#### 手でコピーする

```bash
# macOS / Linux
mkdir -p .bob/skills
cp -R /path/to/liberty-bob-skills/skills/* .bob/skills/
```

```powershell
# Windows（PowerShell）
New-Item -ItemType Directory -Force .bob\skills | Out-Null
Copy-Item -Recurse -Force C:\path\to\liberty-bob-skills\skills\* .bob\skills\
```

同じ名前の Skill がある場合は、プロジェクト固有のものが優先されます。

### 2. Skills を使用

IBM Bob のチャットで、Skill 名や目的を文章で依頼してください。対象のパスや DB の接続先なども、依頼文の中に書けば Skill が読み取ります。

例：

```text
liberty-doctor でこのプロジェクトを診断して
Liberty が起動しないので原因を調べて
server.xml の feature を最小化して
```

## Skills 一覧

各 Skill がファイルを編集するか、コマンドを実行するかは次のとおりです。コマンドを実行する Skill は **Agent モード** で使ってください（Ask モードと Plan モードではコマンドを実行できません）。

| Skill | ファイル編集 | コマンド実行 | 推奨モード |
| --- | --- | --- | --- |
| `liberty-doctor` | しない | 読み取り系のみ（ポートの LISTEN 確認など） | Agent（Ask ではポート確認を省略） |
| `liberty-feature-min` | 生成のあいだだけ server.xml などを一時的に変更し、終了時に元に戻す（最終的には変更しない） | ビルド（`liberty:generate-features` / `generateFeatures`） | Agent |
| `liberty-env-vars-audit` | しない | しない | Ask / Agent |
| `liberty-feature-add` | server.xml / pom.xml / build.gradle を編集する（差分を示してから） | しない | Agent |
| `liberty-datasource-create` | pom.xml / server.xml を編集する（差分を示してから） | ドライバーの取得と接続チェック（`mvn` / `java`） | Agent |
| `liberty-endpoint-map` | しない | 検索のみ（`rg` / `grep`） | Ask / Agent |

#### `liberty-doctor`

**概要**: Liberty プロジェクト全体を一発健康診断（設定 / ビルド / ログ / 起動失敗原因を自動チェック）

**使い方**:

```text
liberty-doctor でこのプロジェクトを診断して
liberty-doctor で src/main/liberty/config/server.xml を診断して
```

**主な機能**:

* 設定の散らばり（server.xml、configDropins、pom.xml の `liberty.var.*` など）を可視化
* ビルドツール・プラグインの整合性チェック
* ポート競合・バインド系（`httpEndpoint` の `host` など）の確認
* よくある起動失敗原因の候補化
* feature 最小化が必要な場合は `liberty-feature-min` へ誘導

***

#### `liberty-feature-min`

**概要**: アプリが使う API から必要な feature の一覧を生成し、server.xml と突き合わせて feature の最小化案を提示

**使い方**:

```text
liberty-feature-min で feature を最小化して
liberty-feature-min で src/main/liberty/config/server.xml を対象にして
liberty-feature-min で、前回の結果を使わずに生成し直して（--force）
```

依頼文に次の語を含めると、オプションとして扱われます。

* `--force`: 前回の生成結果があっても使わず、必ず生成し直す
* `--no-generate`: 生成せず静的分析のみ
* `--dry-run`: 探索と実行コマンドの提示のみ（ビルドしない）
* `--include-tests`: テストコードも参考にする

**主な機能**:

* アプリが使う API から見た必要な feature の一覧を生成（Maven / Gradle 対応。Skill のフォルダにある `scripts/GenerateRequiredFeatures.java` を使います。JDK が必要です）
  * 生成のあいだ、書かれている feature を一時的に外し、終了時に必ず元に戻します（失敗した場合や Ctrl+C で止めた場合も含む。ファイルの更新日時も元に戻します）
  * 使っていない feature の検出や、versionless の構成での生成にも対応します
* server.xml との差分分析
* 削除候補・残すべき feature の分類
* 段階的削減プランの提示
* 検証チェックリストの生成

***

#### `liberty-env-vars-audit`

**概要**: Liberty 構成の `${...}` 変数参照を棚卸しし、未定義・タイポ疑い・環境差分を検出

**使い方**:

```text
liberty-env-vars-audit でこのプロジェクトの変数を棚卸しして
liberty-env-vars-audit で src/main/liberty/config/server.xml を対象にして
```

**主な機能**:

* 変数参照（`${...}`）の全列挙
* Liberty の優先順位に沿った定義元の突き合わせ（server.xml / bootstrap.properties / server.env / jvm.options / `variables/` / pom.xml の `liberty.var.*` など）
* 未定義変数・タイポ疑いの検出
* local / dev / stg / prod 環境差分の可視化
* 秘匿値直書きの検出（値はマスク）
* 環境別テンプレート（server.env、bootstrap.properties など）の提案

***

#### `liberty-feature-add`

**概要**: 指定した機能（JPA、REST Client など）に必要な Liberty feature と依存関係を自動追加

**使い方**:

```text
liberty-feature-add で JPA を追加して
liberty-feature-add で REST Client を使えるようにして
liberty-feature-add で CDI を追加して
```

**主な機能**:

* Java EE 7 / 8、Jakarta EE 9.1 / 10 / 11 の判定
* 適切な feature の選択と追加（集約 feature・versionless 構成にも対応）
* pom.xml / build.gradle への依存関係追加
* 最小サンプルコードの提供（オプション）
* 検証チェックリストの生成

***

#### `liberty-datasource-create`

**概要**: 指定 DB への DataSource を自動作成（JDBC ドライバー追加 → Liberty 設定 → 接続チェック）。Maven プロジェクト専用です。

**使い方**:

```text
liberty-datasource-create で postgres localhost:5432 mydb user env:DB_PASSWORD
liberty-datasource-create で MySQL（db.example.com、DB: mydb、ユーザー: app）の DataSource を作って
```

**入力**:

* DB の種類: postgres / mysql / mariadb / db2 / oracle / mssql
* ホスト名とポート（ポートを省略するとデフォルト）
* データベース名
* ユーザー名
* パスワードの環境変数名（`env:DB_PASSWORD` のように指定。省略すると `DB_PASSWORD` を使います）

> **パスワードそのものはチャットに書かないでください。** チャットに書いた内容は会話履歴に残り、モデルにも送信されます。パスワードは次のどちらかで設定し、Bob には環境変数名だけを伝えてください。
>
> * `mvn liberty:dev` を実行するターミナルで `export DB_PASSWORD=...`
> * `src/main/liberty/config/server.env` に `DB_PASSWORD=...` と書く（git にコミットしないようにする）

**主な機能**:

* JDBC ドライバーを pom.xml に追加（`provided` スコープ）
* liberty-maven-plugin の `copyDependencies` で、ドライバーをサーバへコピーする設定を追加
* server.xml への library / jdbcDriver / authData / dataSource の追加（パスワードは `${env.VAR}` で参照。`InitialContext.lookup` などの直接ルックアップでも authData が使われるように設定）
* 接続チェックの自動実行（Skill のフォルダにある `scripts/JdbcPing.java` を使います。JDK が必要です）

***

#### `liberty-endpoint-map`

**概要**: JAX-RS リソースを走査して REST エンドポイント一覧を生成し、404 / 405 の原因候補を提示

**使い方**:

```text
liberty-endpoint-map で REST エンドポイントの一覧を出して
liberty-endpoint-map で src/ 配下を探索して
```

**主な機能**:

* `@Path` アノテーションの自動検出（サブリソースロケーターにも対応し、MP Rest Client のインターフェースは除外）
* エンドポイント一覧（HTTP メソッド、パス、セキュリティ）の生成
* contextRoot / applicationPath の自動判定
* curl / httpie サンプルの生成
* 404 / 405 トラブルシュート候補の提示

## 注意事項

* Skill は server.xml や server.env などの設定ファイルを読み込みます。出力では秘密情報をマスクしますが、読み込んだ内容はモデルに送られます。本番環境の秘密情報を含むファイルがあるリポジトリでは注意してください。
* ファイルを編集する Skill は、`target/` や `build/` 配下（ビルド時に作られるコピー）ではなく、`src/` 側のファイルを編集します。

## トラブルシューティング

### Bob が Skill を認識しない、または期待どおりに実行しない

Bob が Skill の内容に基づいて処理せず、通常のチャットとして応答してしまう場合は、以下を確認してください。

1. 各 Skill のフォルダが正しい場所に配置されていること
   * `.bob/skills/<Skill名>/SKILL.md`
   * `~/.bob/skills/<Skill名>/SKILL.md`

2. Skill の読み込みを求める確認が表示されたら、許可していること

3. Bob のモードが Skill 実行に適したモードになっていること（コマンドを実行する Skill は Agent モードが必要です）

モードの変更方法：

1. Bob のチャット画面上部でモード選択ボタンをクリック
2. 「Agent」モードを選択
3. Skill 名、または実行したい作業内容を入力

例：

```text
liberty-doctor を実行して
この Liberty プロジェクトを診断して
```

## 開発者向け

### 共通の参照資料（`shared/`）

複数の Skill で使う参照資料（server.xml の決め方、feature の対応表、変数の優先順位）は `shared/` に置き、各 Skill の `reference/` にコピーしています。Skill のフォルダは 1 つずつコピーして使われるため、別のフォルダを参照できないからです。

* **編集するのは `shared/` のファイルだけ** にしてください。`skills/*/reference/` のコピーは直接編集しないでください（先頭にその旨のコメントがあります）
* どのファイルをどの Skill にコピーするかは `shared/targets.txt` に書きます
* `shared/` や `shared/targets.txt` を変えたら、次を実行してコピーを更新します

```bash
tools/sync-shared.sh          # コピーを更新する（targets.txt から外したものは削除する）
tools/sync-shared.sh --check  # コピーが shared/ とずれていないか確認する（ずれていれば終了コード 1）
```

### テスト

```bash
tests/run.sh               # Skill の形式、shared/ の同期、スクリプトのテスト
tests/run.sh --with-maven  # 上に加えて、本物の liberty:generate-features も試す
```

GitHub Actions（`.github/workflows/ci.yml`）でも、push と pull request のたびに同じものを実行します。

Bob で各 Skill を動かしたときの結果は自動では確かめられないので、問題をわざと入れたサンプルプロジェクト（`tests/fixtures/sample-app`）で手動で確認します。手順と期待する結果は `tests/manual/sample-app.md` にあります。詳しくは `tests/README.md` を見てください。

## 貢献

バグ報告や機能追加の提案は、Issue またはプルリクエストでお願いします。

## Note

各 Skill の詳細な動作は `skills/<Skill名>/SKILL.md` を参照してください。
