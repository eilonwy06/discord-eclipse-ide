package com.eilonwy.discord.eclipse.ui;

/**
 * The page shown in place of Discord when there is no connection. Fully self-contained (inline CSS
 * and SVG, no web fonts or images) since by definition nothing can be fetched while it shows.
 * "Try again" calls back into the view through the {@code discordRetry} browser function.
 */
final class OfflinePage {

    private OfflinePage() {
    }

    static String render(boolean dark) {
        String bg = dark ? "#2b2b2b" : "#f5f5f7";
        String fg = dark ? "#e6e6e6" : "#1f1f23";
        String muted = dark ? "#a4a4a8" : "#5c5c63";
        String accent = "#5865F2"; // Discord blurple
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<style>"
                + "html,body{height:100%;margin:0}"
                + "body{display:flex;align-items:center;justify-content:center;background:" + bg + ";color:" + fg
                + ";font-family:'Segoe UI',system-ui,-apple-system,sans-serif}"
                + ".box{max-width:420px;padding:24px;text-align:center}"
                + "svg{width:56px;height:56px;color:" + muted + "}"
                + "h1{font-size:18px;font-weight:600;margin:16px 0 8px}"
                + "p{font-size:13px;line-height:1.5;color:" + muted + ";margin:0 0 20px}"
                + "button{font:inherit;font-size:13px;color:#fff;background:" + accent
                + ";border:0;border-radius:4px;padding:8px 18px;cursor:pointer}"
                + "button:disabled{opacity:.6;cursor:default}"
                + "</style></head><body><div class=\"box\">"
                // a Wi-Fi symbol struck through
                + "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\""
                + " stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">"
                + "<path d=\"M2 8.8a15 15 0 0 1 4.2-2.6\"/><path d=\"M10.7 5.1A15 15 0 0 1 22 8.8\"/>"
                + "<path d=\"M5 12.6a10 10 0 0 1 5.2-2.5\"/><path d=\"M16.9 11.4A10 10 0 0 1 19 12.6\"/>"
                + "<path d=\"M8.5 16.4a5 5 0 0 1 7 0\"/><path d=\"M12 20h.01\"/><path d=\"M3 3l18 18\"/></svg>"
                + "<h1>No internet connection</h1>"
                + "<p>Discord can't be reached right now. Check your network connection. "
                + "This page tries again on its own every few seconds.</p>"
                + "<button id=\"retry\" onclick=\"this.disabled=true;this.textContent='Checking…';discordRetry();\">"
                + "Try again</button>"
                + "</div></body></html>";
    }
}
