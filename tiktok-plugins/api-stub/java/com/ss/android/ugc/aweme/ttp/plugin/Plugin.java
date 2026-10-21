package com.ss.android.ugc.aweme.ttp.plugin;

import android.app.Activity;
import android.content.Context;

import java.util.List;

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

    // API 2
    public void onScreen(Activity activity, String name) {}
    public List onFeed(List posts) { return posts; }
    public String onName(String uid, String name) { return name; }
    public CharSequence onText(CharSequence text) { return text; }
}
