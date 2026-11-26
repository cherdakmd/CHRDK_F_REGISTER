"""Draw original 32px artifact emblems and item models. Requires Pillow.

Every ID has an explicitly chosen silhouette, colour and small pictogram; adding
an artifact to the registry without an art direction fails generation.
"""
import json
import re
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
PACK = ROOT / 'custom-items/resource-pack/assets/chrdk_artifacts'
REGISTRY = ROOT / 'artifacts/src/main/java/ru/cherdakmd/chrdkartifacts/ArtifactRegistry.java'
# id: (main silhouette, pigment, small emblem). The silhouette reflects the name.
ART = {
 'solnechny-obereg':('sun','#f8c568','shield'), 'zhabii-kamen':('stone','#83bf8d','frog'),
 'dyhanie-gor':('feather','#b9d9e3','mountain'), 'nochnoj-svetlyak':('bug','#ede69b','star'),
 'kamen-stojkosti':('stone','#969bc4','shield'), 'vetryanaya-spitsa':('spinner','#e6dbc3','wind'),
 'koren-zhizni':('roots','#bca17b','heart'), 'ruka-rudokopa':('pick','#dfaa70','hand'),
 'grozovoj-kolokol':('bell','#edc774','bolt'), 'rog-tajgi':('horn','#d0bb8a','tree'),
 'plamya-ochaga':('flame','#e79a61','home'), 'kaplya-zhizni':('drop','#e77985','heart'),
 'klyuch-strannika':('key','#ead08b','star'), 'linza-providesa':('eye','#b9a7ed','star'),
 'hartiya-char':('scroll','#e6d29e','spark'), 'sleza-vdovy':('drop','#b5b2d5','moon'),
 'golodnyj-koren':('roots','#a0837b','skull'), 'smolyanoj-mrak':('drop','#5e526d','moon'),
 'krivoe-zerkalo':('mirror','#aaa4bc','crack'), 'rzhavyj-gvozd':('nail','#a06d63','crack'),
 'glinyanaya-dusha':('face','#ba9984','soul'), 'pepel-pogroma':('ash','#a68b8c','flame'),
 'sushonyj-glaz':('eye','#ab8b75','crack'), 'chernaya-moneta':('coin','#797087','skull'),
 'svistok-kostej':('bone','#e4d5be','wind'), 'membrana-prizraka':('shroud','#aaaac7','soul'),
 'rakovina-toski':('shell','#b798b5','moon'), 'zhalo-moroki':('sting','#b28cbd','skull'),
 'chasy-duroshljopa':('clock','#d1a681','crack'), 'porvannyj-kupon':('ticket','#b59683','crack'),
 'pero-feniksa':('feather','#f5a454','flame'), 'magnit-opyta':('magnet','#8acacb','spark'),
 'zhnej-dush':('scythe','#88bdce','soul'), 'ledyanaya-aura':('snow','#9cdaef','spark'),
 'ternistyj-plasch':('cloak','#85b489','thorn'), 'travjanoj-eliksir':('flask','#a7cf84','leaf'),
 'sapogi-vetra':('boot','#d8c896','wind'), 'klever-udachi':('clover','#8dcc83','star'),
 'travyanoj-meshochek':('pouch','#c6b07d','leaf'), 'serdtse-drakona':('heart','#e67b6e','flame'),
 'svitok-ochischeniya':('scroll','#ded8bc','spark'), 'totem-krovi':('totem','#c88f80','drop'),
 'sfera-pobega':('orb','#91b6d8','wind'), 'ledyanaya-stena':('wall','#9cd8e7','snow'),
 'glaz-nachala':('eye','#a7dbb9','spark'), 'korona-bezdny':('crown','#b4a2dd','star'),
 'ender-sdvig':('orb','#bc9ddd','spark'), 'oskolok-vechnosti':('crystal','#f0da9e','star'),
 'okov-tyazhesti':('chain','#a39faa','stone'), 'pustoy-zhivot':('pouch','#8b7e8a','skull'),
 'slabye-kisti':('hand','#b6a09c','crack'), 'krovavyj-svyaz':('chain','#b2797f','drop'),
 'smertnyj-potok':('wave','#939bb7','skull'), 'chernye-slezy':('drop','#6e6b85','soul'),
 'ruga-zhadnosti':('coin','#9b89ba','skull'), 'prah-marka':('ash','#96857f','skull'),
 'koltso-usmerti':('ring','#ac879a','skull'), 'kniga-proklyatiy':('book','#a78aab','skull'),
 'zhertvennyj-kubok':('cup','#ba8584','drop'), 'raskolotaya-luna':('moon','#b6acc8','crack'),
 'shlyapa-bezdelnika':('hat','#a692a4','moon'), 'silok-dushi':('noose','#ae9cb5','soul'),
 'oblaka-durmana':('cloud','#bca3b8','skull'), 'klyuch-propasti':('key','#9588b4','moon'),
 'svist-gadov':('snake','#a1b28f','wind'),
}


