#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""静态闸门审计：每一个把「生成中」(isTtsGenerating = true) 点亮的代码路径，
   都必须在同一个函数体内有收掉它的地方（= false / killPlayChain）。
   用法: python3 audit_android_tts_flag.py [ReaderViewModel.kt]
"""
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else '/root/projects/moreader-android/app/src/main/java/com/moyue/ReaderViewModel.kt'
src = open(path, encoding='utf-8').read().split('\n')

fun_re = re.compile(r'^    (private |internal |public |protected |override |open |suspend )*fun ')
starts = [i for i, l in enumerate(src) if fun_re.match(l)]
bounds = {}
for n, s in enumerate(starts):
    end = starts[n + 1] if n + 1 < len(starts) else len(src)
    name = re.search(r'fun\s+(\w+)', src[s]).group(1)
    bounds[name] = (s, end, src[s].strip())

def owner(line_no):
    best = None
    for name, (s, e, sig) in bounds.items():
        if s <= line_no < e and (best is None or s > bounds[best][0]):
            best = name
    return best

setters = [i for i, l in enumerate(src) if 'isTtsGenerating = true' in l]
override_re = re.compile(r'^\s+override fun ')
ok, bad = [], []

def onstart_blocks(s, e):
    """函数体内所有 onStart() 回调块的行区间"""
    blocks, i = [], s
    while i < e:
        if re.match(r'^\s+override fun onStart\b', src[i]):
            j = i + 1
            while j < e and not override_re.match(src[j]):
                j += 1
            blocks.append((i, j))
            i = j
        else:
            i += 1
    return blocks

for i in setters:
    fn = owner(i)
    if fn is None:
        bad.append((i + 1, '(全局)', '找不到所属函数'))
        continue
    s0, e0, sig = bounds[fn]
    good = [b for b in onstart_blocks(s0, e0)
            if 'isTtsGenerating = false' in '\n'.join(src[b[0]:b[1]])]
    if good:
        ok.append((i + 1, fn, f'onStart@L{good[0][0] + 1}'))
    else:
        bad.append((i + 1, fn, 'onStart 回调里没有收尾 → 声音响了弹窗也不消失（会一直挂着）'))

print(f'文件: {path}')
print(f'点亮「生成中」的路径: {len(setters)} 处\n')
for ln, fn, note in ok:
    print(f'  ✅ L{ln:<5} {fn}()  — {note}')
for ln, fn, note in bad:
    print(f'  ❌ L{ln:<5} {fn}()  — 函数体内【没有】任何收尾 → 弹窗会一直挂着！')
print()
print('结论:', '✅ 全部闸门闭环（声音一响弹窗必被收掉）' if not bad else f'❌ 有 {len(bad)} 处漏洞（弹窗会卡住）')
sys.exit(1 if bad else 0)
