<!-- このファイルは shared/ からコピーしたものです。編集は shared/feature-matrix.md で行い、tools/sync-shared.sh を実行してください。 -->

# Liberty feature の対応表

EE / MicroProfile のバージョンと、Liberty の feature 名の対応。Open Liberty の feature 定義（webProfile-7.0 / 8.0 / 9.1 / 10.0 / 11.0、jakartaee-10.0 / 11.0、microProfile-5.0 / 6.1 / 7.0）で確認した内容。

表に無い feature やバージョンは、推測で書かず、使っている Liberty のバージョンのドキュメントで確認する。

## feature 名の変更（Jakarta EE 9 以降）

Liberty では Jakarta EE 9 以降で feature 名が変わっている。

*   `jaxrs` → `restfulWS`
*   `jpa` → `persistence`
*   `jms` → `messaging`

`jpa-3.x` や `jaxrs-3.x` という feature は **存在しない**。書くと `CWWKF0001E`（feature が見つからない）で起動しない。

## API と feature の対応

| API | Java EE 7 | Java EE 8 | Jakarta EE 9.1 | Jakarta EE 10 | Jakarta EE 11 |
| --- | --- | --- | --- | --- | --- |
| Servlet | `servlet-3.1` | `servlet-4.0` | `servlet-5.0` | `servlet-6.0` | `servlet-6.1` |
| CDI | `cdi-1.2` | `cdi-2.0` | `cdi-3.0` | `cdi-4.0` | `cdi-4.1` |
| REST | `jaxrs-2.0` | `jaxrs-2.1` | `restfulWS-3.0` | `restfulWS-3.1` | `restfulWS-4.0` |
| JSON-B | - | `jsonb-1.0` | `jsonb-2.0` | `jsonb-3.0` | `jsonb-3.0` |
| JSON-P | `jsonp-1.0` | `jsonp-1.1` | `jsonp-2.0` | `jsonp-2.1` | `jsonp-2.1` |
| Persistence | `jpa-2.1` | `jpa-2.2` | `persistence-3.0` | `persistence-3.1` | `persistence-3.2` |
| Bean Validation | `beanValidation-1.1` | `beanValidation-2.0` | `beanValidation-3.0` | `beanValidation-3.0` | `beanValidation-3.1` |
| Security | `appSecurity-2.0` | `appSecurity-3.0` | `appSecurity-4.0` | `appSecurity-5.0` | `appSecurity-6.0` |
| Messaging | `jms-2.0` | `jms-2.0` | `messaging-3.0` | `messaging-3.1` | `messaging-3.1` |
| JDBC | `jdbc-4.1` 〜 `4.3` | `jdbc-4.2` / `4.3` | `jdbc-4.2` / `4.3` | `jdbc-4.2` / `4.3` | `jdbc-4.2` / `4.3` |

REST のクライアント API だけを使う場合は、クライアント専用の feature（Java EE 8：`jaxrsClient-2.1`、Jakarta EE：`restfulWSClient-3.0` / `3.1` / `4.0`）もある。

## MicroProfile

| MicroProfile | 組み合わせる EE | MP Config | MP Rest Client | MP Health |
| --- | --- | --- | --- | --- |
| `microProfile-5.0` | Jakarta EE 9.1 | `mpConfig-3.0` | `mpRestClient-3.0` | `mpHealth-4.0` |
| `microProfile-6.1` | Jakarta EE 10 | `mpConfig-3.1` | `mpRestClient-3.0` | `mpHealth-4.0` |
| `microProfile-7.0` | Jakarta EE 10 / 11 | `mpConfig-3.1` | `mpRestClient-4.0` | `mpHealth-4.0` |

## 集約 feature に含まれるもの

*   `webProfile-*` / `jakartaee-*` / `javaee-*` には、Servlet・CDI・REST・Persistence・JSON-B・JSON-P・Bean Validation・Security・JDBC が含まれる
*   Messaging（JMS）は `webProfile-*` には含まれない（`jakartaee-*` / `javaee-*` には含まれる）
*   MP Rest Client などの MicroProfile の feature は、EE の集約 feature には含まれない（`microProfile-*` には含まれる）
*   含まれているかどうか確信が無い場合は、起動ログの `CWWKF0012I`（インストールされた feature の一覧）で確認する

## バージョンを混ぜない

*   同じ server.xml の中で EE のバージョンを混ぜない。例：`cdi-4.0`（EE 10）と `restfulWS-3.0`（EE 9.1）を混ぜると、依存解決で衝突する（`CWWKF0033E` などの競合）
*   MicroProfile は、組み合わせる EE のバージョンに合わせる（上の表）

## versionless feature と platform

*   `<featureManager>` の中の `<platform>` で platform のバージョンを宣言すると、バージョン無しの名前で feature を書ける

    ```xml
    <featureManager>
        <platform>jakartaee-10.0</platform>
        <platform>microProfile-7.0</platform>
        <feature>restfulWS</feature>
        <feature>mpConfig</feature>
    </featureManager>
    ```

*   `<platform>` は最大 2 つ（MicroProfile 用を 1 つと、Jakarta EE または Java EE 用を 1 つ）
*   platform は server.env の `PREFERRED_PLATFORM_VERSIONS` でも指定できる
*   versionless の名前は platform に合わせる。Jakarta EE：`persistence` / `restfulWS` / `messaging`、Java EE：`jpa` / `jaxrs` / `jms`。`servlet`、`cdi`、`jdbc` などは共通
*   versionless の構成では、generate-features（Maven の `liberty:generate-features`）が失敗する（`CWMIG12156E`。liberty-maven-plugin 3.12.2 で確認）
