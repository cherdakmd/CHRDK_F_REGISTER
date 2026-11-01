"""Игровые постеры: утверждённая иллюстрация, крупный заголовок и минимум текста."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps
from content import SafetyError

LABELS = {'news': 'Новости CHERDAK', 'tip': 'Полезный совет',
          'feature': 'Открой возможности', 'reminder': 'Не забудь о главном'}
FONT = Path('/usr/share/fonts/truetype/dejavu')
BACKGROUND = Path(__file__).resolve().parent / 'assets/world-background.jpg'


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
    if not BACKGROUND.is_file():
        raise SafetyError('Нет утверждённого фона постера')
    with Image.open(BACKGROUND) as source:
        image = ImageOps.fit(source.convert('RGB'), (1280, 720)).convert('RGBA')
    # Иллюстрация остаётся яркой справа; текст читается на естественной тени слева.
    shade = Image.new('RGBA', image.size)
    gradient = ImageDraw.Draw(shade)
    for x in range(1280):
        alpha = round(218 * max(0, 1-x/1160) ** .65)
        gradient.line((x, 0, x, 719), fill=(6, 24, 25, alpha))
    image = Image.alpha_composite(image, shade)
    d = ImageDraw.Draw(image)
    cream, accent = '#fff5df', '#ffd68a'
    d.text((62, 40), 'ЧЕРДАК', font=font(29, True), fill=cream,
           stroke_width=1, stroke_fill='#23312b')
    badge = LABELS[post.kind]
    badge_font = font(21, True)
    width = d.textlength(badge, font=badge_font)
    d.rounded_rectangle((62, 112, 94+width, 155), radius=21, fill='#f1cf8e')
    d.text((78, 120), badge, font=badge_font, fill='#24352d')
    title_font = font(65, True)
    title = lines(d, post.title, title_font, 770)
    if len(title) > 3:
        title_font = font(54, True)
        title = lines(d, post.title, title_font, 770)
    caption_font = font(27)
    caption = lines(d, post.caption, caption_font, 730)
    if len(title) > 3 or len(caption) > 3:
        raise SafetyError('Текст переполняет карточку: сократите заголовок/подпись')
    y = 194
    for line in title:
        d.text((58, y), line, font=title_font, fill=cream,
               stroke_width=2, stroke_fill='#20312b')
        y += 83
    y = max(420, y+24)
    for line in caption:
        d.text((64, y), line, font=caption_font, fill=cream,
               stroke_width=1, stroke_fill='#20312b')
        y += 39
    if y > 604:
        raise SafetyError('Подпись перекрывает подвал карточки')
    d.line((64, 645, 118, 645), fill=accent, width=3)
    d.text((132, 630), 'ТВОЙ МИР. ТВОЯ ИСТОРИЯ.', font=font(19, True), fill=cream,
           stroke_width=1, stroke_fill='#20312b')
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    image.convert('RGB').save(path, format='PNG', optimize=True)
