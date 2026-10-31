#!/usr/bin/env python3
"""Export original emotion template data from local Hermes bytecode.
Uses hermes-dec solely as a bytecode reader; interprets an allowlist of literal
construction instructions, never executes recovered JS. Python environment:
analysis/netease-9.5.40/hermes-venv/bin/python tools/audioeffects/export_emotion_templates.py
"""
import hashlib,json,re
from collections import Counter
from pathlib import Path
from hermes_dec.parsers.hbc_file_parser import HBCReader
from hermes_dec.parsers.serialized_literal_parser import unpack_slp_array,TagType
REPO=Path(__file__).resolve().parents[2]
ROOT=REPO/'analysis/netease-9.5.40'
OUT=REPO/'src/main/resources/tritium/audioeffects/emotion'
h=HBCReader()
with (ROOT/'js-kernel/unpacked/js-kernel.hbc').open('rb') as f:h.read_whole_file(f)
dis=(ROOT/'js-kernel/kernel.disasm.txt').read_text()
def literals(data,count):
    out=[]
    for x in unpack_slp_array(data,count,h.header.version).items:
        if x.tag_type in (TagType.LongStringTag,TagType.ShortStringTag,TagType.ByteStringTag):
            out.append(h.strings[x.value])
        elif x.tag_type==TagType.IntegerTag:
            out.append(x.value if x.value < 2**31 else x.value-2**32)
        else:out.append(x.value)
    return out

def template(module,function,start=0,constant_env=False):
    a=dis.index(f'=> [Function #{function} ')
    b=dis.index('\n===============',a)
    section=dis[a:b]
    # A targeted decoder; no artifact JS is executed.
    regs={3:{}, 0:None, 1:{}}; mod={}; signed=[]; env={}
    frame=int(re.search(r"frame size=(\d+)",section).group(1))
    for line in section.splitlines():
        m=re.match(r'==> ([0-9a-f]+): <(\w+)>: <(.*?)>',line)
        if not m:continue
        offset,op,args=m.groups()
        if int(offset,16)<start:continue
        arg=[int(v) for v in re.findall(r'(?:Reg8|UInt\d+|string_id): (\d+)',args)]
        if op=='NewObject':regs[arg[0]]={}
        elif op=='NewArray':regs[arg[0]]=[None]*arg[1]
        elif op in ('LoadConstTrue','LoadConstFalse'):regs[arg[0]]=op=='LoadConstTrue'
        elif op=='LoadConstUndefined':regs[arg[0]]=None
        elif op=='LoadConstZero':regs[arg[0]]=0
        elif op in ('LoadConstInt','LoadConstDouble','LoadConstUInt8'):
            regs[arg[0]]=float(re.search(r'(?:Imm32|Double|UInt8): (-?[\d.eE+]+)',args).group(1))
        elif op=='Mov':regs[arg[0]]=regs[arg[1]]
        elif op=='LoadConstString':regs[arg[0]]=h.strings[arg[1]]
        elif op=='LoadParam':
            assert arg[1]==5, (op,arg)
            regs[arg[0]]=mod
        elif op=='NewObjectWithBuffer':
            keys=literals(h.object_keys[arg[3]:],arg[2])
            vals=literals(h.object_values[arg[4]:],arg[2])
            regs[arg[0]]=dict(zip(keys,vals))
            for k,v,raw in zip(keys,vals,unpack_slp_array(h.object_values[arg[4]:],arg[2],h.header.version).items):
                if raw.tag_type==TagType.IntegerTag and raw.value>=2**31:
                    signed.append({'function':function,'instruction_offset':offset,'value_buffer_offset':arg[4],'field':k,'decompiler_unsigned':raw.value,'signed_int32':v})
        elif op=='NewArrayWithBuffer':regs[arg[0]]=literals(h.arrays[arg[3]:],arg[2])
        elif op in ('PutNewOwnByIdShort','PutNewOwnById','PutById'):
            regs[arg[0]][h.strings[arg[-1]]]=regs[arg[1]]
        elif op=='PutOwnByIndex':
            if isinstance(regs[arg[0]],list):
                while len(regs[arg[0]])<=arg[2]:regs[arg[0]].append(None)
            regs[arg[0]][arg[2]]=regs[arg[1]]
        elif op in ('StoreToEnvironment','StoreNPToEnvironment'):env[arg[1]]=regs[arg[2]]
        elif op=='TryGetById':
            name=h.strings[arg[-1]]
            assert name in ('Set','Map'),name
            regs[arg[0]]=name
        elif op=='GetByIdShort':
            assert h.strings[arg[-1]]=='prototype'
            regs[arg[0]]=regs[arg[1]]
        elif op=='CreateThis':regs[arg[0]]=[]
        elif op=='Construct':
            assert regs[arg[1]] in ('Set','Map')
            values=regs[frame-8]
            regs[arg[0]]=dict(values) if regs[arg[1]]=='Map' else list(dict.fromkeys(values))
        elif op=='SelectObject':regs[arg[0]]=regs[arg[2]]
        elif op=='Ret':break
        else:raise ValueError((op,line))
    return (env if constant_env else mod['exports']),signed

