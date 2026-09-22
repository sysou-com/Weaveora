#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ComfyUI 官方模板（subgraph 新格式）→ **我们自己的 API 格式工作流**（LTX-2.5 i2v / 生产档）
处理要点：
 1) widgets_values 按 object_info 的 widget 顺序做位置映射（含 'FLOAT,INT'、'COMFY_DYNAMICCOMBO_V3' 这类声明）
 2) 接线（link）优先于 widget 值；子图输入槽 → 父图来源
 3) 动态控件特例：ResizeImageMaskNode 的 resize_type 会派生 `longer_size`（API 里就是这个名字，实测确认）
 4) 去掉「提示词增强」支路（需额外的 gemma4_e2b 模型，我们暂不下载）→ 正词直接取 PrimitiveStringMultiline
 5) 把被引用的父图节点（LoadImage / ResolutionSelector）一并带进 API 图
用法: flatten_ltx25.py <模板json> <输出json> --info <object_info URL>
"""
import json, sys, urllib.request

TPL, OUT = sys.argv[1], sys.argv[2]
INFO = sys.argv[sys.argv.index('--info') + 1]
oi = json.load(urllib.request.urlopen(INFO, timeout=60))

LINKISH = {'IMAGE', 'MASK', 'LATENT', 'MODEL', 'CLIP', 'VAE', 'CONDITIONING', 'AUDIO', 'VIDEO', 'CONTROL_NET',
           'SAMPLER', 'SIGMAS', 'GUIDER', 'NOISE', 'CLIP_VISION', 'UPSCALE_MODEL', 'LATENT_UPSCALE_MODEL',
           'CLIP_VISION_OUTPUT', 'STYLE_MODEL', 'GLIGEN', 'HOOKS', 'LOADER', 'DICT', 'TUPLE', 'POINT', 'BBOX',
           'SEGS', 'DEPTH_MAP', 'WEBCAM', 'PHOTOMAKER', 'BASIC_PIPE', 'MULTI_MODEL', 'AUDIO_ENCODER'}

def is_widget(t):
    if isinstance(t, list):
        return True
    if t in ('INT', 'FLOAT', 'STRING', 'BOOLEAN'):
        return True
    if isinstance(t, str):
        if 'COMBO' in t:
            return True
        toks = [x.strip() for x in t.split(',')]
        if toks and all(x in ('INT', 'FLOAT', 'STRING', 'BOOLEAN') for x in toks):
            return True
    return False

def widget_names(cls):
    spec = oi.get(cls, {}).get('input', {})
    out = []
    for sect in ('required', 'optional'):
        for name, decl in (spec.get(sect) or {}).items():
            t = decl[0] if isinstance(decl, (list, tuple)) and decl else decl
            opts = decl[1] if isinstance(decl, (list, tuple)) and len(decl) > 1 and isinstance(decl[1], dict) else {}
            if opts.get('forceInput'):
                continue
            if is_widget(t):
                out.append(name)
    return out

d = json.load(open(TPL, encoding='utf-8'))
sg = d['definitions']['subgraphs'][0]

def pair(r):
    return (r['id'], r['origin_id'], r['origin_slot']) if isinstance(r, dict) else (r[0], r[1], r[2])

parent_links = {pair(l)[0]: (pair(l)[1], pair(l)[2]) for l in d['links']}
sgl = {pair(l)[0]: (pair(l)[1], pair(l)[2]) for l in sg['links']}
subnode = [n for n in d['nodes'] if n.get('type') == sg['id']][0]
slot_src = {}
for idx, inp in enumerate(subnode.get('inputs', [])):
    lid = inp.get('link')
    if lid is not None and lid in parent_links:
        slot_src[idx] = parent_links[lid]
link_to_slot = {}
for idx, si in enumerate(sg.get('inputs', [])):
    for lid in (si.get('linkIds') or []):
        link_to_slot[lid] = idx

def resolve(link):
    if link in link_to_slot:
        s = slot_src.get(link_to_slot[link])
        return [str(s[0]), s[1]] if s else None
    if link in sgl:
        return [str(sgl[link][0]), sgl[link][1]]
    return None

NOT_PLACEHOLDER = {'MarkdownNote', 'Note'}
DROP_IDS = {380, 382, 383}   # 仅丢「提示词增强」支路（TextGenerateLTX2Prompt + Switch + 它的布尔）；393 单独删
# ⚠️ 不要按类名丢 PrimitiveBoolean：363（Switch to Text to Video?）仍被 LTXVImgToVideoInplace.bypass 引用

api, missing, warn = {}, set(), []
sg_nodes = {n['id']: n for n in sg['nodes']}
for n in sg['nodes']:
    t = n.get('type')
    if t in NOT_PLACEHOLDER or n.get('mode') == 4 or n['id'] in DROP_IDS:
        continue
    if t not in oi:
        missing.add(t)
    names, wv = widget_names(t), list(n.get('widgets_values') or [])
    inputs = {}
    if t == 'ResizeImageMaskNode':           # 动态控件特例（API 名 = longer_size）
        # ★ 实测确认：动态控件在 API 里的键名是带点的 `resize_type.longer_size`（不是 longer_size）
        inputs = {'resize_type': wv[0] if len(wv) > 0 else 'scale longer dimension',
                  'resize_type.longer_size': wv[1] if len(wv) > 1 else 1536,
                  'scale_method': wv[2] if len(wv) > 2 else 'lanczos'}
    else:
        for k, nm in enumerate(names):
            if k < len(wv):
                inputs[nm] = wv[k]
        if len(wv) > len(names):
            warn.append('%s/%s 多出 %d 个值（多为前端控件，忽略）' % (n['id'], t, len(wv) - len(names)))
    for i in n.get('inputs', []):
        if i.get('link') is None:
            continue
        r = resolve(i['link'])
        if r:
            inputs[i['name']] = r
    api[str(n['id'])] = {'class_type': t, 'inputs': inputs}

# 去掉增强支路后，正词直接取 PrimitiveStringMultiline(376)
if '364' in api:
    api['364']['inputs']['text'] = ['376', 0]
if '393' in api:
    del api['393']          # e2b 提示词增强 LLM，不用

# 只保留 Enhancer 的 CLIPLoader(393) 已删；确认 364/373 用的是 387(12B)

# 把被引用的父图节点带进来（LoadImage / ResolutionSelector）
refs = set()
for v in list(api.values()) + [{'inputs': {}}]:
    for val in (v.get('inputs') or {}).values():
        if isinstance(val, list) and val and isinstance(val[0], str) and val[0] not in api:
            refs.add(val[0])
for n in d['nodes']:
    if str(n['id']) not in refs:
        continue
    t = n['type']
    inputs = {}
    if t == 'LoadImage':
        inputs['image'] = (n.get('widgets_values') or [''])[0]
        if len(n.get('widgets_values') or []) > 1:
            inputs['upload'] = n['widgets_values'][1]
    else:
        for k, nm in enumerate(widget_names(t)):
            wv = list(n.get('widgets_values') or [])
            if k < len(wv):
                inputs[nm] = wv[k]
    api[str(n['id'])] = {'class_type': t, 'inputs': inputs}
    refs.discard(str(n['id']))

# 输出：CreateVideo -> SaveVideo
out_links = (sg.get('outputs') or [{}])[0].get('linkIds') or []
src = resolve(out_links[0]) if out_links else None
sv = [n for n in d['nodes'] if n.get('type') == 'SaveVideo'][0]
ins = {'video': src}
wv = list(sv.get('widgets_values') or [])
for k, nm in enumerate(widget_names('SaveVideo')):
    if k < len(wv):
        ins[nm] = wv[k]
api[str(sv['id'])] = {'class_type': 'SaveVideo', 'inputs': ins}

# 未连接到输出的节点直接丢掉（省得白跑）
used, changed = set(), True
save_id = str(sv['id'])
while changed:
    changed = False
    for k, v in api.items():
        if k in used or k == save_id:
            continue
        if k not in used and any(isinstance(x, list) and x and str(x[0]) == k for vv in api.values() for x in vv['inputs'].values()):
            pass
    # 反向：从 save_id 做可达闭包
    break
reach, stack = {save_id}, [save_id]
while stack:
    cur = stack.pop()
    for x in (api[cur]['inputs'] or {}).values():
        if isinstance(x, list) and x and isinstance(x[0], str) and x[0] in api and x[0] not in reach:
            reach.add(x[0]); stack.append(x[0])
dropped = sorted(set(api) - reach, key=lambda z: int(z))
for k in dropped:
    del api[k]

json.dump(api, open(OUT, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
print('API 节点数:', len(api), '| 缺失类:', sorted(missing) if missing else '无')
print('丢弃（不可达）:', dropped)
print('提示:', warn[:6] if warn else '无')
KEY = ('UNETLoader', 'CLIPLoader', 'VAELoader', 'ManualSigmas', 'KSamplerSelect', 'EmptyLTXVLatentVideo',
       'LTXVEmptyLatentAudio', 'LTXVImgToVideoInplace', 'LTXVPreprocess', 'ResolutionSelector', 'PrimitiveInt',
       'PrimitiveStringMultiline', 'RandomNoise', 'CreateVideo', 'LTXVDualCFGGuider', 'LatentUpscaleModelLoader',
       'VAEDecodeTiled', 'ResizeImageMaskNode', 'LTXVConditioning', 'SaveVideo', 'LoadImage', 'SamplerCustomAdvanced')
for k in sorted(api, key=lambda z: int(z)):
    if api[k]['class_type'] in KEY:
        print(' %-4s %-24s %s' % (k, api[k]['class_type'], json.dumps(api[k]['inputs'], ensure_ascii=False)[:165]))
print('保存 ->', OUT)
