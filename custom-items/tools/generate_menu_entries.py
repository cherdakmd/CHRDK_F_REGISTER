"""Original 32px pictograms for the stock /menu entries. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'custom-items/resource-pack/assets/chrdk_menu'
MANIFEST = ROOT / 'src/main/resources/server-menu-visuals.yml'
# Hand-selected primary and secondary symbols; no external artwork or fonts.
# Secondary markers distinguish closely related actions, not dynamic game state.
DESIGNS = {
    'main/profile': ('person', 'star'), 'main/wiki': ('book', 'globe'),
    'main/help': ('book', 'help'), 'main/donate': ('gem', 'heart'),
    'account/my_rank': ('crown', 'person'), 'account/ranks': ('crown', 'star'),
    'account/rank_rules': ('book', 'crown'), 'account/donate': ('heart', 'gem'),
    'account/catalog': ('chest', 'star'), 'account/daily': ('calendar', 'star'),
    'account/guide': ('book', 'compass'), 'account/security': ('shield', 'lock'),
    'account/session': ('clock', 'person'), 'account/vk': ('chat', 'link'),
    'progress/battlepass': ('flag', 'star'), 'progress/achievements': ('trophy', 'star'),
    'progress/playtime_rank': ('helmet', 'clock'), 'progress/quests': ('scroll', 'check'),
    'progress/skills': ('crystal', 'star'), 'progress/fishing': ('fish', 'hook'),
    'progress/goals': ('target', 'person'), 'progress/generator': ('furnace', 'bolt'),
    'economy/balance': ('wallet', 'gem'), 'economy/leaderboard': ('trophy', 'gem'),
    'economy/statistics': ('chart', 'gem'), 'economy/teleport_prices': ('portal', 'gem'),
    'economy/shop': ('shop', 'gem'), 'economy/claim_budget': ('fence', 'gem'),
    'world/claim': ('shield', 'house'), 'world/claim_map': ('map', 'pin'),
    'world/claim_help': ('fence', 'help'), 'world/homes': ('house', 'pin'),
    'world/home_hint': ('bed', 'help'), 'world/random_teleport': ('portal', 'spark'),
    'world/player_teleport': ('door', 'person'), 'world/mob_tiers': ('skull', 'star'),
    'gear/enchants': ('book', 'spark'), 'gear/scribe': ('scroll', 'pen'),
    'gear/artifacts': ('totem', 'spark'), 'gear/collection': ('frame', 'gem'),
    'gear/cosmetics': ('spark', 'heart'), 'social/chat': ('chat', 'person'),
    'social/guild': ('banner', 'shield'), 'social/labels': ('tag', 'star'),
    'social/anticheat': ('shield', 'check'),
}
COLORS = {'main': '#d8bf83', 'account': '#ca9de9', 'progress': '#8fc8ee',
          'economy': '#ecc46c', 'world': '#92ce92', 'gear': '#eaa18d', 'social': '#8ed6cf'}


def picture(draw, kind, color):
    """Draw a recognizable silhouette on a 19px local canvas."""
    def line(points, width=2): draw.line(points, fill=color, width=width, joint='curve')
    def poly(points): draw.polygon(points, fill=color)
    def rect(box, width=2): draw.rectangle(box, outline=color, width=width)
    def ellipse(box, width=2): draw.ellipse(box, outline=color, width=width)
    if kind == 'person':
        ellipse((7, 1, 13, 7)); line([(10,8),(10,16)]); line([(4,16),(5,12),(10,10),(15,12),(16,16)])
    elif kind in ('book', 'scroll'):
        poly([(2,3),(8,2),(10,4),(12,2),(18,3),(18,16),(12,15),(10,17),(8,15),(2,16)])
        draw.line([(10,4),(10,15)], fill='#454051', width=1)
        if kind == 'scroll': draw.line([(4,7),(8,7)], fill='#454051', width=1)
    elif kind == 'crown': poly([(2,5),(6,9),(10,3),(14,9),(18,5),(16,15),(4,15)])
    elif kind == 'gem' or kind == 'crystal':
        poly([(10,1),(17,6),(15,14),(10,18),(5,14),(3,6)])
        draw.line([(10,2),(10,16),(5,6),(16,6)], fill='#454051', width=1)
    elif kind == 'heart': poly([(10,17),(2,10),(2,5),(5,3),(8,3),(10,5),(12,3),(15,3),(18,5),(18,10)])
    elif kind == 'chest':
        rect((2,5,18,16)); line([(2,9),(18,9)]); draw.rectangle((9,8,11,12), fill=color)
    elif kind == 'calendar':
        rect((3,3,17,17)); line([(3,7),(17,7)]); line([(6,1),(6,5)]); line([(14,1),(14,5)]); draw.rectangle((7,10,9,12),fill=color)
    elif kind == 'shield': poly([(3,2),(17,2),(17,10),(10,18),(3,10)])
    elif kind == 'clock':
        ellipse((2,2,18,18)); line([(10,4),(10,10),(14,12)])
    elif kind == 'chat': poly([(2,3),(18,3),(18,13),(11,13),(6,17),(6,13),(2,13)])
    elif kind == 'flag' or kind == 'banner':
        line([(4,2),(4,18)]); poly([(5,3),(17,3),(14,7),(17,11),(5,11)])
    elif kind == 'trophy':
        poly([(5,2),(15,2),(15,9),(12,12),(11,13),(11,16),(15,16),(15,18),(5,18),(5,16),(9,16),(9,13),(6,10)])
        line([(5,4),(2,4),(3,9),(6,10)]); line([(15,4),(18,4),(17,9),(14,10)])
    elif kind == 'helmet': poly([(3,8),(5,3),(15,3),(17,8),(17,16),(13,16),(13,11),(7,11),(7,16),(3,16)])
    elif kind == 'fish':
        poly([(4,10),(1,5),(1,15),(4,11),(8,6),(14,6),(18,10),(14,14),(8,14)])
        draw.point((14,9), fill='#454051')
    elif kind == 'target':
        ellipse((2,2,18,18)); ellipse((6,6,14,14)); draw.rectangle((9,9,11,11), fill=color)
    elif kind == 'furnace':
        rect((2,2,18,18)); rect((5,5,15,9),1); poly([(7,16),(8,12),(10,14),(12,11),(14,16)])
    elif kind == 'wallet':
        poly([(2,5),(16,5),(16,8),(18,8),(18,15),(16,15),(16,17),(2,17)]); draw.point((14,11), fill='#454051')
    elif kind == 'chart':
        line([(3,2),(3,17),(18,17)]); draw.rectangle((6,11,8,15),fill=color); draw.rectangle((10,7,12,15),fill=color); draw.rectangle((14,3,16,15),fill=color)
    elif kind == 'portal':
        ellipse((4,1,16,18),3); line([(8,13),(12,6)],1)
    elif kind == 'shop':
        poly([(2,5),(4,2),(16,2),(18,5),(18,8),(2,8)]); rect((4,8,16,17)); rect((8,11,12,17),1)
    elif kind == 'fence':
        line([(5,3),(5,17)],3); line([(15,3),(15,17)],3); line([(2,7),(18,7)]); line([(2,13),(18,13)])
    elif kind == 'map':
        poly([(2,3),(7,1),(13,3),(18,1),(18,16),(13,18),(7,16),(2,18)])
        draw.line([(7,2),(7,16)],fill='#454051'); draw.line([(13,3),(13,17)],fill='#454051')
    elif kind == 'house':
        poly([(1,9),(10,2),(19,9),(16,9),(16,17),(4,17),(4,9)]); draw.rectangle((9,12,12,17),fill='#454051')
    elif kind == 'bed':
        line([(3,5),(3,17)]); draw.rectangle((5,10,17,15),fill=color); draw.rectangle((5,7,8,9),fill=color); line([(17,15),(17,18)])
    elif kind == 'door':
        rect((4,2,16,18)); draw.point((13,10), fill=color); line([(2,18),(18,18)],1)
    elif kind == 'skull':
        poly([(4,3),(16,3),(18,6),(18,12),(15,12),(15,16),(5,16),(5,12),(2,12),(2,6)])
        draw.rectangle((5,7,7,9),fill='#454051'); draw.rectangle((13,7,15,9),fill='#454051')
    elif kind == 'totem':
        ellipse((7,2,13,7)); poly([(3,7),(17,7),(15,11),(12,11),(13,17),(7,17),(8,11),(5,11)])
    elif kind == 'frame':
        rect((2,2,18,18),3); ellipse((7,7,13,13),2)
    elif kind == 'spark':
        poly([(10,1),(12,7),(18,10),(12,12),(10,18),(8,12),(2,10),(8,8)])
    elif kind == 'tag':
        poly([(2,7),(8,2),(17,2),(17,11),(11,17)]); draw.ellipse((12,4,14,6),fill='#454051')
    else: raise ValueError(kind)


def marker(draw, kind, color):
    # Small symbols on a dark roundel, 8x8px in the lower-right.
    draw.ellipse((21,21,30,30), fill='#252434', outline=color)
    ink = '#fff4d8'
    paths = {
        'star': [(25,22),(26,25),(29,25),(27,27),(28,29),(25,28),(23,29),(24,27),(22,25),(24,25)],
        'heart': [(22,25),(23,23),(25,24),(27,23),(29,25),(25,29)],
        'gem': [(25,22),(29,25),(25,29),(22,25)],
        'person': [(24,23),(26,23),(27,25),(26,26),(28,28),(23,28),(24,26),(23,25)],
        'help': [(23,23),(27,23),(28,24),(28,25),(25,27),(25,28),(24,28),(24,26),(26,25),(26,24),(23,24)],
        'check': [(22,26),(24,28),(29,23),(28,23),(24,26),(23,25)],
        'bolt': [(26,22),(23,26),(25,26),(24,29),(28,25),(26,25)],
        'pin': [(25,22),(28,23),(29,25),(25,29),(22,25),(23,23)],
        'spark': [(25,22),(26,25),(29,26),(26,27),(25,29),(24,27),(22,26),(24,25)],
        'pen': [(23,28),(24,26),(28,22),(29,23),(25,27)],
        'hook': [(27,22),(27,26),(26,29),(23,29),(22,27),(24,27),(24,28),(25,28),(26,26),(26,22)],
        'lock': [(23,25),(24,25),(24,23),(27,23),(27,25),(28,25),(28,29),(23,29)],
        'link': [(22,25),(24,23),(26,23),(26,24),(24,24),(23,26),(25,27),(27,25),(28,25),(28,27),(26,29),(24,29)],
        'clock': [(25,22),(28,23),(29,26),(28,28),(25,29),(22,28),(22,24),(23,23)],
        'compass': [(25,22),(29,26),(25,29),(22,26)],
        'globe': [(25,22),(28,23),(29,26),(28,28),(25,29),(22,28),(22,24),(23,23)],
        'crown': [(22,24),(24,26),(25,23),(27,26),(29,24),(28,28),(23,28)],
        'shield': [(22,23),(29,23),(29,26),(25,29),(22,26)],
        'house': [(22,26),(25,23),(29,26),(28,26),(28,29),(23,29),(23,26)],
    }
    draw.polygon(paths[kind], fill=ink)
    if kind in ('clock', 'globe', 'compass'):
        draw.line((25,24,25,27), fill='#454051')
        draw.line((23,26,27,26), fill='#454051')


manifest = [json.loads(line[line.index('{'):]) for line in MANIFEST.read_text(encoding='utf-8').splitlines() if line.lstrip().startswith('- {')]
assert len(manifest) == len(DESIGNS) and {m['visual'] for m in manifest} == set(DESIGNS)
for name, (symbol, accent) in DESIGNS.items():
    color = COLORS[name.split('/')[0]]
    img = Image.new('RGBA', (32, 32))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((3,3,28,28), radius=5, fill='#29283b', outline='#171722')
    d.rounded_rectangle((5,5,26,26), radius=4, fill='#424056', outline=color, width=2)
    glyph = Image.new('RGBA', (20,20))
    picture(ImageDraw.Draw(glyph), symbol, '#fff1d3')
    img.alpha_composite(glyph, (6,6))
    marker(d, accent, color)
    path = 'entry/' + name
    texture = PACK / 'textures/item' / (path + '.png')
    texture.parent.mkdir(parents=True, exist_ok=True)
    img.save(texture)
    for folder, data in [
        ('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_menu:item/' + path}}),
        ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_menu:item/' + path}}),
    ]:
        target = PACK / folder / (path + '.json')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
print(f'Generated {len(DESIGNS)} menu entry icons')
