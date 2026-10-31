#!/usr/bin/env python3
"""Regenerate the reflection names from a local MCP stable-22 SRG Tiny mapping.

Usage: python3 tools/minecraft/update_render_mappings.py /path/to/mappings-srg.tiny
No game classes or build dependencies are bundled into the injected agent.
"""
from pathlib import Path
import collections
import re
import sys

root = Path(__file__).resolve().parents[2] / 'minecraft-agent/src/main/java/tritium/minecraft/runtime'
sources = list((root / 'render').glob('*.java')) + list((root / 'util/render').glob('*.java'))
sources += [root / 'hook/RenderAccess.java', root / 'hook/RenderRuntime.java']
names = set()
for source in sources:
    if source.name != 'RenderMappings.java':
        names.update(re.findall(r'"([A-Za-z_][A-Za-z0-9_]*)"', source.read_text()))
aliases = collections.defaultdict(set)
for line in Path(sys.argv[1]).read_text().splitlines():
    parts = line.split('\t')
    if len(parts) == 7 and parts[1] in ('f', 'm'):
        named, srg = parts[6], parts[4]
        if named in names and named != srg:
            aliases[named].add(srg)
output = '''package tritium.minecraft.runtime.util.render;
import java.util.*;
/** MCP stable 22 names used by the reflection-only OpenB4 renderer. */
public final class RenderMappings {
 private static final Map<String,String> NAMES=new HashMap<String,String>();
 public static String name(String name){String s=NAMES.get(name);return s==null?name:s;}
 static {
'''
for name, srg in sorted(aliases.items()):
    output += '  NAMES.put("' + name + '","' + name + '/' + '/'.join(sorted(srg)) + '");\n'
output += ' }\n}\n'
(root / 'util/render/RenderMappings.java').write_text(output)
print('Mapped members:', len(aliases))