def symbol(d, name, c):
    dark = '#292638'
    def ln(pts,w=2): d.line(pts,fill=c,width=w,joint='curve')
    def pg(pts): d.polygon(pts,fill=c)
    def rc(box,w=2): d.rectangle(box,outline=c,width=w)
    def el(box,w=2): d.ellipse(box,outline=c,width=w)
    if name == 'sun':
        el((11,11,20,20),3)
        for a,b in [((15,6),(15,9)),((15,22),(15,25)),((6,15),(9,15)),((22,15),(25,15)),((9,9),(11,11)),((20,20),(22,22)),((20,10),(22,8)),((8,22),(10,20))]: ln([a,b])
    elif name in ('stone','crystal'):
        pg([(15,6),(22,11),(23,18),(17,25),(9,22),(7,15),(10,9)]); d.line([(15,8),(15,22),(9,15),(22,13)],fill=dark)
    elif name == 'feather':
        pg([(8,23),(9,15),(15,8),(23,6),(21,14),(17,18)]); d.line((9,23,20,10),fill=dark,width=1)
    elif name == 'bug':
        el((11,12,20,22),2); ln([(13,11),(10,8)]); ln([(18,11),(21,8)]); ln([(9,14),(7,12)]); ln([(22,14),(24,12)]); ln([(9,19),(7,21)]); ln([(22,19),(24,21)])
    elif name == 'spinner':
        el((11,11,20,20),2); ln([(15,7),(15,24)]); ln([(7,15),(24,15)]); ln([(9,9),(22,22)])
    elif name == 'roots':
        ln([(15,7),(15,18),(11,23)] ,3); ln([(15,15),(9,11),(7,9)]); ln([(15,17),(21,11),(23,8)]); ln([(15,18),(20,23)])
    elif name == 'pick':
        ln([(9,23),(19,10)],3); ln([(9,11),(14,8),(20,8),(23,11)],3)
    elif name == 'bell':
        pg([(12,9),(14,7),(18,7),(20,9),(20,17),(23,20),(9,20),(12,17)]); el((14,20,18,24),2)
    elif name == 'horn':
        pg([(7,18),(10,12),(18,8),(23,8),(20,12),(15,15),(12,20)]); ln([(9,20),(12,22)],3)
    elif name == 'flame': pg([(15,6),(17,12),(20,10),(23,17),(21,22),(17,25),(11,24),(8,20),(10,13),(12,16)])
    elif name == 'drop': pg([(15,6),(10,13),(8,18),(10,23),(15,25),(21,23),(23,18),(20,13)])
    elif name == 'key':
        el((8,7,16,15),3); ln([(15,14),(22,21)],3); ln([(18,17),(21,15)]); ln([(20,19),(23,17)])
    elif name == 'eye':
        pg([(6,16),(10,11),(15,9),(21,11),(25,16),(21,21),(15,23),(10,21)]); d.ellipse((13,12,18,20),fill=dark); d.point((16,14),fill='#fff2d6')
    elif name in ('scroll','ticket','book'):
        pg([(8,8),(20,8),(23,11),(23,22),(20,24),(8,24),(10,21),(10,10)]); d.line((12,12,20,12),fill=dark); d.line((12,16,19,16),fill=dark); d.line((12,20,17,20),fill=dark)
        if name == 'book': d.line((10,9,10,23),fill=dark,width=2)
        if name == 'ticket': d.line((14,8,18,14,14,18,18,24),fill=dark,width=1)
    elif name == 'mirror':
        el((8,6,22,20),3); ln([(14,20),(11,24),(18,24)],3)
    elif name == 'nail': ln([(10,22),(20,9)],3); ln([(17,7),(22,11)],3)
    elif name == 'face':
        pg([(10,8),(20,8),(23,12),(22,21),(18,24),(12,24),(8,20),(8,12)]); d.rectangle((11,13,13,15),fill=dark); d.rectangle((18,13,20,15),fill=dark); d.line((13,20,18,20),fill=dark)
    elif name == 'ash':
        pg([(7,23),(8,19),(11,20),(12,15),(15,18),(18,14),(20,20),(23,19),(24,23)]); d.point((14,10),fill=c); d.point((19,8),fill=c); d.point((10,12),fill=c)
    elif name == 'coin':
        el((7,7,24,24),3); el((11,11,20,20),2)
    elif name == 'bone':
        ln([(10,21),(21,10)],4); el((7,19,11,23),2); el((19,7,23,11),2); el((7,21,11,25),2); el((21,7,25,11),2)
    elif name == 'shroud': pg([(7,9),(13,7),(20,8),(24,13),(21,19),(23,24),(18,22),(15,25),(12,22),(8,24),(9,18),(6,14)])
    elif name == 'shell':
        el((8,8,23,23),3); d.arc((11,11,20,20),30,315,fill=dark,width=2)
    elif name == 'sting':
        pg([(8,24),(12,15),(21,7),(24,7),(22,13),(16,20)]); d.line((10,22,20,11),fill=dark)
    elif name == 'clock':
        el((8,8,23,23),3); ln([(15,10),(15,16),(19,18)]); ln([(13,6),(18,6)],2)
    elif name == 'magnet':
        ln([(9,9),(9,18),(12,22),(18,22),(21,18),(21,9)],4); d.line((7,12,11,12),fill=dark,width=2); d.line((19,12,23,12),fill=dark,width=2)
    elif name == 'scythe':
        ln([(10,24),(18,8)],3); ln([(18,8),(23,9),(25,14),(21,12),(17,13)],2)
    elif name == 'snow':
        ln([(15,7),(15,24)]); ln([(7,15),(23,15)]); ln([(9,9),(21,21)]); ln([(21,9),(9,21)])
    elif name == 'cloak': pg([(11,7),(20,7),(23,12),(21,24),(9,24),(7,12)]); d.line((12,9,19,9),fill=dark)
    elif name == 'flask':
        pg([(13,7),(18,7),(18,12),(22,18),(21,24),(10,24),(9,18),(13,12)]); d.line((11,19,20,19),fill=dark)
    elif name == 'boot': pg([(10,7),(17,7),(17,18),(22,19),(23,24),(9,24),(9,20)])
    elif name == 'clover':
        for box in [(9,8,16,15),(15,8,22,15),(9,14,16,21),(15,14,22,21)]: d.ellipse(box,fill=c)
        ln([(15,18),(13,25)])
    elif name == 'pouch':
        pg([(11,7),(20,7),(18,11),(22,15),(22,23),(19,25),(11,25),(8,23),(8,15),(12,11)]); d.line((11,12,20,12),fill=dark,width=2)
    elif name == 'heart': pg([(15,24),(7,16),(7,11),(10,8),(13,8),(15,10),(17,8),(20,8),(23,11),(23,16)])
    elif name == 'totem':
        el((12,6,19,12),2); pg([(7,12),(24,12),(21,17),(19,17),(20,24),(11,24),(12,17),(9,17)])
    elif name == 'orb':
        el((7,7,24,24),3); ln([(11,18),(13,11),(19,10)],1)
    elif name == 'wall':
        rc((7,8,23,24),2); ln([(7,15),(23,15)]); ln([(15,8),(15,15)]); ln([(11,15),(11,24)]); ln([(19,15),(19,24)])
    elif name == 'crown': pg([(7,9),(11,13),(15,7),(19,13),(24,9),(21,23),(10,23)])
    elif name == 'chain':
        el((7,7,16,18),3); el((15,13,24,24),3); ln([(13,18),(18,13)],3)
    elif name == 'hand':
        pg([(8,15),(10,14),(10,8),(12,8),(13,14),(14,7),(16,7),(16,14),(18,9),(20,10),(19,16),(22,14),(23,17),(19,24),(11,24),(8,20)])
    elif name == 'wave':
        ln([(7,11),(11,9),(15,11),(19,9),(24,11)],3); ln([(7,16),(11,14),(15,16),(19,14),(24,16)],3); ln([(7,21),(11,19),(15,21),(19,19),(24,21)],3)
    elif name == 'ring':
        el((7,7,24,24),4); d.polygon([(15,5),(19,8),(15,11),(11,8)],fill=c)
    elif name == 'cup':
        pg([(8,8),(23,8),(22,16),(19,19),(17,20),(17,23),(21,23),(21,25),(10,25),(10,23),(14,23),(14,20),(11,18),(9,15)])
    elif name == 'moon':
        d.ellipse((8,7,24,24),fill=c); d.ellipse((15,4,27,19),fill='#342d44')
    elif name == 'hat': pg([(12,8),(20,8),(22,19),(25,21),(25,23),(6,23),(6,21),(9,19)])
    elif name == 'noose':
        el((9,11,21,24),3); ln([(15,11),(15,7),(21,7)],3)
    elif name == 'cloud':
        for box in [(7,14,16,22),(11,10,20,22),(17,13,24,22)]: d.ellipse(box,fill=c)
    elif name == 'snake':
        ln([(9,23),(18,23),(21,20),(19,17),(12,17),(10,14),(12,10),(18,9),(21,11)],4); d.point((18,10),fill=dark)
    else: raise ValueError(name)


