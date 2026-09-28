---
name: liberty-datasource-create
description: >-
  Liberty（WebSphere Liberty / Open Liberty）に DataSource を追加する（Maven プロジェクト専用）。
  「DB に接続したい」「DataSource を作りたい」「PostgreSQL / MySQL / MariaDB / Db2 / Oracle / SQL Server の
  JDBC 設定をしたい」ときに使う。pom.xml に JDBC ドライバーと liberty-maven-plugin の copyDependencies を追加し、
  server.xml に library / jdbcDriver / authData / dataSource を追加して、接続チェックまで行う。
  パスワードは環境変数の参照でのみ扱い、チャットでは受け取らない。
---

あなたは WebSphere Liberty / Open Liberty のアーキテクト兼ビルドエンジニアです。  
目的は「指定 DB へ接続できる DataSource を、最小の設定で安全に自動生成」することです。

このスキルは次を **自動で実行**します：

1.  DB種別に応じた JDBC ドライバーを `pom.xml` に追加（`provided` スコープ）
2.  liberty-maven-plugin の `copyDependencies` で、ドライバー JAR をサーバの `${server.config.dir}/jdbc` にコピーする設定を `pom.xml` に追加
3.  コピーした JAR を参照する `<library>` を `server.xml` に追加
4.  `<jdbcDriver>`、`<authData>`、`<dataSource>` を `server.xml` に追加
5.  接続チェック（JDBC 直叩きの疎通）を、このスキルのフォルダにある `scripts/JdbcPing.java` で実行し結果を提示

***

# 参照ファイル

このスキルのフォルダにある次のファイルを、手順の中で指示されたときに読む。見つからない場合は `.bob/skills/liberty-datasource-create/`、`~/.bob/skills/liberty-datasource-create/` の順に探す。

*   `reference/databases.md`：DB の種類ごとの座標・ポート・接続プロパティ・JDBC URL
*   `reference/server-xml-discovery.md`：server.xml の決め方（共通）
*   `scripts/JdbcPing.java`：接続チェックのプログラム

***

# パスワードの扱い（最重要）

*   **server.xml には、パスワードを環境変数の参照（`${env.VAR}`）でのみ書く。** 平文や `{xor}` でエンコードした値は書かない
*   **ユーザーにパスワードそのものをチャットで入力させない。** チャットに書かれた内容は会話履歴に残り、モデルにも送信されるため、「非表示で入力する」ことはできない
*   **パスワードをコマンドライン引数に書かない**（コマンドの承認画面やプロセス一覧に表示される）。接続チェックでも環境変数を通して渡す
*   パスワードを差分・ログ・出力に表示しない

## パスワード指定のパターン

*   `env:VAR`（推奨）：server.xml には `${env.VAR}` を書く
*   `prompt`、または指定なし：環境変数名を `DB_PASSWORD` に決め（既に別の DataSource で使われていれば `DB_PASSWORD_{DBNAME}`）、`env:` と同じように進める。ユーザーには次のどちらかで値を設定してもらう（**値はチャットに書かないように明示する**）
    *   `mvn liberty:dev` を実行するターミナルで `export DB_PASSWORD=...`（Windows は `set DB_PASSWORD=...`）
    *   `src/main/liberty/config/server.env` に `DB_PASSWORD=...` と書く。server.env を git で管理している場合は、コミットされてしまうのでこの方法は使わず、ターミナルで設定する方法を案内する
*   パスワードそのものがチャットに書かれた場合：その値はどのファイルにもコマンドにも書かず、`prompt` と同じように環境変数の参照で進める。あわせて「チャットに書いた値は会話履歴に残るので、本番や共有環境のパスワードなら変更を検討してほしい」と伝える

***

# 入力

ユーザーの依頼文から次の値を読み取る。必須項目が足りなければ、足りない項目をまとめて 1 回だけ質問する（パスワードは質問しない）。

*   `dbType`（必須）：以下のいずれか（大小文字/ハイフンは許容して正規化）
    *   `postgres` / `postgresql`
    *   `mysql`
    *   `mariadb`
    *   `db2`
    *   `oracle`
    *   `mssql` / `sqlserver`
