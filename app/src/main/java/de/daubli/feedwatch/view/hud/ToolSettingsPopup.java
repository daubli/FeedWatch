package de.daubli.feedwatch.view.hud;

import java.util.List;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import com.google.android.material.slider.Slider;
import de.daubli.feedwatch.R;

/**
 * Small popup with the settings of a monitoring tool, opened above its button in the stream HUD. Each section lets
 * the user pick one of a few choices or set a value on a slider, a change applies right away and the popup stays
 * open.
 * <p>
 * The popup does not take focus, so the stream stays in immersive fullscreen. Touches outside of it reach the
 * screen below, which should close it with {@link #dismiss()}.
 */
public class ToolSettingsPopup {

    /**
     * One choice of a section, optionally shown with a color dot.
     */
    public static final class Choice {

        final CharSequence label;

        @Nullable
        @ColorInt
        final Integer swatchColor;

        public Choice(CharSequence label) {
            this(label, null);
        }

        public Choice(CharSequence label, @Nullable @ColorInt Integer swatchColor) {
            this.label = label;
            this.swatchColor = swatchColor;
        }
    }

    public interface OnChoiceSelectedListener {

        void onChoiceSelected(int index);
    }

    public interface ValueFormatter {

        CharSequence format(int value);
    }

    public interface OnValueChangedListener {

        void onValueChanged(int value);
    }

    private final LayoutInflater inflater;

    private final View content;

    private final ViewGroup sections;

    private final PopupWindow window;

    public ToolSettingsPopup(Context context, CharSequence title) {
        inflater = LayoutInflater.from(context);
        content = inflater.inflate(R.layout.popup_tool_settings, null);
        sections = content.findViewById(R.id.toolSettingsSections);
        ((TextView) content.findViewById(R.id.toolSettingsTitle)).setText(title);

        window = new PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
        // the content draws its own rounded background
        window.setBackgroundDrawable(new ColorDrawable(0));
        window.setElevation(content.getElevation());
    }

    /**
     * Adds a section with the given choices, one of them selected.
     */
    public ToolSettingsPopup addChoices(CharSequence label, List<Choice> choices, int selected,
            OnChoiceSelectedListener listener) {
        View section = inflater.inflate(R.layout.item_tool_settings_section, sections, false);
        ((TextView) section.findViewById(R.id.toolSettingsSectionLabel)).setText(label);
        ViewGroup choiceViews = section.findViewById(R.id.toolSettingsChoices);

        for (int i = 0; i < choices.size(); i++) {
            Choice choice = choices.get(i);
            View choiceView = inflater.inflate(R.layout.item_tool_settings_choice, choiceViews, false);
            ((TextView) choiceView.findViewById(R.id.toolSettingsChoiceLabel)).setText(choice.label);

            if (choice.swatchColor != null) {
                View swatch = choiceView.findViewById(R.id.toolSettingsChoiceSwatch);
                swatch.setBackgroundTintList(ColorStateList.valueOf(choice.swatchColor));
                swatch.setVisibility(View.VISIBLE);
            }

            int index = i;
            choiceView.setActivated(i == selected);
            choiceView.setOnClickListener(v -> {
                for (int c = 0; c < choiceViews.getChildCount(); c++) {
                    choiceViews.getChildAt(c).setActivated(c == index);
                }
                listener.onChoiceSelected(index);
            });
            choiceViews.addView(choiceView);
        }

        // the last choice needs no gap after it
        View last = choiceViews.getChildAt(choiceViews.getChildCount() - 1);
        if (last != null) {
            ((LinearLayout.LayoutParams) last.getLayoutParams()).setMarginEnd(0);
        }

        sections.addView(section);
        return this;
    }

    /**
     * Adds a section with a slider for whole numbers from {@code from} to {@code to}. The current value is shown next
     * to the label, the ends of the range below the slider. The listener is called while the slider is dragged.
     */
    public ToolSettingsPopup addSlider(CharSequence label, int from, int to, int value, ValueFormatter formatter,
            OnValueChangedListener listener) {
        View section = inflater.inflate(R.layout.item_tool_settings_slider, sections, false);
        ((TextView) section.findViewById(R.id.toolSettingsSliderLabel)).setText(label);
        ((TextView) section.findViewById(R.id.toolSettingsSliderMin)).setText(formatter.format(from));
        ((TextView) section.findViewById(R.id.toolSettingsSliderMax)).setText(formatter.format(to));
        TextView valueView = section.findViewById(R.id.toolSettingsSliderValue);

        int clamped = Math.max(from, Math.min(to, value));
        valueView.setText(formatter.format(clamped));

        Slider slider = section.findViewById(R.id.toolSettingsSlider);
        slider.setValueFrom(from);
        slider.setValueTo(to);
        slider.setStepSize(1);
        slider.setValue(clamped);
        slider.addOnChangeListener((s, newValue, fromUser) -> {
            int rounded = Math.round(newValue);
            valueView.setText(formatter.format(rounded));
            if (fromUser) {
                listener.onValueChanged(rounded);
            }
        });

        sections.addView(section);
        return this;
    }

    public void setOnDismissListener(@Nullable PopupWindow.OnDismissListener listener) {
        window.setOnDismissListener(listener);
    }

    /**
     * Shows the popup above the anchor, aligned to its left edge and kept inside the window.
     */
    public void showAbove(View anchor) {
        float density = anchor.getResources().getDisplayMetrics().density;
        int gap = Math.round(8 * density);
        int margin = Math.round(8 * density);

        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int width = content.getMeasuredWidth();
        int height = content.getMeasuredHeight();

        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        int windowWidth = anchor.getRootView().getWidth();

        int x = Math.max(margin, Math.min(location[0], windowWidth - width - margin));
        int y = Math.max(margin, location[1] - height - gap);
        window.showAtLocation(anchor, Gravity.TOP | Gravity.START, x, y);
    }

    public boolean isShowing() {
        return window.isShowing();
    }

    public void dismiss() {
        window.dismiss();
    }
}
