package dev.lewds.ran.nekoxrmanager.manager;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Append-only log buffer surfaced in LogScreen. Threadsafe. */
public final class InstallLogManager {

    public enum Level { INFO, WARN, ERROR, DEBUG }

    public static final class Entry {
        public final long timestampMs;
        public final Level level;
        public final String tag;
        public final String message;

        public Entry(long ts, Level lvl, String tag, String msg) {
            this.timestampMs = ts; this.level = lvl; this.tag = tag; this.message = msg;
        }

        @NonNull @Override
        public String toString() {
            return String.format("[%d] %s/%s: %s", timestampMs, level, tag, message);
        }
    }

    private final List<Entry> buffer = new CopyOnWriteArrayList<>();

    public void log(Level level, String tag, String message) {
        buffer.add(new Entry(System.currentTimeMillis(), level, tag, message));
    }

    public void info(String tag, String msg)  { log(Level.INFO,  tag, msg); }
    public void warn(String tag, String msg)  { log(Level.WARN,  tag, msg); }
    public void error(String tag, String msg) { log(Level.ERROR, tag, msg); }
    public void debug(String tag, String msg) { log(Level.DEBUG, tag, msg); }

    public List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    public void clear() { buffer.clear(); }
}
