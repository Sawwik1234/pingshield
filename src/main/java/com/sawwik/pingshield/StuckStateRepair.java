package com.sawwik.pingshield;

import java.util.function.Consumer;

/**
 * Восстановление состояния игрока по «расписке» о заморозке (см. {@link StuckStateMarker}),
 * вынесенное в чистую логику: класс не знает ни про Bukkit, ни про PersistentDataContainer,
 * поэтому его проверяют unit-тесты без сервера.
 *
 * <h2>Зачем отдельный класс, а не три строки в менеджере</h2>
 * <p>Порядок действий здесь критичен. Расписка — <b>единственная</b> копия того, каким игрок был
 * до заморозки. Если применить её и сразу удалить, а применение упало посередине (например, чужой
 * плагин бросил исключение в обработчике события), то состояние испорчено, а восстанавливать уже
 * нечем: маркера нет. Поэтому решение «удалять или оставить маркер» принимается по результату
 * применения, а не до него, и это правило живёт в тестируемом коде.</p>
 *
 * <p>Правило простое:</p>
 * <ul>
 *   <li>{@link Outcome#NO_MARKER} — расписки не было, делать нечего;</li>
 *   <li>{@link Outcome#BROKEN} — расписка битая/чужой версии: <b>удаляем</b> (она бесполезна),
 *       но состояние не трогаем и предупреждаем админа;</li>
 *   <li>{@link Outcome#APPLIED} — состояние применено: только теперь <b>удаляем</b> маркер;</li>
 *   <li>{@link Outcome#FAILED} — применение упало: маркер <b>оставляем</b>, чтобы повторить
 *       при следующем входе (или повторной попытке).</li>
 * </ul>
 */
public final class StuckStateRepair {

    /** Чем закончилась попытка восстановления. */
    public enum Outcome {
        /** Маркера нет — игрок вышел штатно, ничего не делаем. */
        NO_MARKER,
        /** Маркер есть, но не разбирается (битый, обрезанный, чужой версии). */
        BROKEN,
        /** Состояние успешно применено. */
        APPLIED,
        /** Применение упало: единственная копия состояния сохранена в маркере. */
        FAILED
    }

    /**
     * Результат попытки.
     *
     * @param outcome чем закончилось
     * @param marker  разобранный маркер (или {@code null}, если разобрать не удалось)
     * @param error   исключение, если применение упало (иначе {@code null})
     */
    public record Result(Outcome outcome, StuckStateMarker marker, Throwable error) {

        /** Можно ли удалять маркер: только когда он бесполезен или уже применён. */
        public boolean mayClearMarker() {
            return outcome == Outcome.BROKEN || outcome == Outcome.APPLIED;
        }

        /** Обязаны ли сохранить маркер: применение не удалось — состояние ещё нужно вернуть. */
        public boolean mustKeepMarker() {
            return outcome == Outcome.FAILED;
        }
    }

    private StuckStateRepair() {
    }

    /**
     * Разбирает расписку и пытается применить её через {@code applier}.
     *
     * <p>Исключение применения не пробрасывается наружу: оно возвращается в {@link Result#error()},
     * чтобы вызывающий код мог оставить маркер и повторить попытку позже, а не потерять состояние.
     * Ловим {@link Throwable}, а не {@code Exception}: на входе игрока может упасть и чужой
     * плагин, бросивший ошибку из обработчика события.</p>
     */
    public static Result run(String raw, Consumer<StuckStateMarker> applier) {
        if (raw == null || raw.isBlank()) {
            return new Result(Outcome.NO_MARKER, null, null);
        }
        StuckStateMarker marker = StuckStateMarker.decode(raw);
        if (marker == null) {
            return new Result(Outcome.BROKEN, null, null);
        }
        try {
            applier.accept(marker);
        } catch (Throwable throwable) {
            return new Result(Outcome.FAILED, marker, throwable);
        }
        return new Result(Outcome.APPLIED, marker, null);
    }
}
