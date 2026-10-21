package bolen.analyst;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/** Окно «Настройки» плагина: где показывать кнопку, её размер и прозрачность, блоки отчёта. */
final class SettingsDialog {

    static final String MODE = "mode";
    static final String MODE_OWN = "own";
    static final String MODE_ALL = "all";
    static final String MODE_ALWAYS = "always";
    static final String SIZE = "btn_size";
    static final String ALPHA = "btn_alpha";
    static final String REP_FORECAST = "rep_forecast";
    static final String REP_COMPARE = "rep_compare";
    static final String REP_GOOD = "rep_good";
    static final String REP_FIX = "rep_fix";

    private static final String[] MODES = {MODE_OWN, MODE_ALL, MODE_ALWAYS};
    private static final String[] MODE_NAMES = {"Только на моих роликах", "На всех роликах", "Всегда (на любом экране)"};
    private static final int[] SIZES = {40, 50, 62};
    private static final String[] SIZE_NAMES = {"Маленькая", "Средняя", "Большая"};
    private static final float[] ALPHAS = {1f, 0.7f, 0.4f};
    private static final String[] ALPHA_NAMES = {"100%", "70%", "40%"};

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Runnable onSaved;
    private final float density;

    SettingsDialog(Activity activity, SharedPreferences prefs, Runnable onSaved) {
        this.activity = activity;
        this.prefs = prefs;
        this.onSaved = onSaved;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    void show() {
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(8), pad, dp(8));

        list.addView(header("Где показывать кнопку 📊"));
        final RadioGroup mode = radios(MODE_NAMES, indexOf(MODES, prefs.getString(MODE, MODE_OWN)), false);
        list.addView(mode);
        list.addView(hint("На чужих роликах просмотров на экране нет: плагин ищет их в данных ленты, "
                + "а если не нашёл — показывает реакцию по лайкам."));

        list.addView(header("Размер кнопки"));
        final RadioGroup size = radios(SIZE_NAMES, indexOf(SIZES, prefs.getInt(SIZE, 50)), true);
        list.addView(size);

        list.addView(header("Прозрачность кнопки"));
        final RadioGroup alpha = radios(ALPHA_NAMES, indexOf(ALPHAS, prefs.getFloat(ALPHA, 1f)), true);
        list.addView(alpha);

        list.addView(header("Что показывать в отчёте"));
        final CheckBox forecast = check("📈 Прогноз просмотров", prefs.getBoolean(REP_FORECAST, true));
        final CheckBox compare = check("Сравнение с роликами из ленты", prefs.getBoolean(REP_COMPARE, true));
        final CheckBox good = check("✅ Что работает", prefs.getBoolean(REP_GOOD, true));
        final CheckBox fix = check("🛠 Что исправить", prefs.getBoolean(REP_FIX, true));
        list.addView(forecast);
        list.addView(compare);
        list.addView(good);
        list.addView(fix);

        list.addView(header("Кнопка"));
        final CheckBox resetPlace = check("Вернуть кнопку на исходное место", false);
        list.addView(resetPlace);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);

        new AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("📊 Настройки аналитика")
                .setView(scroll)
                .setPositiveButton("Сохранить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        SharedPreferences.Editor e = prefs.edit()
                                .putString(MODE, MODES[checked(mode)])
                                .putInt(SIZE, SIZES[checked(size)])
                                .putFloat(ALPHA, ALPHAS[checked(alpha)])
                                .putBoolean(REP_FORECAST, forecast.isChecked())
                                .putBoolean(REP_COMPARE, compare.isChecked())
                                .putBoolean(REP_GOOD, good.isChecked())
                                .putBoolean(REP_FIX, fix.isChecked());
                        if (resetPlace.isChecked()) e.remove("btn_x").remove("btn_y");
                        e.apply();
                        onSaved.run();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private TextView header(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(12);
        t.setAlpha(0.7f);
        return t;
    }

    private RadioGroup radios(String[] names, int selected, boolean horizontal) {
        RadioGroup g = new RadioGroup(activity);
        g.setOrientation(horizontal ? RadioGroup.HORIZONTAL : RadioGroup.VERTICAL);
        for (int i = 0; i < names.length; i++) {
            RadioButton b = new RadioButton(activity);
            b.setId(1000 + i);
            b.setText(names[i]);
            g.addView(b);
        }
        g.check(1000 + Math.max(0, selected));
        return g;
    }

    private static int checked(RadioGroup g) {
        int i = g.getCheckedRadioButtonId() - 1000;
        return i < 0 || i >= g.getChildCount() ? 0 : i;
    }

    private CheckBox check(String text, boolean value) {
        CheckBox c = new CheckBox(activity);
        c.setText(text);
        c.setChecked(value);
        return c;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) if (values[i] == value) return i;
        return 0;
    }

    private static int indexOf(float[] values, float value) {
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - value) < 0.01f) return i;
        return 0;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }
}
