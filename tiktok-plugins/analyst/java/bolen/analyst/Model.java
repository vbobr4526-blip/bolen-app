package bolen.analyst;

import java.lang.annotation.Annotation;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Читает поля объектов TikTok (Aweme, User, AwemeStatistics) рефлексией.
 * Ищет по ключу JSON в @SerializedName (он переживает обфускацию), затем по имени
 * поля и по геттеру — так плагин не завязан на конкретную версию TikTok.
 */
final class Model {

    private static final Object NONE = new Object();
    private static final Map<String, Object> cache = new HashMap<>();

    private Model() {}

    static Object get(Object o, String... keys) {
        if (o == null) return null;
        AccessibleObject a = find(o.getClass(), keys);
        if (a == null) return null;
        try {
            return a instanceof Field ? ((Field) a).get(o) : ((Method) a).invoke(o);
        } catch (Exception e) {
            return null;
        }
    }

    static long num(Object o, String... keys) {
        Object v = get(o, keys);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    static String str(Object o, String... keys) {
        Object v = get(o, keys);
        return v == null ? null : String.valueOf(v);
    }

    private static synchronized AccessibleObject find(Class<?> cls, String[] keys) {
        String cacheKey = cls.getName() + "#" + keys[0];
        Object hit = cache.get(cacheKey);
        if (hit != null) return hit == NONE ? null : (AccessibleObject) hit;

        AccessibleObject found = null;
        for (Class<?> c = cls; c != null && c != Object.class && found == null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (matches(f, keys)) {
                    found = f;
                    break;
                }
            }
        }
        if (found == null) {
            for (Method m : cls.getMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                for (String k : keys) {
                    if (m.getName().equals("get" + Character.toUpperCase(k.charAt(0)) + k.substring(1))) {
                        found = m;
                        break;
                    }
                }
                if (found != null) break;
            }
        }
        if (found != null) {
            try {
                found.setAccessible(true);
            } catch (Exception ignored) {
                found = null;
            }
        }
        cache.put(cacheKey, found == null ? NONE : found);
        return found;
    }

    private static boolean matches(Field f, String[] keys) {
        for (String k : keys) if (f.getName().equals(k)) return true;
        for (Annotation an : f.getAnnotations()) {
            if (!an.annotationType().getName().endsWith("SerializedName")) continue;
            try {
                Object v = an.annotationType().getMethod("value").invoke(an);
                for (String k : keys) if (k.equals(v)) return true;
            } catch (Exception ignored) {
                // аннотация без value — пропускаем
            }
        }
        return false;
    }
}
