"""Cross-module storefront contract. Fail the build instead of shipping orphaned products."""
import re

import yaml


def read(root, module):
    return yaml.safe_load((root / module / 'src/main/resources/config.yml').read_text(encoding='utf-8'))


def leaves(product_id, by_id, path=()):
    if product_id in path or product_id not in by_id:
        raise ValueError('Unknown/cyclic bundle member: ' + product_id)
    p = by_id[product_id]
    result = set()
    if p.get('includes'):
        for child in p['includes']:
            result |= leaves(child, by_id, path + (product_id,))
        return result
    return {product_id}


def validate(root, cfg, products):
    def require(condition, message):
        if not condition:
            raise ValueError('TradeMC cross-module audit: ' + message)
    by_id = {p['id']: p for p in products}
    require(len(by_id) == 26, 'expected 26 distinct sale SKUs')
    require(cfg['shop']['enabled'] and cfg['shop']['max-total-days'] == 365, 'catalog enabled / 365-day horizon')
    for level in cfg['ranks']['levels']:
        monthly = by_id.get('rank-' + level['id'])
        yearly = by_id.get('rank-' + level['id'] + '-year')
        require(monthly is not None and yearly is not None, 'missing rank periods')
        require(monthly.get('rank') == yearly.get('rank') == level['level'], 'rank SKU/tier mismatch')
        require(monthly['days'] == 30 and yearly['days'] == 365, 'rank must have 30/365 days')
        require(yearly['price'] == monthly['price'] * 10, 'yearly price must equal ten monthly purchases')
    cosmetics = read(root, 'cosmetics')['cosmetics']
    require(cosmetics['shop']['enabled'] and cosmetics['trail']['enabled'], 'cosmetic delivery disabled')
    trails = [d for d in cosmetics['trail']['defs'] if d.get('product')]
    trail_ids = {d['product'] for d in trails}
    require(trail_ids == {'trail-flame', 'trail-hearts', 'trail-notes', 'trail-sparkles', 'trail-magic'}, 'five sold trails')
    require(len(trails) == len(trail_ids), 'each sold trail must map to one effect')
    for d in trails:
        require(d['product'] in by_id and by_id[d['product']]['days'] == 30, 'orphan trail/period')
    exclusives = [d for d in cosmetics['trail']['defs'] if not d.get('product')]
    require({d['id'] for d in exclusives} == {'gold', 'aurora'}, 'rank-exclusive trail list changed')
    require({d['rank'] for d in exclusives} == {4, 5}, 'exclusive trails must remain rank IV/V')
    effect = cosmetics['join-effect']
    require(effect['enabled'] and effect['product'] == 'join-effect' and effect['min-rank'] == 4, 'join-effect product/access mismatch')
    require(by_id['join-effect']['days'] == 30, 'join-effect duration')
    palette = cfg['labels']['colors']
    colors = [c for c in palette['list'] if c.get('product')]
    color_ids = {c['product'] for c in colors}
    require(palette['enabled'] and len(colors) == len(color_ids) == 5, 'five separate paid colors')
    require(color_ids == {'nick-aurora', 'nick-flame', 'nick-pearl', 'nick-lagoon', 'nick-crimson'}, 'paid color IDs')
    for c in colors:
        p = by_id[c['product']]
        require(p['days'] == 30, 'paid color duration')
        require(re.findall(r'#[0-9a-fA-F]{6}', p['name']) == re.findall(r'#[0-9a-fA-F]{6}', c['format']), 'color image/name differs from actual palette')
    bp = read(root, 'battlepass')['battlepass']['premium']
    require(bp['enabled'] and bp['product'] == 'battlepass-premium' and bp['min-rank'] == 4, 'premium pass access')
    require(by_id['battlepass-premium']['days'] == 90, 'pass is 90-day access, not season length')
    homes = read(root, 'teleports')['homes']
    require(homes['extra-limit'] == 8 and by_id['homes-plus']['days'] == 30, 'one home pack is +8 / 30 days')
    require(homes['rank-limits'] == [3, 4, 6, 8, 10, 12], 'rank home base differs from advertised examples')
    require(leaves('trails-pack', by_id) == trail_ids, 'trail bundle must contain exactly five sale trails')
    require(leaves('look-pack', by_id) == trail_ids | color_ids, 'look bundle must contain five trails + five colors only')
    for bundle_id in ('trails-pack', 'look-pack'):
        bundle = by_id[bundle_id]
        require(all(by_id[x]['days'] == bundle['days'] == 30 for x in leaves(bundle_id, by_id)), 'bundle/leaf periods mismatch')
        require(bundle['price'] < sum(by_id[x]['price'] for x in bundle['includes']), 'bundle has no discount against cheapest direct contents')
    require(sum(by_id[x]['price'] for x in trail_ids) == 295, 'trail savings text stale')
    require(sum(by_id[x]['price'] for x in color_ids) == 705, 'color savings text stale')
    require(by_id['trails-pack']['price'] == 199 and by_id['look-pack']['price'] == 599, 'bundle savings text stale')
    full = by_id['all-inclusive']
    require(full['price'] == 899 and full['days'] == 30 and full['rank'] == 5 and full['includes-days'] == 30,
            'all-inclusive must grant V and all extras for 899 / 30 days')
    require(leaves('all-inclusive', by_id) == trail_ids | color_ids | {'homes-plus', 'join-effect', 'battlepass-premium'},
            'all-inclusive contents mismatch')
    require(len(leaves('all-inclusive', by_id)) == 13, 'full pack needs 13 distinct extra rights')
    require(not any(by_id[x].get('rank') for x in leaves('all-inclusive', by_id)), 'rank must not be a bundle leaf')
    require(all(not p.get('commands') and not p.get('pass-days') for p in products), 'unexpected item/paid-pass console grants in catalog')
    commands = yaml.safe_load((root / 'src/main/resources/plugin.yml').read_text(encoding='utf-8'))['commands']
    require(all(c in commands for c in ('labels', 'profile', 'rank', 'hat', 'pass')), 'player/store command missing')
    return by_id