def emblem(d, name):
    # 7px handwritten glyphs on a small dark seal, not letters or external fonts.
    glyphs = {
      'star':[(26,20),(27,23),(30,24),(27,25),(26,28),(25,25),(22,24),(25,23)],
      'spark':[(26,20),(27,23),(30,24),(27,25),(26,28),(25,25),(22,24),(25,23)],
      'shield':[(23,21),(29,21),(29,25),(26,28),(23,25)],
      'frog':[(23,22),(24,20),(25,22),(27,22),(28,20),(29,22),(29,27),(23,27)],
      'mountain':[(22,27),(25,21),(27,24),(28,22),(30,27)],
      'wind':[(22,23),(28,23),(29,22),(28,21),(27,22),(22,26),(27,26),(28,27)],
      'heart':[(26,28),(22,24),(23,22),(25,22),(26,23),(27,22),(29,22),(30,24)],
      'hand':[(23,27),(23,23),(24,23),(24,21),(25,21),(25,23),(26,20),(27,20),(27,23),(28,22),(29,23),(29,26),(27,28)],
      'bolt':[(27,20),(23,25),(26,25),(25,28),(29,23),(26,23)],
      'tree':[(26,20),(22,25),(24,25),(22,27),(30,27),(28,25),(30,25)],
      'home':[(22,24),(26,20),(30,24),(29,24),(29,28),(23,28),(23,24)],
      'moon':[(28,20),(25,21),(24,23),(24,26),(27,28),(29,27),(26,27),(25,25),(26,22)],
      'skull':[(23,21),(29,21),(30,23),(29,26),(28,26),(28,28),(24,28),(24,26),(22,25),(22,23)],
      'crack':[(27,20),(24,24),(27,24),(24,28),(28,24),(26,23)],
      'soul':[(26,20),(29,22),(29,26),(27,28),(25,27),(23,28),(23,23)],
      'flame':[(26,20),(28,23),(29,26),(27,28),(24,28),(23,26),(24,23),(25,25)],
      'leaf':[(23,27),(23,23),(26,21),(29,21),(29,24),(27,27)],
      'thorn':[(22,27),(25,22),(26,25),(29,21),(27,27)],
      'drop':[(26,20),(23,24),(23,26),(25,28),(28,28),(29,26),(29,24)],
      'snow':[(25,21),(27,21),(27,23),(29,22),(30,24),(28,25),(30,27),(28,28),(27,26),(27,29),(25,29),(25,26),(23,28),(22,26),(24,25),(22,24),(23,22),(25,23)],
      'stone':[(23,23),(25,21),(28,21),(30,24),(29,27),(25,28),(22,26)],
    }
    d.ellipse((20,19,31,30),fill='#252435',outline='#b9a69b')
    d.polygon(glyphs[name],fill='#fff0cc')
    if name == 'skull':
        d.point((24,23),fill='#252435'); d.point((28,23),fill='#252435')


