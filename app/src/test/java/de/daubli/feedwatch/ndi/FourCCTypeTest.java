package de.daubli.feedwatch.ndi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class FourCCTypeTest {

    @Test
    public void idMatchesTheNdiFourCCCode() {
        // NDIlib_FourCC_video_type_UYVY = NDI_LIB_FOURCC('U', 'Y', 'V', 'Y'), first character in the lowest byte
        assertEquals(0x59565955, FourCCType.UYVY.id);
        assertEquals(0x41524742, FourCCType.BGRA.id);
        assertEquals(0x41424752, FourCCType.RGBA.id);
    }

    @Test
    public void valueOfFindsEachType() {
        for (FourCCType type : FourCCType.values()) {
            assertEquals(type, FourCCType.valueOf(type.id));
        }
    }

    @Test
    public void valueOfRejectsAnUnknownId() {
        int nv12 = 'N' | 'V' << 8 | '1' << 16 | '2' << 24;

        assertThrows(IllegalArgumentException.class, () -> FourCCType.valueOf(nv12));
    }

    @Test
    public void fourCCToStringSpellsTheCode() {
        assertEquals("UYVY", FourCCType.fourCCToString(FourCCType.UYVY.id));
        assertEquals("BGRA", FourCCType.fourCCToString(FourCCType.BGRA.id));
        assertEquals("P216", FourCCType.fourCCToString('P' | '2' << 8 | '1' << 16 | '6' << 24));
    }
}
