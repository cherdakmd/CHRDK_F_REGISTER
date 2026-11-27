"""Generate virtual shop quantity controls using Pillow 11.3.0."""
import json
from pathlib import Path
from PIL import ImageDraw,ImageFont
from generate_core_honor import art
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_diamondshop'
ICONS={'minus_64':('close','64'),'minus_8':('close','8'),'minus_1':('close','1'),
       'plus_1':('create','1'),'plus_8':('create','8'),'plus_64':('create','64'),
       'preset_1':('pages','1'),'preset_8':('pages','8'),'preset_16':('pages','16'),
       'preset_32':('pages','32'),'preset_64':('pages','64'),'minimum':('info','MN'),
       'total_buy':('definition','B'),'total_sell':('definition','S'),'confirm':('create','OK'),
       'unavailable':('close','!'),'back':('back','<'),'maximum':('pages','MX')}


def save(name,base,label):
    image=art(base);d=ImageDraw.Draw(image);font=ImageFont.load_default();d.rounded_rectangle((17,20,29,29),radius=2,fill='#234050',outline='#a6e7ed')
    box=d.textbbox((0,0),label,font=font);w=box[2]-box[0];h=box[3]-box[1];d.text((23-w//2,24-h//2-box[1]),label,font=font,fill='#fff2cb')
    path='menu/quantity/'+name;ref='chrdk_diamondshop:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);image.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name,(base,label) in ICONS.items():save(name,base,label)
    print(f'Generated {len(ICONS)} quantity icons')
