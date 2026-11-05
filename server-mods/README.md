# Только для сервера

Не кладите эти jar в клиентскую папку mods.

- `colonybuy-1.0.3.jar` — покупка зависших запросов колонии (стройматериалы, Domum, инструменты) из казны или своего кошелька: `/colonybuy pay <id|all> [treasury|wallet]`. С colonyboard 1.6 напоминания и итоги приходят стеклянными уведомлениями (кнопки «Подтвердить» / «Открыть запрос»), без спама в чат.
- `colonybuildspeed-1.1.0.jar` — только сервер (в клиентский `mods/` не класть; рядом лежит `.sha256`). Строители MineColonies ставят до 4 простых блоков за рабочий цикл. Снос (`includeDemolish`, по умолчанию включён) снимает до 4 обычных твёрдых блоков CLEAR/REMOVE за тот же цикл: дроп, инструмент и опыт как у родного `mineBlock`. Сундуки, двери, кровати, декор, жидкости, сущности, коренная порода и чужая колония не ускоряются. Значения по умолчанию в jar: `enabled`, `blocksPerCycle=4`, `reach=5.0` (потолок 10), `buildersOnly`, `includeDecorate` выключен, `includeDemolish` включён. Уже записанный `world/serverconfig/colonybuildspeed-server.toml` этот jar не переписывает: если на сервере reach уже 10, так и остаётся. Вместе с ним `minecolonies-server.toml`: `pathfindingmaxthreadcount = 4`.
