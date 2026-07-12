package com.example.heatmap;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 종목별 가격 기록을 기기 안에 직접 누적 저장.
 * - 화면은 1분마다 갱신되지만, 차트용 기록 자체는 5분에 한 번만 실제로 저장함
 *   (매분 기록하면 3일치가 최대 4,320개까지 쌓여서, 매번 그 전체를 파싱+재저장하는
 *   부하가 너무 커짐 - 이게 발열/다운의 주된 원인이었음)
 * - 3일보다 오래된 기록은 저장할 때 자동으로 정리됨
 * - Finnhub 무료 플랜은 과거 차트 데이터(/stock/candle)를 안 주기 때문에,
 *   앱이 스스로 기록을 쌓아서 "최근 3일 차트"를 만드는 방식
 */
public class PriceHistory {

    private static final String PREFS_NAME = "price_history";
    private static final String LAST_RECORDED_PREFS_NAME = "price_history_last_recorded";
    private static final long WINDOW_MS = 3L * 24 * 60 * 60 * 1000; // 3일
    private static final long MIN_RECORD_INTERVAL_MS = 5 * 60 * 1000L; // 실제 기록은 5분에 한 번만

    public static void record(Context ctx, String symbol, double price) {
        if (Double.isNaN(price)) return;

        long now = System.currentTimeMillis();

        // 가벼운 타임스탬프만 저장된 별도 prefs로, 큰 기록 문자열을 안 건드리고 빠르게 확인
        SharedPreferences lastRecordedPrefs = ctx.getSharedPreferences(LAST_RECORDED_PREFS_NAME, Context.MODE_PRIVATE);
        long lastRecordedAt = lastRecordedPrefs.getLong(symbol, 0);
        if (now - lastRecordedAt < MIN_RECORD_INTERVAL_MS) {
            return; // 아직 5분 안 지났으면 무거운 파싱/재저장 없이 그냥 건너뜀
        }

        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String key = "h_" + symbol;
        String existing = prefs.getString(key, "");

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
        lastRecordedPrefs.edit().putLong(symbol, now).apply();
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
