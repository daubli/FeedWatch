package de.daubli.feedwatch;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import de.daubli.feedwatch.view.SystemInsets;

public class AboutActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        SystemInsets.padForSystemBars(findViewById(android.R.id.content));

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView versionText = findViewById(R.id.versionText);
        versionText.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));

        TextView buildText = findViewById(R.id.buildText);
        buildText.setText(getString(R.string.about_build, BuildConfig.GIT_COMMIT));
    }
}
