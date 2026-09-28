# sample-app で各 Skill を試したときに期待する結果

`tests/fixtures/sample-app` には、各 Skill が検出すべき問題をわざと入れてあります。Bob で Skill を試し、次の結果になるかを確かめてください。

## 準備

1.  `tests/fixtures/sample-app` を **このリポジトリの外** にコピーする（このファイルを Bob に読ませないため）
    ```bash
    cp -R tests/fixtures/sample-app /tmp/sample-app
    ```
2.  コピーしたフォルダで git を初期化する（feature-min が元に戻したかを `git status` で確かめるため）
    ```bash
    cd /tmp/sample-app && git init -q && git add -A && git commit -qm init
    ```
3.  Skill をインストールする
    ```bash
    /path/to/liberty-bob-skills/install.sh --project /tmp/sample-app
    ```
4.  `/tmp/sample-app` を VS Code で開き、Bob を **Agent モード** にする

## 仕込んである問題（答え）

| 場所 | 内容 |
| --- | --- |
| server.xml の featureManager | `jpa-3.1` は存在しない feature 名（Jakarta EE 10 では `persistence-3.1`。しかも `jakartaee-10.0` に含まれる） |
| server.xml の featureManager | `mpHealth-4.0` はアプリから使われていない |
| server.xml の featureManager | `jakartaee-10.0` は集約 feature。アプリが使うのは REST・CDI・JSON-B・MP Rest Client だけ |
| server.xml の dataSource | `${db.host:localhost}` は Liberty に無い書き方 |
| server.xml の dataSource | `password` に値が直書きされている |
| server.xml の variable | `${app.greting}` は未定義（pom.xml の `liberty.var.app.greeting` のタイポ） |
| server.xml の httpEndpoint | `host` が無いので `localhost` でしか待ち受けない |
| server.xml の dataSource | `jndiName="jdbc/orders"` が既にある（datasource-create で同じ名前を作ると衝突する） |
| server.env | `DB_USER=app` は `${db.user}` の定義（環境変数名の読み替え。タイポではない） |
| server.xml | `${server.config.dir}` と `${server.output.dir}` は Liberty が定義している変数（未定義ではない） |
| Java | `ExternalApi` は `@RegisterRestClient` の付いた、外部 API を呼ぶ側のインターフェース（サーバーのエンドポイントではない） |
| Java | `OrderResource#items` はサブリソースロケーター（`ItemResource` に GET / POST がある） |

## liberty-doctor

依頼：`liberty-doctor でこのプロジェクトを診断して`

- [ ] 対象の server.xml として `src/main/liberty/config/server.xml` を選ぶ（`target/` 配下は候補にしない）
- [ ] 🔴 `jpa-3.1` が存在しない feature 名であることを指摘する（起動時に `CWWKF0001E` になる）
- [ ] `${db.host:localhost}` が Liberty に無い書き方で、解決されないことを指摘する
- [ ] `${app.greting}` が未定義で、`app.greeting`（pom.xml の `liberty.var.app.greeting`）のタイポの可能性を示す
- [ ] `httpEndpoint` に `host` が無く、`localhost` でしか待ち受けないことを注記する
- [ ] `${db.user}`、`${server.config.dir}`、`${server.output.dir}` を未解決として **扱わない**
- [ ] ログが見つからないこと（まだビルド・起動していない）を所見にする
- [ ] liberty-feature-min スキルへの案内が付く
- [ ] ファイルを編集しない

## liberty-feature-min

依頼：`liberty-feature-min で feature を最小化して`

