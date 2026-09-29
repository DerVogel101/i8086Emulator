package dervogel101.de.state;

public enum CPURegisterFlagsEnum {
    CF(1 << 0),
    PF(1 << 2),
    AF(1 << 4),
    ZF(1 << 6),
    SF(1 << 7),
    TF(1 << 8),
    IF(1 << 9),
    DF(1 << 10),
    OF(1 << 11);

    private final int mask;

    CPURegisterFlagsEnum(int mask) {
        this.mask = mask;
    }

    public int getMask() {
        return mask;
    }
}
