package dev.pechka.selfdebug;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        android.content.SharedPreferences sp = getSharedPreferences("self", MODE_PRIVATE);
        int opens = sp.getInt("opens", 0) + 1;
        sp.edit().putInt("opens", opens).apply();

        TextView title = new TextView(this);
        title.setText("Self Debug " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        title.setTextSize(20);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("opened " + opens + "x · pid " + android.os.Process.myPid());
        sub.setTextSize(11);
        root.addView(sub);

        status = new TextView(this);
        status.setText("idle");
        status.setTextSize(14);
        status.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(status);

        root.addView(button("Check for update", () -> Updater.check(this, true)));
        root.addView(button("Install downloaded update", () -> POOL_ONLY(() -> Updater.installDownloaded(this))));

        TextView hint = new TextView(this);
        hint.setText("RPC methods: app.version, app.check_update, app.install_update, "
                + "app.install_downloaded, app.set_manifest_url");
        hint.setTextSize(11);
        hint.setPadding(0, pad, 0, 0);
        root.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        Updater.setStatusSink(s -> ui.post(() -> status.setText(s)));
        DebugHooks.INSTANCE.install(this);
        Updater.check(this, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Updater.setVisible(true);
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Updater.check(this, false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Updater.setVisible(false);
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private static void POOL_ONLY(Runnable r) {
        Thread t = new Thread(r, "selfdebug-install");
        t.start();
    }
}
