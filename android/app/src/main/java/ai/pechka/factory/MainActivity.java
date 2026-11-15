package ai.pechka.factory;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

/**
 * One screen, two halves.
 *
 * The top half is a small native console — a status line, a label, an input and three buttons —
 * so the app has real containers to inspect and tap. The bottom half is the WebView showing the
 * bundle the ship workflow put in assets/www.
 *
 * Both halves read and write the same {@link FactoryState}, which is also what the debug RPC
 * methods touch. A tap on "Bump" and a `factory.bump` call are therefore the same operation, and
 * either one can be checked against the screen.
 */
public class MainActivity extends Activity {

    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;
    private TextView statusText;
    private TextView labelText;
    private EditText input;
    private FactoryState.Listener listener;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setContentDescription("web");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 0);

        TextView title = new TextView(this);
        title.setText("Factory console");
        title.setTextSize(20f);

        statusText = new TextView(this);
        statusText.setContentDescription("status");
        statusText.setTextSize(14f);

        labelText = new TextView(this);
        labelText.setContentDescription("label");
        labelText.setTextSize(18f);
        labelText.setPadding(0, 16, 0, 16);

        input = new EditText(this);
        input.setHint("label text");
        input.setContentDescription("input");

        Button bump = new Button(this);
        bump.setText("Bump");
        bump.setContentDescription("bump");
        bump.setOnClickListener(v -> FactoryState.bump(this));

        Button setLabel = new Button(this);
        setLabel.setText("Set label");
        setLabel.setContentDescription("setlabel");
        setLabel.setOnClickListener(v -> FactoryState.setLabel(this, input.getText().toString()));

        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setContentDescription("reset");
        reset.setOnClickListener(v -> FactoryState.reset(this));

        root.addView(title);
        root.addView(statusText);
        root.addView(labelText);
        root.addView(input);
        root.addView(bump);
        root.addView(setLabel);
        root.addView(reset);
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        listener = (label, counter, action) -> runOnUiThread(() -> {
            statusText.setText("counter " + counter + " \u00b7 " + action);
            labelText.setText(label);
        });
        FactoryState.addListener(listener);
        FactoryState.setReloadHook(() -> runOnUiThread(() -> web.reload()));

        web.loadUrl(START_URL);
    }

    @Override
    protected void onDestroy() {
        if (listener != null) {
            FactoryState.removeListener(listener);
            FactoryState.setReloadHook(null);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
