package de.daubli.feedwatch.sources;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import de.daubli.feedwatch.ndi.NdiSource;

@RunWith(RobolectricTestRunner.class)
public class SourceListAdapterNdiNameTest {

    private final Context context = ApplicationProvider.getApplicationContext();

    private final TextView title = new TextView(context);

    private final TextView subtitle = new TextView(context);

    @Test
    public void showsTheSourceAboveItsMachine() {
        bind("STUDIO-PC (OBS Program)", "192.168.1.20:5961");

        assertEquals("OBS Program", title.getText().toString());
        assertEquals("STUDIO-PC · 192.168.1.20:5961", subtitle.getText().toString());
        assertEquals(View.VISIBLE, subtitle.getVisibility());
    }

    @Test
    public void showsOnlyTheMachineWithoutAddress() {
        bind("STUDIO-PC (Cam 1)", "");

        assertEquals("Cam 1", title.getText().toString());
        assertEquals("STUDIO-PC", subtitle.getText().toString());
    }

    @Test
    public void keepsParenthesesInsideTheSourceName() {
        bind("MAC (Zoom (Gallery))", "");

        assertEquals("Zoom (Gallery)", title.getText().toString());
        assertEquals("MAC", subtitle.getText().toString());
    }

    @Test
    public void showsOtherNamesAsTheyAre() {
        bind("Camera 1", "10.0.0.5:5961");

        assertEquals("Camera 1", title.getText().toString());
        assertEquals("10.0.0.5:5961", subtitle.getText().toString());
        assertEquals(View.VISIBLE, subtitle.getVisibility());
    }

    @Test
    public void showsNamesWithoutMachineAsTheyAre() {
        bind("(Cam 1)", "");

        assertEquals("(Cam 1)", title.getText().toString());
    }

    @Test
    public void hidesAnEmptySubtitle() {
        subtitle.setVisibility(View.VISIBLE);

        bind("Camera 1", "");

        assertEquals(View.GONE, subtitle.getVisibility());
    }

    private void bind(String name, String address) {
        SourceListAdapter.bindNdiName(new NdiSource(name, address), title, subtitle);
    }
}