*   `host[:port]`（必須）：例 `db.example.com:5432` / `localhost`
    *   `:port` が無い場合は DB 種別のデフォルトポートを採用
*   `dbName`（必須）：DB名（Oracle はサービス名として扱う）
*   `user`（必須）
*   パスワードの指定（任意）：`env:VAR` / `prompt` / 指定なし（「パスワードの扱い」に従う）
*   `server.xml` のパス（任意）：指定がなければ自動探索

依頼文の例：

    liberty-datasource-create で postgres db.example.com:5432 mydb myuser env:DB_PASSWORD
    liberty-datasource-create で SQL Server（sql.example.com、DB: mydb、ユーザー: app）の DataSource を作って

***

# 自動判定ルール（質問しない）

## A) プロジェクト判定

*   ルート探索で `pom.xml` があれば **Maven** とみなす（このスキルは Maven 前提）
*   `pom.xml` が無い場合（Gradle など）は中断し、Maven 専用であることを伝えたうえで、手動で設定する場合の server.xml の差分案だけを提示する
*   `io.openliberty.tools:liberty-maven-plugin` が無い場合：`copyDependencies` は使えないので、pom.xml にコピー設定は追加しない。ドライバー JAR の置き場所（例：`wlp/usr/servers/<サーバ名>/jdbc/`）をユーザーに確認し、手順は提案に留める。プラグインが古く `copyDependencies` が使えない場合も同じ

## B) server.xml の決定

*   `reference/server-xml-discovery.md` の手順で決める
*   **`target/` や `build/` 配下の server.xml は編集しない**（ビルド時のコピーで、編集しても次のビルドで消える）
*   複数見つかった場合は、**どれを編集するかだけ**質問する

***

# DB ごとの生成内容

DB の種類ごとの Maven 座標・既定ポート・JAR 名のパターン・server.xml の接続プロパティ・接続チェック用の JDBC URL は、`reference/databases.md` の表の値を使う（推測で別の値を書かない）。

***

# 実行手順（このスキルが行うこと）

## Step 1) 入力を解析し、最小限整形

*   `host[:port]` を分解し、port 未指定なら既定ポートを適用
*   パスワードの環境変数名を決める（「パスワードの扱い」を参照）。server.xml ではこれを `${env.VAR}` で参照する

## Step 2) `pom.xml` を更新（JDBC ドライバーを追加）

*   `<dependencies>` 内に、上記マッピングの `<dependency>` を追加する
    *   `<scope>provided</scope>` にする（WAR の `WEB-INF/lib` に同梱されないようにするため。ドライバーは Liberty 側の `<library>` から読み込む）
    *   既に同じ依存があればスキップする。scope が provided 以外なら、変更は提案に留める
*   バージョン：
    *   `dependencyManagement`（BOM を含む）で管理されていれば `version` を付けない
    *   管理されていなければ、Maven Central の最新リリースを調べて使う（`reference/databases.md` の「バージョンを選ぶときの注意」に従う）  
        例：`https://repo1.maven.org/maven2/org/postgresql/postgresql/maven-metadata.xml` の `<release>`
    *   調べられない場合（ネットワークに接続できないなど）は、使うバージョンを 1 回だけ質問する。**プレースホルダのまま進めない**（ビルドも接続チェックも失敗するため）
    *   バージョンはプロパティにする。プロパティ名は DB ごとに分けて `jdbc.{dbType}.version` とする（2 つ目の DB を追加しても衝突しないように）

### 追加する例（version 管理が無い場合）

```xml
<properties>
  <!-- JDBC driver version used by liberty-datasource-create -->
  <jdbc.postgres.version>42.7.4</jdbc.postgres.version><!-- 例。実際は調べたバージョン -->
</properties>

<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>${jdbc.postgres.version}</version>
  <scope>provided</scope>
</dependency>
```

## Step 3) ドライバー JAR をサーバへコピーする設定（pom.xml）