- [ ] 実行前に、ビルドを実行すること・一時的に変更して元に戻すことを伝える
- [ ] `scripts/GenerateRequiredFeatures.java` を使って生成する（ファイルを手で書き換えない）
- [ ] 必要な feature の一覧が `restfulWS-3.1`、`cdi-4.0`、`jsonb-3.0`、`mpRestClient-3.0` になる（liberty-maven-plugin 3.12.2 の場合）
- [ ] 実行後に `git status` で src 側に変更が無いことを確かめ、出力に書く
- [ ] `jakartaee-10.0` を 🧩 分解の対象にする
- [ ] `mpHealth-4.0` は一覧に無いが、監視用途の可能性があるので 🟡 要確認にする
- [ ] `jpa-3.1` は存在しない名前なので、削除（🗑）を提案する
- [ ] `<dataSource>` があるので、`jdbc-*` が必要なことを指摘する（一覧には出てこない）
- [ ] `mpRestClient-3.0` が現在の指定で満たされていない（`jakartaee-10.0` は MicroProfile を含まない）ことを 🟡 で指摘する
- [ ] 案B が `restfulWS-3.1`、`cdi-4.0`、`jsonb-3.0`、`mpRestClient-3.0`、`jdbc-4.2` または `4.3` を含む（EE 10 と MicroProfile のバージョンを混ぜない）
- [ ] server.xml を編集しない

## liberty-env-vars-audit

依頼：`liberty-env-vars-audit でこのプロジェクトの変数を棚卸しして`

- [ ] ❌ `${db.host:localhost}`：Liberty に無い書き方。`<variable name="db.host" defaultValue="localhost"/>` への書き換えを提案する
- [ ] ❌ / ✍️ `${app.greting}`：未定義。`app.greeting` のタイポの可能性
- [ ] ✅ `${db.user}`：server.env の `DB_USER` で定義されている（タイポ扱いしない）
- [ ] ⚠️ `${http.port}`：既定値（`defaultValue`）のみ
- [ ] `${server.config.dir}`、`${server.output.dir}` を未定義にしない
- [ ] dataSource の `password` の直書きを検出し、値を `****` で伏せる（一部も見せない）
- [ ] ファイルを編集しない

## liberty-feature-add

依頼：`liberty-feature-add で JPA を追加して`

- [ ] EE のバージョンを Jakarta EE 10 と判定する（根拠：pom.xml の `jakarta.jakartaee-api` 10.0.0）
- [ ] `jakartaee-10.0` に `persistence-3.1` が含まれるので、feature を追加しない
- [ ] `jpa-3.1` や `jpa-3.0` を **追加しない**
- [ ] 既存の `jpa-3.1` が存在しない名前であることを、別枠の置換案として示す（自動では置換しない）

## liberty-endpoint-map

依頼：`liberty-endpoint-map で REST エンドポイントの一覧を出して`

- [ ] jakarta（Jakarta RESTful Web Services）と判定する
- [ ] contextRoot `/shop`（server.xml）、applicationPath `/api`（`App.java`）、ポート 9080（`http.port` の既定値）
- [ ] 次のエンドポイントを出す
  - `GET /shop/api/orders`
  - `GET /shop/api/orders/{id: \d+}`（正規表現に一致しない値は 404 になる旨の注記）
  - `GET /shop/api/orders/{id}/items`（`OrderResource#items` のロケーター経由）
  - `POST /shop/api/orders/{id}/items`（ロケーター経由、Consumes `application/json`）
- [ ] `ExternalApi` をサーバーのエンドポイントに含めず、「外部呼び出し定義（参考）」として別枠に出す
- [ ] `host` が無いので `localhost` からしか呼べないことを注記する
- [ ] ファイルを編集しない

## liberty-datasource-create

依頼：`liberty-datasource-create で postgres localhost:5432 orders app env:DB_PASSWORD`

- [ ] `jndiName="jdbc/orders"` が既にあるので、新しく追加せず「既存を更新するか」を質問する
- [ ] パスワードをチャットで求めない
- [ ] 更新する場合、server.xml の password は `${env.DB_PASSWORD}` にする（平文を書かない）
- [ ] `src/` に JAR を置かない（`copyDependencies` を使う）
