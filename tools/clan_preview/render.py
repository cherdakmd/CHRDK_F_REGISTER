#!/usr/bin/env python3
"""PNG/GIF from Java-exported RGB, not an actual Minecraft-client screenshot."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / 'guilds/src/test/resources/clan-animation-preview.json'
SOURCE = ROOT / 'guilds/src/main/java/ru/cherdakmd/chrdkguilds/GuildStyleAnimation.java'
EFFECTS = {
    'aurora': ('ПЕРЕЛИВ', 'Цветные ленты + ледяной гребень', '6 секунд', '#34eef2'),
    'shine': ('ЗОЛОТОЙ БЛИК', 'Ширина адаптируется к длине тега', '6 секунд', '#ffca56'),
    'pulse': ('ДЫХАНИЕ', '2 с усиление / 4 с затухание', '6 секунд', '#e4a5ff'),
    'prism': ('ПРИЗМА', 'Сглаженный движущийся спектр', '6 секунд', '#7ba7ff'),
    'flame': ('ПЛАМЯ', 'Две волны жара / три оттенка', '4 секунды', '#ff9154'),
}
FONT_DIR = Path('/usr/share/fonts/truetype/dejavu')

def load():
    data = json.loads(FIXTURE.read_text())
    if data.get('schema') != 1 or data.get('sourceSha256') != hashlib.sha256(SOURCE.read_bytes()).hexdigest():
        raise ValueError('Stale Java frame fixture; export it again, do not edit colors by hand')
    if data.get('stepMillis') != 250 or data.get('tag') != 'СЕВЕР' or list(data.get('frames', {})) != list(EFFECTS):
        raise ValueError('Invalid preview contract')
    for frames in data['frames'].values():
        if len(frames) != 48 or any(len(row) != len(data['tag']) for row in frames):
            raise ValueError('Invalid frame dimensions')
        if any(type(c) is not int or not 0 <= c <= 0xffffff for row in frames for c in row):
            raise ValueError('Invalid RGB')
    return data

def font(size, bold=False, mono=False):
    return ImageFont.truetype(str(FONT_DIR / ('DejaVuSansMono' if mono else 'DejaVuSans')
                                  ) + ('-Bold' if bold else '') + '.ttf', size)

def text(draw, xy, value, size=20, color='#b2c0d6', bold=False):
    draw.text(xy, value, font=font(size, bold), fill=color)

def tagged(draw, x, y, tag, colors, size=23):
    f = font(size, True, True)
    for phrase, color in [('Alex · ', '#e8edf8'), ('[', '#6de797'), ('⚜ ', '#ffcc55')]:
        draw.text((x,y), phrase, font=f, fill=color); x += draw.textlength(phrase,font=f)
    for char, color in zip(tag, colors):
        # Subtle vanilla-like text shadow, no invented glow obscuring the actual RGB.
        draw.text((x+1,y+2), char, font=f, fill='#080d17')
        draw.text((x,y), char, font=f, fill=f'#{color:06x}'); x += draw.textlength(char,font=f)
    draw.text((x,y), ']', font=f, fill='#6de797')

def scene(data, tick=8, timeline=False):
    image = Image.new('RGB',(1440,1050),'#0b1020'); d=ImageDraw.Draw(image)
    d.rounded_rectangle((28,24,1412,1026),radius=24,fill='#101a2c',outline='#263b58',width=2)
    text(d,(60,52),'CHERDAK  /  CLAN MOTION',18,'#63e2df',True)
    text(d,(60,86),'Пять характеров. Один клановый тег.',38,'#f2f6ff',True)
    text(d,(60,143),'СКРИН-ПРЕВЬЮ · НЕ СКРИНШОТ MINECRAFT-КЛИЕНТА',17,'#ffce78',True)
    text(d,(60,178),'Реальные RGB из Java-рендерера · 250 мс между кадрами · без смены букв',18)
    starts = (0,6,15) if not timeline else (tick,(tick+6)%48,(tick+15)%48)
    for x,index in zip((386,725,1064),starts):
        text(d,(x,232),f'{index * 0.25:04.2f} СЕК',16,'#8da7cb',True)
    for row,(effect,(title,desc,period,accent)) in enumerate(EFFECTS.items()):
        top=272+row*124
        d.rounded_rectangle((49,top,1391,top+108),radius=12,fill='#17243a',outline='#263a55')
        d.rounded_rectangle((49,top+14,54,top+94),radius=2,fill=accent)
        text(d,(72,top+16),title,21,accent,True)
        text(d,(72,top+50),desc,14)
        text(d,(72,top+76),f'{effect}  /  {period}',14,'#7d95b7')
        for x,index in zip((386,725,1064),starts):
            tagged(d,x,top+36,data['tag'],data['frames'][effect][index])
    d.line((60,927,1380,927),fill='#2b405e',width=1)
    text(d,(60,950),'Статусная рамка остаётся зелёной. Ник не перекрашивается.',18,'#c5d5ea')
    text(d,(60,981),'В игре действует один выбранный эффект · 449 ₽ / 30 дней · лицензии без изменений',16,'#8da7cb')
    if timeline:
        d.rectangle((60,1011,60+int(1320*(tick+1)/48),1015),fill='#59d9e0')
    return image

def render(destination):
    data=load(); destination=Path(destination); destination.mkdir(parents=True,exist_ok=True)
    png=destination/'clan-animations-rc14.png'; gif=destination/'clan-animations-rc14.gif'
    scene(data).save(png,optimize=True)
    frames=[scene(data,i,True).quantize(colors=192) for i in range(48)]
    frames[0].save(gif,save_all=True,append_images=frames[1:],duration=250,loop=0,disposal=2,optimize=False)
    print(png); print(gif)
    return png,gif

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',default=str(ROOT/'build/clan-preview'))
    render(p.parse_args().output)
