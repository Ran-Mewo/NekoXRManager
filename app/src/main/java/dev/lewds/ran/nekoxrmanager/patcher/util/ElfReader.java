package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal ELF symbol resolver. Returns the file offset and size of a named symbol's body.
 *
 * <p>Supports both 64-bit (Elf64) and 32-bit (Elf32) ARM ELFs. We don't need full
 * relocation handling — only enough to resolve a function's file-offset+size so we can
 * sigscan within its body.</p>
 *
 * <p>Looks up the symbol in {@code .dynsym} (the dynamic symbol table). For each symbol,
 * resolves its virtual address to a file offset by walking program headers (PT_LOAD
 * entries) — same arithmetic as a runtime loader.</p>
 */
public final class ElfReader {

    public static final class Symbol {
        public final long fileOffset;
        public final long size;
        public Symbol(long fileOffset, long size) {
            this.fileOffset = fileOffset;
            this.size = size;
        }
    }

    private ElfReader() {}

    /** Returns the file offset and size of the named symbol, or {@code null} if absent. */
    public static Symbol findSymbol(@NonNull RandomAccessFile raf, @NonNull String name) throws IOException {
        byte[] ident = new byte[16];
        raf.seek(0);
        raf.readFully(ident);
        if (ident[0] != 0x7F || ident[1] != 'E' || ident[2] != 'L' || ident[3] != 'F') {
            throw new IOException("Not an ELF file");
        }
        boolean is64 = ident[4] == 2;
        ByteOrder order = ident[5] == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        return is64 ? read64(raf, order, name) : read32(raf, order, name);
    }

    // ---- 64-bit ----

    private static Symbol read64(RandomAccessFile raf, ByteOrder order, String wanted) throws IOException {
        long fileLen = raf.length();
        ByteBuffer hdr = readAt(raf, 0, 64).order(order);
        long e_phoff = hdr.getLong(32);
        long e_shoff = hdr.getLong(40);
        int e_phentsize = hdr.getShort(54) & 0xFFFF;
        int e_phnum = hdr.getShort(56) & 0xFFFF;
        int e_shentsize = hdr.getShort(58) & 0xFFFF;
        int e_shnum = hdr.getShort(60) & 0xFFFF;
        int e_shstrndx = hdr.getShort(62) & 0xFFFF;

        // Program headers (for VA -> file offset)
        long[] ph_vaddr = new long[e_phnum];
        long[] ph_offset = new long[e_phnum];
        long[] ph_filesz = new long[e_phnum];
        boolean[] ph_load = new boolean[e_phnum];
        for (int i = 0; i < e_phnum; i++) {
            ByteBuffer p = readAt(raf, e_phoff + (long) i * e_phentsize, e_phentsize).order(order);
            int type = p.getInt(0);
            ph_load[i] = type == 1; // PT_LOAD
            ph_offset[i] = p.getLong(8);
            ph_vaddr[i]  = p.getLong(16);
            ph_filesz[i] = p.getLong(32);
        }

        // Section header string table → section names
        ByteBuffer shstrSec = readAt(raf, e_shoff + (long) e_shstrndx * e_shentsize, e_shentsize).order(order);
        long shstr_off = shstrSec.getLong(24);
        long shstr_size = shstrSec.getLong(32);
        byte[] shstrtab = new byte[(int) shstr_size];
        raf.seek(shstr_off);
        raf.readFully(shstrtab);

        // Find .dynsym + .dynstr
        long dynsym_off = -1, dynsym_size = 0, dynsym_entsize = 24;
        long dynstr_off = -1, dynstr_size = 0;
        for (int i = 0; i < e_shnum; i++) {
            ByteBuffer s = readAt(raf, e_shoff + (long) i * e_shentsize, e_shentsize).order(order);
            int sh_name = s.getInt(0);
            int sh_type = s.getInt(4);
            long sh_off  = s.getLong(24);
            long sh_size = s.getLong(32);
            long sh_entsize = s.getLong(56);
            String name = cstr(shstrtab, sh_name);
            if (sh_type == 11 && ".dynsym".equals(name)) {
                dynsym_off = sh_off; dynsym_size = sh_size;
                if (sh_entsize > 0) dynsym_entsize = sh_entsize;
            } else if (sh_type == 3 && ".dynstr".equals(name)) {
                dynstr_off = sh_off; dynstr_size = sh_size;
            }
        }
        if (dynsym_off < 0 || dynstr_off < 0) return null;

        byte[] dynstr = new byte[(int) dynstr_size];
        raf.seek(dynstr_off);
        raf.readFully(dynstr);

        // Walk dynsym for matching name
        int count = (int) (dynsym_size / dynsym_entsize);
        for (int i = 0; i < count; i++) {
            ByteBuffer s = readAt(raf, dynsym_off + (long) i * dynsym_entsize, (int) dynsym_entsize).order(order);
            int  st_name  = s.getInt(0);
            long st_value = s.getLong(8);
            long st_size  = s.getLong(16);
            String name = cstr(dynstr, st_name);
            if (name == null) continue;
            // Strip versioned-symbol suffix "@@VERS_x" or "@VERS_x" for matching.
            int at = name.indexOf('@');
            if (at >= 0) name = name.substring(0, at);
            if (!name.equals(wanted)) continue;
            long fileOff = vaToFile(st_value, ph_load, ph_vaddr, ph_offset, ph_filesz);
            if (fileOff < 0 || fileOff + st_size > fileLen) return null;
            return new Symbol(fileOff, st_size);
        }
        return null;
    }

