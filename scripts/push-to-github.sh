#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Пуш уже готового репозитория PingShield в репозиторий, созданный в браузере.
#
#  Использование (из корня проекта, где лежит папка .git):
#      ./scripts/push-to-github.sh <логин-github> [имя-репозитория]
#  Например:
#      ./scripts/push-to-github.sh Sawwik pingshield
#
#  Что делает: настраивает origin, отправляет ветку main и теги.
#  На тег v1.5.0 workflow в Actions автоматически приклеит собранный JAR к релизу.
#
#  Под Windows запускать в Git Bash (идёт с Git for Windows).
#  Пароль: обычный пароль GitHub не подойдёт — Git Credential Manager
#  предложит вход в браузере, либо используйте Personal Access Token как пароль.
# ---------------------------------------------------------------------------
set -euo pipefail

LOGIN="${1:-}"
NAME="${2:-pingshield}"

if [ -z "$LOGIN" ]; then
    echo "Использование: $0 <логин-github> [имя-репозитория]" >&2
    echo "Пример:        $0 Sawwik pingshield" >&2
    exit 1
fi

cd "$(dirname "$0")/.."
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
    echo "Ошибка: это не git-репозиторий. Распакуйте архив целиком (вместе со скрытой папкой .git)." >&2
    exit 1
}

URL="https://github.com/$LOGIN/$NAME.git"
if git remote get-url origin >/dev/null 2>&1; then
    git remote set-url origin "$URL"
else
    git remote add origin "$URL"
fi

echo "origin → $URL"
echo "Ветка:  $(git rev-parse --abbrev-ref HEAD), коммитов: $(git rev-list --count HEAD), тегов: $(git tag | wc -l | tr -d ' ')"

git push -u origin main
git push --tags

cat <<INFO

Готово. Дальше:
  Репозиторий:   https://github.com/$LOGIN/$NAME
  Запуски CI:    https://github.com/$LOGIN/$NAME/actions
  Релиз с JAR:   https://github.com/$LOGIN/$NAME/releases

Последующие изменения публикуются обычными командами:
  git add -A && git commit -m "что изменилось" && git push
Иногда удобно двигать тег версии:
  git tag -a v1.5.1 -m "PingShield 1.5.1" && git push --tags
INFO
