"""Generate Enchants hub navigation art, requiring Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import ImageDraw, ImageFont
from generate_core_honor import art
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_enchants'
ICONS={
 'catalog':('definitions','CT'),'library':('pages','LB'),'scribe':('description','SC'),
 'inspect':('info','IN'),'hand':('grant_entry','HD'),'sources':('definition','SO'),
 'anvil':('level','AN'),'cleanse':('empty_definitions','CL'),'combine':('pages','CB'),
 'guard':('badge','GD'),'cooldowns':('refresh','CD'),'books':('definitions','BK'),
 'workshop':('description','WK'),'effects':('badge','EF'),'light':('badge','LT'),
 'curses':('close','CU'),'search':('filter','SE'),'help':('info','HP'),
 'guard-status':('info','GS'),'admin':('level','AD'),'guard-auto':('badge','AU'),
 'guard-briar_guard':('badge','BR'),'guard-faltering_guard':('badge','FA'),
 'guard-signal_guard':('badge','SI'),'guard-pve_anchor':('badge','PV'),
 'cleanse-choice':('empty_definitions','CH'),'cleanse-effect':('empty_definitions','CE'),
 'combine-effect':('pages','CO'),'confirm':('create','OK'),'close':('close','X'),'back':('back','<')
}


def save(name,base,label):
    image=art(base);d=ImageDraw.Draw(image);font=ImageFont.load_default()
    d.rounded_rectangle((17,20,29,29),radius=2,fill='#38293f',outline='#e4a9e8')
    bounds=d.textbbox((0,0),label,font=font);w=bounds[2]-bounds[0];h=bounds[3]-bounds[1]
    d.text((23-w//2,24-h//2-bounds[1]),label,font=font,fill='#fff2cb')
    path='menu/hub/'+name;ref='chrdk_enchants:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);image.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name,(base,label) in ICONS.items():save(name,base,label)
    print(f'Generated {len(ICONS)} Enchants hub models')
