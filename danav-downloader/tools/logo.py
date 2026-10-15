# Danav logo: flat red square, white "D" outline with a play triangle inside.
from PIL import Image, ImageDraw
def draw(size, rounded=True):
    S=size*8; u=S/48
    im=Image.new('RGBA',(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
    if rounded: d.rounded_rectangle([0,0,S-1,S-1], radius=int(8*u), fill=(220,38,38,255))
    else: d.rectangle([0,0,S,S], fill=(220,38,38,255))
    w=4.4*u; W=(255,255,255,255)
    # D: left bar, top/bottom straight, semicircle
    x0,y0,y1,xs,r=15*u,13.5*u,34.5*u,22.5*u,10.5*u
    d.arc([xs-r,y0,xs+r,y1],-90,90,fill=W,width=int(w))
    # thicken arc centered on path: arc draws inward; redo with offset box
    im2=Image.new('RGBA',(S,S),(0,0,0,0)); d2=ImageDraw.Draw(im2)
    d2.arc([xs-r-w/2,y0-w/2,xs+r+w/2,y1+w/2],-90,90,fill=W,width=int(w))
    d2.line([(x0,y0),(xs,y0)],fill=W,width=int(w)); d2.line([(x0,y1),(xs,y1)],fill=W,width=int(w)); d2.line([(x0,y0),(x0,y1)],fill=W,width=int(w))
    for (cx,cy) in [(x0,y0),(x0,y1)]: d2.ellipse([cx-w/2,cy-w/2,cx+w/2,cy+w/2],fill=W)
    d2.polygon([(20.5*u,19.6*u),(20.5*u,28.4*u),(27.5*u,24*u)],fill=W)
    base=Image.new('RGBA',(S,S),(0,0,0,0)); bd=ImageDraw.Draw(base)
    if rounded: bd.rounded_rectangle([0,0,S-1,S-1], radius=int(8*u), fill=(220,38,38,255))
    base.alpha_composite(im2)
    return base.resize((size,size),Image.LANCZOS)
if __name__=='__main__':
    import sys
    for k,v in {'mdpi':48,'hdpi':72,'xhdpi':96,'xxhdpi':144,'xxxhdpi':192}.items():
        draw(v).save(f'mipmap-{k}/ic_launcher.png')
    draw(512).convert('RGB').save('/home/user/shots/danav_logo.jpg')
