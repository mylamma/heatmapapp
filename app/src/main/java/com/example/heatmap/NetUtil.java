package com.example.heatmap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLEncoder;
import javax.net.ssl.HttpsURLConnection;

/** 여러 곳에서 재사용하는 간단한 HTTPS GET 유틸리티 */
public class NetUtil {

    // ▼▼▼ Cloudflare Worker 배포 후 나온 주소를 여기에 넣으세요 (끝에 슬래시 없이) ▼▼▼
    private static final String NAVER_PROXY_BASE = "https://naver-proxy.bjy225-3f1.workers.dev";
    // ▲▲▲ 여기까지 ▲▲▲

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 4.2.2; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";

    public static String httpGet(String urlStr) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpsURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("GET");

            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 네이버/야후/환율 API 요청은 구형 안드로이드에서 직접 호출하면 실패하는 문제가 있어서,
     * Cloudflare Worker 프록시를 거쳐서 요청함 (프록시가 최신 서버에서 대신 요청해줌).
     */
    public static String proxyGet(String targetUrl) {
        try {
            String encoded = URLEncoder.encode(targetUrl, "UTF-8");
            return httpGet(NAVER_PROXY_BASE + "?url=" + encoded);
        } catch (Exception e) {
            return null;
        }
    }
}

