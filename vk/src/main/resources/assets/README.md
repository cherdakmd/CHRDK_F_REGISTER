# Ресурсы постера дайджеста

Файлы упакованы в `chrdk-vk-*.jar` и читаются `WallDigestCard` из classpath.
Они нужны, чтобы постер на сервере выглядел одинаково независимо от того,
какие шрифты установлены в системе.

| Файл | Источник | Назначение |
| --- | --- | --- |
| `world-background.jpg` | копия `tools/social/assets/world-background.jpg` | фон 1280×720 для карточки сообщества |
| `DejaVuSans.ttf` | `/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf` | обычный текст блоков |
| `DejaVuSans-Bold.ttf` | `/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf` | логотип, бейдж, дата, подвал |

DejaVu Sans распространяется по свободной лицензии DejaVu Fonts
(производная Bitstream Vera Fonts Copyright); файлы переносятся как есть, без
изменений. При отсутствии любого из ресурсов `WallDigestCard` использует
логический `Font.SANS_SERIF` и плоский запасной фон — плагин не падает.

Python-контур `tools/social/` продолжает рисовать свои карточки через Pillow
из системных шрифтов; этот каталог — ресурсы именно Java-плагина, они не
заменяют и не удаляют исходные ассеты соцсетей.
