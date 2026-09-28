#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Дымовой тест PingShield на НАСТОЯЩЕМ сервере.
#
#  Что делает:
#    1. скачивает нужный билд Folia (по умолчанию — 26.1.2, как у вас);
#    2. кладёт собранный JAR плагина в plugins/;
#    3. запускает сервер в отдельной папке (ваш рабочий сервер не трогается);
#    4. через консоль выполняет /pingshield info | net | perf | status | reload;
#    5. проверяет лог: плагин включился, «Invalid plugin.yml» нет, ошибок от плагина нет;
#    6. печатает PASS/FAIL и сохраняет полный лог.
#
#  Использование (нужен Java 25 и ~1 ГБ свободной памяти):
#      ./scripts/smoke-test.sh                     # собрать и проверить
#      ./scripts/smoke-test.sh --skip-build        # проверить уже собранный JAR
#      ./scripts/smoke-test.sh --version 26.1.2    # версия Folia
#      ./scripts/smoke-test.sh --server-jar /путь/folia.jar   # свой JAR сервера
#      ./scripts/smoke-test.sh --keep              # не удалять папку сервера
#
#  Скрипт не ставит никаких «свежих» версий вслепую: версия сервера задаётся
#  явно и сверяется по API PaperMC, чтобы тест шёл на той же базе, что и прод.
# ---------------------------------------------------------------------------
set -euo pipefail

VERSION="26.1.2"
SKIP_BUILD=0
KEEP=0
SERVER_JAR=""
WORK_DIR="${SMOKE_DIR:-/tmp/pingshield-smoke}"
BOOT_WAIT="${SMOKE_BOOT_WAIT:-80}"      # сколько ждать запуска сервера, сек
XMX="${SMOKE_XMX:-900M}"                 # память сервера: на слабой машине можно SMOKE_XMX=512M
JAVA_OPTS="${SMOKE_JAVA_OPTS:--XX:+UseSerialGC}"   # дополнительные флаги JVM (для слабых машин/контейнеров)
CMD_WAIT=4                               # пауза между командами, сек

while [ $# -gt 0 ]; do
    case "$1" in
        --version)     VERSION="$2"; shift 2 ;;
        --server-jar)  SERVER_JAR="$2"; shift 2 ;;
        --skip-build)  SKIP_BUILD=1; shift ;;
        --keep)        KEEP=1; shift ;;
        --reuse)       REUSE=1; shift ;;   # не пересоздавать папку сервера (мир и libraries уже на месте)
        *) echo "неизвестный аргумент: $1" >&2; exit 2 ;;
    esac
done

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
PLUGIN_JAR="$(ls -1 "$ROOT"/build/libs/PingShield-*.jar 2>/dev/null | head -1 || true)"

say()  { printf '\033[36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[32m%s\033[0m\n' "$*"; }
bad()  { printf '\033[31m%s\033[0m\n' "$*"; }

command -v java >/dev/null || { bad "не найден java (нужен JDK 25: Minecraft 26.x)"; exit 1; }
command -v curl >/dev/null || { bad "не найден curl"; exit 1; }

# --- 1. сборка ---------------------------------------------------------------
if [ "$SKIP_BUILD" = "0" ]; then
    say "Сборка плагина (./gradlew build)…"
    ./gradlew build --no-daemon --console=plain -q
fi
[ -n "$PLUGIN_JAR" ] && [ -f "$PLUGIN_JAR" ] || { bad "не найден build/libs/PingShield-*.jar — соберите проект"; exit 1; }
ok "Плагин: $PLUGIN_JAR"

# --- 2. сервер ---------------------------------------------------------------
mkdir -p "$WORK_DIR"
if [ -z "$SERVER_JAR" ]; then
    SERVER_JAR="$WORK_DIR/folia-$VERSION.jar"
    if [ ! -f "$SERVER_JAR" ]; then
        say "Ищу сборку Folia $VERSION в API PaperMC (fill.papermc.io)…"
        URL="$(curl -fsS --max-time 60 \
            "https://fill.papermc.io/v3/projects/folia/versions/$VERSION/builds" \
            -H "User-Agent: PingShield-smoke-test/1.0" \
            | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["downloads"]["server:default"]["url"])')"
        [ -n "$URL" ] || { bad "не удалось получить ссылку на сборку Folia $VERSION"; exit 1; }
        say "Скачиваю: $(basename "$URL")"
        curl -fsSL --max-time 600 -o "$SERVER_JAR" "$URL"
    fi
