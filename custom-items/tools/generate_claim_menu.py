"""Generate deterministic menu tiles for the Claims inventory. Requires Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_claims'
BASE = ('info', 'prev', 'next', 'clan', 'close', 'empty_mine', 'empty_sale', 'entry_mine', 'entry_other_world', 'entry_sale', 'entry_access')
TABS = ('tab_mine', 'tab_sale', 'tab_access')
NAMES = BASE + tuple(f'{name}_{state}' for name in TABS for state in ('normal', 'selected'))


def art(name):
    base = name.removesuffix('_selected').removesuffix('_normal'); selected = name.endswith('_selected')
    colors = {'info':'#d3c18e','prev':'#82bcd0','next':'#82bcd0','clan':'#b5add9','close':'#da7d78',
              'empty_mine':'#9aadb3','empty_sale':'#b7a789','entry_mine':'#87c984','entry_other_world':'#a598d1',
              'entry_sale':'#e4c26c','entry_access':'#80c3cf','tab_mine':'#87c984','tab_sale':'#e4c26c','tab_access':'#80c3cf'}
    color, ink, light = colors[base], '#1c2630', '#fff1d1'
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((2,2,29,29), radius=5, fill='#252d35', outline='#151c25')
    d.rounded_rectangle((4,4,27,27), radius=4, fill='#42505a', outline=light if selected else color, width=2)
    if base.startswith('entry_') or base == 'tab_mine':
        d.polygon([(8,15),(16,9),(24,15),(24,23),(8,23)], fill=color, outline=ink)
        d.polygon([(7,15),(16,7),(25,15),(22,16),(16,11),(10,16)], fill=light, outline=ink)
        d.rectangle((14,17,18,23), fill=ink)
        if base == 'tab_mine': d.ellipse((20,19,25,24), fill='#fff1d1', outline=ink)
        if base == 'entry_other_world': d.ellipse((20,6,24,10), fill='#d4c4ff')
        if base == 'entry_sale': d.ellipse((20,18,26,24), fill='#eac95b', outline=ink)
        if base == 'entry_access': d.line((8,22,12,18,15,21), fill=light, width=2)
    elif base.startswith('empty_'):
        d.polygon([(9,9),(19,9),(23,13),(23,23),(9,23)], fill=color, outline=ink)
        if base == 'empty_sale': d.ellipse((14,13,19,18), outline=light, width=2)
        else: d.line((12,14,20,14), fill=light); d.line((12,18,18,18), fill=light)
    elif base in ('prev','next'):
        points = [(8,16),(16,9),(16,13),(24,13),(24,19),(16,19),(16,23)]
        if base == 'next': points = [(32-x,y) for x,y in points]
        d.polygon(points, fill=color, outline=ink)
    elif base == 'close':
        d.line((10,10,22,22), fill=color, width=4); d.line((22,10,10,22), fill=color, width=4)
    elif base == 'clan':
        d.line((10,7,10,25), fill=light, width=2); d.polygon([(11,8),(23,8),(23,18),(17,16),(11,18)], fill=color, outline=ink)
        d.polygon([(16,10),(19,12),(16,14),(13,12)], fill=light)
    elif base == 'tab_sale':
        d.ellipse((8,8,24,24), fill=color, outline=ink); d.line((16,10,16,22), fill=light, width=2); d.line((12,13,19,13,20,16,12,18,19,19), fill=light, width=2)
    elif base == 'tab_access':
        d.polygon([(10,8),(21,7),(21,24),(10,24)], fill=color, outline=ink); d.rectangle((18,15,19,16), fill=light)
    else:
        d.rounded_rectangle((9,7,23,24), radius=2, fill=color, outline=ink); d.line((12,12,20,12), fill=light, width=2); d.line((12,16,20,16), fill=light); d.line((12,20,18,20), fill=light)
    if selected: d.line((9,26,23,26), fill=light, width=2)
    return im


def save(name):
    path = 'menu/' + name; ref = 'chrdk_claims:item/' + path
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} Claims menu icons')
