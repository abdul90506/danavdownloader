import sys, os
from PIL import Image
def sheet(names, out, cols=4):
    ims=[Image.open(f'/tmp/ui_{n}.png').convert('RGB') for n in names]
    w=360; ims=[i.resize((w,int(i.height*w/i.width))) for i in ims]
    h=max(i.height for i in ims); rows=(len(ims)+cols-1)//cols
    S=Image.new('RGB',(cols*w+(cols-1)*8,rows*h+(rows-1)*8),'#888')
    for k,i in enumerate(ims): S.paste(i,((k%cols)*(w+8),(k//cols)*(h+8)))
    S.save(os.path.expanduser(f'~/shots/{out}.jpg'),quality=80)
args=sys.argv[1:]
sheet(args[1:], args[0])
