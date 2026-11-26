"""Create original 32px skill crystal art and item models. Requires Pillow.

This generator is deterministic. SkillType is the authoritative set of IDs;
it aborts if a new skill lacks a deliberate colour and symbol.
"""
import json
import re
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'custom-items/resource-pack/assets/chrdk_skills'
SOURCE = ROOT / 'skills/src/main/java/ru/cherdakmd/chrdkskills/SkillType.java'
# Each skill has its own gem colour and icon; coordinates are integer pixels.
COLORS = {
    'mining': (99, 174, 201), 'woodcutting': (83, 169, 91),
    'digging': (190, 120, 75), 'farming': (216, 185, 64),
    'fishing': (59, 162, 222), 'combat': (222, 80, 73),
    'archery': (213, 139, 67), 'defense': (133, 148, 210),
    'alchemy': (187, 93, 204), 'enchanting': (116, 101, 222),
}
ids = re.findall(r'^\s*[A-Z]+\("([a-z]+)", Category\.', SOURCE.read_text(), re.MULTILINE)
assert len(ids) == 10 and set(ids) == set(COLORS)


def shade(color, factor):
    return tuple(min(255, int(c * factor)) for c in color) + (255,)


def draw_icon(draw, name):
    ink = (255, 250, 205, 255)
    dark = (40, 39, 58, 255)
    # Symbols use silhouettes, not text, so they remain recognisable at GUI scale.
    if name == 'mining':
        draw.line([(11,12),(20,12),(22,14)], fill=ink, width=2)
        draw.line([(17,12),(13,21)], fill=ink, width=2)
    elif name == 'woodcutting':
        draw.line([(16,11),(16,21)], fill=ink, width=2)
        draw.polygon([(16,10),(11,16),(14,16),(11,19),(21,19),(18,16),(21,16)], fill=ink)
    elif name == 'digging':
        draw.line([(19,11),(13,17)], fill=ink, width=2)
        draw.polygon([(12,16),(15,19),(12,22),(10,20)], fill=ink)
    elif name == 'farming':
        draw.line([(15,21),(17,11)], fill=ink, width=2)
        for x, y in [(14,13),(17,14),(13,16),(16,17)]:
            draw.rectangle((x,y,x+2,y+1), fill=ink)
    elif name == 'fishing':
        draw.polygon([(11,16),(14,13),(20,14),(22,16),(20,19),(14,19)], fill=ink)
        draw.polygon([(12,16),(9,13),(9,19)], fill=ink)
        draw.point((19,15), fill=dark)
    elif name == 'combat':
        draw.line([(12,21),(21,11)], fill=ink, width=2)
        draw.line([(11,16),(16,21)], fill=ink, width=2)
    elif name == 'archery':
        draw.arc((10,10,21,22), 270, 90, fill=ink, width=2)
        draw.line([(16,10),(16,22)], fill=ink)
        draw.line([(11,16),(21,16)], fill=ink, width=2)
        draw.polygon([(21,14),(23,16),(21,18)], fill=ink)
    elif name == 'defense':
        draw.polygon([(11,11),(21,11),(21,16),(16,22),(11,16)], fill=ink)
        draw.line([(16,12),(16,19)], fill=dark, width=1)
    elif name == 'alchemy':
        draw.line([(14,10),(18,10)], fill=ink, width=2)
        draw.polygon([(15,11),(17,11),(17,14),(20,18),(19,21),(13,21),(12,18),(15,14)], fill=ink)
        draw.line([(14,18),(18,18)], fill=dark)
    elif name == 'enchanting':
        draw.polygon([(11,12),(15,11),(16,13),(17,11),(21,12),(21,20),(17,19),(16,21),(15,19),(11,20)], fill=ink)
        draw.line([(16,13),(16,20)], fill=dark)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


for name in ids:
    c = COLORS[name]
    img = Image.new('RGBA', (32,32)); d = ImageDraw.Draw(img)
    outline = shade(c, .28); base = shade(c, .75)
    gem = [(16,3),(24,9),(25,20),(16,28),(7,20),(8,9)]
    d.polygon(gem, fill=base, outline=outline, width=1)
    d.polygon([(16,4),(16,25),(9,19),(9,10)], fill=shade(c, .95))
    d.polygon([(16,4),(23,10),(16,12),(9,10)], fill=shade(c, 1.35))
    d.line([(8,20),(16,25),(24,20)], fill=shade(c, .5), width=1)
    d.line([(10,10),(13,8)], fill=shade(c, 1.6), width=1)
    draw_icon(d, name)
    texture = PACK / 'textures/item/crystal' / (name + '.png')
    texture.parent.mkdir(parents=True, exist_ok=True); img.save(texture)
    model = 'crystal/' + name
    write_json(PACK / 'models/item' / (model + '.json'), {
        'parent': 'minecraft:item/generated', 'textures': {'layer0': 'chrdk_skills:item/' + model}})
    write_json(PACK / 'items' / (model + '.json'), {
        'model': {'type': 'minecraft:model', 'model': 'chrdk_skills:item/' + model}})
print(f'Generated {len(ids)} skill crystal textures')