def report(root, cfg, products, version):
    validate(root, cfg, products)
    lines = [f'# Аудит всех товаров TradeMC — {version}\n\n',
             'Проверены стандартные конфиги, код доступа/выдачи и регрессионные тесты. '
             '**Это не проверка реальной оплаты или установленного сервера.**\n\n',
             f'## Сверка {len(products)} товаров\n\n',
             '| ID | Название | Цена | Дней | Выдача и повторная покупка |\n',
             '| --- | --- | ---: | ---: | --- |\n']
    for p in products:
        name = re.sub(r'<[^>]+>', '', p['name'])
        if p['id'] == 'all-inclusive':
            behavior = 'Покровитель V + 13 дополнений по 30 дней; одна продажа 899 ₽'
        elif p.get('rank'):
            behavior = f"Ранг {p['rank']}; продление/повышение с сохранением остатка, без понижения"
        elif p['id'] == 'homes-plus':
            behavior = '+8 за каждый пакет; независимые сроки; складывается с рангом'
        elif p['id'].startswith('trail-'):
            behavior = 'Один каталожный шлейф; выбрать в /cosmetics; повтор продлевает срок'
        elif p['id'].startswith('nick-'):
            behavior = 'Один платный цвет; /labels color; ранг V не заменяет покупку'
        elif p['id'] == 'join-effect':
            behavior = 'Автоматический эффект следующего входа; отдельного переключателя нет'
        elif p['id'] == 'battlepass-premium':
            behavior = '90 дней доступа к золотой ветке; IV–V уже открывают её'
        else:
            behavior = f"{len(leaves(p['id'], {x['id']: x for x in products}))} отдельных прав; срок каждой части продлевается отдельно"
        lines.append(f"| `{p['id']}` | {name} | {p['price']} ₽ | {p['days']} | {behavior} |\n")
    lines.append('''
## Добавлено в 1.4.198: «Всё включено»

899 ₽ / 30 дней: ранг V на корне + пакет +8 домов, пять каталожных шлейфов,
пять платных цветов, эффект входа и премиум-пропуск. Все 13 дополнительных
прав в этом наборе выдаются на 30 дней; отдельный пропуск остаётся на 90 дней.
Стандартный V даёт 12 домов: с первым пакетом будет 20; старые пакеты также
учитываются. Каждый новый пакет домов истекает независимо, остальные права
и ранг продлеваются по прежним правилам. Сначала проверяется весь состав.
Набор не меняет базовую цену V при пересчёте повышения/в меню рангов.
Для постоянного V набор не выдаётся: ручная сверка, а не выдача только дополнений.
Отзыв гибрида через shoprevoke заблокирован, чтобы не снимать только половину
покупки или чужие пересекающиеся права. Возвраты разбираются вручную.

## Найдено и исправлено в 1.4.197

1. Несуществующий `/nickcolors` заменён реальной `/labels color <id>` в документах и картинках.
2. `/cosmetics` управляет шлейфом, не эффектом входа: исправлено обещание переключателя.
3. Старое право набора могло открыть ранговые «Золотую пыль» и «Сияние»: покупка набора больше не обходит пустой product рангового эксклюзива.
4. В описании ранга обещался XP сезона, но код начисляет бонус только профессиям: неподтверждённое обещание снято, реальные бонусы сохранены. Прибавка +10 относится к потолку профессии, не к десяти уровням модуля Skills.
5. Повторные покупки у горизонта 365 дней могли терять оплаченные дни: полный срок теперь проверяется до выдачи, невозможная доставка идёт на ручную сверку.
6. Младший ранг через shopbuy/shopgift не попадал в ручную очередь, в отличие от shoppay: теперь все три пути используют одну проверку/очередь.
7. Постоянный младший ранг с временным старшим мог потерять остаток при продлении или понизить временную ступень: расчёт теперь учитывает оба слоя.
8. Битый/циклический набор мог выдаться частично: состав и сроки всех частей проверяются до первой выдачи.
9. Товар с периодом больше настроенного горизонта молча укорачивался при загрузке: теперь он исключается с диагностикой, не продаётся под ложным сроком.
10. «Все шлейфы» уточнено до пяти каталожных; два эксклюзива не входят. «Весь вид» не содержит ранг, дома, эффект входа или боевой пропуск.
11. Сравнение цен уточнено: поштучно 1 000 ₽, через набор шлейфов 904 ₽, «Весь вид» 599 ₽ — экономия 305 ₽ против более дешёвого способа.
12. Бонус Boosty в сообщении считался по запросу даже при срезанном бонусе: показываются только реально добавленные полные дни, для набора явно указана сумма по составляющим.
13. Год — ровно 365 дней по цене десяти покупок по 30 дней. 90 дней пропуска — срок доступа, не длина сезона и не автопрохождение заданий.

14. Ник доставки не проверялся до выдачи: буквальная подстановка `%username%` и некорректный ник теперь не создают покупку/аккаунт, а требуют ручной сверки.

## Сохранённые правила и ограничения

- Цены и ID не менялись. Дома — правила 1.4.196: база ранга + 8 × активные пакеты, без игрового потолка; сохранённые точки не удаляются.
- Никаких бесплатных платных товаров за прогресс: награды — отдельный XP-буст. Оплаченный премиум-трек не выдаёт платные товары.
- Повтор косметики/пропуска продлевает дни, не усиливает эффект; набор продлевает каждую составляющую. Если один оплаченный срок не помещается — весь набор требует ручного решения.
- Бонус Boosty условный, зависит от подтверждения и настроек, не увеличивает цену карточки; бонусные дни ограничены горизонтом. Оплаченный основной срок не урезается.
- Вход без очереди не обходит физическую вместимость сервера. Ранг не покупает уровни профессии, а повышает их потолок/скорость.
- Невозможная доставка — запись PENDING_REVIEW, не автоматическое подтверждение денег/возврат. Администратор сверяет кабинет и журнал. Не подтверждать запись вслепую: approve не является повторной выдачей SKU.
- RCON без номера заказа требует защищённого доступа; плагин не может независимо проверить деньги или отличить повтор команды от второй покупки.
- Старые пользовательские конфиги не затираются: названия/описания необходимо сверить с новой версией. Старые архивы релизов не переписываются.

## Что проверяется автоматически

26 уникальных SKU и цен; пары рангов 30/365; связи шлейфов/цветов/эффекта/пропуска с товарами;
состав/циклы наборов; периоды листьев; реальная палитра градиентов; формулы цен; размер пакета домов;
существующие команды; соответствие TXT/MD/манифеста; 25 PNG 1200×1200 с разными SHA-256; CRC и
воспроизводимость ZIP. Java-тесты отдельно проверяют доступ, арифметику сроков, постоянный/временный
ранг и подключение проверок к командам. Полный Build CI выполняется перед релизом.
''')
    result = ''.join(lines)
    if tuple(map(int, version.split('-')[0].split('.'))) >= (1, 4, 248):
        result = result.replace("Вход без очереди не обходит физическую вместимость сервера.",
                                "Ранг не даёт приоритета подключения и не обходит вместимость сервера.")
    return result
