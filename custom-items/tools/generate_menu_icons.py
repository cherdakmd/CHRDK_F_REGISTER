"""Generate original pixel-art menu category and navigation icons. Requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

PACK = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_menu'
COLORS = {
    'category/account': '#c990e4', 'category/progress': '#7ebbe9',
    'category/economy': '#edc35b', 'category/world': '#80c583',
    'category/gear': '#ee937b', 'category/social': '#80d3cb',
    'navigation/back': '#aebcd2', 'navigation/close': '#e77b7b',
}

for name, color in COLORS.items():
    image = Image.new('RGBA', (32, 32))
    d = ImageDraw.Draw(image)
    # A framed plaque makes the set cohesive, but the central symbol is distinct.
    d.rounded_rectangle((3, 3, 28, 28), radius=5, fill='#29283b', outline='#171722', width=1)
    d.rounded_rectangle((5, 5, 26, 26), radius=4, fill='#424056', outline=color, width=2)
    d.line((9, 7, 22, 7), fill='#b8b0ad', width=1)
    ink = '#fff1d3'
    if name.endswith('/account'):
        d.ellipse((13, 10, 19, 16), fill=ink)
        d.rounded_rectangle((10, 18, 22, 23), radius=2, fill=ink)
    elif name.endswith('/progress'):
        d.polygon([(16,9),(18,14),(23,14),(19,18),(21,23),(16,20),(11,23),(13,18),(9,14),(14,14)], fill=ink)
    elif name.endswith('/economy'):
        d.polygon([(16,9),(23,16),(16,23),(9,16)], fill=ink)
        d.line((16,10,16,22), fill=color, width=1)
        d.line((10,16,22,16), fill=color, width=1)
    elif name.endswith('/world'):
        d.ellipse((9, 9, 23, 23), outline=ink, width=2)
        d.arc((13, 9, 19, 23), 0, 360, fill=ink, width=1)
        d.line((10, 16, 22, 16), fill=ink, width=1)
    elif name.endswith('/gear'):
        d.polygon([(9,10),(23,10),(21,15),(18,17),(18,21),(21,21),(22,23),(10,23),(11,21),(14,21),(14,17),(11,15)], fill=ink)
    elif name.endswith('/social'):
        d.ellipse((9,10,15,16), fill=ink)
        d.ellipse((17,10,23,16), fill=ink)
        d.rounded_rectangle((8,18,15,22), radius=2, fill=ink)
        d.rounded_rectangle((17,18,24,22), radius=2, fill=ink)
        d.line((14,20,18,20), fill=ink, width=2)
    elif name.endswith('/back'):
        d.polygon([(9,16),(16,10),(16,13),(23,13),(23,19),(16,19),(16,22)], fill=ink)
    else:
        d.line((11,11,21,21), fill=ink, width=3)
        d.line((21,11,11,21), fill=ink, width=3)
    target = PACK / 'textures/item' / (name + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    image.save(target)
    for folder, data in [
        ('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_menu:item/' + name}}),
        ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_menu:item/' + name}}),
    ]:
        target = PACK / folder / (name + '.json')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
print(f'Generated {len(COLORS)} menu icons')
