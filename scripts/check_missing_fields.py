# -*- coding: utf-8 -*-
"""
把 javac 报的 "cannot find symbol: method getX()/setX()" 分成两类：

  · Lombok 会生成的  —— 目标类里确实有字段 x（@Data/@Getter 会生成访问器），本机没跑注解处理才报，无害
  · 真的不存在的    —— 目标类里根本没有这个字段，这是真 bug

这个区分是必要的：没有它，一个「字段压根不存在」的错误会淹没在几千条 Lombok 噪音里。
（这次就是这么漏掉 Task.errorMessage 的。）
"""
import re, sys, os, glob

err_path, src_root = sys.argv[1], sys.argv[2]
only = sys.argv[3] if len(sys.argv) > 3 else None

# 类名 -> 源文件
classes = {}
for f in glob.glob(os.path.join(src_root, '**', '*.java'), recursive=True):
    classes.setdefault(os.path.basename(f)[:-5], []).append(f)

def fields_of(cls):
    out = set()
    for f in classes.get(cls, []):
        try:
            src = open(f, encoding='utf-8').read()
        except Exception:
            continue
        for m in re.finditer(r'^\s*(?:private|protected|public)\s+(?:static\s+|final\s+|transient\s+|volatile\s+)*'
                             r'[\w.<>\[\],?\s]+?\s+(\w+)\s*[;=]', src, re.M):
            out.add(m.group(1))
        # record 的分量也算字段
        for m in re.finditer(r'\brecord\s+\w+\s*\(([^)]*)\)', src, re.S):
            for part in m.group(1).split(','):
                part = part.strip()
                if part:
                    out.add(part.split()[-1])
    return out

lines = open(err_path, encoding='utf-8', errors='replace').read().split('\n')
real, lombok, unknown = [], 0, []
i = 0
while i < len(lines):
    m = re.match(r'^(src/.*\.java):(\d+): error: cannot find symbol$', lines[i])
    if not m:
        i += 1
        continue
    where, ln = m.group(1), m.group(2)
    sym = loc = None
    for j in range(i + 1, min(i + 6, len(lines))):
        s = re.match(r'^\s*symbol:\s+method\s+(\w+)\(', lines[j])
        if s:
            sym = s.group(1)
        l = re.match(r'^\s*location:\s+.*?of type ([\w.]+)', lines[j]) or \
            re.match(r'^\s*location:\s+class ([\w.]+)', lines[j])
        if l:
            loc = l.group(1).split('.')[-1]
    i += 1
    if not sym or not loc:
        continue
    if only and only not in where:
        continue
    if not re.match(r'^(get|set|is)[A-Z]', sym):
        continue
    prop = re.sub(r'^(get|set|is)', '', sym)
    prop = prop[0].lower() + prop[1:]
    if loc not in classes:
        unknown.append((where, ln, sym, loc))
        continue
    if prop in fields_of(loc):
        lombok += 1
    else:
        real.append((where, ln, sym, loc, prop))

print("Lombok 能解释的：%d 条" % lombok)
print("目标类不在本仓库、无法判断：%d 条" % len(unknown))
if real:
    print("\n❌ 字段根本不存在（真 bug）：")
    seen = set()
    for w, ln, sym, loc, prop in real:
        key = (w, sym, loc)
        if key in seen:
            continue
        seen.add(key)
        print("   %s:%s  %s.%s  —— %s 里没有字段 %s" % (w, ln, loc, sym, loc, prop))
else:
    print("\n✅ 没有「字段不存在」这类真错误")
