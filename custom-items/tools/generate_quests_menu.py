"""Generate original quest GUI icons; requires Pillow. Never replaces vanilla assets."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_quests'
TYPES = ('kill', 'mine', 'gather')
STATES = ('available', 'in_progress', 'ready', 'done', 'locked', 'wrong_faction', 'rank')
PALETTE = {
    'available': '#66bddd', 'in_progress': '#ebad4b', 'ready': '#61cc82',
    'done': '#b4bdd1', 'locked': '#867b97', 'wrong_faction': '#df716d', 'rank': '#c89de1',
}


def quest_art(kind, state):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    ink = '#302a3a'
    # Scroll plus a large symbol for the quest type. The small badge is status,
    # with a shape as well as a colour (not colour alone).
    d.rounded_rectangle((3, 2, 25, 29), radius=3, fill='#b88955', outline=ink, width=1)
    d.rectangle((6, 5, 22, 26), fill='#f0dba5', outline='#714e45')
    d.line((7, 3, 22, 3), fill='#ffe8b6', width=2)
    if kind == 'kill':
        d.polygon([(9, 22), (11, 17), (19, 8), (21, 7), (20, 11), (13, 19), (12, 23)], fill='#929fac', outline=ink)
        d.line((9, 17, 15, 22), fill='#673d39', width=2)
    elif kind == 'mine':
        d.line((10, 23, 19, 10), fill='#93623d', width=3)
        d.line((8, 11, 12, 8, 19, 8, 22, 11), fill='#637988', width=3)
        d.point((11, 8), fill='#dde6df')
    else:
        d.rectangle((8, 14, 20, 22), fill='#a86539', outline=ink)
        d.rectangle((8, 12, 20, 16), fill='#c6894e', outline=ink)
        d.rectangle((13, 15, 15, 18), fill='#f1c86b')
    d.ellipse((18, 18, 31, 31), fill=ink)
    d.ellipse((19, 19, 30, 30), fill=PALETTE[state])
    white = '#fff5de'
    if state == 'available':
        d.line((24, 21, 24, 26), fill=white, width=2); d.point((24, 28), fill=white)
    elif state == 'in_progress':
        d.ellipse((21, 21, 28, 28), outline=white); d.line((24, 22, 24, 25, 27, 25), fill=white)
    elif state == 'ready':
        d.line((21, 25, 23, 27, 28, 22), fill=white, width=2)
    elif state == 'done':
        d.line((21, 24, 23, 26, 27, 22), fill=white); d.line((22, 27, 27, 27), fill=white)
    elif state == 'locked':
        d.arc((22, 20, 27, 26), 180, 360, fill=white, width=1); d.rectangle((21, 24, 28, 28), outline=white)
    elif state == 'wrong_faction':
        d.line((22, 22, 27, 27), fill=white, width=2); d.line((27, 22, 22, 27), fill=white, width=2)
    else:
        d.polygon([(21, 22), (23, 24), (25, 21), (27, 24), (29, 22), (28, 28), (22, 28)], outline=white, fill='#9367a9')
    return im


def control_art(name):
    im = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(im)
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#393548', outline='#c7a66a', width=2)
    if name == 'info':
        d.rectangle((9, 7, 22, 24), fill='#ead6a2', outline='#744c3a')
        d.line((12, 12, 19, 12), fill='#7c5c49', width=2); d.line((12, 16, 19, 16), fill='#7c5c49', width=2); d.line((12, 20, 17, 20), fill='#7c5c49')
    elif name == 'back':
        d.polygon([(7, 16), (15, 9), (15, 13), (24, 13), (24, 19), (15, 19), (15, 23)], fill='#bed9dd', outline='#29475a')
    else:
        d.line((10, 10, 22, 22), fill='#ed8279', width=4); d.line((22, 10, 10, 22), fill='#ed8279', width=4)
    return im


def save(path, image):
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); image.save(target)
    reference = 'chrdk_quests:item/' + path
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': reference}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': reference}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for kind in TYPES:
        for state in STATES: save('menu/quest/' + kind + '_' + state, quest_art(kind, state))
    for name in ('info', 'back', 'close'): save('menu/' + name, control_art(name))
    print('Generated 21 quest state icons and 3 controls')