fi
SERVER_JAR="$(cd "$(dirname "$SERVER_JAR")" && pwd)/$(basename "$SERVER_JAR")"
ok "Сервер: $(basename "$SERVER_JAR") ($(du -h "$SERVER_JAR" | cut -f1))"

# --- 3. песочница сервера ----------------------------------------------------
if [ "$REUSE" = "1" ] && [ -d "$WORK_DIR/run" ]; then
    say "Переиспользую папку сервера (мир и libraries на месте) — быстрее и легче по памяти"
    rm -f "$WORK_DIR/run/plugins/"*.jar
else
    rm -rf "$WORK_DIR/run"
fi
mkdir -p "$WORK_DIR/run/plugins"
cp "$PLUGIN_JAR" "$WORK_DIR/run/plugins/"
printf 'eula=true\n' > "$WORK_DIR/run/eula.txt"
cat > "$WORK_DIR/run/server.properties" <<'PROPS'
online-mode=false
level-type=flat
spawn-protection=0
max-players=2
view-distance=3
simulation-distance=3
motd=PingShield smoke test
PROPS
if ! ls "$WORK_DIR/run/plugins/"PingShield-*.jar >/dev/null 2>&1; then
    bad "JAR плагина не попал в $WORK_DIR/run/plugins — проверить копирование"
    exit 1
fi
LOG="$WORK_DIR/console.log"

# --- 4. запуск и команды -----------------------------------------------------
say "Запускаю сервер, жду ${BOOT_WAIT} с, затем выполняю команды…"
set +e
timeout $((BOOT_WAIT + 120)) bash -c "cd '$WORK_DIR/run' && {
    sleep $BOOT_WAIT
    echo 'pingshield info';        sleep $CMD_WAIT
    echo 'pingshield net';         sleep $CMD_WAIT
    echo 'pingshield perf';        sleep $CMD_WAIT
    echo 'pingshield status';      sleep $CMD_WAIT
    echo 'pingshield reload';      sleep $CMD_WAIT
    echo 'stop'
} | java -Xmx$XMX $JAVA_OPTS -jar '$SERVER_JAR' --nogui" > "$LOG" 2>&1
RC=$?
set -e

# --- 5. вердикт --------------------------------------------------------------
FAILED=0
check() { # check <описание> <шаблон>
    if grep -aqE "$2" "$LOG"; then ok "  ✔ $1"; else bad "  ✖ $1"; FAILED=1; fi
}
check_absent() {
    if grep -aqE "$2" "$LOG"; then bad "  ✖ $1"; FAILED=1; else ok "  ✔ $1"; fi
}

PLUGIN_VER="$(basename "$PLUGIN_JAR" | sed -n 's/PingShield-\(.*\)\.jar/\1/p')"
say "Проверяю лог: $LOG (ожидаю версию $PLUGIN_VER)"
check "в логе загружена версия $PLUGIN_VER"         "Loading server plugin PingShield v$PLUGIN_VER"
check "плагин включился (Enabling PingShield)"      "Enabling PingShield"
check "плагин отключился штатно (Disabling)"        "Disabling PingShield"
# проверяем ASCII-часть сообщения: в консоли кириллица может отображаться каракулями
check "Folia распознана (режим регионов)"           "EntityScheduler \\(Folia-safe\\)"
check_absent "нет Invalid plugin.yml"               "Invalid plugin.yml"
check_absent "нет ошибок нашего плагина"            "\\[PingShield\\].*(Exception|ERROR)"

# вывод команд (в консоли может быть каракули вместо кириллицы — ищем ASCII-части)
check "команда /pingshield info ответила"           "PingShield v[0-9]"
check "команда /pingshield perf ответила"           "PingShield perf"
check "команда /pingshield net ответила"            "p90"

if [ "$RC" -ge 128 ] && [ "$RC" -ne 124 ]; then
    bad "  ✖ сервер убит сигналом (код $RC) — вероятно, мало памяти"
    FAILED=1
fi

echo
if [ "$FAILED" = "0" ]; then
    ok "PASS: плагин загружается и работает на Folia $VERSION"
    say "Полный лог: $LOG"
else
    bad "FAIL: смотрите лог $LOG"
fi

if [ "$KEEP" = "0" ]; then
    # Папку run оставляем: повторный прогон с --reuse не будет качать и генерировать мир заново
    say "Папка сервера сохранена для повторного прогона: $WORK_DIR/run"
else
    say "Папка сервера оставлена: $WORK_DIR/run"
fi
exit "$FAILED"
