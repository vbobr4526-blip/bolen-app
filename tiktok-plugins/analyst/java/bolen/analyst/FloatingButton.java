package bolen.analyst;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Круглая кнопка 📊 поверх экрана: её можно перетащить в любое место,
 * короткое нажатие — click, долгое — longClick. Позиция хранится в долях экрана.
 */
final class FloatingButton {

    interface Listener {
        void onClick();

        void onLongClick();

        void onMoved(float fx, float fy);
    }

    private static final long LONG_PRESS_MS = 600;

    final TextView view;
    private final ViewGroup root;
    private final float density;
    private int size;
    private float alpha = 1f;

    FloatingButton(Activity activity, final float fx, final float fy, final Listener listener) {
        root = (ViewGroup) activity.getWindow().getDecorView();
        density = activity.getResources().getDisplayMetrics().density;
        size = Math.round(50 * density);

        view = new TextView(activity);
        view.setText("📊");
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC1F1F1F);
        bg.setStroke(dp(activity, 2), 0x66FFFFFF);
        view.setBackgroundDrawable(bg);
        view.setVisibility(View.GONE);

        root.addView(view, new FrameLayout.LayoutParams(size, size));
        root.post(new Runnable() {
            @Override
            public void run() {
                place(fx, fy);
            }
        });

        final int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        final Handler main = new Handler(Looper.getMainLooper());
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY, startX, startY;
            private boolean dragging, longFired;
            private final Runnable longPress = new Runnable() {
                @Override
                public void run() {
                    longFired = true;
                    listener.onLongClick();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = view.getX();
                        startY = view.getY();
                        dragging = false;
                        longFired = false;
                        view.setAlpha(Math.max(0.2f, alpha * 0.7f));
                        main.postDelayed(longPress, LONG_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                            dragging = true;
                            main.removeCallbacks(longPress);
                        }
                        if (dragging) {
                            view.setX(clamp(startX + dx, root.getWidth() - size));
                            view.setY(clamp(startY + dy, root.getHeight() - size));
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        view.setAlpha(alpha);
                        if (dragging) {
                            listener.onMoved(fraction(view.getX(), root.getWidth() - size),
                                    fraction(view.getY(), root.getHeight() - size));
                        } else if (!longFired && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            listener.onClick();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    void show(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != v) {
            view.setVisibility(v);
            if (visible) view.bringToFront();
        }
    }

    /** Размер в dp и прозрачность (1 — непрозрачная). */
    void style(int sizeDp, float alpha) {
        this.alpha = alpha;
        size = Math.round(sizeDp * density);
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        lp.width = size;
        lp.height = size;
        view.setLayoutParams(lp);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.44f);
        view.setAlpha(alpha);
    }

    void detach() {
        root.removeView(view);
    }

    void place(final float fx, final float fy) {
        if (root.getWidth() == 0) {
            root.post(new Runnable() {
                @Override
                public void run() {
                    place(fx, fy);
                }
            });
            return;
        }
        view.setX(fx * Math.max(0, root.getWidth() - size));
        view.setY(fy * Math.max(0, root.getHeight() - size));
    }

    private static float clamp(float value, float max) {
        return Math.max(0, Math.min(value, Math.max(0, max)));
    }

    private static float fraction(float value, float max) {
        return max <= 0 ? 0 : Math.max(0, Math.min(1, value / max));
    }

    private static int dp(Activity a, float dp) {
        return Math.round(dp * a.getResources().getDisplayMetrics().density);
    }
}
