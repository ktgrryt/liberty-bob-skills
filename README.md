# Liberty Bob Skills

WebSphere Liberty / Open Liberty 開発を支援する IBM Bob 用 Skills 集です。

## 概要

このリポジトリは、IBM Bob で WebSphere Liberty / Open Liberty の開発・運用を効率化するための Skills を提供します。コードレビュー、設定管理、トラブルシューティング、移行支援など、日常的な作業を自動化・効率化できます。

## Skills とは？

Skills は、IBM Bob に特定の作業手順や専門知識を追加するための仕組みです。

よく行う作業（レビュー依頼、手順の定型化、チェックリスト実行、構成診断など）を Skill として登録しておくことで、Bob が対象プロジェクトの文脈に合わせて処理を実行できます。

## セットアップ

### 1. Skills の配置

以下のいずれかのディレクトリに `skills/` フォルダを配置してください。

```text
.bob/skills/          # プロジェクト固有の Skills
~/.bob/skills/        # グローバル Skills
````

### 2. Skills を使用

IBM Bob のチャット画面で、利用したい Skill 名または目的を入力してください。

例：

```text
liberty-doctor を実行して
このプロジェクトを Liberty の観点で診断して
server.xml の feature を最小化して
```

Bob が利用可能な Skills を認識している場合、該当する Skill の内容に基づいて処理を実行します。

## Skills 一覧

#### `liberty-doctor`

**概要**: Liberty プロジェクト全体を一発健康診断（設定 / ビルド / ログ / 起動失敗原因を自動チェック）

**使い方**:

```text
liberty-doctor                        # カレントディレクトリを診断
liberty-doctor path/to/server.xml     # 特定の server.xml を診断
```

**主な機能**:

* 設定の散らばり（server.xml、configDropins）を可視化
* ビルドツール・プラグインの整合性チェック
* ポート競合・バインド系の確認
* よくある起動失敗原因の候補化
* feature 最小化が必要な場合は `liberty-feature-min` へ誘導

***

#### `liberty-feature-min`

**概要**: generated-features.xml を生成・差分分析し、server.xml の feature 最小化案を提示

**使い方**:

```text
liberty-feature-min                              # 自動探索・生成
liberty-feature-min path/to/server.xml           # 特定の server.xml を対象
liberty-feature-min path/to/server.xml --force   # 強制再生成
```

**主な機能**:

* generated-features.xml の自動生成（Maven / Gradle 対応）
* server.xml との差分分析
* 削除候補・残すべき feature の分類
* 段階的削減プランの提示
* 検証チェックリストの生成

***

#### `liberty-env-vars-audit`

**概要**: Liberty 構成の `${...}` 変数参照を棚卸しし、未定義・タイポ疑い・環境差分を検出

**使い方**:

```text
liberty-env-vars-audit                    # カレントディレクトリを棚卸し
liberty-env-vars-audit path/to/server.xml  # 特定の server.xml を対象
```

**主な機能**:

* 変数参照（`${...}`）の全列挙
* 未定義変数・タイポ疑いの検出
* local / dev / stg / prod 環境差分の可視化
* 秘匿値直書きの検出（値はマスク）
* 環境別テンプレート（.env、server.env）の提案

***

#### `liberty-feature-add`

**概要**: 指定した機能（JPA、REST Client など）に必要な Liberty feature と依存関係を自動追加

**使い方**:

```text
liberty-feature-add JPA          # JPA 機能を追加
liberty-feature-add REST Client  # REST Client 機能を追加
liberty-feature-add CDI          # CDI 機能を追加
```

**主な機能**:

* Java EE / Jakarta EE 世代の自動判定
* 適切な feature の選択と追加
* pom.xml / build.gradle への依存関係追加
* 最小サンプルコードの提供（オプション）
* 検証チェックリストの生成

***

#### `liberty-datasource-create`

**概要**: 指定 DB への DataSource を自動作成（JDBC ドライバー追加 → Liberty 設定 → 接続チェック）

**使い方**:

```text
liberty-datasource-create postgres localhost:5432 mydb user env:DB_PASSWORD
liberty-datasource-create mysql db.example.com mydb user prompt
liberty-datasource-create oracle host:1521 service user env:ORACLE_PASSWORD
```

**引数**:

* `<dbType>`: postgres / mysql / mariadb / db2 / oracle / mssql
* `<host[:port]>`: ホスト名とポート（ポート省略時はデフォルト）
* `<dbName>`: データベース名
* `<user>`: ユーザー名
* `<password>`: パスワード（`env:VAR` で環境変数、`prompt` で対話入力）

**主な機能**:

* JDBC ドライバーの pom.xml 追加
* server.xml への library / jdbcDriver / dataSource 追加
* 接続チェックの自動実行
* 環境変数参照の推奨

***

#### `liberty-endpoint-map`

**概要**: JAX-RS リソースを走査して REST エンドポイント一覧を生成し、404 / 405 の原因候補を提示

**使い方**:

```text
liberty-endpoint-map       # カレントディレクトリを探索
liberty-endpoint-map src/  # 特定ディレクトリを探索
```

**主な機能**:

* `@Path` アノテーションの自動検出
* エンドポイント一覧（HTTP メソッド、パス、セキュリティ）の生成
* contextRoot / applicationPath の自動判定
* curl / httpie サンプルの生成
* 404 / 405 トラブルシュート候補の提示

## トラブルシューティング

### Bob が Skill を認識しない、または期待どおりに実行しない

Bob が Skill の内容に基づいて処理せず、通常のチャットとして応答してしまう場合は、以下を確認してください。

1. `skills/` フォルダが正しい場所に配置されていること
   * `.bob/skills/`
   * `~/.bob/skills/`

2. Bob のモードが Skill 実行に適したモードになっていること

モードの変更方法：

1. Bob のチャット画面上部でモード選択ボタンをクリック
2. 「Agent」モードを選択
3. Skill 名、または実行したい作業内容を入力

例：

```text
liberty-doctor を実行して
この Liberty プロジェクトを診断して
```

## 貢献

バグ報告や機能追加の提案は、Issue またはプルリクエストでお願いします。

## Note

各 Skill の詳細な使い方や引数については、`skills/` ディレクトリ内の各 Markdown ファイルを参照してください。
