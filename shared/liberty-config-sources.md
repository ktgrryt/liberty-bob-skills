# Liberty の変数の定義元と優先順位

server.xml などの `${...}` がどこで定義され、どの値が使われるか。Open Liberty の「Server configuration overview」と liberty-maven-plugin のドキュメントで確認した内容。

## 優先順位

下にあるほど優先される。同じ名前が複数の場所にあれば、最も優先度の高いものが使われる。

1.  server.xml の `<variable defaultValue="...">`（configDropins と、pom.xml の `liberty.defaultVar.*` を含む）
2.  環境変数（`server.env`、pom.xml の `liberty.env.*`、コンテナや OS の環境変数）
3.  `bootstrap.properties`（pom.xml の `liberty.bootstrap.*` を含む）
4.  Java システムプロパティ（`jvm.options` の `-D`、pom.xml の `liberty.jvm.*`）
5.  `${server.config.dir}/variables/` 配下のファイル
6.  server.xml の `<variable value="...">`（configDropins と、pom.xml の `liberty.var.*` を含む。configDropins/overrides のものは server.xml より優先）
7.  コマンドラインで指定した変数

## ファイルの場所

### server.env

読まれる順（同じ変数は後に読んだものが使われる）：

1.  `${wlp.install.dir}/etc/`
2.  `${wlp.user.dir}/shared/`
3.  `${server.config.dir}/`

### jvm.options

読まれる順：

1.  `${wlp.user.dir}/shared/`
2.  `${server.config.dir}/configDropins/defaults/`
3.  `${server.config.dir}/`
4.  `${server.config.dir}/configDropins/overrides/`

どれも無い場合は `${wlp.install.dir}/etc/` を読む。**jvm.options は変数置換（`${...}`）をサポートしない。**

### bootstrap.properties

`${server.config.dir}/bootstrap.properties`。サーバの起動の初期に読まれる。

### variables ディレクトリ

*   既定は `${server.config.dir}/variables/`。環境変数 `VARIABLE_SOURCE_DIRS` で別の場所を指定できる（Windows は `;`、Unix は `:` で区切る）
*   ファイル名が変数名、ファイルの中身が値になる
*   Kubernetes の Secret / ConfigMap をマウントする先としてよく使われる

## Liberty が最初から定義している変数

次の変数は定義が無くても解決されるので、「未定義」として扱わない。

*   ディレクトリの変数：`wlp.install.dir`、`wlp.user.dir`、`usr.extension.dir`、`shared.app.dir`、`shared.config.dir`、`shared.resource.dir`、`shared.stack.dir`、`server.config.dir`、`server.output.dir`、`wlp.server.name`
*   Java の標準システムプロパティ（`user.home`、`user.dir`、`java.home`、`java.io.tmpdir` など）

## 環境変数名の読み替え

`${name}` の名前がそのまま見つからない場合、Liberty は環境変数を次の順に探す。

1.  そのまま（例：`my.env.var`）
2.  英数字以外を `_` に置き換えた名前（`my_env_var`）
3.  さらにすべて大文字にした名前（`MY_ENV_VAR`）

→ server.env の `DB_USER=...` は `${db.user}` の定義になる。`db.user` と `DB_USER` の違いはタイポではない。

`${env.NAME}` と書くと、環境変数 `NAME` を直接参照する。

## Liberty に無い書き方

*   `${name:default}` のように、`${...}` の中で既定値を書く構文は **無い**（`name:default` という名前の変数として扱われ、解決されない）。既定値は `<variable name="name" defaultValue="default"/>` で与える
*   `${name:default}` は MicroProfile Config（`microprofile-config.properties` など）の書き方で、解決のルールが別
*   算術式（例：`${http.port+1}`）は使える

## ビルドプラグインが生成する定義（Maven）

pom.xml の `<properties>`（プロファイルの中も含む）に書くと、プラグインがサーバの構成ファイルを生成する。

| プロパティ | 生成されるもの |
| --- | --- |
| `liberty.var.{name}` | `<variable name="{name}" value="...">`（configDropins/overrides の `liberty-plugin-variable-config.xml`） |
| `liberty.defaultVar.{name}` | `<variable name="{name}" defaultValue="...">`（configDropins/defaults の `liberty-plugin-variable-config.xml`） |
| `liberty.bootstrap.{name}` | bootstrap.properties の `{name}=...` |
| `liberty.env.{name}` | server.env の `{name}=...` |
| `liberty.jvm.{任意}` | jvm.options（値だけが書かれる） |

*   liberty-maven-plugin の `<bootstrapProperties>` / `<jvmOptions>` / `<serverEnvFile>` / `<bootstrapPropertiesFile>` / `<jvmOptionsFile>` でも、同じファイルに値を渡せる
*   コマンドラインの `-D` で指定した値は、pom.xml のプロパティより優先される
*   Gradle の Liberty プラグインにも同じ種類の設定がある（例：`liberty.server.var.*`）。名前は使っているプラグインのドキュメントで確認する
