# SSLFlow Guard

A targeted Java Agent workaround for a pathological zero-progress loop in OpenJDK's
`java.net.http` TLS reader (`SSLFlowDelegate$Reader`). It is intended for workloads
such as Minecraft Java Edition where the loop can manifest as extreme short-lived
allocation from `SSLFlowDelegate.getAppBuffer()` and force ZGC into continuous
allocation-rate collections.

## What it patches

The agent instruments this JDK method at class-load/retransform time:

```text
jdk.internal.net.http.common.SSLFlowDelegate$Reader.unwrapBuffer(ByteBuffer)
```

Immediately after `SSLEngine.unwrap(src, dst)` returns, it checks exactly:

```text
Status             == OK
HandshakeStatus    == NOT_HANDSHAKING
bytesConsumed()    == 0
bytesProduced()    == 0
src.hasRemaining() == true
```

When detected, it sets:

```java
minBytesRequired = src.remaining();
```

`Reader.processData()` already uses `minBytesRequired` as its underflow threshold.
The current loop therefore stops and waits for additional TLS input instead of
immediately calling `unwrap()` and `getAppBuffer()` again.

The guard does **not** discard encrypted bytes, close the connection, synthesize an
`SSLEngineResult`, or interfere with handshake states such as `NEED_WRAP`,
`NEED_TASK`, `NEED_UNWRAP`, or `NEED_UNWRAP_AGAIN`.

## Requirements

- Java 17+ to build/run the project.
- Gradle 9.7.1 recommended (Gradle 9.1+ supports running on Java 25).
- ASM 9.10.1.
- Shadow plugin 9.6.1.

The production JAR relocates ASM into `dev.sslflowguard.internal.asm`, avoiding
classpath collisions with Forge/NeoForge/Fabric or other Java agents that ship their
own ASM version.

The agent performs a preflight check against the JDK's actual
`SSLFlowDelegate$Reader.class`. It requires the expected field/method and exactly one
matching `SSLEngine.unwrap(ByteBuffer, ByteBuffer)` call. A future vendor/JDK layout
change therefore fails loudly at startup instead of silently disabling the guard.

## Build — Production is the default

With Gradle installed:

```bash
gradle clean build
```

If you want a standard Gradle Wrapper in your checkout, generate it once with:

```bash
gradle wrapper --gradle-version 9.7.1
./gradlew clean build
```

Windows after generating the wrapper:

```bat
gradlew.bat clean build
```

Production agent output:

```text
build/libs/sslflow-guard-1.0.0.jar
```

A diagnostic non-fat JAR is also produced as:

```text
build/libs/sslflow-guard-1.0.0-thin.jar
```

**Use the non-`thin` JAR as the Java Agent.** It is self-contained and has ASM shaded.

## Use with Minecraft

Add this JVM argument **before launching Minecraft**:

```text
-javaagent:/absolute/path/to/sslflow-guard-1.0.0.jar
```

Windows example:

```text
-javaagent:C:/Minecraft/tools/sslflow-guard-1.0.0.jar
```

Production mode is the default. It only prints startup/patch status and does not log
every guard hit.

A successful setup should eventually print:

```text
[SSLFlowGuard] patched jdk.internal.net.http.common.SSLFlowDelegate$Reader: zero-progress guard active (production mode).
```

No `--add-opens` or `--add-exports` JVM options are required.

## Debug mode

For A/B verification:

```text
-javaagent:/absolute/path/to/sslflow-guard-1.0.0.jar=debug
```

Whenever the exact zero-progress state is blocked, the transformed JDK class prints:

```text
[SSLFlowGuard] zero-progress SSL unwrap blocked; waiting for more TLS input
```

This log call is injected directly into the JDK class and does not call back into the
agent, so there is no module/classloader dependency. Debug mode is only intended for
verification; production mode should be used normally.

## Quiet mode

```text
-javaagent:/absolute/path/to/sslflow-guard-1.0.0.jar=quiet
```

Options may be comma-separated, e.g. `debug,quiet`. `quiet` suppresses agent
startup/patch messages; an explicitly enabled debug hit message is still printed.

## Expected behavior

Without the guard, the suspected state is effectively:

```text
unwrap -> OK / NOT_HANDSHAKING / 0 consumed / 0 produced
       -> getAppBuffer()
       -> unwrap again immediately
       -> ...
```

With the guard:

```text
unwrap -> OK / NOT_HANDSHAKING / 0 / 0, encrypted bytes remain
       -> minBytesRequired = current remaining bytes
       -> processData loop exits
       -> wait/request more network input
       -> retry only after additional bytes arrive
```

## Implementation notes

- The patch is inserted immediately after the actual `SSLEngine.unwrap` invocation.
- Normal TLS records take the first fast-path exit after `bytesConsumed() != 0`.
- The injected bytecode preserves the original `SSLEngineResult` on the operand stack.
- No methods/fields are added to the JDK class, so the same transform is suitable for
  load-time transformation and retransformation of an already-loaded target.
- ASM recomputes frames/max stack for the modified method.

## Safety / rollback

This is a targeted runtime workaround for a JDK-internal implementation detail, not
an upstream OpenJDK fix. Keep the original JVM arguments so the agent can be removed
immediately if a specific server/proxy/TLS implementation shows unexpected behavior.

If the current JDK no longer matches the implementation this project was designed
for, startup intentionally fails with an `SSLFlowGuard FATAL` compatibility message
rather than guessing at a patch location.
