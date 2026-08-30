package dev.sslflowguard;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Inserts a zero-progress guard immediately after SSLEngine.unwrap(ByteBuffer, ByteBuffer)
 * returns inside SSLFlowDelegate.Reader.unwrapBuffer(ByteBuffer).
 *
 * The injected logic is deliberately self-contained: the transformed JDK class does not
 * reference any class from this agent, avoiding bootstrap/module class-loader problems.
 */
final class SSLFlowGuardTransformer implements ClassFileTransformer {
    static final String TARGET_CLASS =
            "jdk/internal/net/http/common/SSLFlowDelegate$Reader";
    static final String TARGET_METHOD = "unwrapBuffer";
    static final String TARGET_METHOD_DESC =
            "(Ljava/nio/ByteBuffer;)Ljdk/internal/net/http/common/SSLFlowDelegate$EngineResult;";
    static final String MIN_BYTES_FIELD = "minBytesRequired";

    private static final String SSL_ENGINE = "javax/net/ssl/SSLEngine";
    private static final String SSL_ENGINE_RESULT = "javax/net/ssl/SSLEngineResult";
    private static final String SSL_STATUS = "javax/net/ssl/SSLEngineResult$Status";
    private static final String SSL_HANDSHAKE_STATUS =
            "javax/net/ssl/SSLEngineResult$HandshakeStatus";
    private static final String BYTE_BUFFER = "java/nio/ByteBuffer";

    private static final String UNWRAP_DESC =
            "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Ljavax/net/ssl/SSLEngineResult;";

    private final AgentOptions options;
    private final AtomicBoolean patched = new AtomicBoolean();

    SSLFlowGuardTransformer(AgentOptions options) {
        this.options = options;
    }

    @Override
    public byte[] transform(
            Module module,
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer) throws IllegalClassFormatException {

        if (!TARGET_CLASS.equals(className)) {
            return null;
        }

        try {
            Compatibility compatibility = inspect(classfileBuffer);
            if (!compatibility.supported()) {
                throw new IllegalClassFormatException(
                        "Unsupported SSLFlowDelegate$Reader layout: " + compatibility.reason());
            }

            ClassReader reader = new ClassReader(classfileBuffer);
            SafeClassWriter writer = new SafeClassWriter(
                    reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader);
            GuardClassVisitor visitor = new GuardClassVisitor(writer, options.debug);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (visitor.unwrapInjectionCount != 1) {
                throw new IllegalClassFormatException(
                        "Expected exactly one SSLEngine.unwrap injection point, found "
                                + visitor.unwrapInjectionCount);
            }

            byte[] transformed = writer.toByteArray();

            // Reparse the result so malformed frame/constant-pool output fails immediately.
            new ClassReader(transformed);

            patched.set(true);
            if (!options.quiet) {
                System.err.printf(
                        "[SSLFlowGuard] patched %s: zero-progress guard active (%s mode).%n",
                        TARGET_CLASS.replace('/', '.'), options.debug ? "debug" : "production");
            }
            return transformed;
        } catch (IllegalClassFormatException e) {
            System.err.println("[SSLFlowGuard] ERROR: " + e.getMessage());
            throw e;
        } catch (Throwable t) {
            System.err.println("[SSLFlowGuard] ERROR while transforming " + TARGET_CLASS);
            t.printStackTrace(System.err);
            IllegalClassFormatException wrapped =
                    new IllegalClassFormatException("SSLFlowGuard transform failed: " + t);
            wrapped.initCause(t);
            throw wrapped;
        }
    }

    boolean isPatched() {
        return patched.get();
    }

    static String compatibilityProblem(byte[] classBytes) {
        Compatibility compatibility = inspect(classBytes);
        return compatibility.supported() ? null : compatibility.reason();
    }

