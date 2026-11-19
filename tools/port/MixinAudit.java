import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Static mixin audit: for every mixin in the given configs, checks that target classes, target methods,
 * injection-point invokes/fields, @Shadow members and accessors exist in the supplied runtime jars.
 * Usage: MixinAudit <classesDir> <mixinsJson...> -- <jar...>
 */
public class MixinAudit {
    static final Map<String, ClassNode> CP = new HashMap<>();
    static final List<String> problems = new ArrayList<>();
    static int checks;

    public static void main(String[] args) throws Exception {
        Path classes = Paths.get(args[0]);
        List<String> configs = new ArrayList<>();
        int i = 1;
        for (; i < args.length && !args[i].equals("--"); i++) configs.add(args[i]);
        for (i++; i < args.length; i++) loadJar(new FileInputStream(args[i]));
        System.out.println("classpath classes: " + CP.size());
        for (String cfg : configs) {
            String json = Files.readString(Paths.get(cfg));
            String pkg = json.replaceAll("(?s).*\"package\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            for (String name : listEntries(json)) {
                Path f = classes.resolve((pkg + "." + name).replace('.', '/') + ".class");
                if (!Files.exists(f)) { problems.add(name + ": mixin class missing from build"); continue; }
                auditMixin(name, read(Files.readAllBytes(f)));
            }
        }
        System.out.println("checks: " + checks + ", problems: " + problems.size());
        problems.forEach(p -> System.out.println("  " + p));
    }

