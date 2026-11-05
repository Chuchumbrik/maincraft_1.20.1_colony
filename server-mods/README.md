# Только для сервера

Не кладите эти jar в клиентскую папку mods.

- `colonybuy-1.0.3.jar` — покупка зависших запросов колонии (стройматериалы, Domum, инструменты) из казны или своего кошелька: `/colonybuy pay <id|all> [treasury|wallet]`. С colonyboard 1.6 напоминания и итоги приходят стеклянными уведомлениями (кнопки «Подтвердить» / «Открыть запрос»), без спама в чат.
- `colonybuildspeed-1.0.0.jar` — строители MineColonies ставят до 4 простых блоков за рабочий цикл (в радиусе 5 блоков; сундуки/двери/кровати/декор/сущности — по-старому, по одному). Конфиг `world/serverconfig/colonybuildspeed-server.toml`: `enabled`, `blocksPerCycle=4`, `reach=5.0`, `buildersOnly`, `includeDecorate`. Вместе с ним на сервере `minecolonies-server.toml`: `pathfindingmaxthreadcount = 4`.
