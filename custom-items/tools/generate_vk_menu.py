"""Generate fixed virtual VK menu icons, without account or personal data. Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_vk'
NAMES = ('link_unlinked', 'link_linked', 'notify_unlinked', 'notify_off', 'notify_on',
         'chat_hidden', 'chat_visible', 'badge', 'security_active', 'security_frozen', 'unlink_confirm', 'close')


def art(name):
    image = Image.new('RGBA', (32, 32)); d = ImageDraw.Draw(image)
    red, green, blue, light, ink, gray = '#ed7972', '#76d29d', '#7ab8ea', '#fff2df', '#19212d', '#a6adbb'
    color = red if name in ('unlink_confirm', 'close') else green if name in ('notify_on', 'link_linked', 'security_active') else gray if name in ('notify_unlinked', 'notify_off', 'chat_hidden') else blue
    d.rounded_rectangle((2,2,29,29), radius=5, fill='#252b3a', outline='#151b26')
    d.rounded_rectangle((4,4,27,27), radius=4, fill='#404b5c', outline=color, width=2)
    if name.startswith('link_'):
        d.ellipse((7,11,17,20), outline=color, width=3); d.ellipse((15,11,25,20), outline=color, width=3)
        if name == 'link_unlinked': d.line((12,23,21,8), fill=light, width=2)
        else: d.line((12,16,20,16), fill=light, width=2)
    elif name.startswith('notify_'):
        d.polygon([(16,7),(11,10),(10,17),(8,20),(24,20),(22,17),(21,10)], fill=color, outline=ink)
        d.ellipse((14,21,18,24), fill=light)
        if name == 'notify_off': d.line((9,24,24,8), fill=red, width=2)
        if name == 'notify_unlinked': d.rectangle((21,20,25,25), fill=gray, outline=ink); d.line((22,18,24,20), fill=light)
        if name == 'notify_on': d.line((23,9,25,12), fill=light, width=2)
    elif name.startswith('chat_'):
        d.rounded_rectangle((7,8,24,20), radius=3, fill=color, outline=ink)
        d.polygon([(11,20),(11,24),(16,20)], fill=color, outline=ink)
        d.line((10,12,21,12), fill=light); d.line((10,15,19,15), fill=light)
        if name == 'chat_hidden': d.line((9,24,24,8), fill=red, width=2)
    elif name == 'badge':
        d.polygon([(8,9),(17,9),(24,16),(16,24),(8,17)], fill=color, outline=ink); d.ellipse((10,11,13,14), fill=light)
        d.line((17,14,19,16,17,18), fill=light, width=2)
    elif name.startswith('security_'):
        d.polygon([(16,7),(24,10),(23,18),(20,22),(16,25),(12,22),(9,18),(8,10)], fill=color, outline=ink)
        if name == 'security_active': d.line((11,16,15,20,21,12), fill=light, width=2)
        else: d.line((16,11,16,21), fill=light, width=2); d.line((12,14,20,18), fill=light); d.line((20,14,12,18), fill=light)
    elif name == 'unlink_confirm':
        d.polygon([(16,6),(26,24),(6,24)], fill=red, outline=ink); d.line((16,12,16,18), fill=light, width=3); d.rectangle((15,20,17,22), fill=light)
    else:
        d.line((10,10,22,22), fill=red, width=4); d.line((22,10,10,22), fill=red, width=4)
    return image


def save(name):
    path = 'menu/' + name; ref = 'chrdk_vk:item/' + path
    target = ROOT / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True, exist_ok=True); art(name).save(target)
    for folder, data in [('models/item', {'parent': 'minecraft:item/generated', 'textures': {'layer0': ref}}),
                         ('items', {'model': {'type': 'minecraft:model', 'model': ref}})]:
        target = ROOT / folder / (path + '.json'); target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    for name in NAMES: save(name)
    print(f'Generated {len(NAMES)} VK menu icons')