    static List<String> listEntries(String json) {
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"client", "mixins", "server"}) {
            var m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", java.util.regex.Pattern.DOTALL).matcher(json);
            if (m.find()) for (var e : m.group(1).split(",")) { e = e.trim().replace("\"", ""); if (!e.isEmpty()) out.add(e); }
        }
        return out;
    }

    static void loadJar(InputStream in) throws IOException {
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String n = e.getName();
                byte[] b = z.readAllBytes();
                if (n.endsWith(".jar")) loadJar(new ByteArrayInputStream(b));
                else if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                    try { ClassNode c = read(b); CP.putIfAbsent(c.name, c); } catch (Throwable ignored) { }
                }
            }
        }
    }

    static ClassNode read(byte[] b) {
        ClassNode c = new ClassNode();
        new ClassReader(b).accept(c, ClassReader.SKIP_FRAMES);
        return c;
    }

    static List<AnnotationNode> anns(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> l = new ArrayList<>();
        if (a != null) l.addAll(a);
        if (b != null) l.addAll(b);
        return l;
    }

    static Object val(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return (List<T>) l;
        return List.of((T) o);
    }

    static void auditMixin(String name, ClassNode mixin) {
        AnnotationNode mix = null;
        boolean pseudo = false;
        for (AnnotationNode a : anns(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) mix = a;
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;")) pseudo = true;
        }
        if (mix == null) { problems.add(name + ": no @Mixin"); return; }
        List<String> targets = new ArrayList<>();
        for (Object t : list(val(mix, "value"))) targets.add(((Type) t).getInternalName());
        for (Object t : list(val(mix, "targets"))) targets.add(((String) t).replace('.', '/'));
        for (String t : targets) {
            checks++;
            ClassNode target = CP.get(t);
            if (target == null) {
                if (!pseudo) problems.add(name + ": target class missing " + t);
                else System.out.println("  (pseudo, target absent: " + t + " in " + name + ")");
                continue;
            }
            auditMembers(name, mixin, target);
        }
    }

    static final Set<String> INJECTORS = Set.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
            "ModifyConstant", "ModifyExpressionValue", "ModifyReturnValue", "WrapOperation", "WrapWithCondition",
            "WrapMethod", "ModifyReceiver");

    static String simple(String desc) { return desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1); }

    static void auditMembers(String name, ClassNode mixin, ClassNode target) {
        if (mixin.fields != null) for (FieldNode f : mixin.fields)
            for (AnnotationNode a : anns(f.visibleAnnotations, f.invisibleAnnotations))
                if (simple(a.desc).equals("Shadow")) {
                    checks++;
                    if (findField(target, f.name, f.desc) == null && findField(target, f.name, null) == null)
                        problems.add(name + ": @Shadow field " + f.name + " missing in " + target.name);
                }
        for (MethodNode m : mixin.methods)
            for (AnnotationNode a : anns(m.visibleAnnotations, m.invisibleAnnotations)) {
                String kind = simple(a.desc);
                if (kind.equals("Shadow") || kind.equals("Overwrite")) {
                    checks++;
                    String n = m.name.startsWith("shadow$") ? m.name.substring(7) : m.name;
                    if (findMethod(target, n, m.desc) == null)
                        problems.add(name + ": @" + kind + " method " + n + m.desc + " missing in " + target.name);
                } else if (kind.equals("Accessor")) {
                    checks++;
                    String n = (String) val(a, "value");
                    if (n == null || n.isEmpty()) n = strip(m.name, "get", "set", "is");
                    if (findField(target, n, null) == null)
                        problems.add(name + ": @Accessor field " + n + " missing in " + target.name);
                } else if (kind.equals("Invoker")) {
                    checks++;
                    String n = (String) val(a, "value");
                    if (n == null || n.isEmpty()) n = strip(m.name, "call", "invoke");
                    boolean ok = false;
                    for (MethodNode tm : target.methods) if (tm.name.equals(n)) ok = true;
                    if (!ok) problems.add(name + ": @Invoker method " + n + " missing in " + target.name);
                } else if (INJECTORS.contains(kind)) {
                    List<String> sels = list(val(a, "method"));
                    List<MethodNode> hit = new ArrayList<>();
                    List<String> missing = new ArrayList<>();
                    for (String sel : sels) {
                        checks++;
                        List<MethodNode> found = select(target, sel);
                        if (found.isEmpty()) missing.add(sel);
                        hit.addAll(found);
                    }
                    // require=0 with alternative names: only a problem when none of them exist.
                    boolean optional = Integer.valueOf(0).equals(val(a, "require"));
                    if (!missing.isEmpty() && !(optional && !hit.isEmpty()))
                        for (String sel : missing)
                            problems.add(name + "." + m.name + ": @" + kind + " target method '" + sel + "' missing in " + target.name);
                    List<AnnotationNode> ats = new ArrayList<>(list(val(a, "at")));
                    for (AnnotationNode at : ats) auditAt(name + "." + m.name, kind, at, hit, target);
                }
            }
    }

    static String strip(String n, String... prefixes) {
        for (String p : prefixes) if (n.startsWith(p) && n.length() > p.length()) {
            String r = n.substring(p.length());
            return Character.toLowerCase(r.charAt(0)) + r.substring(1);
        }
        return n;
    }

    static List<MethodNode> select(ClassNode target, String sel) {
        String n = sel, d = null;
        int p = sel.indexOf('(');
        if (p >= 0) { n = sel.substring(0, p); d = sel.substring(p); }
        if (n.startsWith("L") && n.contains(";")) n = n.substring(n.indexOf(';') + 1); // owner-qualified
        List<MethodNode> out = new ArrayList<>();
        for (MethodNode m : target.methods)
            if ((n.equals("*") || m.name.equals(n)) && (d == null || m.desc.equals(d))) out.add(m);
        return out;
    }

    static void auditAt(String where, String kind, AnnotationNode at, List<MethodNode> methods, ClassNode target) {
        String value = (String) val(at, "value");
        String tgt = (String) val(at, "target");
        if (value == null || tgt == null || tgt.isEmpty() || methods.isEmpty()) return;
        checks++;
        if (value.equals("INVOKE") || value.equals("INVOKE_ASSIGN") || value.equals("INVOKE_STRING")) {
            int semi = tgt.indexOf(';'), par = tgt.indexOf('(');
            if (semi < 0 || par < 0) return;
            String owner = tgt.substring(1, semi), mname = tgt.substring(semi + 1, par), mdesc = tgt.substring(par);
            for (MethodNode m : methods) for (AbstractInsnNode insn : m.instructions)
                if (insn instanceof MethodInsnNode mi && mi.name.equals(mname) && mi.desc.equals(mdesc)) return;
            problems.add(where + ": @" + kind + " INVOKE " + owner.substring(owner.lastIndexOf('/') + 1) + "." + mname + mdesc
                    + " not found in " + target.name + "." + methods.get(0).name);
        } else if (value.equals("FIELD")) {
            int semi = tgt.indexOf(';'), col = tgt.indexOf(':');
            String fname = col > 0 ? tgt.substring(semi + 1, col) : tgt.substring(semi + 1);
            for (MethodNode m : methods) for (AbstractInsnNode insn : m.instructions)
                if (insn instanceof FieldInsnNode fi && fi.name.equals(fname)) return;
            problems.add(where + ": @" + kind + " FIELD " + tgt + " not found in " + target.name + "." + methods.get(0).name);
        } else if (value.equals("NEW")) {
            String type = tgt.startsWith("L") ? tgt.substring(1, tgt.length() - 1) : tgt;
            if (type.contains("(")) type = tgt.substring(1, tgt.indexOf(';'));
            for (MethodNode m : methods) for (AbstractInsnNode insn : m.instructions)
                if (insn instanceof TypeInsnNode ti && ti.getOpcode() == org.objectweb.asm.Opcodes.NEW && ti.desc.equals(type)) return;
            problems.add(where + ": @" + kind + " NEW " + type + " not found in " + target.name + "." + methods.get(0).name);
        }
    }

    static FieldNode findField(ClassNode c, String n, String d) {
        for (ClassNode k = c; k != null; k = k.superName == null ? null : CP.get(k.superName))
            for (FieldNode f : k.fields) if (f.name.equals(n) && (d == null || f.desc.equals(d))) return f;
        return null;
    }

    static MethodNode findMethod(ClassNode c, String n, String d) {
        Deque<ClassNode> q = new ArrayDeque<>(List.of(c));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            ClassNode k = q.poll();
            if (!seen.add(k.name)) continue;
            for (MethodNode m : k.methods) if (m.name.equals(n) && m.desc.equals(d)) return m;
            if (k.superName != null && CP.containsKey(k.superName)) q.add(CP.get(k.superName));
            for (String it : k.interfaces) if (CP.containsKey(it)) q.add(CP.get(it));
        }
        return null;
    }
}
