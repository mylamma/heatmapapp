package com.example.heatmap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 종목별 가격 기록을 기기 안에 직접 누적 저장.
 * - 앱이 1분마다 시세를 받아올 때마다 그 값을 그대로 기록 (record 호출)
 * - 3일보다 오래된 기록은 저장할 때 자동으로 정리됨
 * - Finnhub 무료 플랜은 과거 차트 데이터(/stock/candle)를 안 주기 때문에,
 *   앱이 스스로 기록을 쌓아서 "최근 3일 차트"를 만드는 방식
 */
public class PriceHistory {

    private static final String PREFS_NAME = "price_history";
    private static final long WINDOW_MS = 3L * 24 * 60 * 60 * 1000; // 3일

    public static void record(Context ctx, String symbol, double price) {
        if (Double.isNaN(price)) return;
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = "h_" + symbol;
        String existing = prefs.getString(key, "");
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        if (!existing.isEmpty()) {
            for (String entry : existing.split(";")) {
                String[] parts = entry.split(":");
                if (parts.length != 2) continue;
                try {
                    long t = Long.parseLong(parts[0]);
                    if (now - t <= WINDOW_MS) {
                        sb.append(entry).append(";");
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        sb.append(now).append(":").append(price);
        prefs.edit().putString(key, sb.toString()).apply();
    }

    /** [timestamp(ms), price] 쌍의 목록을 시간순으로 반환 */
    public static List<double[]> getHistory(Context ctx, String symbol) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String existing = prefs.getString("h_" + symbol, "");
        List<double[]> result = new ArrayList<>();
        if (existing.isEmpty()) return result;

        for (String entry : existing.split(";")) {
            String[] parts = entry.split(":");
            if (parts.length != 2) continue;
            try {
                double t = Double.parseDouble(parts[0]);
                double p = Double.parseDouble(parts[1]);
                result.add(new double[]{t, p});
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }
}
