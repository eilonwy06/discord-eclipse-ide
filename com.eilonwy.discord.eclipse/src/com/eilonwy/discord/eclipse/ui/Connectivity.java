package com.eilonwy.discord.eclipse.ui;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Can Discord be reached from here? Any HTTP answer at all counts as yes, whatever its status: a
 * 403 from a CDN still proves the network path works. Only a failure to get an answer counts as no.
 *
 * <p>Requests go through the default {@link ProxySelector}, which picks up the proxy configured in
 * Eclipse's Network Connections preferences, so a proxied network is not mistaken for none.
 */
final class Connectivity {

    static final URI DISCORD = URI.create("https://discord.com/app");

    private static final HttpClient CLIENT = build();

    private Connectivity() {
    }

    private static HttpClient build() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL);
        ProxySelector proxies = ProxySelector.getDefault();
        if (proxies != null) {
            b.proxy(proxies);
        }
        return b.build();
    }

    /** Blocks for up to about 8 seconds; call it off the UI thread. */
    static boolean reachable(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(8))
                .build();
        try {
            CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