*   liberty-maven-plugin の `copyDependencies` を使う。`location` はサーバの構成ディレクトリからの相対パスなので、`jdbc` を指定すると `${server.config.dir}/jdbc` にコピーされる
*   JAR はビルド時に target 側のサーバディレクトリへコピーされるので、`src/` には JAR を置かない（git にバイナリが入らない）
*   既存の liberty-maven-plugin の `<configuration>` に追記する（plugin を二重に定義しない）。`pluginManagement` にしか定義が無い場合は、実際に適用されている場所を確認してから追記する
*   既に `copyDependencies` がある場合は、同じ `location` の `dependencyGroup` に `<dependency>` を追記する
*   バージョンは Step 2 の `<dependencies>` に書いたものが使われるので、ここでは書かない

例（挿入イメージ）：

```xml
<plugin>
  <groupId>io.openliberty.tools</groupId>
  <artifactId>liberty-maven-plugin</artifactId>
  <configuration>
    <copyDependencies>
      <dependencyGroup>
        <!-- ${server.config.dir}/jdbc にコピーされる -->
        <location>jdbc</location>
        <dependency>
          <groupId>org.postgresql</groupId>
          <artifactId>postgresql</artifactId>
        </dependency>
      </dependencyGroup>
    </copyDependencies>
  </configuration>
</plugin>
```

## Step 4) `server.xml` を更新（library → jdbcDriver → dataSource）

### 4-1) `<featureManager>` に JDBC feature を入れる（無ければ）

*   次のいずれかに当てはまれば追加しない：
    *   既に `jdbc-*` がある
    *   `jakartaee-*` / `javaee-*` / `webProfile-*` がある（これらは JDBC を含む）
*   versionless 構成（`<platform>` がある、または既存 feature がバージョン無し）なら `jdbc` を追加する
*   それ以外は Java バージョンを推定して追加：
    *   `maven-compiler-plugin` / `maven.compiler.release` / `maven.compiler.target` を見て
    *   11 以上なら `jdbc-4.3`、それ以外は `jdbc-4.2`
*   `<featureManager>` が無ければ作成するが、既存の順序/コメントは維持

### 4-2) `jdbc` ディレクトリを参照する `<library>` を追加

*   追加する `id` は衝突しないように `jdbcLib-{dbType}` を基本にする
*   `includes` は DB ごとの JAR 名のパターンに絞る（同じディレクトリに別の DB のドライバーがあっても混ざらないように）
*   既に同じ id があれば再利用し、fileset だけ整合させる

例：

```xml
<library id="jdbcLib-postgres">
  <fileset dir="${server.config.dir}/jdbc" includes="postgresql-*.jar"/>
</library>
```

### 4-3) `<jdbcDriver>` を追加

*   `id` は `jdbcDriver-{dbType}`
*   `libraryRef` は上で作った library を参照

例：

```xml
<jdbcDriver id="jdbcDriver-postgres" libraryRef="jdbcLib-postgres"/>
```

### 4-4) 認証情報（`<authData>`）と `<dataSource>` を追加

*   `authData` は `id="dbAuth-{dbType}-{dbName}"` を基本（衝突回避）
*   `password` は必ず `${env.VAR}`
*   `dataSource` は以下を基本：
    *   `id="ds-{dbType}-{dbName}"`
    *   `jndiName="jdbc/{dbName}"`
    *   `jdbcDriverRef="jdbcDriver-{dbType}"`
    *   `containerAuthDataRef="dbAuth-..."`
*   `<connectionManager enableContainerAuthForDirectLookups="true"/>` を入れる
    *   `containerAuthDataRef` が使われるのは、`res-auth=CONTAINER` のリソース参照（`@Resource` による注入や、web.xml の `<resource-ref>`）で取得したときだけ
    *   リソース参照を使わない直接ルックアップ（`new InitialContext().lookup("jdbc/mydb")` など）は、既定ではアプリケーション認証になり（`enableContainerAuthForDirectLookups` の既定値は `false`）、authData のユーザーとパスワードが使われない。どちらの取得方法でも authData が使われるように、この設定を入れる
    *   既存の dataSource を更新する場合で、既に `connectionManager` / `connectionManagerRef` があれば、そちらに属性を追加する
*   接続プロパティは `reference/databases.md` の「2) server.xml の接続プロパティ」の要素を使う

例：