    private static Compatibility inspect(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        Inspector inspector = new Inspector();
        reader.accept(inspector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (!TARGET_CLASS.equals(inspector.actualClassName)) {
            return Compatibility.no("class name changed to " + inspector.actualClassName);
        }
        if (!inspector.hasMinBytesRequired) {
            return Compatibility.no("missing int field 'minBytesRequired'");
        }
        if (!inspector.hasTargetMethod) {
            return Compatibility.no("missing method " + TARGET_METHOD + TARGET_METHOD_DESC);
        }
        if (inspector.unwrapCallCount != 1) {
            return Compatibility.no("expected exactly one SSLEngine.unwrap call in target method, found "
                    + inspector.unwrapCallCount);
        }
        return Compatibility.yes();
    }

    private static final class Inspector extends ClassVisitor {
        String actualClassName;
        boolean hasMinBytesRequired;
        boolean hasTargetMethod;
        int unwrapCallCount;

        Inspector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            actualClassName = name;
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(
                int access, String name, String descriptor, String signature, Object value) {
            if (MIN_BYTES_FIELD.equals(name) && "I".equals(descriptor)) {
                hasMinBytesRequired = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            if (!TARGET_METHOD.equals(name) || !TARGET_METHOD_DESC.equals(descriptor)) {
                return null;
            }
            hasTargetMethod = true;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKEVIRTUAL
                            && SSL_ENGINE.equals(owner)
                            && "unwrap".equals(methodName)
                            && UNWRAP_DESC.equals(methodDescriptor)) {
                        unwrapCallCount++;
                    }
                }
            };
        }
    }

    private static final class GuardClassVisitor extends ClassVisitor {
        private final boolean debug;
        int unwrapInjectionCount;

        GuardClassVisitor(ClassVisitor delegate, boolean debug) {
            super(Opcodes.ASM9, delegate);
            this.debug = debug;
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodVisitor downstream = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (!TARGET_METHOD.equals(name) || !TARGET_METHOD_DESC.equals(descriptor)) {
                return downstream;
            }

            return new MethodVisitor(Opcodes.ASM9, downstream) {
                @Override
                public void visitMethodInsn(
                        int opcode, String owner, String name, String descriptor, boolean isInterface) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);

                    if (opcode == Opcodes.INVOKEVIRTUAL
                            && SSL_ENGINE.equals(owner)
                            && "unwrap".equals(name)
                            && UNWRAP_DESC.equals(descriptor)) {
                        unwrapInjectionCount++;
                        injectZeroProgressGuard(this, debug);
                    }
                }
            };
        }
    }

    /**
     * Entry stack: [SSLEngineResult]
     * Exit stack:  [SSLEngineResult]
     *
     * If the unwrap result is OK + NOT_HANDSHAKING + 0 consumed + 0 produced while
     * source still has bytes, minBytesRequired is set to source.remaining(). This
     * makes Reader.processData() stop spinning and wait until additional TLS bytes arrive.
     */
    private static void injectZeroProgressGuard(MethodVisitor mv, boolean debug) {
        Label done = new Label();

        // result.bytesConsumed() == 0
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SSL_ENGINE_RESULT,
                "bytesConsumed", "()I", false);
        mv.visitJumpInsn(Opcodes.IFNE, done);

        // result.bytesProduced() == 0
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SSL_ENGINE_RESULT,
                "bytesProduced", "()I", false);
        mv.visitJumpInsn(Opcodes.IFNE, done);

        // result.getStatus() == Status.OK
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SSL_ENGINE_RESULT,
                "getStatus", "()Ljavax/net/ssl/SSLEngineResult$Status;", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SSL_STATUS, "OK",
                "Ljavax/net/ssl/SSLEngineResult$Status;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, done);

        // result.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, SSL_ENGINE_RESULT,
                "getHandshakeStatus", "()Ljavax/net/ssl/SSLEngineResult$HandshakeStatus;", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, SSL_HANDSHAKE_STATUS, "NOT_HANDSHAKING",
                "Ljavax/net/ssl/SSLEngineResult$HandshakeStatus;");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, done);

        // src.hasRemaining()
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BYTE_BUFFER, "hasRemaining", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, done);

        // this.minBytesRequired = src.remaining();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BYTE_BUFFER, "remaining", "()I", false);
        mv.visitFieldInsn(Opcodes.PUTFIELD, TARGET_CLASS, MIN_BYTES_FIELD, "I");

        if (debug) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
            mv.visitLdcInsn("[SSLFlowGuard] zero-progress SSL unwrap blocked; waiting for more TLS input");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                    "println", "(Ljava/lang/String;)V", false);
        }

        mv.visitLabel(done);
        // COMPUTE_FRAMES reconstructs the frame at this new branch target. The original
        // SSLEngineResult remains on the operand stack for the JDK's following ASTORE.
    }

    private record Compatibility(boolean supported, String reason) {
        static Compatibility yes() {
            return new Compatibility(true, "ok");
        }

        static Compatibility no(String reason) {
            return new Compatibility(false, reason);
        }
    }

    /**
     * COMPUTE_FRAMES normally needs to resolve common superclasses. Prefer the target
     * loader when available; fall back conservatively to Object if a vendor JDK hides
     * an internal type during transformation.
     */
    private static final class SafeClassWriter extends ClassWriter {
        private final ClassLoader targetLoader;

        SafeClassWriter(ClassReader reader, int flags, ClassLoader targetLoader) {
            super(reader, flags);
            this.targetLoader = targetLoader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            ClassLoader loader = targetLoader != null
                    ? targetLoader
                    : ClassLoader.getPlatformClassLoader();
            try {
                Class<?> c1 = Class.forName(type1.replace('/', '.'), false, loader);
                Class<?> c2 = Class.forName(type2.replace('/', '.'), false, loader);
                if (c1.isAssignableFrom(c2)) return type1;
                if (c2.isAssignableFrom(c1)) return type2;
                if (c1.isInterface() || c2.isInterface()) return "java/lang/Object";
                do {
                    c1 = c1.getSuperclass();
                } while (c1 != null && !c1.isAssignableFrom(c2));
                return c1 == null ? "java/lang/Object" : Type.getInternalName(c1);
            } catch (Throwable ignored) {
                return "java/lang/Object";
            }
        }
    }
}
