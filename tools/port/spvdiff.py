"""Compare two directories of SPIR-V modules, ignoring debug info.

Drops OpLine/OpSource/OpString/OpName and NonSemantic debug instructions, renumbers result ids by order of
first appearance, then compares the remaining instruction streams. Proves that toggling -g (embedded source)
did not change the generated shader code.
Usage: python spvdiff.py <dirA> <dirB> [spirv-dis path]
"""
import pathlib, re, subprocess, sys

a_dir, b_dir = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
dis = sys.argv[3] if len(sys.argv) > 3 else r"C:\VulkanSDK\1.4.357.0\Bin\spirv-dis.exe"
DEBUG = re.compile(r"\b(OpLine|OpNoLine|OpSource|OpSourceContinued|OpSourceExtension|OpString|OpName|OpMemberName|"
                   r"OpModuleProcessed|OpExtInstWithForwardRefsKHR)\b")


def norm(path):
    out = subprocess.run([dis, "--raw-id", "--no-header", str(path)], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True).stdout
    # Keep only instruction lines: OpSource/OpString print embedded source text across many following lines.
    lines = [l.strip() for l in out.splitlines()
             if re.match(r"\s*(%\S+ = )?Op\w+", l) and not DEBUG.search(l)]
    # NonSemantic debug-info sets (Shader.DebugInfo etc.): drop the import, every OpExtInst using it, and the
    # extension declaration. Their results are only referenced by other debug instructions.
    debug_sets = {m.group(1) for l in lines
                  for m in [re.match(r'(%\d+) = OpExtInstImport "NonSemantic', l)] if m}
    lines = [l for l in lines
             if not re.search(r'OpExtInstImport "NonSemantic', l)
             and not ("OpExtInst " in l and any(re.search(rf"OpExtInst %\d+ {re.escape(s)}\b", l) for s in debug_sets))
             and 'OpExtension "SPV_KHR_non_semantic_info"' not in l]
    # Constants/types/undefs that only debug instructions used are now unreferenced: drop them (to a fixpoint).
    decl = re.compile(r"(%\d+) = (OpConstant\w*|OpUndef|OpType\w+|OpSpecConstant\w*)\b")
    while True:
        refs = {}
        for l in lines:
            m = re.match(r"(%\d+) = ", l)
            for r in re.findall(r"%\d+", l[m.end():] if m else l):
                refs[r] = refs.get(r, 0) + 1
        keep = [l for l in lines if not ((m := decl.match(l)) and refs.get(m.group(1), 0) == 0)]
        if len(keep) == len(lines):
            break
        lines = keep
    ids, result = {}, []
    for l in lines:
        result.append(re.sub(r"%\d+", lambda m: ids.setdefault(m.group(0), f"%{len(ids)}"), l))
    return result


bad = 0
for a in sorted(a_dir.glob("*.spv")):
    b = b_dir / a.name
    if not b.exists():
        print("missing", a.name); bad += 1; continue
    na, nb = norm(a), norm(b)
    if na != nb:
        bad += 1
        print(f"DIFF {a.name}: {len(na)} vs {len(nb)} instructions")
print(f"{bad} of {len(list(a_dir.glob('*.spv')))} modules differ in code")
