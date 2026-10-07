# 開発・リリースの進め方

- 誰でも使えるOSSのプラグインとして作る。特定のサーバー（spa77-smpなど）専用の文面・設定・依存を入れない。サーバー固有の設定は使う側のリポジトリ（`../spsmc-infra/plugins/Friend/`）に置く。
- Spigot・Paper 1.20.4 以降で動くよう、Java 17 と Bukkit API（spigot-api）だけを使う。Paper専用API（Adventure、`serializeAsBytes`など）は使わない。
- 文面は `messages_en.yml` と `messages_ja.yml` の両方に同じキーで足す。
- 作業前に`git status --short`を確認し、既存の変更を上書き・削除しない。
- 変更したら `mvn -B package` と `python3 scripts/test-friend-paper.py` を通す。古い版の確認は `PAPER_JAR=paper-1.20.4-499.jar python3 scripts/test-friend-paper.py`（`server-data/`にJARを置く）。
- 共有チェストの画面の操作（2人で同時に開いての出し入れ、閉じたときの保存）、コマンドの表示、テレポートの待ち時間は自動テストで確かめられない。変えたらJava版とBedrock版の実クライアントで確かめ、確かめたかどうかを報告する。
