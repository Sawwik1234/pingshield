# PingShield × LuckPerms — справочник прав

Версия PingShield 1.5.0+ · LuckPerms API 5.5 (soft-depend)

## 1. Дерево прав

| Право | Что даёт | По умолчанию |
|---|---|---|
| `pingshield.admin` | **зонтик**: все команды + уведомления (14 детей) | op |
| `pingshield.command.reload` | `/pingshield reload` | op |
| `pingshield.command.status` | `/pingshield status` | op |
| `pingshield.command.net` | `/pingshield net` — состояние сети целиком (медиана, общий скачок) | op |
| `pingshield.command.check` | `/pingshield check <игрок>` | op |
| `pingshield.command.protect` | `/pingshield protect <игрок>` | op |
| `pingshield.command.unprotect` | `/pingshield unprotect <игрок>` | op |
| `pingshield.command.info` | `/pingshield info` | op |
| `pingshield.command.profile` | `/pingshield profile <игрок>` | op |
| `pingshield.command.perf` | `/pingshield perf` | op |
| `pingshield.command.cp` | `/pingshield cp <игрок>` (CoreProtect) | op |
| `pingshield.command.search` | `/pingshield search` (кнопки поиска) | op |
| `pingshield.command.perms` | `/pingshield perms <игрок>` — откуда взялся порог | op |
| `pingshield.command.via` | `/pingshield via` — сводка по Via | op |
| `pingshield.bypass` | игрок **никогда** не попадает под защиту | нет |
| `pingshield.notify` | уведомления о входах других игроков в защиту | op |
| `pingshield.notify.self` | дублировать игроку личное уведомление | ✅ все |
| `pingshield.coreprotect` | доступ к интеграции CoreProtect | op |
| `pingshield.threshold.<мс>` | **персональный порог**: `pingshield.threshold.4000` | нет |
| meta `pingshield-threshold` | персональный порог числом (приоритетнее права) | нет |
| meta `pingshield-freeze-mode` | `passive` / `teleport` / `fly` / `immunity_only` | нет |

## 2. Порядок приоритетов порога

```
meta pingshield-threshold  →  право pingshield.threshold.<мс>  →  thresholds.overrides (конфиг)  →  общий порог
```

Если прав-порогов несколько (например, от двух групп), берётся **самый строгий (наименьший)** —
случайно ослабить защиту нельзя, только осознанно.

## 3. Готовые команды LuckPerms

```bash
# --- персонал ---
/lp group staff permission set pingshield.admin true
# или точечно, если нельзя перезагружать конфиг:
/lp group moderator permission set pingshield.command.status true
/lp group moderator permission set pingshield.command.net true
/lp group moderator permission set pingshield.command.check true
/lp group moderator permission set pingshield.command.profile true
/lp group moderator permission set pingshield.notify true

# --- игроки с мобильным интернетом: поднимаем порог, чтобы их не морозило на обычных скачках ---
/lp group mobile permission set pingshield.threshold.4500 true

# --- конкретный игрок: порог числом через meta ---
/lp user Steve meta set pingshield-threshold 4000

# --- донат с очень плохим каналом: щит без заморозки (играть можно, урон отключён) ---
/lp user Steve meta set pingshield-freeze-mode immunity_only

# --- арена: там защита мешает, даём обход только в мире арены (контекст LuckPerms) ---
/lp user Steve permission set pingshield.bypass true world=arena

# --- на втором сервере сети порог другой (контекст server=) ---
/lp group mobile permission set pingshield.threshold.4000 true server=survival

# --- проверить, что реально действует ---
/permparents Steve pingshield.threshold.4500
/pingshield perms Steve        # покажет итоговый порог, режим и источник значения
```

## 4. Почему порог сделан правом с числом, а не настройкой в конфиге

Права LuckPerms считаются **с контекстами** (`server`, `world`, `dimension`, собственные контексты
плагинов). Поэтому один и тот же игрок может иметь разные пороги в разных мирах и на разных серверах
сети — без правки `config.yml` и без перезапуска. Плюс изменение прав применяется **мгновенно**:
PingShield подписан на `UserDataRecalculateEvent` и сбрасывает кэш, не дожидаясь опроса.

## 5. Если LuckPerms не установлен

Всё продолжает работать на правах Bukkit (`player.hasPermission(...)` — их выдают любые
permission-плагины) и на `thresholds.overrides` из конфига. Никаких ошибок в логе:
обращения к API LuckPerms изолированы в отдельном классе, который не загружается без плагина.
