package dervogel101.de.launcher;

import dervogel101.de.state.CPUMemory;

import java.util.Arrays;

/** Stateless 8086 instruction display. It never executes code or guesses unknown lengths. */
public final class Disassembler8086 {
    private static final String[] R8 = {"AL", "CL", "DL", "BL", "AH", "CH", "DH", "BH"};
    private static final String[] R16 = {"AX", "CX", "DX", "BX", "SP", "BP", "SI", "DI"};
    private static final String[] SREG = {"ES", "CS", "SS", "DS"};
    private static final String[] EA = {"BX+SI", "BX+DI", "BP+SI", "BP+DI", "SI", "DI", "BP", "BX"};
    private static final String[] ALU = {"ADD", "OR", "ADC", "SBB", "AND", "SUB", "XOR", "CMP"};
    private static final String[] JCC = {"JO", "JNO", "JB", "JAE", "JE", "JNE", "JBE", "JA",
            "JS", "JNS", "JP", "JNP", "JL", "JGE", "JLE", "JG"};
    private static final String[] SHIFT = {"ROL", "ROR", "RCL", "RCR", "SHL", "SHR", null, "SAR"};
    private static final String[] GROUP3 = {"TEST", null, "NOT", "NEG", "MUL", "IMUL", "DIV", "IDIV"};

    private Disassembler8086() {}

