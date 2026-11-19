# Port tools

- `MixinAudit.java` — static check that every mixin's target class/method, INVOKE/FIELD/NEW points, @Shadow and accessors exist in a given set of runtime jars. Needs ASM (asm + asm-tree 9.x) on the classpath.
  `java -cp <classes>;asm.jar;asm-tree.jar MixinAudit versions/<mc>/build/classes/java/main versions/<mc>/build/resources/main/caustica.mixins.json ... -- <minecraft-merged-deobf.jar> <fabric-api.jar> <mod jars...> <lwjgl jars...>`
  Validated on 26.2: no false positives.
- `bcdiff.py` — compares two jars' classes ignoring line numbers / constant-pool indices; use after a multi-version refactor to prove an existing version's bytecode is unchanged.