    // ---- 32-bit ----

    private static Symbol read32(RandomAccessFile raf, ByteOrder order, String wanted) throws IOException {
        long fileLen = raf.length();
        ByteBuffer hdr = readAt(raf, 0, 52).order(order);
        long e_phoff = hdr.getInt(28) & 0xFFFFFFFFL;
        long e_shoff = hdr.getInt(32) & 0xFFFFFFFFL;
        int e_phentsize = hdr.getShort(42) & 0xFFFF;
        int e_phnum = hdr.getShort(44) & 0xFFFF;
        int e_shentsize = hdr.getShort(46) & 0xFFFF;
        int e_shnum = hdr.getShort(48) & 0xFFFF;
        int e_shstrndx = hdr.getShort(50) & 0xFFFF;

        long[] ph_vaddr = new long[e_phnum];
        long[] ph_offset = new long[e_phnum];
        long[] ph_filesz = new long[e_phnum];
        boolean[] ph_load = new boolean[e_phnum];
        for (int i = 0; i < e_phnum; i++) {
            ByteBuffer p = readAt(raf, e_phoff + (long) i * e_phentsize, e_phentsize).order(order);
            int type = p.getInt(0);
            ph_load[i] = type == 1;
            ph_offset[i] = p.getInt(4) & 0xFFFFFFFFL;
            ph_vaddr[i]  = p.getInt(8) & 0xFFFFFFFFL;
            ph_filesz[i] = p.getInt(16) & 0xFFFFFFFFL;
        }

        ByteBuffer shstrSec = readAt(raf, e_shoff + (long) e_shstrndx * e_shentsize, e_shentsize).order(order);
        long shstr_off = shstrSec.getInt(16) & 0xFFFFFFFFL;
        long shstr_size = shstrSec.getInt(20) & 0xFFFFFFFFL;
        byte[] shstrtab = new byte[(int) shstr_size];
        raf.seek(shstr_off);
        raf.readFully(shstrtab);

        long dynsym_off = -1, dynsym_size = 0, dynsym_entsize = 16;
        long dynstr_off = -1, dynstr_size = 0;
        for (int i = 0; i < e_shnum; i++) {
            ByteBuffer s = readAt(raf, e_shoff + (long) i * e_shentsize, e_shentsize).order(order);
            int sh_name = s.getInt(0);
            int sh_type = s.getInt(4);
            long sh_off = s.getInt(16) & 0xFFFFFFFFL;
            long sh_size = s.getInt(20) & 0xFFFFFFFFL;
            long sh_entsize = s.getInt(36) & 0xFFFFFFFFL;
            String name = cstr(shstrtab, sh_name);
            if (sh_type == 11 && ".dynsym".equals(name)) {
                dynsym_off = sh_off; dynsym_size = sh_size;
                if (sh_entsize > 0) dynsym_entsize = sh_entsize;
            } else if (sh_type == 3 && ".dynstr".equals(name)) {
                dynstr_off = sh_off; dynstr_size = sh_size;
            }
        }
        if (dynsym_off < 0 || dynstr_off < 0) return null;

        byte[] dynstr = new byte[(int) dynstr_size];
        raf.seek(dynstr_off);
        raf.readFully(dynstr);

        int count = (int) (dynsym_size / dynsym_entsize);
        for (int i = 0; i < count; i++) {
            ByteBuffer s = readAt(raf, dynsym_off + (long) i * dynsym_entsize, (int) dynsym_entsize).order(order);
            int  st_name  = s.getInt(0);
            long st_value = s.getInt(4) & 0xFFFFFFFFL;
            long st_size  = s.getInt(8) & 0xFFFFFFFFL;
            String name = cstr(dynstr, st_name);
            if (name == null) continue;
            int at = name.indexOf('@');
            if (at >= 0) name = name.substring(0, at);
            if (!name.equals(wanted)) continue;
            long fileOff = vaToFile(st_value, ph_load, ph_vaddr, ph_offset, ph_filesz);
            if (fileOff < 0 || fileOff + st_size > fileLen) return null;
            return new Symbol(fileOff, st_size);
        }
        return null;
    }

    private static long vaToFile(long va, boolean[] load, long[] vaddr, long[] offset, long[] filesz) {
        for (int i = 0; i < load.length; i++) {
            if (!load[i]) continue;
            if (va >= vaddr[i] && va < vaddr[i] + filesz[i]) {
                return offset[i] + (va - vaddr[i]);
            }
        }
        return -1;
    }

    private static ByteBuffer readAt(RandomAccessFile raf, long off, int len) throws IOException {
        byte[] buf = new byte[len];
        raf.seek(off);
        raf.readFully(buf);
        return ByteBuffer.wrap(buf);
    }

    private static String cstr(byte[] table, int offset) {
        if (offset < 0 || offset >= table.length) return null;
        int end = offset;
        while (end < table.length && table[end] != 0) end++;
        return new String(table, offset, end - offset);
    }
}
