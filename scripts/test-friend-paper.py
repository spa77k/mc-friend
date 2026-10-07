#!/usr/bin/env python3
"""隔離したPaperサーバーで、申請・承認・手数料・上限・共有チェスト・解消を確かめる。

server-data/ に Paper の JAR（paper-26.2-129.jar）と、plugins/Vault.jar・plugins/EssentialsX-2.22.0.jar を置いておく。
先に `mvn -B package` を実行しておく（target/test-classes を使う）。
"""
from pathlib import Path
import os
import shutil
import subprocess
import sys
import threading
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "server-data"
WORK = ROOT / "target/friend-paper-smoke"
JAVA = os.environ.get("JAVA_BIN", "/opt/homebrew/opt/openjdk/bin/java")
PAPER = os.environ.get("PAPER_JAR", "paper-26.2-129.jar")


def main():
    if WORK.exists():
        shutil.rmtree(WORK)
    plugins = WORK / "plugins"
    (plugins / "Friend").mkdir(parents=True)
    for source, target in (
        (SOURCE / PAPER, WORK / "paper.jar"),
        (SOURCE / "plugins/Vault.jar", plugins / "Vault.jar"),
        (SOURCE / "plugins/EssentialsX-2.22.0.jar", plugins / "EssentialsX.jar"),
        (ROOT / "target/friend-1.0.1.jar", plugins / "Friend.jar"),
    ):
        shutil.copy2(source, target)
    (plugins / "Friend/config.yml").write_text(
        "language: ja\nfee: 500\nmax-friends: 2\n"
    )
    with zipfile.ZipFile(plugins / "FriendProbe.jar", "w") as jar:
        jar.writestr("plugin.yml", "name: FriendProbe\nversion: 1\n"
                     "main: io.github.spa77k.friend.FriendProbe\n"
                     "api-version: '1.20'\ndepend: [Friend, Vault, Essentials]\n")
        for source in (ROOT / "target/test-classes/io/github/spa77k/friend").glob("FriendProbe*.class"):
            jar.write(source, "io/github/spa77k/friend/" + source.name)
    (WORK / "eula.txt").write_text("eula=true\n")
    (WORK / "server.properties").write_text(
        "server-ip=127.0.0.1\nserver-port=25588\nonline-mode=false\n"
        "spawn-protection=0\nmax-players=1\nlevel-type=minecraft:flat\n"
    )
    process = subprocess.Popen(
        [JAVA, "-Xms512M", "-Xmx1G", "-jar", "paper.jar", "--nogui"],
        cwd=WORK, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    lines = []
    done = threading.Event()

    def read_output():
        for line in process.stdout:
            lines.append(line)
            if "FRIEND_PROBE_PASS" in line or "FRIEND_PROBE_FAIL" in line:
                done.set()

    threading.Thread(target=read_output, daemon=True).start()
    try:
        if not done.wait(240):
            raise RuntimeError("Paperの検証が240秒以内に終わりませんでした")
    finally:
        try:
            process.wait(60)
        except subprocess.TimeoutExpired:
            process.kill()
    output = "".join(lines)
    for line in lines:
        if any(word in line for word in ("ok: ", "[Friend]", "FRIEND_PROBE", "Exception", "Error", "\tat ")):
            print(line.rstrip())
    if "FRIEND_PROBE_PASS" not in output:
        sys.exit(1)


if __name__ == "__main__":
    main()
