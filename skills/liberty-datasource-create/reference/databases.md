# DB ごとの生成内容（固定マッピング）

liberty-datasource-create が DB の種類ごとに使う値。推測で別の値を書かない。

## 1) JDBC ドライバー

| dbType | 座標（groupId:artifactId） | 既定ポート | JAR 名のパターン |
| --- | --- | --- | --- |
| postgres | `org.postgresql:postgresql` | 5432 | `postgresql-*.jar` |
| mysql | `com.mysql:mysql-connector-j` | 3306 | `mysql-connector-j-*.jar` |
| mariadb | `org.mariadb.jdbc:mariadb-java-client` | 3306 | `mariadb-java-client-*.jar` |
| db2 | `com.ibm.db2:jcc` | 50000 | `jcc-*.jar` |
| oracle | `com.oracle.database.jdbc:ojdbc11`（Java 11+。Java 8 は `ojdbc8`） | 1521 | `ojdbc11-*.jar`（`ojdbc8-*.jar`） |
| mssql | `com.microsoft.sqlserver:mssql-jdbc` | 1433 | `mssql-jdbc-*.jar` |

バージョンを選ぶときの注意：

*   プレビュー版（`-preview` など）は避ける
*   mssql-jdbc は `12.x.x.jre11` / `12.x.x.jre8` のように Java バージョン別に分かれているので、プロジェクトの Java バージョンに合うものを選ぶ

## 2) server.xml の接続プロパティ

Liberty の推奨に従い、DB 専用の `properties.*` 要素があればそれを使う：

*   postgres：`<properties.postgresql serverName="{host}" portNumber="{port}" databaseName="{dbName}"/>`
*   db2：`<properties.db2.jcc serverName="{host}" portNumber="{port}" databaseName="{dbName}"/>`
*   mssql：`<properties.microsoft.sqlserver serverName="{host}" portNumber="{port}" databaseName="{dbName}"/>`
*   oracle：`<properties.oracle URL="jdbc:oracle:thin:@//{host}:{port}/{dbName}"/>`（dbName をサービス名として扱う）
*   mysql / mariadb（専用の要素が無い）：`<properties serverName="{host}" portNumber="{port}" databaseName="{dbName}"/>`

## 3) 接続チェック用の JDBC URL

*   postgres: `jdbc:postgresql://{host}:{port}/{dbName}`
*   mysql: `jdbc:mysql://{host}:{port}/{dbName}`
*   mariadb: `jdbc:mariadb://{host}:{port}/{dbName}`
*   db2: `jdbc:db2://{host}:{port}/{dbName}`
*   oracle: `jdbc:oracle:thin:@//{host}:{port}/{dbName}`
*   mssql: `jdbc:sqlserver://{host}:{port};databaseName={dbName}`

## 4) DB ごとの注意

*   SQL Server：mssql-jdbc 10 以降は既定で `encrypt=true` なので、自己署名証明書の DB では接続チェックが `SSL` になる。開発環境に限り `trustServerCertificate=true` を案内する
*   MySQL：MySQL 8 の既定の認証方式（`caching_sha2_password`）では、TLS を使わない接続で `Public Key Retrieval is not allowed` になることがある（接続チェックは `UNKNOWN` になる）。開発環境に限り `allowPublicKeyRetrieval=true`（接続チェックの URL は `?allowPublicKeyRetrieval=true`、server.xml は `<properties>` の属性）を案内する。本番では DB 側で TLS を有効にする
