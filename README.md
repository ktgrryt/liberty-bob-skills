# Liberty Bob Commands

WebSphere Liberty / Open Liberty 開発を支援するBob用スラッシュコマンド集です。

## 概要

このリポジトリは、IBM BobでWebSphere Liberty / Open Libertyの開発・運用を効率化するためのスラッシュコマンドを提供します。コードレビュー、設定管理、トラブルシューティング、移行支援など、日常的な作業を自動化・効率化できます。

## スラッシュコマンドとは？

スラッシュコマンドは、チャット入力欄で `/` から始まるコマンドです。よく行う作業（レビュー依頼、手順の定型化、チェックリスト実行など）を短いコマンド1つで呼び出せます。

## セットアップ

### 1. コマンドの配置

以下のいずれかのディレクトリに`commands/`フォルダを配置してください：

```
.bob/commands/          # プロジェクト固有のコマンド
~/.bob/commands/        # グローバルコマンド
```

### 2. コマンドを使用

Bobのチャット入力欄で `/` を入力すると、利用可能なコマンド一覧が表示されます。

文字を追加すると候補が絞り込まれます。コマンドを選択後、必要に応じて引数を追加して実行してください。


## コマンド一覧

#### `/liberty-doctor`
**概要**: Libertyプロジェクト全体を一発健康診断（設定/ビルド/ログ/起動失敗原因を自動チェック）

**使い方**:
```
/liberty-doctor                        # カレントディレクトリを診断
/liberty-doctor path/to/server.xml     # 特定のserver.xmlを診断
```

**主な機能**:
- 設定の散らばり（server.xml、configDropins）を可視化
- ビルドツール・プラグインの整合性チェック
- ポート競合・バインド系の確認
- よくある起動失敗原因の候補化
- feature最小化が必要な場合は`/liberty-feature-min`へ誘導


#### `/liberty-feature-min`
**概要**: generated-features.xmlを生成・差分分析し、server.xmlのfeature最小化案を提示

**使い方**:
```
/liberty-feature-min                        # 自動探索・生成
/liberty-feature-min path/to/server.xml     # 特定のserver.xmlを対象
/liberty-feature-min path/to/server.xml --force  # 強制再生成
```

**主な機能**:
- generated-features.xmlの自動生成（Maven/Gradle対応）
- server.xmlとの差分分析
- 削除候補・残すべきfeatureの分類
- 段階的削減プランの提示
- 検証チェックリストの生成



#### `/liberty-env-vars-audit`
**概要**: Liberty構成の${...}変数参照を棚卸しし、未定義・タイポ疑い・環境差分を検出

**使い方**:
```
/liberty-env-vars-audit                    # カレントディレクトリを棚卸し
/liberty-env-vars-audit path/to/server.xml # 特定のserver.xmlを対象
```

**主な機能**:
- 変数参照（${...}）の全列挙
- 未定義変数・タイポ疑いの検出
- local/dev/stg/prod環境差分の可視化
- 秘匿値直書きの検出（値はマスク）
- 環境別テンプレート（.env、server.env）の提案


#### `/liberty-feature-add`
**概要**: 指定した機能（JPA、REST Clientなど）に必要なLiberty featureと依存関係を自動追加

**使い方**:
```
/liberty-feature-add JPA              # JPA機能を追加
/liberty-feature-add REST Client      # REST Client機能を追加
/liberty-feature-add CDI              # CDI機能を追加
```

**主な機能**:
- Java EE/Jakarta EE世代の自動判定
- 適切なfeatureの選択と追加
- pom.xml/build.gradleへの依存関係追加
- 最小サンプルコードの提供（オプション）
- 検証チェックリストの生成



#### `/liberty-datasource-create`
**概要**: 指定DBへのDataSourceを自動作成（JDBCドライバー追加→Liberty設定→接続チェック）

**使い方**:
```
/liberty-datasource-create postgres localhost:5432 mydb user env:DB_PASSWORD
/liberty-datasource-create mysql db.example.com mydb user prompt
/liberty-datasource-create oracle host:1521 service user env:ORACLE_PASSWORD
```

**引数**:
- `<dbType>`: postgres/mysql/mariadb/db2/oracle/mssql
- `<host[:port]>`: ホスト名とポート（ポート省略時はデフォルト）
- `<dbName>`: データベース名
- `<user>`: ユーザー名
- `<password>`: パスワード（`env:VAR`で環境変数、`prompt`で対話入力）

**主な機能**:
- JDBCドライバーのpom.xml追加
- server.xmlへのlibrary/jdbcDriver/dataSource追加
- 接続チェックの自動実行
- 環境変数参照の推奨



#### `/liberty-endpoint-map`
**概要**: JAX-RSリソースを走査してRESTエンドポイント一覧を生成し、404/405の原因候補を提示

**使い方**:
```
/liberty-endpoint-map           # カレントディレクトリを探索
/liberty-endpoint-map src/      # 特定ディレクトリを探索
```

**主な機能**:
- @Pathアノテーションの自動検出
- エンドポイント一覧（HTTPメソッド、パス、セキュリティ）の生成
- contextRoot/applicationPathの自動判定
- curl/httpieサンプルの生成
- 404/405トラブルシュート候補の提示


## トラブルシューティング

### Bobがスラッシュコマンドを実行しない

Bobがスラッシュコマンドを実行するのではなく、コマンドの内容を実装しようとしてしまう場合は、BobのモードをCodeやAdvancedに変更してからスラッシュコマンドを実行してください。

モードの変更方法：
1. Bobのチャット画面上部でモード選択ボタンをクリック
2. 「💻 Code」または「🛠️ Advanced」モードを選択
3. スラッシュコマンドを実行



## 貢献

バグ報告や機能追加の提案は、Issueまたはプルリクエストでお願いします。



**Note**: 各コマンドの詳細な使い方や引数については、`commands/`ディレクトリ内の各Markdownファイルを参照してください。
