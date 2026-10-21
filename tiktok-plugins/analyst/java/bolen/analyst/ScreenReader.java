package bolen.analyst;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Читает статистику ролика прямо с экрана. На СВОИХ роликах TikTok рисует строку
 * «Просмотры: 462» (и «Больше статистики»), на чужих её нет — по ней плагин понимает,
 * что ролик твой. Лайки, комментарии, сохранения и репосты — числа в правой колонке
 * сверху вниз, возраст — «4 ч. назад», автор — слева от возраста.
 */
final class ScreenReader {

    static final class Item {
        final String text;
        final Rect rect;

        Item(String text, Rect rect) {
            this.text = text;
            this.rect = rect;
        }
    }

    private static final Pattern VIEWS = Pattern.compile(
            "^(просмотры|просмотров|views|перегляди)\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VIEWS_LABEL = Pattern.compile(
            "^(просмотры|просмотров|views|перегляди)\\s*:?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COUNT =Pattern.compile(
            "^(\\d+(?:[.,]\\d+)?)\\s*(тыс|млн|млрд|k|m|b)?\\.?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern AGE = Pattern.compile(
            "(\\d+)\\s*(мин|ч|д|дн|нед|min|m|h|d|w)\\.?\\s*(назад|ago)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private ScreenReader() {}

    static void collect(View v, View ignore, List<Item> out) {
        if (v == ignore || v.getVisibility() != View.VISIBLE || out.size() > 300) return;
        if (v instanceof TextView) {
            CharSequence t = ((TextView) v).getText();
            Rect r = new Rect();
            if (t != null && t.length() > 0 && v.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0) {
                out.add(new Item(t.toString().trim(), r));
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), ignore, out);
        }
    }

    /**
     * Ролик на экране или null. Свой ролик (own) — со строкой «Просмотры: …»; чужой узнаётся
     * по «… назад» и колонке чисел справа, просмотров у него на экране нет (play = −1).
     */
    static Analyst.Video read(List<Item> items, int screenWidth) {
        Item viewsItem = null;
        long views = -1;
        for (Item it : items) {
            Matcher m = VIEWS.matcher(it.text);
            if (m.find()) {
                long n = count(m.group(2));
                if (n >= 0) {
                    viewsItem = it;
                    views = n;
                    break;
                }
            }
            if (VIEWS_LABEL.matcher(it.text).matches()) {
                // «Просмотры:» и «462» — разные TextView: число справа в той же строке
                Item best = null;
                for (Item other : items) {
                    if (other == it || other.rect.left < it.rect.left || count(other.text) < 0) continue;
                    if (Math.abs(other.rect.centerY() - it.rect.centerY()) > it.rect.height()) continue;
                    if (best == null || other.rect.left < best.rect.left) best = other;
                }
                if (best != null) {
                    viewsItem = it;
                    views = count(best.text);
                    break;
                }
            }
        }

        Item ageItem = null;
        double ageHours = -1;
        for (Item it : items) {
            Matcher m = AGE.matcher(it.text);
            if (m.find()) {
                ageHours = hours(Long.parseLong(m.group(1)), m.group(2).toLowerCase(Locale.ROOT));
                ageItem = it;
                break;
            }
        }
        if (viewsItem == null && ageItem == null) return null;

        // правая колонка: лайки, комментарии, сохранения, репосты — сверху вниз
        int bound = viewsItem != null ? viewsItem.rect.top : ageItem.rect.bottom + ageItem.rect.height();
        List<Item> column = new ArrayList<>();
        for (Item it : items) {
            if (it.rect.centerX() > screenWidth * 0.75 && it.rect.bottom <= bound && count(it.text) >= 0) {
                column.add(it);
            }
        }
        if (viewsItem == null && column.size() < 3) return null; // не экран ролика (профиль, поиск…)
        Collections.sort(column, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.rect.top - b.rect.top;
            }
        });

        Analyst.Video v = new Analyst.Video();
        v.fromScreen = true;
        v.own = viewsItem != null;
        v.play = views;
        v.digg = v.comment = v.collect = v.share = -1;
        if (column.size() >= 4) {
            List<Item> last = column.subList(column.size() - 4, column.size());
            v.digg = count(last.get(0).text);
            v.comment = count(last.get(1).text);
            v.collect = count(last.get(2).text);
            v.share = count(last.get(3).text);
        } else if (column.size() == 3) {
            v.digg = count(column.get(0).text);
            v.comment = count(column.get(1).text);
            v.share = count(column.get(2).text);
        }

        if (ageItem != null) {
            v.createTime = (long) (System.currentTimeMillis() / 1000.0 - ageHours * 3600);
            v.ageApprox = true;

            // автор — текст слева от «… назад» в той же строке
            Item author = null;
            for (Item it : items) {
                if (it != ageItem && it.rect.right <= ageItem.rect.left + 4 && count(it.text) < 0
                        && Math.abs(it.rect.centerY() - ageItem.rect.centerY()) < ageItem.rect.height()
                        && (author == null || it.rect.right > author.rect.right)) {
                    author = it;
                }
            }
            if (author != null) v.nickname = author.text;

            // описание — первый текст слева под строкой с «… назад»
            int descBottom = viewsItem != null ? viewsItem.rect.top + 4 : ageItem.rect.bottom + ageItem.rect.height() * 4;
            Item desc = null;
            for (Item it : items) {
                if (it.rect.top >= ageItem.rect.bottom - 4 && it.rect.bottom <= descBottom
                        && it.rect.centerX() < screenWidth * 0.75 && count(it.text) < 0
                        && (desc == null || it.rect.top < desc.rect.top)) {
                    desc = it;
                }
            }
            if (desc != null) v.setDesc(desc.text);
        }
        return v;
    }

    /** «462», «62,2 тыс.», «1.2M», «6 372» → число, или −1. */
    static long count(String s) {
        if (s == null) return -1;
        String t = s.replace(' ', ' ').replaceAll("(?<=\\d) (?=\\d{3}\\b)", "").trim();
        Matcher m = COUNT.matcher(t);
        if (!m.matches()) return -1;
        double n = Double.parseDouble(m.group(1).replace(',', '.'));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
        switch (unit) {
            case "тыс":
            case "k":
                n *= 1_000;
                break;
            case "млн":
            case "m":
                n *= 1_000_000;
                break;
            case "млрд":
            case "b":
                n *= 1_000_000_000;
                break;
            default:
                break;
        }
        return Math.round(n);
    }

    private static double hours(long n, String unit) {
        switch (unit) {
            case "мин":
            case "min":
            case "m":
                return n / 60.0;
            case "ч":
            case "h":
                return n;
            case "нед":
            case "w":
                return n * 24 * 7;
            default:
                return n * 24;
        }
    }
}
