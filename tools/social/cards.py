"""Простые детерминированные PNG-карточки, без генеративных сервисов и ключей."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from content import SafetyError

LABELS = {'news': 'НОВОСТИ ОБНОВЛЕНИЙ', 'tip': 'СОВЕТ ИГРОКУ',
          'feature': 'ВОЗМОЖНОСТИ СЕРВЕРА', 'reminder': 'ПОЛЕЗНОЕ НАПОМИНАНИЕ'}
FONT = Path('/usr/share/fonts/truetype/dejavu')


def font(size, bold=False):
    return ImageFont.truetype(str(FONT / ('DejaVuSans-Bold.ttf' if bold else 'DejaVuSans.ttf')), size)


def lines(draw, text, face, width):
    result = ['']
    for word in text.split():
        if draw.textlength(word, font=face) > width:
            raise SafetyError('Слово не помещается на карточке')
        candidate = (result[-1] + ' ' + word).strip()
        if draw.textlength(candidate, font=face) > width:
            result.append(word)
        else:
            result[-1] = candidate
    return result


def render(post, path):
    image = Image.new('RGB', (1280, 720), '#101d29')
    d = ImageDraw.Draw(image)
    accent = '#ffc978' if post.kind == 'news' else '#81e5c2'
    for x in range(0, 1280, 40):
        for y in range(0, 720, 40):
            if (x // 40 + y // 40) % 3 == 0:
                d.rectangle((x, y, x + 38, y + 38), fill='#142330')
    d.rectangle((0, 0, 16, 720), fill=accent)
    d.text((64, 45), 'CHRDK', font=font(32, True), fill='#f5f1e9')
    d.text((64, 139), LABELS[post.kind], font=font(21, True), fill=accent)
    # Пиксельный алмаз — геометрия, не чужой игровой ресурс.
    cx, cy, s = 1060, 338, 34
    for row, cols in enumerate(((2, 3), (1, 2, 3, 4), (0, 1, 2, 3, 4, 5), (1, 2, 3, 4), (2, 3))):
        for col in cols:
            x, y = cx + (col - 3) * s, cy + (row - 2) * s
            d.rectangle((x, y, x+s-3, y+s-3), fill=accent if col >= row else '#439d93')
    title_font = font(56, True)
    title = lines(d, post.title, title_font, 810)
    if len(title) > 3:
        title_font = font(46, True)
        title = lines(d, post.title, title_font, 810)
    caption_font = font(27)
    caption = lines(d, post.caption, caption_font, 800)
    if len(title) > 3 or len(caption) > 3:
        raise SafetyError('Текст переполняет карточку: сократите заголовок/подпись')
    y = 205
    for line in title:
        d.text((60, y), line, font=title_font, fill='#f5f1e9'); y += 72
    y = max(425, y + 15)
    for line in caption:
        d.text((64, y), line, font=caption_font, fill='#b5c6cf'); y += 39
    if y > 590:
        raise SafetyError('Подпись перекрывает подвал карточки')
    d.line((64, 620, 1216, 620), fill='#354653', width=2)
    d.text((64, 649), 'ВЫЖИВАНИЕ  /  РЕМЕСЛО  /  СВОЯ ИСТОРИЯ', font=font(18), fill='#99afbd')
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format='PNG', optimize=True)
