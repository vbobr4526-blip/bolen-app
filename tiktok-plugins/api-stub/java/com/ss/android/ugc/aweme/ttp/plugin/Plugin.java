package com.ss.android.ugc.aweme.ttp.plugin;

import android.app.Activity;
import android.content.Context;

/**
 * Заглушка базового класса плагина TikTokMod (API 3) — только для компиляции.
 * В .mtp не попадает: настоящий класс уже есть внутри мода.
 */
public abstract class Plugin {
    public void onStart(Context context) {}
    public void onActivityCreated(Activity activity) {}
    public void onActivityResumed(Activity activity) {}
    public void onActivityPaused(Activity activity) {}
    public void onStop() {}
}
