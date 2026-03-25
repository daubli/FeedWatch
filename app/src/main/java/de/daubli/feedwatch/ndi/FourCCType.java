package de.daubli.feedwatch.ndi;

public enum FourCCType {
    UYVY('U', 'Y', 'V', 'Y'), BGRA('B', 'G', 'R', 'A'), RGBA('R', 'G', 'B', 'A');

    public int id;

    FourCCType(char c1, char c2, char c3, char c4) {
        id = (c1 & 0xFF) | ((c2 & 0xFF) << 8) | ((c3 & 0xFF) << 16) | ((c4 & 0xFF) << 24);
    }

    public static FourCCType valueOf(int id) throws IllegalArgumentException {
        if (id == UYVY.id) {
            return UYVY;
        } else if (id == BGRA.id) {
            return BGRA;
        } else if (id == RGBA.id) {
            return RGBA;
        } else {
            throw new IllegalArgumentException("Unknown FourCC type id: " + id);
        }
    }

    public static String fourCCToString(int fourCC) {
        char a = (char) (fourCC & 0xFF);
        char b = (char) ((fourCC >> 8) & 0xFF);
        char c = (char) ((fourCC >> 16) & 0xFF);
        char d = (char) ((fourCC >> 24) & 0xFF);
        return "" + a + b + c + d;
    }
}