OUT.mkdir(parents=True,exist_ok=True)
emotions=['bitter','calm','confident','default','depressed','ecstatic','energetic','healing','joyful','lonely','nostalgic','passionate','relaxed','romantic','sad','sweet']
families=['folk-family','instrumental-family','pop-family','rhythm-family']
templates={};signed=[];modules={}
for e in emotions:
    for f in families:
        i=len(templates); key=e+':'+f
        value,fixes=template(875+i,9266+i)
        templates[key]=value; signed.extend(fixes); modules[key]=875+i
config=json.loads((ROOT/'extracted/assets/default_custom_config_IuRPVVmc3WWul9fT.json').read_text())
def locate(x,key):
    if isinstance(x,dict):
        if key in x:return x[key]
        for v in x.values():
            found=locate(v,key)
            if found is not None:return found
    return None
config=locate(config,'play#emotionEffect')
if isinstance(config,str):config=json.loads(config)
assert len(templates)==64 and len(config)==3
for name,obj in [('templates.json',templates),('config.json',config)]:
    (OUT/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
active=Counter(k for t in templates.values() for k,v in t.items() if isinstance(v,dict) and v.get('on') is True)
peq=Counter(str(f.get('type')) for t in templates.values() for f in t.get('peq',{}).get('f',[]) if f.get('on') is True)
manifest={'apk_version':'9.5.40','hbc_version':h.header.version,'hbc_sha256':hashlib.sha256((ROOT/'js-kernel/unpacked/js-kernel.hbc').read_bytes()).hexdigest(),'template_count':len(templates),'modules':modules,'signed_integer_corrections':signed,'active_module_counts':dict(active),'active_peq_type_counts':dict(peq),'source':'APK embedded rn_js-kernel Hermes; local offline REA extraction','warning':'Client bundle defaults; runtime remote config may differ.'}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'templates':len(templates),'active':dict(active),'peq':dict(peq),'signed_corrections':len(signed)},ensure_ascii=False))

# Classification distribution parameters, from a literal-only initializer tail.
classification,_=template(958,9464,0x148,True)
assert set(range(2,26)) <= classification.keys()
(OUT/'classification.json').write_text(json.dumps(classification,ensure_ascii=False,indent=2)+'\n')
# Export the genre maps from the bytecode literal objects themselves.
objects={}
for line in dis.splitlines():
    if 'NewObjectWithBuffer' in line and "1000: 'pop-family'" in line:
        args=[int(v) for v in re.findall(r'(?:Reg8|UInt\d+): (\d+)',line)]
        objects['genreFamilies']=dict(zip(literals(h.object_keys[args[3]:],args[2]),literals(h.object_values[args[4]:],args[2])))
        break
# Unicode tag mapping uses two ordered dictionaries: preferred business labels,
# then synonyms. Decode array constants from module960's initializer.
a=dis.index('=> [Function #9505 ');b=dis.index('\n===============',a)
part=dis[a:b]
start=int(re.search(r'==> ([0-9a-f]+): <TryGetById>.*?String: \'Set\'',part).group(1),16)
tags,_=template(960,9505,start,True)
objects.update({'ignoredTags':tags[0],'unmappedTags':tags[1],'preferredTags':tags[2],'synonymTags':tags[3]})
(OUT/'labels.json').write_text(json.dumps(objects,ensure_ascii=False,indent=2)+'\n')
multichannel,_=template(943,9375,0x47,True)
(OUT/'multichannel.json').write_text(json.dumps(multichannel[2],ensure_ascii=False,indent=2)+'\n')

manifest['resources_sha256']={name:hashlib.sha256((OUT/name).read_bytes()).hexdigest() for name in ('templates.json','config.json','classification.json','labels.json','multichannel.json')}
manifest['classification_source']={'module':958,'function':9464,'literal_initializer_offset':'0x148'}
manifest['labels_source']={'modules':[945,960,961],'chinese_mapping_function':9505}
manifest['multichannel_source']={'module':943,'function':9375,'literal_initializer_offset':'0x47'}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
