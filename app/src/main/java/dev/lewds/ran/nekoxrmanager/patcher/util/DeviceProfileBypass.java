package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.lewds.ran.nekoxrmanager.patcher.DeviceProfile;

/**
 * ARCore packed-profile DB renderer + injector.
 *
 * <p>ARCore's {@code assets/packed_profiles/profiles.toc} maps
 * {@code "<6-char-hash>:<api>"} keys (plus raw fingerprint variants) to
 * {file_idx, offset, length} slabs inside {@code profiles_NNNNN.dat}. If no
 * key matches, the native loader returns "no profile" and ARCore apps see
 * UNSUPPORTED_DEVICE.</p>
 *
 * <p>This util:
 * <ol>
 *   <li>{@link #renderBinarypb} encodes a {@link DeviceProfile} into the
 *       ARCore DeviceProfile protobuf wire-format. Schema RE'd from
 *       {@code protoc --decode_raw} on stock dat slabs — same wire format
 *       used by {@code tools/build_profile.py}.</li>
 *   <li>{@link #appendTocEntries} appends new TocEntry records to the
 *       existing protobuf-encoded TOC, pointing at a fresh slab written
 *       alongside as {@code profiles_<idx>.dat}.</li>
 * </ol>
 */
public final class DeviceProfileBypass {

    private DeviceProfileBypass() {}

    // ---------- protobuf wire-format encoder (no protoc dependency) ----------

    private static final int WT_VARINT = 0;
    private static final int WT_FIXED64 = 1;
    private static final int WT_LEN = 2;

    private static void writeVarint(@NonNull ByteArrayOutputStream out, long value) {
        // two's-complement int64 for negatives, same as protoc.
        long v = value;
        while (true) {
            int b = (int) (v & 0x7FL);
            v >>>= 7;
            if (v == 0L) { out.write(b); return; }
            out.write(b | 0x80);
        }
    }

    private static void writeTag(@NonNull ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeVarintField(@NonNull ByteArrayOutputStream out, int field, long value) {
        writeTag(out, field, WT_VARINT);
        writeVarint(out, value);
    }

    private static void writeDoubleField(@NonNull ByteArrayOutputStream out, int field, double value) {
        writeTag(out, field, WT_FIXED64);
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putDouble(value);
        out.write(bb.array(), 0, 8);
    }

    private static void writeStringField(@NonNull ByteArrayOutputStream out, int field, @NonNull String value) {
        byte[] s = value.getBytes(StandardCharsets.UTF_8);
        writeTag(out, field, WT_LEN);
        writeVarint(out, s.length);
        out.write(s, 0, s.length);
    }

    private static void writeMessageField(@NonNull ByteArrayOutputStream out, int field, @NonNull byte[] body) {
        writeTag(out, field, WT_LEN);
        writeVarint(out, body.length);
        out.write(body, 0, body.length);
    }

    private static byte[] xyz(double x, double y, double z) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(28);
        writeDoubleField(out, 1, x);
        writeDoubleField(out, 2, y);
        writeDoubleField(out, 3, z);
        return out.toByteArray();
    }