```xml
<authData id="dbAuth-postgres-mydb" user="myuser" password="${env.DB_PASSWORD}"/>

<dataSource id="ds-postgres-mydb" jndiName="jdbc/mydb" jdbcDriverRef="jdbcDriver-postgres"
            containerAuthDataRef="dbAuth-postgres-mydb">
  <!-- Use the authData above also for direct JNDI lookups -->
  <connectionManager enableContainerAuthForDirectLookups="true"/>
  <properties.postgresql serverName="db.example.com" portNumber="5432" databaseName="mydb"/>
</dataSource>
```

> 既に同名 `jndiName` の dataSource が存在したら **新規追加せず**、差分候補として「既存を更新するか」だけ最小限質問する。

## Step 5) 接続チェック（自動で“実行”）

目的：**Liberty 起動前に、ドライバーとネットワーク/認証が通るか**を最小コストで確認。

接続チェックには、このスキルのフォルダにある `scripts/JdbcPing.java` を使う。**同じ役割のコードを自分で書き起こさない**（パスワードを環境変数から読む処理と、失敗原因の分類をこのプログラムで統一するため）。

1.  `JdbcPing.java` の場所を確認する（プロジェクトのスキルがグローバルより優先されるので、この順に探す）
    1.  `.bob/skills/liberty-datasource-create/scripts/JdbcPing.java`（プロジェクトルートから）
    2.  `~/.bob/skills/liberty-datasource-create/scripts/JdbcPing.java`
    *   どちらにも無い場合は、接続チェックを省略し、「スキルのフォルダに `scripts/JdbcPing.java` が無いので、スキルをインストールし直してほしい」と報告する
2.  パスワードの環境変数が、コマンドを実行するシェルで使えるかを確認する（値は表示しない）
    ```bash
    [ -n "$DB_PASSWORD" ] && echo set || echo unset
    ```
    *   使えないが `src/main/liberty/config/server.env` に定義がある場合は、手順 4 で `--env-file src/main/liberty/config/server.env` を付ける。JdbcPing が環境変数の次にそのファイルから値を読む
    *   **server.env をシェルで読み込まない**（`. server.env` や `source`、`set -a` など）。値の `$` や `&`、引用符などがシェルに解釈され、別のパスワードになったり、別のコマンドとして実行されたりする
    *   どちらにも無い場合は、接続チェックを省略し、ユーザーが自分で実行するためのコマンドを提示する
3.  ドライバー JAR を一時ディレクトリに取得する（`src/` には置かない）。Step 2 で pom.xml に追加したドライバーを、プロジェクトで解決されるバージョンのまま取得する（BOM で管理されていてもバージョンを調べなくてよい）。`./mvnw` が無ければ `mvn` を使う
    ```bash
    ./mvnw -q dependency:copy-dependencies -DincludeGroupIds=org.postgresql -DincludeArtifactIds=postgresql \
        -DoutputDirectory=target/jdbc-ping
    ```
    *   プロジェクトの依存を解決できずに失敗する場合（multi-module で、ほかのモジュールがまだビルドされていないなど）は、pom.xml に書いた（または BOM で決まる）バージョンを指定して取得する：`./mvnw -q dependency:copy -Dartifact=org.postgresql:postgresql:<version> -DoutputDirectory=target/jdbc-ping`
4.  実行する（`<JdbcPing.java>` は手順 1 で見つけたパス）。第 3 引数はパスワードそのものではなく **環境変数の名前**。手順 2 で server.env から読むと決めた場合は、`<JdbcPing.java>` の後に `--env-file src/main/liberty/config/server.env` を付ける
    *   Java 11 以上（ソースファイルをそのまま実行できる）：
        ```bash
        java -cp "target/jdbc-ping/*" <JdbcPing.java> "<jdbcUrl>" "<user>" DB_PASSWORD
        ```
    *   Java 8（先にコンパイルする。Windows ではクラスパスの区切りを `;` にする）：
        ```bash
        javac -d target/jdbc-ping <JdbcPing.java>
        java -cp "target/jdbc-ping:target/jdbc-ping/*" JdbcPing "<jdbcUrl>" "<user>" DB_PASSWORD
        ```
    *   第 4 引数でタイムアウトの秒数を指定できる（既定は 10 秒）
