"""Generate an original 32px icon for newly issued guide books; requires Pillow."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_core'
image = Image.new('RGBA', (32, 32))
d = ImageDraw.Draw(image)
d.polygon([(5, 4), (21, 2), (27, 6), (27, 25), (20, 29), (5, 27)], fill='#302638')
d.polygon([(7, 5), (21, 4), (25, 7), (25, 23), (20, 26), (7, 25)], fill='#624536')
d.line([(9, 6), (9, 24), (20, 25), (24, 23)], fill='#e7c579', width=2)
d.polygon([(11, 9), (19, 8), (23, 11), (23, 20), (19, 23), (11, 22)], fill='#d9bd7e')
d.polygon([(17, 10), (20, 15), (18, 15), (18, 21), (16, 21), (16, 15), (14, 15)], fill='#276b74')
d.point((17, 12), fill='#fcf2ce')
for folder, payload in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_core:item/guide_book'}}), ('items', {'model': {'type': 'minecraft:model', 'model': 'chrdk_core:item/guide_book'}})]:
    target = root / folder / 'guide_book.json'; target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(payload, indent=2) + '\n', encoding='utf-8')
target = root / 'textures/item/guide_book.png'; target.parent.mkdir(parents=True, exist_ok=True); image.save(target)
