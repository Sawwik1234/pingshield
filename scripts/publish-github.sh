#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Публикация PingShield в новый репозиторий GitHub.
#
#  Использование (из корня проекта):
#     GITHUB_TOKEN=<токен> ./scripts/publish-github.sh                 # public
#     GITHUB_TOKEN=<токен> REPO_NAME=PingShield VISIBILITY=private ./scripts/publish-github.sh
#     ./scripts/publish-github.sh --check                              # только проверка, без сети
#
#  Токену нужны права (fine-grained PAT):
#     Administration: Read and write   — создать репозиторий
#     Contents:       Read and write   — запушить код и тег
#
#  Скрипт идемпотентен: если репозиторий уже существует, создавать не будет,
#  просто подтолкнёт в него main и теги. Токен НЕ сохраняется в .git/config:
#  он передаётся только на время одной команды push.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_NAME="${REPO_NAME:-PingShield}"
VISIBILITY="${VISIBILITY:-public}"
DESCRIPTION="${DESCRIPTION:-PingShield — защита игроков с очень высоким пингом (заморозка + иммунитет). Paper/Purpur/Folia.}"
TOKEN="${GITHUB_TOKEN:-}"

cd "$(dirname "$0")/.."

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
info()  { printf '\033[36m%s\033[0m\n' "$*"; }
die()   { red "Ошибка: $*"; exit 1; }

# ------------------------------------------------------------------ проверки
check() {
    command -v git >/dev/null || die "git не найден"
    command -v curl >/dev/null || die "curl не найден"
    git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "это не git-репозиторий (нужен git init)"
    [ -f build.gradle.kts ] || die "запускать из корня проекта (нет build.gradle.kts)"
    [ -f gradlew ] || die "нет gradlew"
    [ -x gradlew ] || chmod +x gradlew

    local branch
    branch="$(git rev-parse --abbrev-ref HEAD)"
    [ "$branch" = "main" ] || info "внимание: текущая ветка '$branch', а пушим main"

    if [ -n "$(git status --porcelain)" ]; then
        info "в рабочем дереве есть незакоммиченные изменения — коммитим их перед публикацией"
        git add -A
        git commit -q -m "docs: подготовка к публикации репозитория"
    fi

    [ "$VISIBILITY" = "public" ] || [ "$VISIBILITY" = "private" ] || die "VISIBILITY должен быть public или private"
    case "$REPO_NAME" in
        *[!A-Za-z0-9._-]*) die "недопустимое имя репозитория: $REPO_NAME" ;;
    esac
    green "Готово: ветка main, $(git rev-list --count HEAD) коммит(ов), тегов: $(git tag | wc -l | tr -d ' ')"
}

if [ "${1:-}" = "--check" ]; then
    check
    info "Проверка пройдена. Для публикации: GITHUB_TOKEN=<токен> $0"
    exit 0
fi

check
[ -n "$TOKEN" ] || die "не задан GITHUB_TOKEN (см. комментарий в начале скрипта)"

API="https://api.github.com"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")

# ------------------------------------------------------------------ кто мы
USER_JSON="$(curl -fsS "${AUTH[@]}" "$API/user")" || die "токен отклонён GitHub (проверьте права: Contents + Administration = Read and write)"
OWNER="$(printf '%s' "$USER_JSON" | sed -n 's/.*"login"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
[ -n "$OWNER" ] || die "не удалось определить логин владельца токена"
info "Аккаунт: $OWNER"

# ------------------------------------------------------------------ создание репозитория
STATUS="$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" "$API/repos/$OWNER/$REPO_NAME")"
if [ "$STATUS" = "200" ]; then
    info "Репозиторий $OWNER/$REPO_NAME уже существует — создавать не буду"
elif [ "$STATUS" = "404" ]; then
    info "Создаю репозиторий $OWNER/$REPO_NAME ($VISIBILITY)"
    PAYLOAD="$(printf '{"name":"%s","description":"%s","private":%s,"has_issues":true,"has_wiki":false,"has_projects":false,"auto_init":false}' \
        "$REPO_NAME" "$DESCRIPTION" "$([ "$VISIBILITY" = "private" ] && echo true || echo false)")"
    curl -fsS -X POST "${AUTH[@]}" "$API/user/repos" -d "$PAYLOAD" >/dev/null \
        || die "GitHub отказал в создании (проверьте право Administration: Read and write)"
    green "Репозиторий создан: https://github.com/$OWNER/$REPO_NAME"
else
    die "неожиданный ответ API при проверке репозитория: HTTP $STATUS"
fi

# ------------------------------------------------------------------ бейдж CI в README
BADGE="[![Build](https://github.com/$OWNER/$REPO_NAME/actions/workflows/build.yml/badge.svg)](https://github.com/$OWNER/$REPO_NAME/actions/workflows/build.yml)"
if ! grep -q "actions/workflows/build.yml/badge.svg" README.md; then
    python3 - "$REPO_NAME" <<'PY' || true
import pathlib, sys
p = pathlib.Path("README.md")
lines = p.read_text(encoding="utf-8").split("\n")
# вставляем бейдж после заголовка первого уровня
for i, line in enumerate(lines):
    if line.startswith("# "):
        lines.insert(i + 1, "")
        lines.insert(i + 2, "@@BADGE@@")
        break
text = "\n".join(lines).replace("@@BADGE@@", "{{BADGE}}")
p.write_text(text, encoding="utf-8")
PY
    # подстановка через sed, чтобы не экранировать ссылку в heredoc
    python3 - "$BADGE" <<'PY'
import pathlib, sys
p = pathlib.Path("README.md")
p.write_text(p.read_text(encoding="utf-8").replace("{{BADGE}}", sys.argv[1]), encoding="utf-8")
PY
    git add README.md
    git commit -q -m "docs: бейдж CI в README"
    green "Бейдж CI добавлен в README"
fi

# ------------------------------------------------------------------ remote + push
if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "https://github.com/$OWNER/$REPO_NAME.git"
else
    git remote add origin "https://github.com/$OWNER/$REPO_NAME.git"
fi
info "origin → https://github.com/$OWNER/$REPO_NAME.git"

# Токен уходит одним http-заголовком только на время push — в .git/config он не попадает
BASIC="$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')"
info "Пуш main..."
git -c credential.helper= -c "http.extraHeader=AUTHORIZATION: basic $BASIC" \
     push -u origin main
info "Пуш тегов (на тег v* workflow приклеит JAR к релизу)..."
git -c credential.helper= -c "http.extraHeader=AUTHORIZATION: basic $BASIC" \
     push --tags

green "Готово!"
echo
echo "  Репозиторий: https://github.com/$OWNER/$REPO_NAME"
echo "  Actions:     https://github.com/$OWNER/$REPO_NAME/actions"
echo "  Релиз:       https://github.com/$OWNER/$REPO_NAME/releases/tag/v1.5.0"
echo
echo "Напоминание: если репозиторий создавался в браузере — remote уже настроен,"
echo "дальше достаточно обычного 'git push' из этой папки."
