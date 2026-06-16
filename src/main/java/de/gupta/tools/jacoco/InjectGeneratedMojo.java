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
 * Scans compiled interface classes and injects {@code @javax.annotation.Generated} onto any
 * default method whose entire body is {@code aload_0 + invokeinterface + xreturn} (i.e. a
 * trivial single-call delegation).  JaCoCo 0.8.2+ skips methods carrying any annotation whose
 * simple name is {@code Generated}, so these aliases disappear from the coverage report without
 * any source-level annotation.
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

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!isInterface) return null;
                int notDefault = Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC |
                                 Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC;
                if ((access & notDefault) != 0) return null;
                return new DelegateDetector(name, descriptor, result);
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
        private final Set<String> result;

        private int step = 0;
        private boolean ok = true;
        private boolean alreadyAnnotated = false;

        DelegateDetector(String name, String descriptor, Set<String> result) {
            super(Opcodes.ASM9);
            this.name = name;
            this.descriptor = descriptor;
            this.result = result;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            if (desc.endsWith("/Generated;")) alreadyAnnotated = true;
            return null;
        }

        // ── The three instructions we expect, in order ────────────────────────────────────────

        @Override
        public void visitVarInsn(int opcode, int var) {
            step++;
            if (step != 1 || opcode != Opcodes.ALOAD || var != 0) ok = false;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String mName,
                                    String mDesc, boolean itf) {
            step++;
            if (step != 2 || opcode != Opcodes.INVOKEINTERFACE) ok = false;
        }

        @Override
        public void visitInsn(int opcode) {
            step++;
            if (step == 3) ok &= isReturn(opcode);
            else ok = false;
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
            if (ok && step == 3 && !alreadyAnnotated) result.add(name + descriptor);
        }

        private static boolean isReturn(int opcode) {
            return opcode == Opcodes.RETURN  || opcode == Opcodes.ARETURN ||
                   opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN ||
                   opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN;
        }
    }
}
