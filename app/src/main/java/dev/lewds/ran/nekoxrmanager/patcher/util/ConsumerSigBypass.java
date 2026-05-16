package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;

import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.github.diamondminer88.zip.ZipCompression;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rewrites the body of
 * {@code com.google.ar.core.SessionCreateJniHelper.checkApkSignature(Context):boolean}
 * to {@code return true} so that consumer apps stop rejecting our re-signed
 * {@code com.google.ar.core}.
 *
 * <p>The check is part of the ARCore SDK AAR every consumer app bundles; the
 * native shim ({@code libarcore_sdk_c.so}) calls back into this Java method via
 * the {@code @UsedByNative("session_create.cc")} annotation, which we preserve.
 * Failing the check produces logcat lines tagged {@code ARCore-SessionCreateJniHelper}
 * and surfaces as "AR not supported on device" in the consumer app.</p>
 *
 * <p>Operates directly on the APK (multidex-aware: any {@code classes*.dex}
 * entry that defines the class is rewritten and written back via {@link ZipUtil};
 * the others are left untouched).</p>
 */
public final class ConsumerSigBypass {

    public static final String TARGET_CLASS_DESCRIPTOR =
            "Lcom/google/ar/core/SessionCreateJniHelper;";
    public static final String TARGET_METHOD_NAME = "checkApkSignature";
    public static final String TARGET_METHOD_RETURN = "Z";
    public static final List<String> TARGET_METHOD_PARAMS =
            List.of("Landroid/content/Context;");

    private static final Pattern DEX_NAME = Pattern.compile("classes\\d*\\.dex");

    private ConsumerSigBypass() {}

    public static class Result {
        public final String patchedDexEntry;
        public final int instructionsBefore;
        public Result(String entry, int n) { this.patchedDexEntry = entry; this.instructionsBefore = n; }
    }

    /**
     * Locate the dex inside {@code apk} that defines {@code SessionCreateJniHelper},
     * NOP its {@code checkApkSignature} body, and write the rewritten dex back into
     * the APK. Throws if no dex contains the class.
     */
    @NonNull
    public static Result patchApk(@NonNull File apk) throws IOException {
        List<String> dexEntries = new ArrayList<>();
        for (String n : ZipUtil.listEntries(apk, "")) {
            if (DEX_NAME.matcher(n).matches()) dexEntries.add(n);
        }
        if (dexEntries.isEmpty()) {
            throw new IOException("no classes*.dex in " + apk.getName());
        }
        java.util.Collections.sort(dexEntries);
        for (String entry : dexEntries) {
            byte[] orig = ZipUtil.readEntry(apk, entry);
            if (orig == null) continue;
            int[] before = { -1 };
            byte[] patched = tryPatchDexBytes(orig, before);
            if (patched != null) {
                ZipUtil.replaceEntry(apk, entry, patched, ZipCompression.DEFLATE);
                return new Result(entry, before[0]);
            }
        }
        throw new IOException("class " + TARGET_CLASS_DESCRIPTOR
                + " not found in any classes*.dex in " + apk.getName());
    }

    /**
     * Returns rewritten dex bytes if {@code dexBytes} contains the target class,
     * or {@code null} if it does not. Sets {@code beforeOut[0]} to the original
     * instruction count when patched.
     */
    private static byte[] tryPatchDexBytes(@NonNull byte[] dexBytes, int[] beforeOut) throws IOException {
        DexBackedDexFile parsed = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(), new ByteArrayInputStream(dexBytes));
        boolean found = false;
        for (ClassDef cls : parsed.getClasses()) {
            if (TARGET_CLASS_DESCRIPTOR.equals(cls.getType())) { found = true; break; }
        }
        if (!found) return null;

        List<ClassDef> rewritten = new ArrayList<>(parsed.getClasses().size());
        for (ClassDef cls : parsed.getClasses()) {
            if (!TARGET_CLASS_DESCRIPTOR.equals(cls.getType())) {
                rewritten.add(cls);
                continue;
            }
            rewritten.add(rewriteSigCheckMethod(cls, beforeOut));
        }
        if (beforeOut[0] < 0) {
            throw new IOException(TARGET_CLASS_DESCRIPTOR + " present but "
                    + TARGET_METHOD_NAME + " not found");
        }

