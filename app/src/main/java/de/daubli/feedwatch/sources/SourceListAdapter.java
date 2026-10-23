package de.daubli.feedwatch.sources;

import java.util.List;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import de.daubli.feedwatch.R;
import de.daubli.feedwatch.ndi.NdiSource;

/**
 * Source list rows: a type badge, the source name, where it comes from and whether it is the default source.
 */
public class SourceListAdapter extends ArrayAdapter<VideoSource> {

    private final LayoutInflater inflater;

    @Nullable
    private String defaultSourceKey;

    public SourceListAdapter(@NonNull Context context, @NonNull List<VideoSource> sources) {
        super(context, 0, sources);
        inflater = LayoutInflater.from(context);
    }

    public void setDefaultSourceKey(@Nullable String defaultSourceKey) {
        this.defaultSourceKey = defaultSourceKey;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View view = convertView != null ? convertView : inflater.inflate(R.layout.item_source, parent, false);
        TextView badge = view.findViewById(R.id.sourceBadge);
        TextView title = view.findViewById(R.id.sourceTitle);
        TextView subtitle = view.findViewById(R.id.sourceSubtitle);
        View defaultBadge = view.findViewById(R.id.sourceDefaultBadge);

        VideoSource source = getItem(position);

        boolean isDefault = source != null && source.getStableKey().equals(defaultSourceKey);
        defaultBadge.setVisibility(isDefault ? View.VISIBLE : View.GONE);

        if (source instanceof NdiSource) {
            badge.setText(R.string.badge_ndi);
            badge.setBackgroundResource(R.drawable.bg_badge_ndi);
            bindNdiName((NdiSource) source, title, subtitle);
        } else {
            badge.setText(R.string.badge_usb);
            badge.setBackgroundResource(R.drawable.bg_badge_usb);
            String name = source != null ? source.getSourceName() : null;
            title.setText(name != null && !name.isEmpty() ? name : getContext().getString(R.string.badge_usb));
            subtitle.setText(R.string.source_usb_subtitle);
            subtitle.setVisibility(View.VISIBLE);
        }
        return view;
    }

    // NDI names look like "MACHINE (Source)": show the source big and the machine below it
    @VisibleForTesting
    static void bindNdiName(NdiSource source, TextView title, TextView subtitle) {
        String name = source.getSourceName();
        String address = source.getUrlAddress();
        int open = name.indexOf(" (");

        if (open > 0 && name.endsWith(")")) {
            String host = name.substring(0, open);
            title.setText(name.substring(open + 2, name.length() - 1));
            subtitle.setText(address.isEmpty() ? host : host + " · " + address);
        } else {
            title.setText(name);
            subtitle.setText(address);
        }
        subtitle.setVisibility(subtitle.length() > 0 ? View.VISIBLE : View.GONE);
    }
}
