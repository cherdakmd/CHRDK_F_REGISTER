"""Generate virtual workshop controls; never generate or alter player inputs."""
import json
from pathlib import Path
from PIL import ImageDraw, ImageFont
from generate_core_honor import art
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_enchants'
ICONS={'instructions':('definitions','IN'),'left':('filter','<'),'right':('filter','>'),
       'cost_scribe':('pages','SC'),'cost_cleanse':('pages','CL'),'cost_combine':('pages','CO'),
       'choice':('definition','?'),'selected':('grant_entry','OK'),'prev':('prev','<'),
       'next':('next','>'),'combine':('pages','CB'),'prepare':('info','1'),
       'confirm':('create','2'),'close':('close','X')}


def save(name,base,label):
    im=art(base);d=ImageDraw.Draw(im);font=ImageFont.load_default()
    d.rounded_rectangle((17,20,29,29),radius=2,fill='#38293f',outline='#e4a9e8');box=d.textbbox((0,0),label,font=font);w=box[2]-box[0];h=box[3]-box[1]
    d.text((23-w//2,24-h//2-box[1]),label,font=font,fill='#fff2cb')
    path='menu/workshop/'+name;ref='chrdk_enchants:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);im.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name,(base,label) in ICONS.items():save(name,base,label)
    print(f'Generated {len(ICONS)} workshop icons')
