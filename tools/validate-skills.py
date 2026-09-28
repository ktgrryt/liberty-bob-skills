#!/usr/bin/env python3
"""skills/ の各 Skill の形式を検査する。

検査すること:
  - 各フォルダに SKILL.md がある
  - frontmatter に name と description だけがある（Bob が文書化しているのはこの 2 つ）
  - name がフォルダ名と同じで、description が空でない
  - SKILL.md が `reference/...` / `scripts/...` として指しているファイルが実在する
  - reference/ と scripts/ のファイルが、SKILL.md のどこかから指されている
  - Markdown のコードブロック（```）が閉じている（skills/、shared/、tests/manual/、README.md）

使い方: python3 tools/validate-skills.py（問題があれば終了コード 1）
標準ライブラリだけで動くように、frontmatter は必要な範囲だけを自前で読む。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOWED_KEYS = {"name", "description"}
REF_PATTERN = re.compile(r"`((?:reference|scripts)/[A-Za-z0-9._-]+)`")
FENCE_PATTERN = re.compile(r"^\s*```")

errors = []


def error(path, message):
    errors.append(f"{path.relative_to(ROOT)}: {message}")


def read_frontmatter(path, text):
    """先頭の --- から --- までを読み、トップレベルのキーと値（ブロック形式は続く行を連結）を返す。"""
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        error(path, "frontmatter が無い（先頭の行が --- ではない）")
        return {}
    try:
        end = lines.index("---", 1)
    except ValueError:
        error(path, "frontmatter が閉じていない")
        return {}
    keys = {}
    current = None
    for line in lines[1:end]:
        m = re.match(r"^([A-Za-z0-9_-]+):\s*(.*)$", line)
        if m:
            current = m.group(1)
            value = m.group(2).strip()
            keys[current] = "" if value in (">-", ">", "|", "|-") else value.strip("'\"")
        elif current and line.startswith((" ", "\t")):
            keys[current] = (keys[current] + " " + line.strip()).strip()
        elif line.strip():
            error(path, f"frontmatter を読めない行: {line!r}")
    return keys


def check_fences(path):
    count = sum(1 for line in path.read_text(encoding="utf-8").splitlines() if FENCE_PATTERN.match(line))
    if count % 2:
        error(path, f"コードブロック（```）が閉じていない（{count} 個）")


def check_skill(skill_dir):
    skill_md = skill_dir / "SKILL.md"
    if not skill_md.is_file():
        error(skill_dir, "SKILL.md が無い")
        return
    text = skill_md.read_text(encoding="utf-8")
    keys = read_frontmatter(skill_md, text)
    for key in sorted(set(keys) - ALLOWED_KEYS):
        error(skill_md, f"frontmatter に Bob が文書化していない項目がある: {key}")
    if keys.get("name") != skill_dir.name:
        error(skill_md, f"name（{keys.get('name')!r}）がフォルダ名（{skill_dir.name!r}）と違う")
    if not keys.get("description"):
        error(skill_md, "description が空（Bob は description の無い Skill を無視する）")

    referenced = set(REF_PATTERN.findall(text))
    for ref in sorted(referenced):
        if not (skill_dir / ref).is_file():
            error(skill_md, f"指しているファイルが無い: {ref}")
    for sub in ("reference", "scripts"):
        for f in sorted((skill_dir / sub).rglob("*")):
            if f.is_file() and f.relative_to(skill_dir).as_posix() not in referenced:
                error(f, "SKILL.md のどこからも指されていない")


def main():
    skills_dir = ROOT / "skills"
    skill_dirs = sorted(d for d in skills_dir.iterdir() if d.is_dir())
    if not skill_dirs:
        error(skills_dir, "Skill が 1 つも無い")
    for d in skill_dirs:
        check_skill(d)

    md_files = [ROOT / "README.md"]
    for base in ("skills", "shared", "tests/manual"):
        md_files += sorted((ROOT / base).rglob("*.md"))
    for f in md_files:
        if f.is_file():
            check_fences(f)

    if errors:
        print("\n".join(errors))
        print(f"\n{len(errors)} problem(s) found")
        return 1
    print(f"{len(skill_dirs)} skills ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
