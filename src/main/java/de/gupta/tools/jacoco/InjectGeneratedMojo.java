package de.gupta.tools.jacoco;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.objectweb.asm.*;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans compiled interface classes and injects {@code @javax.annotation.Generated} onto trivial
 * delegation methods so JaCoCo 0.8.2+ excludes them from coverage reports.
 *
 * <p>Two patterns are matched:
 * <ul>
 *   <li><b>Default instance methods</b>: body is sequential arg-loads + {@code invokeinterface} + return.
 *   <li><b>Static methods</b>: same pattern but with {@code invokestatic}, and only when the callee
 *       is in the <em>same</em> class (alias → canonical), not a different class (canonical → impl).
 * </ul>
 */
@Mojo(name = "inject-generated", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class InjectGeneratedMojo extends AbstractMojo {

    // JaCoCo matches on simple name "Generated" — any package works.
    private static final String GENERATED_DESC = "Ljavax/annotation/Generated;";

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException {
        if (!outputDirectory.exists()) {
            return;
        }
        try (Stream<Path> stream = Files.walk(outputDirectory.toPath())) {
            stream.filter(p -> p.toString().endsWith(".class"))
                  .forEach(this::processClass);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to scan " + outputDirectory, e);
        }
    }

    private void processClass(Path classFile) {
        try {
            byte[] bytes = Files.readAllBytes(classFile);
            Set<String> targets = findDelegates(bytes);
            if (targets.isEmpty()) return;

            Files.write(classFile, injectAnnotations(bytes, targets));
            targets.forEach(sig ->
                getLog().debug("@Generated injected: " + classFile.getFileName() + " -> " + sig));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Pass 1: identify which method signatures (name+descriptor) need annotation ─────────────

    private Set<String> findDelegates(byte[] bytes) {
        Set<String> result = new HashSet<>();
        ClassReader reader = new ClassReader(bytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            boolean isInterface;
            String className;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
                className = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!isInterface) return null;
                int skip = Opcodes.ACC_ABSTRACT | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC;
                if ((access & skip) != 0) return null;
                boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                return new DelegateDetector(name, descriptor, className, isStatic, result);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return result;
    }

    // ── Pass 2: rewrite the class, adding the annotation to targeted methods ──────────────────

    private byte[] injectAnnotations(byte[] bytes, Set<String> targets) {
        ClassReader reader = new ClassReader(bytes);
        // Do NOT pass reader to ClassWriter: that triggers a direct byte-copy optimisation
        // which silently discards any visitAnnotation() calls made before the ClassReader
        // feeds the method body, causing our @Generated injection to vanish.
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targets.contains(name + descriptor)) return mv;

                // visitAnnotation must be called before visitCode — here we are still inside
                // visitMethod() so the ClassReader has not yet fed any method content.
                AnnotationVisitor av = mv.visitAnnotation(GENERATED_DESC, false);
                AnnotationVisitor arr = av.visitArray("value");
                arr.visit(null, "jacoco-delegate-filter");
                arr.visitEnd();
                av.visitEnd();
                return mv;
            }
        }, 0);
        return writer.toByteArray();
    }

    // ── Instruction-level detector ────────────────────────────────────────────────────────────

    private static final class DelegateDetector extends MethodVisitor {

        private final String name;
        private final String descriptor;
        private final String className;
        private final boolean isStatic;
        private final Set<String> result;

        // Expected local variable slot for the next load instruction.
        // Starts at 0 (this for instance, first arg for static), increments by 1 or 2 (long/double).
        private int expectedVar = 0;
        private boolean sawInvoke = false;
        private boolean sawReturn = false;
        private boolean ok = true;
        private boolean alreadyAnnotated = false;

        DelegateDetector(String name, String descriptor, String className,
                         boolean isStatic, Set<String> result) {
            super(Opcodes.ASM9);
            this.name = name;
            this.descriptor = descriptor;
            this.className = className;
            this.isStatic = isStatic;
            this.result = result;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.endsWith("/Generated;")) alreadyAnnotated = true;
            return null;
        }

        // ── Loads: must be sequential (slot 0, 1, …) before the invoke ──────────────────────

        @Override
        public void visitVarInsn(int opcode, int var) {
            if (sawInvoke || sawReturn) { ok = false; return; }
            boolean isLoad = opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD ||
                             opcode == Opcodes.FLOAD || opcode == Opcodes.LLOAD ||
                             opcode == Opcodes.DLOAD;
            if (!isLoad || var != expectedVar) { ok = false; return; }
            expectedVar += (opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD) ? 2 : 1;
        }

        // ── Single invoke call ────────────────────────────────────────────────────────────────

        @Override
        public void visitMethodInsn(int opcode, String owner, String mName,
                                    String mDesc, boolean itf) {
            if (sawInvoke || sawReturn) { ok = false; return; }
            if (opcode == Opcodes.INVOKEINTERFACE) {
                sawInvoke = true;
            } else if (opcode == Opcodes.INVOKESTATIC && isStatic && owner.equals(className)) {
                // Static alias → canonical: only match when callee is in the same class.
                // This prevents canonical methods (which delegate to impl classes) from matching.
                sawInvoke = true;
            } else {
                ok = false;
            }
        }

        // ── Return must be the last instruction ───────────────────────────────────────────────

        @Override
        public void visitInsn(int opcode) {
            if (sawReturn || !sawInvoke) { ok = false; return; }
            if (!isReturn(opcode)) { ok = false; return; }
            sawReturn = true;
        }

        // ── Any other real instruction → not a trivial delegate ───────────────────────────────

        @Override public void visitFieldInsn(int o, String a, String b, String c) { ok = false; }
        @Override public void visitIntInsn(int o, int v) { ok = false; }
        @Override public void visitLdcInsn(Object v) { ok = false; }
        @Override public void visitTypeInsn(int o, String t) { ok = false; }
        @Override public void visitJumpInsn(int o, Label l) { ok = false; }
        @Override public void visitIincInsn(int v, int i) { ok = false; }
        @Override public void visitTableSwitchInsn(int mn, int mx, Label d, Label... ls) { ok = false; }
        @Override public void visitLookupSwitchInsn(Label d, int[] ks, Label[] ls) { ok = false; }
        @Override public void visitMultiANewArrayInsn(String d, int n) { ok = false; }
        @Override public void visitInvokeDynamicInsn(String n, String d, Handle h, Object... a) { ok = false; }

        @Override
        public void visitEnd() {
            if (ok && sawInvoke && sawReturn && !alreadyAnnotated)
                result.add(name + descriptor);
        }

        private static boolean isReturn(int opcode) {
            return opcode == Opcodes.RETURN  || opcode == Opcodes.ARETURN ||
                   opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
                   opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN;
        }
    }
}