source = REGISTRY.read_text(encoding='utf-8')
entries = re.findall(r'register\("([a-z0-9-]+)",\s*(GOOD|CURSED),\s*(ACTIVE|PASSIVE),\s*Material\.[A-Z_]+', source)
assert len(entries) == 65 and {x[0] for x in entries} == set(ART), 'Artifact catalogue and art differ'
for artifact_id, side, mode in entries:
    motif, pigment, accent = ART[artifact_id]
    im = Image.new('RGBA',(32,32)); d = ImageDraw.Draw(im)
    frame = '#d8bd83' if side == 'GOOD' else '#a78ab8'
    d.polygon([(11,2),(21,2),(27,8),(29,16),(26,24),(20,29),(11,29),(5,24),(2,16),(5,8)], fill='#1d1c2b')
    d.polygon([(11,4),(20,4),(25,9),(27,16),(24,23),(19,27),(12,27),(7,23),(4,16),(7,9)], fill='#342d44', outline=frame)
    if mode == 'ACTIVE': d.point((16,5),fill='#fff1c7')
    symbol(d,motif,pigment); emblem(d,accent)
    path = 'artifact/' + artifact_id
    target = PACK / 'textures/item' / (path + '.png'); target.parent.mkdir(parents=True,exist_ok=True); im.save(target)
    for folder,data in [
      ('models/item',{'parent':'minecraft:item/generated','textures':{'layer0':'chrdk_artifacts:item/'+path}}),
      ('items',{'model':{'type':'minecraft:model','model':'chrdk_artifacts:item/'+path}})]:
        target = PACK / folder / (path+'.json'); target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
print(f'Generated {len(entries)} artifact emblems')
