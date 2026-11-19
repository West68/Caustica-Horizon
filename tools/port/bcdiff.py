"""Compare two jars' classes ignoring line numbers / constant-pool indices. Prints classes with real bytecode diffs."""
import sys, zipfile, subprocess, tempfile, os, re, hashlib

JP = r"C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\javap.exe"
a, b = zipfile.ZipFile(sys.argv[1]), zipfile.ZipFile(sys.argv[2])
tmp = tempfile.mkdtemp()
DROP = re.compile(r"line \d+:|Last modified|SHA-256|MD5|Classfile|checksum|LineNumberTable|^\s*#\d+ = |SourceFile")


def norm(data, tag):
    p = os.path.join(tmp, tag + ".class")
    open(p, "wb").write(data)
    out = subprocess.run([JP, "-c", "-p", "-v", p], capture_output=True, text=True, errors="replace").stdout
    return [re.sub(r"#\d+", "#", l) for l in out.splitlines() if not DROP.search(l)]


names = [n for n in a.namelist() if n.endswith(".class")]
for n in names:
    if n not in b.namelist():
        print("only-old", n); continue
    da, db = a.read(n), b.read(n)
    if da == db:
        continue
    la, lb = norm(da, "a"), norm(db, "b")
    if la != lb:
        import difflib
        d = [l for l in difflib.unified_diff(la, lb, lineterm="", n=0) if l[:1] in "+-" and l[:3] not in ("+++", "---")]
        print(f"{len(d):4d} {n}")
        for l in d[:6]:
            print("      ", l[:150])
