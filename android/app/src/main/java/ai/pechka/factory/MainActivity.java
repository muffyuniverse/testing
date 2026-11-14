package ai.pechka.factory;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;

/**
 * One screen: a WebView showing the bundle that the ship workflow put in assets/www.
 *
 * The bundle is served through WebViewAssetLoader on https://appassets.androidplatform.net
 * rather than loaded as file://, because a file:// page cannot fetch its own build.json —
 * and the page reads that snapshot before it tries anything on the network.
 */
public class MainActivity extends Activity {

    private static final String START_URL =
            "https://appassets.androidplatform.net/assets/www/index.html";

    private WebView web;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }
        });

        setContentView(web);
        web.loadUrl(START_URL);
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