    public record Instruction(int cs, int ip, byte[] bytes, String mnemonic, String operands,
                              boolean call, boolean unsupported) {
        public Instruction {
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        public int length() { return bytes.length; }
        public int physicalAddress() { return ((cs << 4) + ip) & 0xFFFFF; }
        public int nextIp() { return (ip + bytes.length) & 0xFFFF; }
        public String assembly() { return operands.isEmpty() ? mnemonic : mnemonic + " " + operands; }
    }

    public static Instruction decode(CPUMemory memory, int cs, int ip) {
        Cursor c = new Cursor(memory, cs & 0xFFFF, ip & 0xFFFF);
        int first = c.u8();
        int op = first;
        String prefix = "";
        String segment = "";
        while (op == 0x26 || op == 0x2E || op == 0x36 || op == 0x3E
                || op == 0xF0 || op == 0xF2 || op == 0xF3) {
            if (c.pos >= 15) return db(memory, cs, ip);
            switch (op) {
                case 0x26 -> segment = "ES:";
                case 0x2E -> segment = "CS:";
                case 0x36 -> segment = "SS:";
                case 0x3E -> segment = "DS:";
                case 0xF0 -> prefix = "LOCK ";
                case 0xF2 -> prefix = "REPNE ";
                case 0xF3 -> prefix = "REP ";
                default -> { }
            }
            op = c.u8();
        }

        String name = null;
        String args = "";
        boolean call = false;
        boolean unsupported = false;
        int width;
        int modrm;
        int ext;

        if (op < 0x40 && (op & 7) <= 5) {
            name = ALU[op >>> 3];
            width = (op & 1) == 0 ? 8 : 16;
            if ((op & 7) < 4) {
                modrm = c.u8();
                String mem = c.rm(modrm, width, segment);
                String reg = c.reg(modrm, width);
                args = (op & 2) == 0 ? mem + ", " + reg : reg + ", " + mem;
            } else {
                args = (width == 8 ? "AL" : "AX") + ", " + c.imm(width);
            }
        } else if (op >= 0x40 && op <= 0x5F) {
            name = op < 0x48 ? "INC" : op < 0x50 ? "DEC" : op < 0x58 ? "PUSH" : "POP";
            args = R16[op & 7];
        } else if (op >= 0x70 && op <= 0x7F) {
            name = JCC[op & 15];
            args = c.rel8();
        } else if (op >= 0xB0 && op <= 0xBF) {
            name = "MOV";
            width = op < 0xB8 ? 8 : 16;
            args = (width == 8 ? R8[op & 7] : R16[op & 7]) + ", " + c.imm(width);
        } else if (op >= 0xD8 && op <= 0xDF) {
            modrm = c.u8();
            name = "ESC";
            args = c.rm(modrm, 16, segment);
            unsupported = true;
        } else {
            switch (op) {
                case 0x06, 0x0E, 0x16, 0x1E -> { name = "PUSH"; args = SREG[(op >>> 3) & 3]; }
                case 0x07, 0x17, 0x1F -> { name = "POP"; args = SREG[(op >>> 3) & 3]; }
                case 0x27 -> name = "DAA";
                case 0x2F -> name = "DAS";
                case 0x37 -> name = "AAA";
                case 0x3F -> name = "AAS";
                case 0x80, 0x81, 0x83 -> {
                    modrm = c.u8(); ext = (modrm >>> 3) & 7;
                    width = op == 0x80 ? 8 : 16;
                    name = ALU[ext];
                    args = c.rm(modrm, width, segment) + ", " + (op == 0x83 ? c.signed8() : c.imm(width));
                }
                case 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B -> {
                    modrm = c.u8(); width = (op & 1) == 0 ? 8 : 16;
                    name = op <= 0x85 ? "TEST" : op <= 0x87 ? "XCHG" : "MOV";
                    String rm = c.rm(modrm, width, segment), reg = c.reg(modrm, width);
                    args = op >= 0x8A ? reg + ", " + rm : rm + ", " + reg;
                }
                case 0x8C, 0x8E -> {
                    modrm = c.u8(); ext = (modrm >>> 3) & 7;
                    if (ext >= 4 || op == 0x8E && ext == 1) return db(memory, cs, ip);
                    name = "MOV";
                    String rm = c.rm(modrm, 16, segment), sr = SREG[ext];
                    args = op == 0x8C ? rm + ", " + sr : sr + ", " + rm;
                }
                case 0x8D, 0xC4, 0xC5 -> {
                    modrm = c.u8();
                    if ((modrm & 0xC0) == 0xC0) return db(memory, cs, ip);
                    name = op == 0x8D ? "LEA" : op == 0xC4 ? "LES" : "LDS";
                    String source = c.rm(modrm, 16, segment);
                    args = c.reg(modrm, 16) + ", " + (op == 0x8D ? source : source.replaceFirst("^WORD ", "DWORD "));
                }
                case 0x8F -> {
                    modrm = c.u8(); if ((modrm & 0x38) != 0) return db(memory, cs, ip);
                    name = "POP"; args = c.rm(modrm, 16, segment);
                }
                case 0x90 -> name = "NOP";
                case 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97 -> { name = "XCHG"; args = "AX, " + R16[op & 7]; }
                case 0x98 -> name = "CBW";
                case 0x99 -> name = "CWD";
                case 0x9A -> { name = "CALL"; args = c.farPointer(); call = true; }
                case 0x9B -> name = "WAIT";
                case 0x9C -> name = "PUSHF";
                case 0x9D -> name = "POPF";
                case 0x9E -> name = "SAHF";
                case 0x9F -> name = "LAHF";
                case 0xA0, 0xA1, 0xA2, 0xA3 -> {
                    name = "MOV"; width = (op & 1) == 0 ? 8 : 16;
                    String acc = width == 8 ? "AL" : "AX";
                    String mem = segment + "[" + c.imm(16) + "]";
                    args = op < 0xA2 ? acc + ", " + mem : mem + ", " + acc;
                }
                case 0xA4, 0xA5 -> name = (op & 1) == 0 ? "MOVSB" : "MOVSW";
                case 0xA6, 0xA7 -> name = (op & 1) == 0 ? "CMPSB" : "CMPSW";
                case 0xA8, 0xA9 -> { name = "TEST"; width = (op & 1) == 0 ? 8 : 16;
                    args = (width == 8 ? "AL" : "AX") + ", " + c.imm(width); }
                case 0xAA, 0xAB -> name = (op & 1) == 0 ? "STOSB" : "STOSW";
                case 0xAC, 0xAD -> name = (op & 1) == 0 ? "LODSB" : "LODSW";
                case 0xAE, 0xAF -> name = (op & 1) == 0 ? "SCASB" : "SCASW";
                case 0xC2, 0xCA -> { name = op == 0xC2 ? "RET" : "RETF"; args = c.imm(16); }
                case 0xC3 -> name = "RET";
                case 0xC6, 0xC7 -> {
                    modrm = c.u8(); if ((modrm & 0x38) != 0) return db(memory, cs, ip);
                    width = op == 0xC6 ? 8 : 16; name = "MOV";
                    args = c.rm(modrm, width, segment) + ", " + c.imm(width);
                }
                case 0xCB -> name = "RETF";
                case 0xCC -> name = "INT3";
                case 0xCD -> { name = "INT"; args = c.imm(8); }
                case 0xCE -> name = "INTO";
                case 0xCF -> name = "IRET";
                case 0xD0, 0xD1, 0xD2, 0xD3 -> {
                    modrm = c.u8(); ext = (modrm >>> 3) & 7;
                    if (SHIFT[ext] == null) return db(memory, cs, ip);
                    width = (op & 1) == 0 ? 8 : 16; name = SHIFT[ext];
                    args = c.rm(modrm, width, segment) + ", " + (op < 0xD2 ? "1" : "CL");
                }
                case 0xD4 -> { name = "AAM"; args = c.imm(8); }
                case 0xD5 -> { name = "AAD"; args = c.imm(8); }
                case 0xD7 -> name = "XLAT";
                case 0xE0 -> { name = "LOOPNE"; args = c.rel8(); }
                case 0xE1 -> { name = "LOOPE"; args = c.rel8(); }
                case 0xE2 -> { name = "LOOP"; args = c.rel8(); }
                case 0xE3 -> { name = "JCXZ"; args = c.rel8(); }
                case 0xE4, 0xE5, 0xE6, 0xE7 -> {
                    name = op < 0xE6 ? "IN" : "OUT";
                    String acc = (op & 1) == 0 ? "AL" : "AX", port = c.imm(8);
                    args = op < 0xE6 ? acc + ", " + port : port + ", " + acc;
                }
                case 0xE8 -> { name = "CALL"; args = c.rel16(); call = true; }
                case 0xE9 -> { name = "JMP"; args = c.rel16(); }
                case 0xEA -> { name = "JMP"; args = c.farPointer(); }
                case 0xEB -> { name = "JMP"; args = c.rel8(); }
                case 0xEC, 0xED, 0xEE, 0xEF -> {
                    name = op < 0xEE ? "IN" : "OUT";
                    String acc = (op & 1) == 0 ? "AL" : "AX";
                    args = op < 0xEE ? acc + ", DX" : "DX, " + acc;
                }
                case 0xF4 -> name = "HLT";
                case 0xF5 -> name = "CMC";
                case 0xF6, 0xF7 -> {
                    modrm = c.u8(); ext = (modrm >>> 3) & 7;
                    if (GROUP3[ext] == null) return db(memory, cs, ip);
                    width = op == 0xF6 ? 8 : 16; name = GROUP3[ext];
                    args = c.rm(modrm, width, segment) + (ext == 0 ? ", " + c.imm(width) : "");
                }
                case 0xF8 -> name = "CLC";
                case 0xF9 -> name = "STC";
                case 0xFA -> name = "CLI";
                case 0xFB -> name = "STI";
                case 0xFC -> name = "CLD";
                case 0xFD -> name = "STD";
                case 0xFE, 0xFF -> {
                    modrm = c.u8(); ext = (modrm >>> 3) & 7;
                    width = op == 0xFE ? 8 : 16;
                    if (ext > (op == 0xFE ? 1 : 6) || (ext == 3 || ext == 5) && (modrm & 0xC0) == 0xC0)
                        return db(memory, cs, ip);
                    name = switch (ext) {
                        case 0 -> "INC"; case 1 -> "DEC"; case 2, 3 -> "CALL";
                        case 4, 5 -> "JMP"; default -> "PUSH";
                    };
                    call = ext == 2 || ext == 3;
                    args = c.rm(modrm, width, segment);
                    if (ext == 3 || ext == 5) args = "FAR " + args.replaceFirst("^WORD ", "DWORD ");
                }
                default -> { return db(memory, cs, ip); }
            }
        }
        if (c.pos > 15) return db(memory, cs, ip);
        if (!segment.isEmpty() && args.isEmpty() && (op >= 0xA4 && op <= 0xAF || op == 0xD7))
            prefix = segment + " " + prefix;
        return new Instruction(c.cs, c.ip, c.bytes(), prefix + name, args, call, unsupported);
    }

    private static Instruction db(CPUMemory memory, int cs, int ip) {
        int value = memory.read8(cs, ip);
        return new Instruction(cs & 0xFFFF, ip & 0xFFFF, new byte[]{(byte) value},
                "DB", "0x%02X".formatted(value), false, true);
    }

    private static final class Cursor {
        final CPUMemory memory;
        final int cs, ip;
        final byte[] raw = new byte[32];
        int pos;

        Cursor(CPUMemory memory, int cs, int ip) { this.memory = memory; this.cs = cs; this.ip = ip; }
        int u8() { int v = memory.read8(cs, (ip + pos) & 0xFFFF); raw[pos++] = (byte) v; return v; }
        int u16() { return u8() | (u8() << 8); }
        String imm(int width) { return width == 8 ? "0x%02X".formatted(u8()) : "0x%04X".formatted(u16()); }
        String signed8() { int v = (byte) u8(); return v < 0 ? "-0x%02X".formatted(-v) : "0x%02X".formatted(v); }
        String rel8() { int d = (byte) u8(); return "0x%04X".formatted((ip + pos + d) & 0xFFFF); }
        String rel16() { int d = (short) u16(); return "0x%04X".formatted((ip + pos + d) & 0xFFFF); }
        String farPointer() { int off = u16(), seg = u16(); return "0x%04X:0x%04X".formatted(seg, off); }
        String reg(int modrm, int width) { return (width == 8 ? R8 : R16)[(modrm >>> 3) & 7]; }
        String rm(int modrm, int width, String segment) {
            int mod = modrm >>> 6, index = modrm & 7;
            if (mod == 3) return (width == 8 ? R8 : R16)[index];
            String address;
            if (mod == 0 && index == 6) address = imm(16);
            else {
                address = EA[index];
                if (mod == 1) {
                    int d = (byte) u8();
                    if (d != 0) address += d < 0 ? "-0x%02X".formatted(-d) : "+0x%02X".formatted(d);
                } else if (mod == 2) {
                    int d = (short) u16();
                    if (d != 0) address += d < 0 ? "-0x%04X".formatted(-d) : "+0x%04X".formatted(d);
                }
            }
            return (width == 8 ? "BYTE " : "WORD ") + segment + "[" + address + "]";
        }
        byte[] bytes() { return Arrays.copyOf(raw, pos); }
    }
}
