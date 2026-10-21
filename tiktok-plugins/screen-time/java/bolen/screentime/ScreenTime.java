package bolen.screentime;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.ss.android.ugc.aweme.ttp.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * «Экранное время»: считает, сколько ты сегодня провёл в TikTok,
 * и каждые N минут непрерывного листания показывает окно «сделай перерыв».
 *
 * Методы host() вызываются через рефлексию: так плагин не зависит от того,
 * интерфейс PluginContext или класс и какого типа колбэк у кнопок.
 */
public final class ScreenTime extends Plugin {

    private static final long TICK_MS = 30_000;
    /** Перерыв дольше этого обнуляет текущую сессию. */
    private static final long BREAK_MS = 5 * 60_000;
    private static final int[] INTERVALS = {10, 20, 30, 45, 60, 0};

    private final Handler main = new Handler(Looper.getMainLooper());
    private Object host;

    private long resumedAt;        // когда TikTok вышел на экран, 0 — не на экране
    private long pausedAt;         // когда ушёл с экрана
    private long sessionMs;        // непрерывное время в этой сессии
    private long nextReminderMs;   // на каком sessionMs напомнить

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                flush();
                maybeRemind();
            } catch (Throwable t) {
                log("tick: " + t);
            }
            if (resumedAt != 0) main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onStart(Context context) {
        resetSession();
        call("addSettingsRow", "Сегодня в TikTok", "Нажми, чтобы посмотреть", new Runnable() {
            @Override
            public void run() {
                flush();
                call("showWindow", "Экранное время",
                        "Сегодня: " + format(todayMs()) + "\n"
                                + "Без перерыва: " + format(sessionMs) + "\n\n"
                                + "Напоминания: " + intervalText(interval()));
            }
        });
        call("addSettingsRow", "Напоминать о перерыве",
                "Сейчас: " + intervalText(interval()) + ". Нажми, чтобы изменить", new Runnable() {
            @Override
            public void run() {
                int next = nextInterval(interval());
                prefs().edit().putInt("interval", next).apply();
                resetSession();
                call("showWindow", "Напоминания", "Теперь: " + intervalText(next));
            }
        });
        log("started, interval " + interval() + " min");
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (resumedAt != 0) return; // переход между экранами внутри TikTok
        long now = System.currentTimeMillis();
        if (pausedAt != 0 && now - pausedAt > BREAK_MS) resetSession();
        resumedAt = now;
        main.removeCallbacks(tick);
        main.postDelayed(tick, TICK_MS);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        flush();
        resumedAt = 0;
        pausedAt = System.currentTimeMillis();
        main.removeCallbacks(tick);
    }

    @Override
    public void onStop() {
        flush();
        resumedAt = 0;
        main.removeCallbacks(tick);
    }

    // --- учёт времени ---

    private void flush() {
        if (resumedAt == 0) return;
        long now = System.currentTimeMillis();
        long delta = now - resumedAt;
        resumedAt = now;
        if (delta <= 0) return;
        sessionMs += delta;

        SharedPreferences p = prefs();
        String day = today();
        long total = day.equals(p.getString("day", "")) ? p.getLong("today_ms", 0) : 0;
        p.edit().putString("day", day).putLong("today_ms", total + delta).apply();
    }

    private void maybeRemind() {
        int minutes = interval();
        if (minutes == 0 || sessionMs < nextReminderMs) return;
        nextReminderMs = sessionMs + minutes * 60_000L;
        call("showWindow", "Пора сделать перерыв",
                "Ты листаешь уже " + format(sessionMs) + " без остановки.\n"
                        + "За сегодня: " + format(todayMs()) + ".\n\n"
                        + "Встань, попей воды, посмотри в окно 🙂");
    }

    private void resetSession() {
        sessionMs = 0;
        int minutes = interval();
        nextReminderMs = minutes == 0 ? Long.MAX_VALUE : minutes * 60_000L;
    }

    private long todayMs() {
        SharedPreferences p = prefs();
        return today().equals(p.getString("day", "")) ? p.getLong("today_ms", 0) : 0;
    }

    private int interval() {
        return prefs().getInt("interval", 20);
    }

    private static int nextInterval(int current) {
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == current) return INTERVALS[(i + 1) % INTERVALS.length];
        }
        return INTERVALS[0];
    }

    private static String intervalText(int minutes) {
        return minutes == 0 ? "выключены" : "каждые " + minutes + " мин";
    }

    private static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    private static String format(long ms) {
        long min = ms / 60_000;
        if (min < 60) return min + " мин";
        return (min / 60) + " ч " + String.format(Locale.US, "%02d", min % 60) + " мин";
    }

    // --- доступ к моду (PluginContext) через рефлексию ---

    private SharedPreferences prefs() {
        return (SharedPreferences) call("prefs");
    }

    private void log(String line) {
        call("log", "[screentime] " + line);
    }

    private Object host() {
        if (host != null) return host;
        for (Class<?> c = getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("host");
                m.setAccessible(true);
                return host = m.invoke(this);
            } catch (NoSuchMethodException ignored) {
                // ищем выше по иерархии
            } catch (Exception e) {
                throw new RuntimeException("host()", e);
            }
        }
        throw new IllegalStateException("host() not found");
    }

    private Object call(String name, Object... args) {
        Object h = host();
        for (Method m : h.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] real = new Object[args.length];
            for (int i = 0; i < args.length; i++) real[i] = adapt(types[i], args[i]);
            try {
                return m.invoke(h, real);
            } catch (Exception e) {
                throw new RuntimeException(name, e);
            }
        }
        throw new IllegalStateException("host()." + name + " not found");
    }

    /** Подгоняет Runnable под любой тип колбэка, который ждёт мод. */
    private static Object adapt(Class<?> type, final Object arg) {
        if (!(arg instanceof Runnable) || type.isInstance(arg) || !type.isInterface()) return arg;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] a) {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("equals")) return proxy == a[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return "ScreenTimeAction";
                }
                ((Runnable) arg).run();
                Class<?> r = method.getReturnType();
                if (r == boolean.class) return true;
                if (r.isPrimitive() && r != void.class) return 0;
                return null;
            }
        });
    }
}
