# テスト

Skill の手順そのもの（Bob がどう動くか）は自動ではテストできないので、次の 2 つに分けています。

| 種類 | 対象 | 方法 |
| --- | --- | --- |
| 自動 | Skill の形式、`shared/` の同期、`scripts/` のプログラム | `tests/run.sh`（CI でも同じものを実行） |
| 手動 | Bob で各 Skill を動かしたときの結果 | `tests/fixtures/sample-app` と `tests/manual/sample-app.md` |

## 自動テスト

```bash
tests/run.sh               # Skill の形式、shared/ の同期、スクリプトのテスト（偽のビルドコマンドを使う）
tests/run.sh --with-maven  # 上に加えて、sample-app で本物の liberty:generate-features も試す
```

必要なもの：JDK 11 以上、Maven、Python 3、bash。

| ファイル | 内容 |
| --- | --- |
| `tools/validate-skills.py` | frontmatter（`name` と `description` だけか、`name` がフォルダ名と同じか）、`reference/` と `scripts/` の参照、コードブロックの閉じ |
| `tools/sync-shared.sh --check` | `skills/*/reference/` のコピーが `shared/` とずれていないか |
| `tests/scripts/test-jdbc-ping.sh` | `JdbcPing.java`：成功、失敗の分類（AUTH / DRIVER / NETWORK / DNS）、パスワードを表示しないこと、Java 8 向けのコンパイル。H2 と PostgreSQL のドライバーを Maven で取得する（DB サーバーは不要） |
| `tests/scripts/test-generate-required-features.sh` | `GenerateRequiredFeatures.java`：元に戻すこと（ビルド失敗、SIGTERM、SIGKILL の後の `--restore`）、実行前に止めるケース、Java 8 向けのコンパイル。`--with-maven` で sample-app を使った本物の生成 |

## 手動テスト（Bob で確かめる）

`tests/fixtures/sample-app` は、各 Skill が検出すべき問題をわざと入れた Maven プロジェクトです。Bob に答えを読まれないように、プロジェクトの中には説明を書いていません。

手順と期待する結果は `tests/manual/sample-app.md` にあります。Skill を変更したら、関係する Skill の項目を確かめてください。

sample-app に問題を追加・変更したときは、`tests/manual/sample-app.md` と、`tests/scripts/test-generate-required-features.sh` の本物の生成のテスト（期待する feature）も合わせて直してください。
