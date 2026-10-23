package de.daubli.feedwatch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.text.SpannableStringBuilder;

/**
 * Typing an IP address of an additional source: the dots are added after each block of three digits.
 */
@RunWith(RobolectricTestRunner.class)
public class SettingsActivityAddressInputTest {

    @Test
    public void addsADotAfterThreeDigits() {
        assertEquals("192.", afterTyping("192"));
        assertEquals("192.168.", afterTyping("192.168"));
        assertEquals("192.168.100.", afterTyping("192.168.100"));
    }

    @Test
    public void leavesShorterBlocks() {
        assertEquals("1", afterTyping("1"));
        assertEquals("10.0", afterTyping("10.0"));
        assertEquals("192.168.1", afterTyping("192.168.1"));
    }

    @Test
    public void addsNoDotAfterTheLastBlock() {
        assertEquals("192.168.1.100", afterTyping("192.168.1.100"));
    }

    @Test
    public void dropsAFourthDigit() {
        assertEquals("192.168.1.100", afterTyping("192.168.1.1001"));
        assertEquals("192.168", afterTyping("192.1681"));
    }

    @Test
    public void dropsADotTypedAfterTheAddedOne() {
        assertEquals("192.", afterTyping("192.."));
    }

    @Test
    public void leavesAnEmptyInput() {
        assertEquals("", afterTyping(""));
    }

    private static String afterTyping(String text) {
        SpannableStringBuilder editable = new SpannableStringBuilder(text);
        SettingsActivity.insertAddressDot(editable);
        return editable.toString();
    }
}
