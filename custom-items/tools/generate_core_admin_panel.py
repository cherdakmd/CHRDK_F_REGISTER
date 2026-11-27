"""Generate moderation panel symbols from the shared Core glyphs (Pillow 11.3.0)."""
import json
from pathlib import Path
from PIL import ImageDraw, ImageFont
from generate_core_honor import art

ROOT = Path(__file__).resolve().parents[1] / 'resource-pack/assets/chrdk_core'
# Base glyph and short badge make related controls readable without encoding private data.
ICONS = {
    'moderators': ('grants', 'M'), 'journal': ('definitions', 'J'), 'mutes': ('grants', 'U'),
    'honors': ('badge', 'H'), 'filter': ('filter', 'F'), 'refresh': ('refresh', 'R'),
    'prev': ('prev', '<'), 'next': ('next', '>'), 'close': ('close', 'X'), 'back': ('back', 'B'),
    'info': ('info', 'I'), 'warn': ('description', '!'), 'ban': ('close', 'B'), 'unban': ('create', 'B'),
    'role_add': ('create', 'M'), 'role_remove': ('delete', 'M'), 'punish': ('level', 'P'),
    'warn_active': ('definition', '!'), 'warn_removed': ('empty_definitions', '!'),
    'moderator_entry': ('grant_entry', 'M'), 'journal_entry': ('definition', 'J'),
    'mute_entry': ('definition', 'U'), 'punish_info': ('info', 'P'), 'mute_preset': ('pages', 'T'),
    'mute_custom': ('description', 'T'), 'unmute': ('create', 'U'), 'kick': ('next', 'K'),
    'ip_ban': ('close', 'IP'), 'ip_unban': ('create', 'IP'), 'chat_history': ('pages', 'C'),
    'chat_entry': ('definition', 'C'), 'pages': ('pages', '#'), 'empty': ('empty_definitions', '-')
}


def save(name, base, label):
    im = art(base); draw = ImageDraw.Draw(im); font = ImageFont.load_default()
    draw.rounded_rectangle((18, 20, 29, 29), radius=2, fill='#303242', outline='#f1d496')
    box = draw.textbbox((0, 0), label, font=font); w=box[2]-box[0]; h=box[3]-box[1]
    draw.text((23-(w//2), 24-(h//2)-box[1]), label, font=font, fill='#fff2cb')
    path = 'menu/admin_panel/' + name; ref = 'chrdk_core:item/' + path
    target = ROOT/'textures/item'/(path+'.png'); target.parent.mkdir(parents=True,exist_ok=True); im.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json'); target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__ == '__main__':
    for name,(base,label) in ICONS.items(): save(name,base,label)
    print(f'Generated {len(ICONS)} moderation panel models')