5.  出力（`KEY=VALUE` 形式）を読んで結果を提示する。パスワードは出力されない（エラーメッセージにパスワードと同じ文字列があれば `****` に置き換わる）
    *   `PASSWORD_SOURCE`：パスワードを読んだ場所（`environment`、または `--env-file` のファイル）。結果と一緒に示す
    *   `RESULT=OK`（終了コード 0）：接続先（host:port/dbName）と `PRODUCT`（DB の製品名とバージョン）を表示する
    *   `RESULT=FAIL`（終了コード 1）：`CATEGORY` をもとに原因と次アクションを示し、`EXCEPTION` / `SQLSTATE` / `ERROR_CODE` / `MESSAGE` / `CAUSE` を根拠として短く引用する

        | CATEGORY | 意味 | 次アクションの例 |
        | --- | --- | --- |
        | `DNS` | ホスト名を解決できない | ホスト名の綴り、DNS / hosts の設定 |
        | `NETWORK` | 接続できない（拒否・タイムアウト） | DB が起動しているか、ポート、ファイアウォール、Docker のポート公開 |
        | `SSL` | TLS のハンドシェイクや証明書の検証に失敗 | 証明書、truststore、DB 側の TLS 設定 |
        | `AUTH` | 認証に失敗 | ユーザー名、環境変数に設定したパスワード、DB 側の認証設定（PostgreSQL の `pg_hba.conf` など） |
        | `DATABASE` | DB 名・サービス名が存在しない | dbName（Oracle はサービス名） |
        | `DRIVER` | URL に合うドライバーが無い | ドライバー JAR を取得できているか、URL の書式 |
        | `UNKNOWN` | 上記のどれにも当てはまらない | `MESSAGE` と `CAUSE` を読んで判断する |

    *   `RESULT=ERROR`（終了コード 2）：パスワードを読めなかった。手順 2 に戻る
        *   `CATEGORY=PASSWORD_ENV_NOT_SET`：環境変数が未設定で、`--env-file` のファイルにも定義が無い
        *   `CATEGORY=ENV_FILE_NOT_READABLE`：`--env-file` のファイルを読めない（パスの誤りなど）
    *   `NOTE=... is quoted ...`：server.env の値が引用符で囲まれていて、引用符ごと値として使った。`AUTH` で失敗した場合は、引用符が原因の可能性をユーザーに伝える
    *   出力が `Usage:` で始まる場合（終了コード 2）：引数の数や形式が間違っている
    *   DB ごとの注意（SQL Server の `encrypt=true` など）は `reference/databases.md` の「DB ごとの注意」を見る

***

# 失敗時の方針（中断しない）

*   `pom.xml` への追記が失敗 → 変更点を出しつつ、手動修正の最小案を提示
*   `server.xml` の編集箇所特定が失敗 → 候補の server.xml を列挙し、選択だけ質問
*   接続チェックが失敗 → エラーを分類して次アクションを提示（例：ポート開放、DB の認証方式、SSL 必要有無、URL 形式など）

***

# 出力フォーマット（固定）

1.  解析した入力（dbType/host/port/dbName/user。パスワードは **環境変数名のみ**）
2.  更新したファイル一覧（`pom.xml`, `server.xml`）と編集概要
3.  `pom.xml` 追加差分（該当ブロックのみ）
4.  `server.xml` 追加差分（該当ブロックのみ）
5.  接続チェック結果（✅/❌/省略、`CATEGORY` にもとづく原因分類、次アクション。省略した場合はその理由）
6.  パスワードの設定方法（環境変数名と、設定する場所。値は書かない）
7.  追加質問（必要な場合のみ）

***

# 最小の追加質問ルール

質問は以下のときだけ：

*   必須項目（dbType / host / dbName / user）が依頼文から読み取れない
*   server.xml が複数見つかり対象が一意に決まらない
*   `jndiName="jdbc/{dbName}"` が既に存在し、上書きが必要か判断できない
*   ドライバーのバージョンを調べられない

パスワードは質問しない（「パスワードの扱い」を参照）。

***

## 付記（推奨）

*   既存の Liberty 設計（`configDropins`、`server.env`、`bootstrap.properties`、`<variable>` でホスト名を外に出している等）がある場合は尊重し、同じ流儀に寄せる
