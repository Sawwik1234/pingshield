#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Публикация текущей версии PingShield: пуш main + тега v$VERSION,
#  затем ожидание CI на теге и сверка JAR из GitHub Release с локальной сборкой.
#
#  Запуск (из корня проекта):
#     GITHUB_TOKEN=<токен> ./scripts/publish-and-verify.sh
#
#  Скрипт ничего не коммитит: рабочее дерево должно быть чистым, коммит
#  и тег текущей версии уже созданы. Токен передаётся только заголовком на время push.
# ---------------------------------------------------------------------------
set -euo pipefail

VERSION="${VERSION:-1.6.10}"
REPO="${REPO:-Sawwik1234/pingshield}"
EXPECT_BYTES="${EXPECT_BYTES:-156712}"
EXPECT_SHA="${EXPECT_SHA:-49ee0f44e201fe6027218262e4b9a07a3e1f89fbdc161f73fe7c426c4ba48550}"
TOKEN="${GITHUB_TOKEN:-}"

cd "$(dirname "$0")/.."

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
info()  { printf '\033[36m%s\033[0m\n' "$*"; }
die()   { red "Ошибка: $*"; exit 1; }

[ -n "$TOKEN" ] || die "не задан GITHUB_TOKEN"

# Среда может сбрасывать биты +x у файлов — лечим и просим git их не считать
# изменением (в индексе режимы уже зафиксированы как 755).
chmod +x gradlew scripts/*.sh 2>/dev/null || true
git config core.fileMode false
[ -z "$(git status --porcelain)" ] || die "в рабочем дереве есть незакоммиченные изменения — сначала коммит"
git rev-parse -q --verify "refs/tags/v$VERSION" >/dev/null || die "нет тега v$VERSION"
git rev-parse --verify HEAD >/dev/null || die "это не git-репозиторий"

git remote get-url origin >/dev/null 2>&1 \
    && git remote set-url origin "https://github.com/$REPO.git" \
    || git remote add origin "https://github.com/$REPO.git"

BASIC="$(printf 'x-access-token:%s' "$TOKEN" | base64 | tr -d '\n')"
push_git() {
    git -c credential.helper= -c "http.extraHeader=AUTHORIZATION: basic $BASIC" "$@"
}

# ------------------------------------------------------------------ кто мы
API="https://api.github.com"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28")
LOGIN="$(curl -fsS "${AUTH[@]}" "$API/user" | sed -n 's/.*"login"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)" \
    || die "токен отклонён GitHub"
[ -n "$LOGIN" ] || die "не удалось определить аккаунт по токену"
info "Аккаунт: $LOGIN · репозиторий: $REPO"

# ------------------------------------------------------------------ push
info "Пуш main..."
push_git push -u origin main
info "Пуш тегов (на тег v* CI соберёт JAR и приклеит его к релизу)..."
push_git push --tags
green "Код отправлен."

# ------------------------------------------------------------------ ждём CI на теге
# Разбор ответов GitHub — через python3 (json), а не sed: строка с ошибкой внутри
# set -e не должна убивать скрипт, если запуск ещё не появился.
api_get() { curl -fsS "${AUTH[@]}" "$1" 2>/dev/null || true; }

info "Ищу запуск CI для тега v$VERSION..."
DEADLINE=$(( $(date +%s) + 1500 ))
RUN_ID=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    RUN_ID="$(api_get "$API/repos/$REPO/actions/runs?event=push&per_page=40" | python3 -c '
import sys, json
want = "v" + sys.argv[1]
try:
    runs = json.load(sys.stdin).get("workflow_runs", [])
except Exception:
    runs = []
for run in runs:
    if run.get("head_branch") == want:
        print(run["id"]); break
' "$VERSION")"
    [ -n "$RUN_ID" ] && break
    sleep 15
done
[ -n "$RUN_ID" ] || die "запуск CI для тега v$VERSION не появился за 25 минут"

info "Запуск #$RUN_ID: жду завершения (сборка + тесты + smoke на настоящей Folia)…"
STATUS=""; CONCLUSION=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    read -r STATUS CONCLUSION <<<"$(api_get "$API/repos/$REPO/actions/runs/$RUN_ID" | python3 -c '
import sys, json
try:
    run = json.load(sys.stdin)
except Exception:
    run = {}
print(run.get("status") or "-", run.get("conclusion") or "-")
')"
    [ "$STATUS" = "completed" ] && break
    sleep 20
done
URL="https://github.com/$REPO/actions/runs/$RUN_ID"
[ "$STATUS" = "completed" ] || die "CI не завершился за 25 минут — $URL"
[ "$CONCLUSION" = "success" ] || die "CI завершился со статусом $CONCLUSION — $URL"
green "CI успешен: $URL"

# ------------------------------------------------------------------ проверка релиза
info "Проверяю релиз v$VERSION…"
ASSET_URL="$(api_get "$API/repos/$REPO/releases/tags/v$VERSION" | python3 -c '
import sys, json
want = "PingShield-" + sys.argv[1] + ".jar"
try:
    assets = json.load(sys.stdin).get("assets", [])
except Exception:
    assets = []
for asset in assets:
    if asset.get("name") == want:
        print(asset["browser_download_url"]); break
' "$VERSION")"
[ -n "$ASSET_URL" ] || die "релиз v$VERSION не появился или в нём нет файла PingShield-$VERSION.jar"
info "Релиз найден, скачиваю JAR и сверяю с собранным локально…"
TMP="$(mktemp -d)"
curl -fsSL -H "Authorization: Bearer $TOKEN" "$ASSET_URL" -o "$TMP/release.jar"
GOT_BYTES="$(stat -c%s "$TMP/release.jar")"
GOT_SHA="$(sha256sum "$TMP/release.jar" | cut -d' ' -f1)"

echo
green "════════════════ ПУБЛИКАЦИЯ $VERSION ЗАВЕРШЕНА ════════════════"
echo "  Коммит:   $(git rev-parse --short HEAD)   тег: v$VERSION"
echo "  Релиз:    https://github.com/$REPO/releases/tag/v$VERSION"
echo "  JAR:      $GOT_BYTES байт (ожидалось $EXPECT_BYTES)"
echo "  sha256:   $GOT_SHA"
echo "  ожидался: $EXPECT_SHA"
if [ "$GOT_BYTES" = "$EXPECT_BYTES" ] && [ "$GOT_SHA" = "$EXPECT_SHA" ]; then
    green "  ✔ файл в релизе совпадает с собранным локально (байт-в-байт)"
else
    red "  ✘ файл в релизе отличается от локального — проверьте артефакт вручную"
fi
rm -rf "$TMP"
