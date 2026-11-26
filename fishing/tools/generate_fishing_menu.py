"""Generate original pixel-art icons for Fishing's main GUI. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageEnhance

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_fishing'
IDS = ('album', 'sale', 'rod', 'bait_shop', 'bait_equip', 'orders', 'cooking')
UNAVAILABLE = {'bait_shop', 'bait_equip', 'orders'}


def art(name):
    im = Image.new('RGBA', (32, 32))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((3,3,28,28), radius=5, fill='#172d42', outline='#0c1a2b')
    d.rounded_rectangle((5,5,26,26), radius=4, fill='#285770', outline='#8dd3df', width=2)
    ink, gold, dark, aqua = '#f8e9c7', '#f4c46c', '#294054', '#8ee0e4'
    if name == 'album':
        d.polygon([(8,9),(14,8),(16,10),(18,8),(24,9),(24,23),(18,22),(16,24),(14,22),(8,23)], fill=ink)
        d.line((16,10,16,22), fill=dark)
        d.polygon([(9,15),(11,13),(14,13),(15,15),(14,17),(11,17)], fill='#43889d')
        d.polygon([(10,15),(8,13),(8,17)],fill='#43889d')
        d.line((18,13,22,13),fill=dark); d.line((18,16,22,16),fill=dark)
    elif name == 'sale':
        d.polygon([(7,16),(9,13),(14,12),(19,15),(19,17),(14,20),(9,19)], fill=aqua)
        d.polygon([(9,16),(6,13),(6,19)], fill=aqua); d.point((16,15), fill=dark)
        d.ellipse((17,17,25,25), fill=gold, outline='#996b38'); d.line((21,19,21,23),fill=dark)
    elif name == 'rod':
        d.line([(8,23),(20,8),(23,7)], fill=ink, width=2)
        d.line([(20,8),(24,13),(24,19)], fill=aqua)
        d.arc((20,18,25,23),0,180,fill=gold,width=2); d.line((7,24,11,20),fill=gold,width=3)
    elif name == 'bait_shop':
        d.rectangle((10,10,21,23), fill=ink, outline=dark, width=1)
        d.rectangle((12,7,19,10), fill=gold); d.line([(12,17),(14,14),(17,18),(19,15)], fill='#ce785a', width=2)
        d.ellipse((19,19,26,26), fill=gold, outline=dark); d.line((22,21,22,24), fill=dark)
    elif name == 'bait_equip':
        d.line([(20,8),(20,18)], fill=ink, width=2); d.arc((12,15,21,24),0,180,fill=ink,width=2)
        d.line([(12,19),(13,17)], fill=ink, width=2)
        d.line([(10,11),(12,9),(14,11),(16,9)], fill='#f4b28d', width=3)
        d.line([(8,25),(12,25)], fill=aqua)
    elif name == 'orders':
        d.polygon([(8,7),(21,7),(23,9),(23,22),(21,24),(8,24),(10,22),(10,9)], fill=ink)
        d.line((12,11,20,11),fill=dark); d.line((12,14,18,14),fill=dark)
        d.line([(13,19),(15,21),(20,16)], fill='#62a676', width=2)
    elif name == 'cooking':
        d.ellipse((8,12,22,23), fill=dark, outline=ink, width=2)
        d.line((21,19,26,24),fill=gold,width=3)
        d.polygon([(10,17),(12,15),(17,15),(19,17),(17,19),(12,19)],fill=aqua)
        d.polygon([(12,17),(10,15),(10,19)],fill=aqua); d.point((17,16),fill=dark)
        d.line((12,8,12,11),fill=ink); d.line((17,7,17,10),fill=ink)
    else: raise ValueError(name)
    return im


def save(name, image):
    path = 'menu/main/' + name
    target = PACK / 'textures/item' / (path + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)
    for directory, data in (
        ('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_fishing:item/' + path}}),
        ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_fishing:item/' + path}}),
    ):
        target = PACK / directory / (path + '.json')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


for name in IDS:
    image = art(name)
    save(name, image)
    if name in UNAVAILABLE:
        muted = ImageEnhance.Color(image).enhance(0.15)
        d = ImageDraw.Draw(muted)
        d.ellipse((20,20,29,29), fill='#443743', outline='#e5a2a4')
        d.line((22,22,27,27), fill='#ffe4de', width=2)
        d.line((27,22,22,27), fill='#ffe4de', width=2)
        save(name + '_unavailable', muted)
print('Generated 7 Fishing navigation icons and 3 unavailable states')