    private static byte[] xy(double x, double y) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(20);
        writeDoubleField(out, 1, x);
        writeDoubleField(out, 2, y);
        return out.toByteArray();
    }

    private static byte[] quat(double w, double x, double y, double z) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(36);
        writeDoubleField(out, 1, w);
        writeDoubleField(out, 2, x);
        writeDoubleField(out, 3, y);
        writeDoubleField(out, 4, z);
        return out.toByteArray();
    }

    private static byte[] stream(int usage, int ordinal, int format, int w, int h) {
        ByteArrayOutputStream st = new ByteArrayOutputStream();
        writeVarintField(st, 1, usage);
        writeVarintField(st, 2, ordinal);

        ByteArrayOutputStream res = new ByteArrayOutputStream();
        writeVarintField(res, 1, w);
        writeVarintField(res, 2, h);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeMessageField(body, 1, st.toByteArray());
        writeVarintField(body, 2, format);
        writeMessageField(body, 3, res.toByteArray());
        return body.toByteArray();
    }

    private static byte[] transform(double[] p, double[] qWxyz) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeMessageField(out, 1, xyz(p[0], p[1], p[2]));
        writeMessageField(out, 2, quat(qWxyz[0], qWxyz[1], qWxyz[2], qWxyz[3]));
        return out.toByteArray();
    }

    // ---------- DeviceProfile encoder ----------

    // Enum values for the DeviceProfile proto. Same numeric ids the textproto
    // names map to in the public ARCore SDK.
    private static final int REAR_FACING = 1;
    private static final int POS_Y_READOUT = 3;
    private static final int BEGINNING_OF_EXPOSURE = 1;
    private static final int RUNTIME_CHECK_REQUIRED = 1;
    private static final int DEVICE_PROFILE_TIMEBASE = 1;
    private static final int DEPTH_API_UNSUPPORTED = 2;

    private static final int USAGE_FEATURE_TRACKING = 1;
    private static final int USAGE_AR_PASSTHROUGH = 2;
    private static final int USAGE_AUXILIARY_CV = 5;
    private static final int FORMAT_YUV_420_888 = 35;
    private static final int FORMAT_PRIVATE = 34;

    // Frame-id enum values for extrinsic records (fields 1 and 2 of each
    // camera_extrinsics / general_extrinsics entry).
    private static final int FRAME_IMU_0 = 100;
    private static final int FRAME_ANDROID_FRAME = 40;
    private static final int FRAME_OPENGL = 42;
    private static final int FRAME_CAMERA_PRIMARY = 40;
    private static final int GYROSCOPE_CALIBRATED = 2;

    private static final double[] ZERO_P = { 0.0, 0.0, 0.0 };
    private static final double[] Q_GL  = { 0.70710678118654757, 0.0, 0.0, 0.70710678118654746 };
    private static final double[] Q_ID  = { 1.0, 0.0, 0.0, 0.0 };

    /**
     * Renders {@code profile} to ARCore's DeviceProfile protobuf wire-format.
     * Schema documented in {@code tools/build_profile.py}.
     */
    @NonNull
    public static byte[] renderBinarypb(@NonNull DeviceProfile p) {
        // --- camera[0] ---
        ByteArrayOutputStream cam = new ByteArrayOutputStream();
        writeStringField(cam, 1, p.cameraId);
        writeVarintField(cam, 2, REAR_FACING);
        writeVarintField(cam, 3, 33_333_333L);        // rolling_shutter_readout (ns)
        writeVarintField(cam, 4, POS_Y_READOUT);
        writeVarintField(cam, 5, 0);                  // camera_timestamp_alignment
        writeVarintField(cam, 6, p.captureWidth);
        writeVarintField(cam, 7, p.captureHeight);
        writeMessageField(cam, 8, xy(p.focalX, p.focalY));
        writeMessageField(cam, 9, xy(p.principalX, p.principalY));
        writeMessageField(cam, 11, xyz(p.k0, p.k1, p.k2));
        writeVarintField(cam, 14, BEGINNING_OF_EXPOSURE);

        ByteArrayOutputStream sg = new ByteArrayOutputStream();
        writeMessageField(sg, 1, stream(USAGE_FEATURE_TRACKING, 0, FORMAT_YUV_420_888, p.captureWidth, p.captureHeight));
        writeMessageField(sg, 1, stream(USAGE_AUXILIARY_CV,    0, FORMAT_YUV_420_888, p.captureWidth, p.captureHeight));
        writeMessageField(sg, 1, stream(USAGE_AR_PASSTHROUGH,  0, FORMAT_PRIVATE,     p.captureWidth, p.captureHeight));
        ByteArrayOutputStream fr = new ByteArrayOutputStream();
        writeVarintField(fr, 1, p.fps);
        writeVarintField(fr, 2, p.fps);
        writeMessageField(sg, 3, fr.toByteArray());
        writeMessageField(cam, 16, sg.toByteArray());

        writeVarintField(cam, 17, RUNTIME_CHECK_REQUIRED);
        writeVarintField(cam, 18, DEVICE_PROFILE_TIMEBASE);
        writeVarintField(cam, 27, DEPTH_API_UNSUPPORTED);

        // --- imu key=100 ---
        ByteArrayOutputStream imu = new ByteArrayOutputStream();
        writeMessageField(imu, 1,  xyz(1.0, 1.0, 1.0));    // gyro_scale
        writeMessageField(imu, 2,  xyz(0.0, 0.0, 0.0));    // gyro_misalignment
        writeMessageField(imu, 3,  xyz(1.0, 1.0, 1.0));    // accel_scale
        writeMessageField(imu, 4,  xyz(0.0, 0.0, 0.0));    // accel_misalignment
        writeMessageField(imu, 5,  quat(1.0, 0.0, 0.0, 0.0));
        writeDoubleField(imu, 6,  p.gyroNoiseSigma);
        writeDoubleField(imu, 7,  p.gyroBiasSigma);
        writeDoubleField(imu, 8,  p.accelNoiseSigma);
        writeDoubleField(imu, 9,  p.accelBiasSigma);
        writeMessageField(imu, 10, xyz(0.0, 0.0, 0.0));
        writeMessageField(imu, 11, xyz(0.0, 0.0, 0.0));
        writeVarintField(imu, 14, 0);
        writeVarintField(imu, 15, 0);
        writeVarintField(imu, 16, GYROSCOPE_CALIBRATED);

        ByteArrayOutputStream imuEntry = new ByteArrayOutputStream();
        writeVarintField(imuEntry, 1, FRAME_IMU_0);
        writeMessageField(imuEntry, 2, imu.toByteArray());

        // --- camera_extrinsics: IMU_0 → camera_id, with the device-specific quat ---
        double[] qCam = p.cameraExtrinsicQuat;
        ByteArrayOutputStream camExt = new ByteArrayOutputStream();
        writeVarintField(camExt, 1, FRAME_IMU_0);
        writeStringField(camExt, 2, p.cameraId);
        writeMessageField(camExt, 3, transform(ZERO_P, qCam));

        // --- general_extrinsics (3 entries from the textproto template) ---
        ByteArrayOutputStream gen1 = new ByteArrayOutputStream();
        writeVarintField(gen1, 1, FRAME_IMU_0);
        writeVarintField(gen1, 2, FRAME_CAMERA_PRIMARY);
        writeMessageField(gen1, 3, transform(ZERO_P, qCam));

        ByteArrayOutputStream gen2 = new ByteArrayOutputStream();
        writeVarintField(gen2, 1, FRAME_ANDROID_FRAME);
        writeVarintField(gen2, 2, FRAME_OPENGL);
        writeMessageField(gen2, 3, transform(ZERO_P, Q_GL));

        ByteArrayOutputStream gen3 = new ByteArrayOutputStream();
        writeVarintField(gen3, 1, FRAME_IMU_0);
        writeVarintField(gen3, 2, FRAME_ANDROID_FRAME);
        writeMessageField(gen3, 3, transform(ZERO_P, Q_ID));

        // --- assemble DeviceProfile ---
        ByteArrayOutputStream prof = new ByteArrayOutputStream();
        writeMessageField(prof, 1, cam.toByteArray());
        writeMessageField(prof, 2, imuEntry.toByteArray());
        writeMessageField(prof, 3, camExt.toByteArray());
        writeMessageField(prof, 4, gen1.toByteArray());
        writeMessageField(prof, 4, gen2.toByteArray());
        writeMessageField(prof, 4, gen3.toByteArray());
        return prof.toByteArray();
    }

    // ---------- TOC entry append ----------

    /**
     * Appends TocEntry records for each {@code key} to {@code existingToc}, all
     * pointing at a single contiguous slab in {@code profiles_<newFileIdx>.dat}
     * starting at offset 0 with length {@code profileLength}.
     *
     * <p>The TOC is a protobuf stream of {@code message TocEntry { string key=1;
     * message Locator { int32 file_idx=1, offset=2, length=3 } locator=2 }}
     * messages, each wrapped in a field-1 length-delimited record at the
     * top-level. Append order is preserved.</p>
     */
    @NonNull
    public static byte[] appendTocEntries(@NonNull byte[] existingToc, @NonNull List<String> keys,
                                          int newFileIdx, int profileLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(existingToc.length + keys.size() * 32);
        out.write(existingToc, 0, existingToc.length);
        for (String key : keys) {
            ByteArrayOutputStream loc = new ByteArrayOutputStream();
            writeVarintField(loc, 1, newFileIdx);
            writeVarintField(loc, 2, 0);
            writeVarintField(loc, 3, profileLength);

            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            writeStringField(entry, 1, key);
            writeMessageField(entry, 2, loc.toByteArray());

            // outer top-level field-1 wrap
            writeMessageField(out, 1, entry.toByteArray());
        }
        return out.toByteArray();
    }

    /**
     * Finds the next free {@code profiles_NNNNN.dat} index, given the list of
     * existing entries under {@code assets/packed_profiles/} in the APK.
     */
    public static int nextDatIndex(@NonNull List<String> existingPackedProfileEntries) {
        int max = -1;
        for (String n : existingPackedProfileEntries) {
            // entries look like "assets/packed_profiles/profiles_00046.dat"
            int slash = n.lastIndexOf('/');
            String base = slash >= 0 ? n.substring(slash + 1) : n;
            if (!base.startsWith("profiles_") || !base.endsWith(".dat")) continue;
            try {
                int idx = Integer.parseInt(base.substring("profiles_".length(), base.length() - ".dat".length()));
                if (idx > max) max = idx;
            } catch (NumberFormatException ignored) {}
        }
        return max + 1;
    }
}
