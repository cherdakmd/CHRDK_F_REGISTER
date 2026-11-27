"""Generate virtual DiamondShop administrative controls, not product cards."""
import json
from pathlib import Path
from PIL import ImageDraw,ImageFont
from generate_core_honor import art
ROOT=Path(__file__).resolve().parents[1]/'resource-pack/assets/chrdk_diamondshop'
ICONS={'empty':('empty_definitions','-'),'close':('close','X'),'save':('create','SV'),'prev':('prev','<'),'next':('next','>'),'back':('back','<'),
       'restore':('refresh','R'),'remove':('close','RM'),'remove_confirm':('delete','!!'),
       'price_down_small':('close','25'),'price_down':('close','1'),'price_up':('create','1'),'price_up_small':('create','25'),
       'stock_down':('close','ST'),'stock_up':('create','ST'),'sell_enabled':('create','S'),'sell_disabled':('close','S'),
       'category':('filter','CT'),'tariff_down':('close','T'),'tariff_up':('create','T')}


def save(name,base,label):
    image=art(base);d=ImageDraw.Draw(image);font=ImageFont.load_default();d.rounded_rectangle((17,20,29,29),radius=2,fill='#234050',outline='#a6e7ed')
    box=d.textbbox((0,0),label,font=font);w=box[2]-box[0];h=box[3]-box[1];d.text((23-w//2,24-h//2-box[1]),label,font=font,fill='#fff2cb')
    path='menu/admin/'+name;ref='chrdk_diamondshop:item/'+path
    target=ROOT/'textures/item'/(path+'.png');target.parent.mkdir(parents=True,exist_ok=True);image.save(target)
    for folder,data in [('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':ref}}),('items',{'model':{'type':'minecraft:model','model':ref}})]:
        target=ROOT/folder/(path+'.json');target.parent.mkdir(parents=True,exist_ok=True);target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')


if __name__=='__main__':
    for name,(base,label) in ICONS.items():save(name,base,label)
    print(f'Generated {len(ICONS)} shop admin icons')
