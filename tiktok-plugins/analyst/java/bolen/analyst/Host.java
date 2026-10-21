package bolen.analyst;

import android.content.SharedPreferences;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Доступ к host() (PluginContext) через рефлексию: плагин не зависит от того,
 * интерфейс это или класс и какого типа колбэки у offer / addSettingsRow / away.
 */
final class Host {

    private final Object plugin;
    private Object host;

    Host(Object plugin) {
        this.plugin = plugin;
    }

    SharedPreferences prefs() {
        return (SharedPreferences) call("prefs");
    }

    void log(String line) {
        call("log", "[analyst] " + line);
    }

    Object call(String name, Object... args) {
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

    private Object host() {
        if (host != null) return host;
        for (Class<?> c = plugin.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("host");
                m.setAccessible(true);
                return host = m.invoke(plugin);
            } catch (NoSuchMethodException ignored) {
                // ищем выше по иерархии
            } catch (Exception e) {
                throw new RuntimeException("host()", e);
            }
        }
        throw new IllegalStateException("host() not found");
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
                    return "AnalystAction";
                }
                ((Runnable) arg).run();
                Class<?> r = method.getReturnType();
                if (r == boolean.class) return true;
                if (r == long.class) return 0L;
                if (r == int.class) return 0;
                return null;
            }
        });
    }
}
