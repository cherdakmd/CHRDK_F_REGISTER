# Проверяемое превью клановых анимаций

Это визуализация RGB, **не скриншот запущенного Minecraft**, не проверка клиентских
шрифтов/пакетов и не MSPT smoke. Три колонки показывают моменты времени,
пять строк — альтернативные эффекты; одновременно в клане выбран только один.

`GuildAnimationPreview` экспортирует 48 кадров всех пяти вариантов из production
`GuildStyleAnimation.render`, не копирует формулы на Python. Fixture хранит SHA-256
исходника; JUnit сравнивает каждый кадр и хеш с текущим неизменённым Java-кодом.

Пересоздание fixture на Java 25: `./gradlew :guilds:exportClanAnimationPreview`
(для проблемного Maven-зеркала передать проверенный `-Pchrdk.papiJar=/path/to/pinned.jar`
как в `.github/scripts/gradle_build.py`). Из корня репозитория, после установки Pillow
из `tools/clan_shop/requirements.txt`:

```sh
python3 -m unittest discover -s tools/clan_preview -p 'test_*.py'
python3 tools/clan_preview/render.py --output build/clan-preview
```

PNG/GIF — производные файлы в ignored `build/`; не коммитить бинарные превью.
Шрифт визуализации DejaVu Sans Mono, не игровой шрифт Minecraft.
Локальный bootstrap для этой среды использовал ECJ с механической заменой arrow-switch
на эквивалентные if/else; CI обязательно сверяет fixture с нетрансформированным Java 25.
