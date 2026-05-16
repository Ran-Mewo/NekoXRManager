package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import pxb.android.axml.AxmlReader;
import pxb.android.axml.AxmlVisitor;
import pxb.android.axml.AxmlWriter;
import pxb.android.axml.NodeVisitor;

/**
 * Minimal AndroidManifest.xml editor for the ARCore-spoofer use case. Only touches:
 *
 * <ul>
 *   <li>{@code application/@android:debuggable} — optional, behind a pref</li>
 *   <li>{@code application/@android:extractNativeLibs=true} — required so libarcore_c.so lands on
 *       disk where our binary patch can take effect</li>
 *   <li>An optional {@code <uses-feature android:name="android.hardware.camera.ar" required="false"/>}
 *       so the installer doesn't reject devices that don't advertise the feature</li>
 * </ul>
 *
 * <p>Implementation uses pxb.android.axml (Aliucord fork of the original by P. Xiang Bao).</p>
 */
public final class ManifestPatcher {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final int ATTR_RES_DEBUGGABLE         = 0x0101000f;
    private static final int ATTR_RES_EXTRACT_NATIVE_LIBS = 0x010104ea;
    private static final int ATTR_RES_NAME               = 0x01010003;
    private static final int ATTR_RES_REQUIRED           = 0x0101028e;

    public static final class Options {
        public boolean setDebuggable;
        public boolean ensureExtractNativeLibsTrue = true;
        public boolean ensureCameraArFeature = true;
    }

    private ManifestPatcher() {}

    public static byte[] patch(byte[] manifestBytes, @NonNull Options opts) throws IOException {
        AxmlReader reader = new AxmlReader(manifestBytes);
        AxmlWriter writer = new AxmlWriter();

        reader.accept(new AxmlVisitor(writer) {
            @Override
            public NodeVisitor child(@Nullable String ns, @Nullable String name) {
                NodeVisitor manifestNode = super.child(ns, name);
                if (!"manifest".equals(name)) return manifestNode;

                return new NodeVisitor(manifestNode) {
                    private boolean cameraArFeatureSeen = false;

                    @Override
                    public NodeVisitor child(@Nullable String childNs, @Nullable String childName) {
                        NodeVisitor child = super.child(childNs, childName);

                        if ("uses-feature".equals(childName)) {
                            return new NodeVisitor(child) {
                                @Override
                                public void attr(@Nullable String aNs, @Nullable String aName,
                                                 int aResId, int aType, @Nullable Object aValue) {
                                    if (ANDROID_NS.equals(aNs) && "name".equals(aName)
                                            && "android.hardware.camera.ar".equals(aValue)) {
                                        cameraArFeatureSeen = true;
                                    }
                                    super.attr(aNs, aName, aResId, aType, aValue);
                                }
                            };
                        }

                        if ("application".equals(childName)) {
                            return rewriteApplication(child, opts);
                        }
                        return child;
                    }

                    @Override
                    public void end() {
                        if (opts.ensureCameraArFeature && !cameraArFeatureSeen) {
                            NodeVisitor f = super.child(null, "uses-feature");
                            f.attr(ANDROID_NS, "name", ATTR_RES_NAME,
                                    NodeVisitor.TYPE_STRING, "android.hardware.camera.ar");
                            f.attr(ANDROID_NS, "required", ATTR_RES_REQUIRED,
                                    NodeVisitor.TYPE_INT_BOOLEAN, 0); // false
                            f.end();
                        }
                        super.end();
                    }
                };
            }
        });

        return writer.toByteArray();
    }

    private static NodeVisitor rewriteApplication(NodeVisitor app, Options opts) {
        return new NodeVisitor(app) {
            private boolean debuggableWritten = false;
            private boolean extractWritten = false;

            @Override
            public void attr(@Nullable String ns, @Nullable String name,
                             int resId, int type, @Nullable Object value) {
                if (ANDROID_NS.equals(ns)) {
                    if ("debuggable".equals(name) && opts.setDebuggable) {
                        super.attr(ns, name, ATTR_RES_DEBUGGABLE, NodeVisitor.TYPE_INT_BOOLEAN, -1);
                        debuggableWritten = true;
                        return;
                    }
                    if ("extractNativeLibs".equals(name) && opts.ensureExtractNativeLibsTrue) {
                        super.attr(ns, name, ATTR_RES_EXTRACT_NATIVE_LIBS,
                                NodeVisitor.TYPE_INT_BOOLEAN, -1);
                        extractWritten = true;
                        return;
                    }
                }
                super.attr(ns, name, resId, type, value);
            }

            @Override
            public NodeVisitor child(@Nullable String ns, @Nullable String name) {
                ensureAttrs();
                return super.child(ns, name);
            }

            @Override
            public void end() {
                ensureAttrs();
                super.end();
            }

            private void ensureAttrs() {
                if (opts.setDebuggable && !debuggableWritten) {
                    super.attr(ANDROID_NS, "debuggable", ATTR_RES_DEBUGGABLE,
                            NodeVisitor.TYPE_INT_BOOLEAN, -1);
                    debuggableWritten = true;
                }
                if (opts.ensureExtractNativeLibsTrue && !extractWritten) {
                    super.attr(ANDROID_NS, "extractNativeLibs", ATTR_RES_EXTRACT_NATIVE_LIBS,
                            NodeVisitor.TYPE_INT_BOOLEAN, -1);
                    extractWritten = true;
                }
            }
        };
    }
}