        DexFile out = new ImmutableDexFile(parsed.getOpcodes(), rewritten);
        MemoryDataStore store = new MemoryDataStore();
        DexPool.writeTo(store, out);
        return store.getData();
    }

    @NonNull
    private static ImmutableClassDef rewriteSigCheckMethod(
            @NonNull ClassDef cls, int[] originalInstructionCount) {
        return new ImmutableClassDef(
                cls.getType(),
                cls.getAccessFlags(),
                cls.getSuperclass(),
                cls.getInterfaces(),
                cls.getSourceFile(),
                cls.getAnnotations(),
                cls.getStaticFields(),
                cls.getInstanceFields(),
                rewriteMethods(cls.getDirectMethods(), originalInstructionCount),
                rewriteMethods(cls.getVirtualMethods(), originalInstructionCount)
        );
    }

    @NonNull
    private static List<Method> rewriteMethods(
            @NonNull Iterable<? extends Method> input, int[] originalInstructionCount) {
        List<Method> out = new ArrayList<>();
        for (Method m : input) {
            if (isCheckApkSignature(m)) {
                MethodImplementation impl = m.getImplementation();
                int before = 0;
                if (impl != null) {
                    for (com.android.tools.smali.dexlib2.iface.instruction.Instruction ignored : impl.getInstructions()) before++;
                }
                originalInstructionCount[0] = before;
                out.add(makeAlwaysTrueMethod(m));
            } else {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean isCheckApkSignature(@NonNull Method m) {
        if (!TARGET_METHOD_NAME.equals(m.getName())) return false;
        if (!TARGET_METHOD_RETURN.equals(m.getReturnType())) return false;
        List<? extends CharSequence> params = m.getParameterTypes();
        if (params.size() != TARGET_METHOD_PARAMS.size()) return false;
        for (int i = 0; i < params.size(); i++) {
            if (!TARGET_METHOD_PARAMS.get(i).contentEquals(params.get(i))) return false;
        }
        return true;
    }

    /**
     * Build a replacement {@link ImmutableMethod} whose body is
     * {@code const/4 v0, 0x1; return v0}. We keep the original method's
     * {@code .locals}, parameters, and annotations (incl. {@code @UsedByNative}).
     */
    @NonNull
    private static ImmutableMethod makeAlwaysTrueMethod(@NonNull Method m) {
        MethodImplementation oldImpl = m.getImplementation();
        int registerCount = oldImpl != null ? oldImpl.getRegisterCount() : 1;
        ImmutableInstruction11n loadOne = new ImmutableInstruction11n(Opcode.CONST_4, 0, 1);
        ImmutableInstruction11x ret = new ImmutableInstruction11x(Opcode.RETURN, 0);
        ImmutableMethodImplementation newImpl = new ImmutableMethodImplementation(
                Math.max(registerCount, 1),
                Arrays.asList(loadOne, ret),
                /* tryBlocks */ null,
                /* debugItems */ null
        );
        return new ImmutableMethod(
                m.getDefiningClass(),
                m.getName(),
                m.getParameters(),
                m.getReturnType(),
                m.getAccessFlags(),
                m.getAnnotations(),
                m.getHiddenApiRestrictions(),
                newImpl
        );
    }

    /**
     * Cheap pre-flight: returns true if {@code apk} has a {@code lib/*\/libarcore_sdk_c.so}
     * entry. Used by the picker UI to filter the installed-package list.
     */
    public static boolean apkContainsArCoreSdk(@NonNull File apk) {
        try (ZipFile z = new ZipFile(apk)) {
            for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.startsWith("lib/") && n.endsWith("/libarcore_sdk_c.so")) return true;
            }
        } catch (IOException ignored) {
        }
        return false;
    }
}
