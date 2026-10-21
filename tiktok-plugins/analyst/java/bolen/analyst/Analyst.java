package bolen.analyst;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.ss.android.ugc.aweme.ttp.plugin.Plugin;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * «📊 Аналитик»: перетаскиваемая кнопка 📊, которая появляется только на твоих роликах.
 * По нажатию — окно со скоростью набора просмотров, вовлечённостью, грубым прогнозом
 * и советами для следующего ролика.
 *
 * Какое видео на экране, мод напрямую не сообщает. Плагин раз в TICK_MS читает видимые
 * TextView (см. ScreenReader): на своих роликах TikTok пишет «Просмотры: …», оттуда же
 * берутся лайки, комментарии и возраст. Если тот же ролик пришёл в onFeed, данные уточняются.
 */
public final class Analyst extends Plugin {

    /** Характерное время затухания просмотров, часов (модель V·(1−e^(−t/τ))). */
    private static final double TAU_H = 36;
    private static final long SNAPSHOT_MIN_MS = 20 * 60_000;
    private static final long TICK_MS = 800;
    private static final int MAX_VIDEOS = 300;
    private static final int MAX_SAMPLES = 300;
    static final int DESC_KEY = 30;

    private final Host host = new Host(this);
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Map<String, Video> videos = new LinkedHashMap<String, Video>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Video> eldest) {
            return size() > MAX_VIDEOS;
        }
    };

    /** Вовлечённость и скорость роликов из ленты — для сравнения. */
    private final List<Double> scoreSamples = new ArrayList<>();
    private final List<Double> speedSamples = new ArrayList<>();

    private final Map<Activity, FloatingButton> buttons = new WeakHashMap<>();
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    /** Ролик, который сейчас виден на экране (только главный поток). */
    private Video visible;

    private int feedLogs;
    /** Диагностика: что плагин видел (окно «Диагностика» в настройках). */
    private volatile int feedCalls, parsedOk, parsedFail;
    private volatile String feedClass = "—";
    private final Map<String, String> screenDiag = new LinkedHashMap<String, String>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 3;
        }
    };
    private final Set<String> loggedScreens = new HashSet<>();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Activity a = resumed.get();
            if (a == null) return;
            try {
                FloatingButton b = buttons.get(a);
                visible = findVisible(a, b == null ? null : b.view);
                if (b != null) b.show(visible != null || host.prefs().getBoolean("always", false));
            } catch (Throwable t) {
                host.log("tick: " + t);
            }
            main.postDelayed(this, TICK_MS);
        }
    };

    // --- хуки ---

    @Override
    public void onStart(Context context) {
        loadSamples();
        prune();
        host.call("addSettingsRow", "Как пользоваться", "Кнопка 📊 на твоих роликах", new Runnable() {
            @Override
            public void run() {
                host.call("showWindow", "📊 Аналитик",
                        "Открой свой ролик (из профиля) — появится кнопка 📊. Нажми её: статистика "
                                + "именно этого ролика, прогноз и что исправить в следующем.\n\n"
                                + "Свой ролик плагин узнаёт по строке «Просмотры: …» внизу — "
                                + "TikTok показывает её только автору. Настраивать ничего не нужно.\n\n"
                                + "Кнопку можно перетащить пальцем в любое место, она запомнит его.\n\n"
                                + "Прогноз точнее, если открыть тот же ролик ещё раз через 20+ минут.");
            }
        });
        host.call("addSettingsRow", "Диагностика", "Если кнопка не появляется — открой и пришли скриншот",
                new Runnable() {
                    @Override
                    public void run() {
                        host.call("showWindow", "📊 Диагностика", diagnostics());
                    }
                });
        host.call("addSettingsRow", "Кнопка всегда на экране",
                "Для проверки: показывать 📊 везде. Нажми, чтобы переключить", new Runnable() {
                    @Override
                    public void run() {
                        boolean on = !host.prefs().getBoolean("always", false);
                        host.prefs().edit().putBoolean("always", on).apply();
                        host.call("showWindow", "📊 Аналитик", on
                                ? "Кнопка 📊 теперь видна всегда."
                                : "Кнопка 📊 снова появляется только на твоих роликах.");
                    }
                });
        host.call("addSettingsRow", "Сбросить статистику", "Забыть замеры и сравнение с лентой", new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = host.prefs();
                float x = prefs.getFloat("btn_x", 0.04f), y = prefs.getFloat("btn_y", 0.3f);
                prefs.edit().clear().putFloat("btn_x", x).putFloat("btn_y", y).apply();
                synchronized (Analyst.this) {
                    scoreSamples.clear();
                    speedSamples.clear();
                }
                host.call("showWindow", "📊 Аналитик", "Статистика сброшена. Место кнопки сохранено.");
            }
        });
        host.log("started");
    }

    @Override
    public void onScreen(Activity activity, String name) {
        if (loggedScreens.size() < 20 && loggedScreens.add(name)) host.log("screen: " + name);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        try {
            resumed = new WeakReference<>(activity);
            if (!buttons.containsKey(activity)) {
                SharedPreferences prefs = host.prefs();
                buttons.put(activity, new FloatingButton(activity,
                        prefs.getFloat("btn_x", 0.04f), prefs.getFloat("btn_y", 0.3f), buttonListener));
            }
            main.removeCallbacks(tick);
            main.post(tick);
        } catch (Throwable t) {
            host.log("resume: " + t);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        main.removeCallbacks(tick);
        FloatingButton b = buttons.get(activity);
        if (b != null) b.show(false);
    }

    @Override
    public void onStop() {
        main.removeCallbacks(tick);
        for (FloatingButton b : buttons.values()) b.detach();
        buttons.clear();
    }

    @Override
    public List onFeed(List posts) {
        if (posts == null || posts.isEmpty()) return posts;
        final List<Object> copy = new ArrayList<Object>(posts);
        try {
            host.call("away", "analyst: feed", new Runnable() {
                @Override
                public void run() {
                    parseFeed(copy);
                }
            });
        } catch (Throwable t) {
            parseFeed(copy);
        }
        return posts;
    }

    private final FloatingButton.Listener buttonListener = new FloatingButton.Listener() {
        @Override
        public void onClick() {
            try {
                analyse(visible);
            } catch (Throwable t) {
                host.log("analyse: " + t);
                host.call("showWindow", "📊 Аналитик", "Не получилось разобрать ролик. Подробности в журнале мода.");
            }
        }

        @Override
        public void onLongClick() {
            // долгое нажатие ничего не делает: оно лишь не даёт сработать клику перед перетаскиванием
        }

        @Override
        public void onMoved(float fx, float fy) {
            host.prefs().edit().putFloat("btn_x", fx).putFloat("btn_y", fy).apply();
            for (FloatingButton b : buttons.values()) {
                b.view.setX(fx * Math.max(0, ((View) b.view.getParent()).getWidth() - b.view.getWidth()));
                b.view.setY(fy * Math.max(0, ((View) b.view.getParent()).getHeight() - b.view.getHeight()));
            }
        }
    };

    // --- какой ролик на экране ---

    /** Твой ролик на экране (по строке «Просмотры: …»), дополненный данными из ленты, если нашёлся там. */
    private Video findVisible(Activity a, View ignore) {
        List<ScreenReader.Item> items = new ArrayList<>();
        View root = a.getWindow().getDecorView();
        ScreenReader.collect(root, ignore, items);
        Video found = ScreenReader.read(items, root.getWidth());
        if (found != null && found.descKey != null) {
            synchronized (this) {
                for (Video cached : videos.values()) {
                    if (found.descKey.equals(cached.descKey)) {
                        found.mergeFrom(cached);
                        break;
                    }
                }
            }
        }

        StringBuilder d = new StringBuilder();
        d.append("тексты на экране: ").append(items.size())
                .append(", твой ролик: ").append(found == null ? "нет" : found.toString());
        int shown = 0;
        for (ScreenReader.Item it : items) {
            if (shown++ >= 6) break;
            String t = it.text.replace('\n', ' ');
            d.append("\n  «").append(t.length() > 40 ? t.substring(0, 40) + "…" : t).append('»');
        }
        synchronized (screenDiag) {
            String name = a.getClass().getName();
            screenDiag.remove(name);
            screenDiag.put(name, d.toString());
        }
        return found;
    }

    static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private String diagnostics() {
        int total, withDesc = 0;
        synchronized (this) {
            total = videos.size();
            for (Video v : videos.values()) if (v.descKey != null) withDesc++;
        }
        StringBuilder s = new StringBuilder();
        s.append("onFeed вызван: ").append(feedCalls).append(" раз, класс ").append(feedClass).append('\n');
        s.append("Прочитано роликов: ").append(parsedOk).append(", не прочитано: ").append(parsedFail).append('\n');
        s.append("В памяти: ").append(total).append(", с описанием: ").append(withDesc).append('\n');
        s.append("Экраны:\n").append(loggedScreens.isEmpty() ? "—" : loggedScreens.toString()).append('\n');
        synchronized (screenDiag) {
            for (Map.Entry<String, String> e : screenDiag.entrySet()) {
                String n = e.getKey();
                s.append("\n[").append(n.substring(n.lastIndexOf('.') + 1)).append("] ").append(e.getValue()).append('\n');
            }
        }
        String text = s.toString();
        host.log("diagnostics:\n" + text);
        return text;
    }

    // --- лента ---

    private void parseFeed(List<Object> posts) {
        try {
            feedCalls++;
            Object head = posts.get(0);
            if (head != null) feedClass = head.getClass().getName();
            int parsed = 0;
            Video sample = null;
            for (Object post : posts) {
                Video v = Video.read(post);
                if (v == null) {
                    parsedFail++;
                    continue;
                }
                parsed++;
                parsedOk++;
                sample = v;
                synchronized (this) {
                    videos.put(v.aid, v);
                    if (v.play >= 1000) {
                        add(scoreSamples, v.score());
                        if (v.ageHours() > 0) add(speedSamples, v.play / v.ageHours());
                    }
                }
            }
            if (feedLogs < 3) {
                feedLogs++;
                host.log("feed: " + posts.size() + " posts (" + (head == null ? "null" : head.getClass().getName())
                        + "), parsed " + parsed + (sample == null ? "" : ", e.g. " + sample));
                saveSamples();
            }
        } catch (Throwable t) {
            host.log("feed: " + t);
        }
    }

    private static void add(List<Double> list, double value) {
        list.add(value);
        if (list.size() > MAX_SAMPLES) list.remove(0);
    }

    // --- разбор ---

    private void analyse(Video v) {
        if (v == null) {
            host.call("showWindow", "📊 Аналитик",
                    "Это не твой ролик — или плагин не нашёл на экране строку «Просмотры: …».\n\n"
                            + "Открой свой ролик из профиля. Если не помогает — «Диагностика» в настройках плагина.");
            return;
        }
        saveSamples();
        host.call("showWindow", "📊 " + (v.handle() == null ? "Твой ролик" : "@" + v.handle()), report(v));
    }

    private String report(Video v) {
        StringBuilder s = new StringBuilder();
        double age = v.ageHours();
        if (age > 0) s.append("Опубликован ").append(ago(age)).append('\n');
        if (v.desc != null && !v.desc.isEmpty()) {
            s.append('«').append(v.desc.length() > 70 ? v.desc.substring(0, 70) + "…" : v.desc).append("»\n");
        }
        s.append('\n');

        if (v.play <= 0) {
            return s.append("TikTok не отдал число просмотров для этого ролика.").toString();
        }

        double like = pct(v.digg, v.play), comment = pct(v.comment, v.play);
        double share = pct(v.share, v.play), save = pct(v.collect, v.play);

        s.append("👁 ").append(big(v.play)).append(" просмотров");
        double speed = age > 0 ? v.play / age : -1;
        if (speed > 0) s.append(" · ⚡ ").append(big((long) speed)).append("/час");
        s.append('\n');
        s.append("❤️ ").append(p(like)).append("  💬 ").append(p(comment))
                .append("  🔁 ").append(p(share)).append("  🔖 ").append(p(save)).append('\n');

        int scoreRank, speedRank;
        synchronized (this) {
            scoreRank = rank(scoreSamples, v.score());
            speedRank = speed > 0 ? rank(speedSamples, speed) : -1;
        }
        s.append("Вовлечённость: ").append(level(v.score(), 6, 12));
        if (scoreRank >= 0) s.append(" — лучше ").append(scoreRank).append("% роликов из ленты");
        s.append('\n');
        if (speedRank >= 0) s.append("Скорость: быстрее ").append(speedRank).append("% роликов из ленты\n");

        s.append("\n📈 Прогноз (грубо)\n").append(forecast(v, age)).append('\n');

        List<String> good = new ArrayList<>();
        List<String> fix = new ArrayList<>();
        advise(v, like, comment, share, save, speedRank, good, fix);
        if (!good.isEmpty()) {
            s.append("\n✅ Что работает\n");
            for (String g : good) s.append("• ").append(g).append('\n');
        }
        if (!fix.isEmpty()) {
            s.append("\n🛠 Что исправить в следующем ролике\n");
            for (String f : fix) s.append("• ").append(f).append('\n');
        }
        return s.toString().trim();
    }

    private String forecast(Video v, double age) {
        if (age <= 0) return "Нет даты публикации — прогноз невозможен.";
        if (age < 0.5) return "Слишком рано: ролику меньше 30 минут. Нажми 📊 через час.";
        if (age > 24 * 10) return "Ролику больше 10 дней — он уже набрал почти всё, если не выстрелит заново.";

        double total;
        String basis;
        Long[] snap = v.aid == null ? null : takeSnapshot(v);
        if (snap != null) {
            double hours = (System.currentTimeMillis() - snap[0]) / 3_600_000.0;
            double rate = Math.max(0, (v.play - snap[1]) / hours);
            // в модели остаток просмотров = текущая скорость × τ
            total = v.play + rate * TAU_H;
            basis = "по росту за последние " + ago(hours).replace(" назад", "");
        } else {
            total = v.play / (1 - Math.exp(-age / TAU_H));
            basis = "по первому замеру; открой ролик снова через 20+ мин — станет точнее";
        }
        total = Math.max(total, v.play);
        double lo = age < 2 ? 0.5 : 0.7, hi = age < 2 ? 2.0 : 1.4;

        StringBuilder s = new StringBuilder();
        if (age < 24) {
            double day = Math.max(v.play, total * (1 - Math.exp(-24 / TAU_H)));
            s.append("Через сутки: ").append(range(day, lo, hi)).append('\n');
        }
        s.append("Итого: ").append(range(total, lo, hi)).append('\n');
        s.append("(").append(basis).append(")");
        return s.toString();
    }

    /** Возвращает прошлый замер {время, просмотры}, если ему 20+ минут, и записывает новый. */
    private Long[] takeSnapshot(Video v) {
        SharedPreferences prefs = host.prefs();
        String key = "s_" + v.aid;
        String old = prefs.getString(key, null);
        long now = System.currentTimeMillis();
        Long[] result = null;
        if (old != null) {
            String[] parts = old.split(":");
            long time = Long.parseLong(parts[0]), play = Long.parseLong(parts[1]);
            if (now - time < SNAPSHOT_MIN_MS) return null; // старый замер пока свежий — оставляем его
            if (play <= v.play) result = new Long[]{time, play};
        }
        prefs.edit().putString(key, now + ":" + v.play).apply();
        return result;
    }

    private static void advise(Video v, double like, double comment, double share, double save,
                               int speedRank, List<String> good, List<String> fix) {
        if (like >= 8) good.add("Лайков много — ролик цепляет.");
        if (comment >= 0.5) good.add("Активно комментируют — тема вызывает реакцию.");
        if (share >= 0.5) good.add("Им делятся — это сильнейший сигнал для алгоритма.");
        if (save >= 0.5) good.add("Сохраняют — в ролике есть польза.");

        if (like < 3) {
            fix.add("Цепляет слабо: усиль первые 1–2 секунды — сразу результат или интрига, "
                    + "текст-крючок на экране, без медленного вступления.");
        }
        if (comment < 0.1) {
            fix.add("Мало комментариев: задай вопрос в конце или в подписи, оставь спорную деталь, "
                    + "закрепи свой комментарий-вопрос.");
        }
        if (share < 0.1) {
            fix.add("Мало репостов: дай повод переслать — «отправь тому, кто…», жизненная ситуация, польза.");
        }
        if (save < 0.1) {
            fix.add("Мало сохранений: добавь то, что хочется пересмотреть — список, рецепт, инструкцию, лайфхак.");
        }
        if (like >= 6 && speedRank >= 0 && speedRank < 30 && v.ageHours() < 24) {
            fix.add("Реакция хорошая, а показов пока мало — ролик может разогнаться позже, не удаляй его. "
                    + "Следующий выложи, когда твоя аудитория онлайн (часто 18:00–22:00).");
        }
        if (v.desc != null) {
            if (!v.desc.contains("#")) fix.add("В подписи нет хэштегов: добавь 3–5 по теме — 1–2 широких и 2–3 узких.");
            if (v.desc.length() > 150) fix.add("Подпись длинная: главное — в первые 1–2 строки, остальное режется.");
        }
        if (like >= 8 && (share >= 0.5 || save >= 0.5)) {
            fix.add("Формат заходит — сними продолжение или «часть 2», пока тема горячая.");
        }
        if (fix.isEmpty()) fix.add("Явных слабых мест нет. Повторяй формат и тестируй разные первые секунды.");
    }

    // --- хранение выборок ---

    private void loadSamples() {
        SharedPreferences prefs = host.prefs();
        synchronized (this) {
            parse(prefs.getString("scores", ""), scoreSamples);
            parse(prefs.getString("speeds", ""), speedSamples);
        }
    }

    private void saveSamples() {
        String scores, speeds;
        synchronized (this) {
            scores = join(scoreSamples);
            speeds = join(speedSamples);
        }
        host.prefs().edit().putString("scores", scores).putString("speeds", speeds).apply();
    }

    /** Удаляет замеры старше двух недель. */
    private void prune() {
        SharedPreferences prefs = host.prefs();
        long cutoff = System.currentTimeMillis() - 14L * 24 * 3_600_000;
        SharedPreferences.Editor edit = prefs.edit();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            if (!e.getKey().startsWith("s_") || !(e.getValue() instanceof String)) continue;
            try {
                if (Long.parseLong(((String) e.getValue()).split(":")[0]) < cutoff) edit.remove(e.getKey());
            } catch (RuntimeException ignored) {
                edit.remove(e.getKey());
            }
        }
        edit.apply();
    }

    private static void parse(String csv, List<Double> into) {
        if (csv.isEmpty()) return;
        for (String part : csv.split(",")) {
            try {
                into.add(Double.parseDouble(part));
            } catch (NumberFormatException ignored) {
                // битое значение — пропускаем
            }
        }
    }

    private static String join(List<Double> list) {
        StringBuilder s = new StringBuilder();
        for (Double d : list) {
            if (s.length() > 0) s.append(',');
            s.append(String.format(Locale.US, "%.3f", d));
        }
        return s.toString();
    }

    // --- форматирование ---

    /** Процент значений выборки ниже value, или −1, если выборка мала. */
    private static int rank(List<Double> samples, double value) {
        if (samples.size() < 20) return -1;
        int below = 0;
        for (double d : samples) if (d < value) below++;
        return below * 100 / samples.size();
    }

    private static String level(double value, double normal, double strong) {
        return value >= strong ? "сильная" : value >= normal ? "нормальная" : "слабая";
    }

    private static double pct(long part, long total) {
        return part < 0 || total <= 0 ? 0 : part * 100.0 / total;
    }

    private static String p(double pct) {
        return String.format(new Locale("ru"), pct < 1 ? "%.2f%%" : "%.1f%%", pct);
    }

    private static String range(double value, double lo, double hi) {
        return big((long) (value * lo)) + " – " + big((long) (value * hi));
    }

    static String big(long n) {
        Locale ru = new Locale("ru");
        if (n >= 1_000_000) return String.format(ru, "%.1f млн", n / 1_000_000.0);
        if (n >= 10_000) return String.format(ru, "%.0f тыс", n / 1000.0);
        if (n >= 1_000) return String.format(ru, "%.1f тыс", n / 1000.0);
        return String.valueOf(n);
    }

    private static String ago(double hours) {
        if (hours < 1) return Math.max(1, Math.round(hours * 60)) + " мин назад";
        if (hours < 48) return Math.round(hours) + " ч назад";
        return Math.round(hours / 24) + " дн назад";
    }

    // --- модель ролика ---

    static final class Video {
        String aid, uid, nickname, uniqueId, desc, descKey;
        long createTime, play, digg, comment, share, collect;
        /** Прочитан с экрана: числа округлены («62,2 тыс.»), возраст — до часа/дня. */
        boolean fromScreen, ageApprox;

        static Video read(Object post) {
            if (post == null) return null;
            // элементы ленты бывают обёртками (FollowFeed и т. п.) вокруг Aweme
            Object inner = Model.get(post, "aweme", "aweme_info", "awemeInfo");
            Object aweme = inner != null ? inner : post;
            Video v = new Video();
            v.aid = Model.str(aweme, "aid", "awemeId", "aweme_id");
            if (v.aid == null || v.aid.isEmpty()) return null;
            v.setDesc(Model.str(aweme, "desc", "description"));
            v.createTime = Model.num(aweme, "createTime", "create_time");

            Object author = Model.get(aweme, "author");
            v.uid = Model.str(author, "uid");
            v.nickname = Model.str(author, "nickname", "nickName");
            v.uniqueId = Model.str(author, "uniqueId", "unique_id");

            Object stats = Model.get(aweme, "statistics", "statistic");
            v.play = Model.num(stats, "playCount", "play_count");
            v.digg = Model.num(stats, "diggCount", "digg_count");
            v.comment = Model.num(stats, "commentCount", "comment_count");
            v.share = Model.num(stats, "shareCount", "share_count");
            v.collect = Model.num(stats, "collectCount", "collect_count");
            return v;
        }

        void setDesc(String text) {
            desc = text;
            descKey = null;
            if (text == null) return;
            String norm = normalize(text);
            if (norm.length() >= 3) descKey = norm.substring(0, Math.min(DESC_KEY, norm.length()));
            if (aid == null && descKey != null) aid = "d" + Integer.toHexString(descKey.hashCode());
        }

        /** Дополняет ролик с экрана точными данными того же ролика из ленты. */
        void mergeFrom(Video feed) {
            aid = feed.aid;
            uid = feed.uid;
            nickname = feed.nickname;
            uniqueId = feed.uniqueId;
            if (feed.desc != null) desc = feed.desc;
            if (feed.createTime > 0) {
                createTime = feed.createTime;
                ageApprox = false;
            }
            play = Math.max(play, feed.play);
            digg = Math.max(digg, feed.digg);
            comment = Math.max(comment, feed.comment);
            share = Math.max(share, feed.share);
            collect = Math.max(collect, feed.collect);
        }

        double ageHours() {
            if (createTime <= 0) return -1;
            long sec = createTime > 100_000_000_000L ? createTime / 1000 : createTime;
            return Math.max(0, System.currentTimeMillis() / 1000.0 - sec) / 3600.0;
        }

        /** Взвешенная вовлечённость: репосты, комментарии и сохранения весят больше лайков. */
        double score() {
            return pct(digg, play) + 5 * pct(comment, play) + 5 * pct(share, play) + 3 * pct(collect, play);
        }

        String handle() {
            return uniqueId != null && !uniqueId.isEmpty() ? uniqueId : nickname;
        }

        @Override
        public String toString() {
            return Arrays.asList("aid=" + aid, "uid=" + uid, "play=" + play, "digg=" + digg,
                    "comment=" + comment, "share=" + share, "collect=" + collect,
                    "created=" + createTime).toString();
        }
    }
}
