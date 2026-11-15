package ai.pechka.factory;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The app's small, inspectable state: a counter, a label and the last action taken.
 *
 * Both the native UI and the debug RPC read and write it through the same methods, so a tap on
 * the screen and a `factory.bump` call are the same operation — which is what makes the RPC
 * testable against what is actually on screen.
 */
public final class FactoryState {

    public interface Listener {
        void onState(String label, int counter, String lastAction);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final long STARTED_AT = System.currentTimeMillis();

    private static int counter = 0;
    private static String label = "factory";
    private static String lastAction = "boot";
    private static volatile Runnable reloadHook;

    private FactoryState() {
    }

    public static synchronized void addListener(Listener listener) {
        LISTENERS.add(listener);
        listener.onState(label, counter, lastAction);
    }

    public static synchronized void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void setReloadHook(Runnable hook) {
        reloadHook = hook;
    }

    private static void publish() {
        for (Listener listener : LISTENERS) {
            listener.onState(label, counter, lastAction);
        }
    }

    public static synchronized JSONObject status(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            out.put("counter", counter);
            out.put("label", label);
            out.put("last_action", lastAction);
            out.put("uptime_ms", System.currentTimeMillis() - STARTED_AT);
            out.put("package", ctx.getPackageName());
            out.put("version", versionName(ctx));
            out.put("ui_attached", LISTENERS.size() > 0);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public static synchronized JSONObject bump(Context ctx) {
        counter += 1;
        lastAction = "bump";
        publish();
        return status(ctx);
    }

    public static synchronized JSONObject reset(Context ctx) {
        counter = 0;
        lastAction = "reset";
        publish();
        return status(ctx);
    }

    public static synchronized JSONObject setLabel(Context ctx, String text) {
        label = (text == null || text.trim().isEmpty()) ? "factory" : text.trim();
        lastAction = "set_label";
        publish();
        return status(ctx);
    }

    public static JSONObject reload(Context ctx) {
        lastAction = "reload";
        publish();
        Runnable hook = reloadHook;
        if (hook != null) {
            hook.run();
        }
        JSONObject out = status(ctx);
        try {
            out.put("reloaded", hook != null);
        } catch (Exception ignored) {
        }
        return out;
    }

    public static JSONObject toast(Context ctx, String text) {
        lastAction = "toast";
        final String shown = text == null ? "" : text;
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx.getApplicationContext(), shown, Toast.LENGTH_SHORT).show());
        publish();
        JSONObject out = status(ctx);
        try {
            out.put("toasted", shown);
        } catch (Exception ignored) {
        }
        return out;
    }
}
