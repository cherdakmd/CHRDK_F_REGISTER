"""Generate virtual catalog filter icons. Existing individual book textures are reused."""
import json
from pathlib import Path
from PIL import ImageDraw, ImageFont
from generate_core_honor import art
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_enchants'
ICONS={
 'empty':('empty_definitions','--'),'prev':('prev','<'),'next':('next','>'),'result':('definitions','RS'),'clear':('close','X'),
 'group_all':('filter','*'),'group_sword':('filter','SW'),'group_axe':('filter','AX'),'group_bow':('filter','BW'),
 'group_crossbow':('filter','XB'),'group_helmet':('filter','HE'),'group_chestplate':('filter','CH'),
 'group_leggings':('filter','LG'),'group_boots':('filter','BT'),'group_pickaxe':('filter','PK'),
 'group_shovel':('filter','SH'),'group_hoe':('filter','HO'),
 'rarity_all':('badge','*'),'rarity_common':('badge','C'),'rarity_rare':('badge','R'),
 'rarity_epic':('badge','E'),'rarity_legendary':('badge','L'),
 'polarity_all':('info','*'),'polarity_light':('info','+'),'polarity_curse':('close','-'),
 'hand_on':('create','H'),'hand_off':('close','H'),
 'sort_id':('pages','ID'),'sort_name':('pages','NA'),'sort_rarity':('pages','RA')
}


def save(name,base,label):
    im=art(base);d=ImageDraw.Draw(im);font=ImageFont.load_default()
    d.rounded_rectangle((17,20,29,29),radius=2,fill='#38293f',outline='#e4a9e8')
    box=d.textbbox((0,0),label,font=font);w=box[2]-box[0];h=box[3]-box[1]
    d.text((23-w//2,24-h//2-box[1]),label,font=font,fill='#fff2cb')
    path='menu/catalog/'+name;ref='chrdk_enchants:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);im.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name,(base,label) in ICONS.items():save(name,base,label)
    print(f'Generated {len(ICONS)} catalog controls')
