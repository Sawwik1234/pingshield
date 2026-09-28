#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Публикация PingShield 1.6.4 «одной командой»: пуш main + тега v1.6.4,
#  затем ожидание CI на теге и проверка, что в релиз приклеился нужный JAR.
#
#  Запуск (из корня проекта):
#     GITHUB_TOKEN=<токен> ./scripts/publish-and-verify.sh
#
#  Скрипт ничего не коммитит: рабочее дерево должно быть чистым, коммит 1.6.4
#  и тег v1.6.4 уже созданы. Токен передаётся только заголовком на время push.
# ---------------------------------------------------------------------------
set -euo pipefail

VERSION="${VERSION:-1.6.4}"
REPO="${REPO:-Sawwik1234/pingshield}"
EXPECT_BYTES="${EXPECT_BYTES:-154072}"
EXPECT_SHA="${EXPECT_SHA:-f8f663468364004ca5bc0d9894233cd6e5325a07b3e6c4c551f4fb452cd88164}"
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
info "Ищу запуск CI для тега v$VERSION..."
DEADLINE=$(( $(date +%s) + 1500 ))
RUN_ID=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    RUN_ID="$(curl -fsS "${AUTH[@]}" \
        "$API/repos/$REPO/actions/runs?event=push&per_page=30" \
        | tr ',' '\n' | grep -A1 '"head_branch":"v'"$VERSION"'"' | sed -n 's/.*"id":\([0-9]*\).*/\1/p' | head -1)"
    [ -n "$RUN_ID" ] && break
    sleep 15
done
[ -n "$RUN_ID" ] || die "запуск CI для тега v$VERSION не появился за 25 минут"

info "Запуск #$RUN_ID: жду завершения (сборка + smoke на настоящей Folia)…"
STATUS=""
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
    STATUS="$(curl -fsS "${AUTH[@]}" "$API/repos/$REPO/actions/runs/$RUN_ID" \
        | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p' | head -1)"
    [ "$STATUS" = "completed" ] && break
    sleep 20
done
[ "$STATUS" = "completed" ] || die "CI не завершился за 25 минут (смотреть: https://github.com/$REPO/actions/runs/$RUN_ID)"

CONCLUSION="$(curl -fsS "${AUTH[@]}" "$API/repos/$REPO/actions/runs/$RUN_ID" \
    | sed -n 's/.*"conclusion"[[:space:]]*:[[:space:]]*"\([a-z_]*\)".*/\1/p' | head -1)"
URL="https://github.com/$REPO/actions/runs/$RUN_ID"
[ "$CONCLUSION" = "success" ] || die "CI завершился со статусом $CONCLUSION — $URL"
green "CI успешен: $URL"

# ------------------------------------------------------------------ проверка релиза
info "Проверяю релиз v$VERSION…"
RELEASE="$(curl -fsS "${AUTH[@]}" "$API/repos/$REPO/releases/tags/v$VERSION")" || die "релиз v$VERSION не найден"
ASSET_URL="$(printf '%s' "$RELEASE" | tr ',' '\n' | sed -n 's/.*"browser_download_url":"\([^"]*PingShield-'"$VERSION"'\.jar\)".*/\1/p' | head -1)"
[ -n "$ASSET_URL" ] || die "в релизе нет файла PingShield-$VERSION.jar"
TMP="$(mktemp -d)"
curl -fsSL -H "Authorization: Bearer $TOKEN" "$ASSET_URL" -o "$TMP/release.jar"
GOT_BYTES="$(stat -c%s "$TMP/release.jar")"
GOT_SHA="$(sha256sum "$TMP/release.jar" | cut -d' ' -f1)"

echo
green "════════════════ ПУБЛИКАЦИЯ 1.6.4 ЗАВЕРШЕНА ════════════════"
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
