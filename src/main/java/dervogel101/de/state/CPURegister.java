package dervogel101.de.state;

import org.jetbrains.annotations.NotNull;

public class CPURegister {
    private int ax, bx, cx, dx;
    private int sp, bp, si, di;

    private int cs, ds, ss, es;
    private int ip;

    private int flags;

    public boolean getFlag(@NotNull CPURegisterFlagsEnum flag) {
        return (flags & flag.getMask()) != 0;
    }

    public void setFlag(CPURegisterFlagsEnum flag, boolean value) {
        if (value) {
            flags |= flag.getMask();
        } else {
            flags &= ~flag.getMask();
        }
    }

    public boolean getCF() {
        return getFlag(CPURegisterFlagsEnum.CF);
    }

    public void setCF(boolean value) {
        setFlag(CPURegisterFlagsEnum.CF, value);
    }

    public boolean getPF() {
        return getFlag(CPURegisterFlagsEnum.PF);
    }

    public void setPF(boolean value) {
        setFlag(CPURegisterFlagsEnum.PF, value);
    }

    public boolean getAF() {
        return getFlag(CPURegisterFlagsEnum.AF);
    }

    public void setAF(boolean value) {
        setFlag(CPURegisterFlagsEnum.AF, value);
    }

    public boolean getZF() {
        return getFlag(CPURegisterFlagsEnum.ZF);
    }

    public void setZF(boolean value) {
        setFlag(CPURegisterFlagsEnum.ZF, value);
    }

    public boolean getSF() {
        return getFlag(CPURegisterFlagsEnum.SF);
    }

    public void setSF(boolean value) {
        setFlag(CPURegisterFlagsEnum.SF, value);
    }

    public boolean getTF() {
        return getFlag(CPURegisterFlagsEnum.TF);
    }

    public void setTF(boolean value) {
        setFlag(CPURegisterFlagsEnum.TF, value);
    }

    public boolean getIF() {
        return getFlag(CPURegisterFlagsEnum.IF);
    }

    public void setIF(boolean value) {
        setFlag(CPURegisterFlagsEnum.IF, value);
    }

    public boolean getDF() {
        return getFlag(CPURegisterFlagsEnum.DF);
    }

    public void setDF(boolean value) {
        setFlag(CPURegisterFlagsEnum.DF, value);
    }

    public boolean getOF() {
        return getFlag(CPURegisterFlagsEnum.OF);
    }

    public void setOF(boolean value) {
        setFlag(CPURegisterFlagsEnum.OF, value);
    }

    public void setAx(int value) {
        this.ax = value & 0xFFFF;
    }

    public int getAx() {
        return ax;
    }

    public void setBx(int value) {
        this.bx = value & 0xFFFF;
    }

    public int getBx() {
        return bx;
    }

    public void setCx(int value) {
        this.cx = value & 0xFFFF;
    }

    public int getCx() {
        return cx;
    }

    public void setDx(int value) {
        this.dx = value & 0xFFFF;
    }

    public int getDx() {
        return dx;
    }

    public void setSp(int value) {
        this.sp = value & 0xFFFF;
    }

    public int getSp() {
        return sp;
    }

    public void setBp(int value) {
        this.bp = value & 0xFFFF;
    }

    public int getBp() {
        return bp;
    }

    public void setSi(int value) {
        this.si = value & 0xFFFF;
    }

    public int getSi() {
        return si;
    }

    public void setDi(int value) {
        this.di = value & 0xFFFF;
    }

    public int getDi() {
        return di;
    }

    public void setCs(int value) {
        this.cs = value & 0xFFFF;
    }

    public int getCs() {
        return cs;
    }

    public void setDs(int value) {
        this.ds = value & 0xFFFF;
    }

    public int getDs() {
        return ds;
    }

    public void setSs(int value) {
        this.ss = value & 0xFFFF;
    }

    public int getSs() {
        return ss;
    }

    public void setEs(int value) {
        this.es = value & 0xFFFF;
    }

    public int getEs() {
        return es;
    }

    public void setIp(int value) {
        this.ip = value & 0xFFFF;
    }

    public int getIp() {
        return ip;
    }

    public void setFlags(int value) {
        this.flags = value & 0xFFFF;
    }

    public int getFlags() {
        return flags;
    }
}
