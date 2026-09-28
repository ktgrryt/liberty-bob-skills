# server.xml の決め方

Liberty の設定を読む・直すときに、対象にする server.xml を決める手順。

## 探す順番

1.  ユーザーが指定したパス（ディレクトリが指定された場合は、そこを探索ルートにして 2 以降を探す）
2.  `src/main/liberty/config/server.xml`（Maven / Gradle の Liberty プラグインの標準）
3.  `config/server.xml`
4.  `wlp/usr/servers/*/server.xml`（Liberty ランタイムを直接置いている構成）
5.  その他の `**/server.xml`

## 除外するディレクトリ

`target/`、`build/`、`node_modules/`、`.git/` 配下は候補から除外する。

*   `target/liberty/wlp/usr/servers/*/` や `build/wlp/usr/servers/*/` にある server.xml は、ビルド時に src 側からコピーされたもの
*   **ここを編集しても、次のビルドで上書きされて消える。** 修正案や編集は、必ず src 側のファイルに対して行う
*   実際に起動したサーバが読んでいる構成（プラグインが生成した configDropins など）を確かめる目的で **読む** のはよい

## 複数見つかった場合

候補を番号付きで列挙し、1 回だけ選んでもらう。

例：

*   `1) src/main/liberty/config/server.xml`
*   `2) wlp/usr/servers/app/server.xml`

→ 「番号だけで選んでください（例：1）」

選ばれた server.xml を「主」として扱う。ほかの server.xml を補助として読んでもよいが、出力では主と補助を区別して示す。

## 一緒に読む構成ファイル

server.xml と同じディレクトリ（`${server.config.dir}`）にある次のファイルも、構成の一部として扱う。

*   `configDropins/defaults/*.xml`：server.xml より **先** に読まれる
*   `configDropins/overrides/*.xml`：server.xml より **後** に読まれ、server.xml の内容より **優先** される
    *   各ディレクトリの中は、ファイル名のアルファベット順に読まれる
    *   `generated-features.xml`（generate-features の出力）や `liberty-plugin-variable-config.xml`（pom.xml の `liberty.var.*` から生成される）もここに置かれる
*   `<include>` / `<includeOptional>` の参照先
    *   `${...}` 変数を使った参照先は、変数を解決できる範囲で読む
