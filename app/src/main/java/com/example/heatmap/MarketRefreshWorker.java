package com.example.heatmap;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 앱(MainActivity)이 화면에 떠 있지 않아도(프로세스가 꺼져 있어도) 실행 가능한
 * "시장 판단 -> 데이터 조회" 과정을 담당. AlarmManager로 깨어난 HourlyUpdateReceiver가
 * 이 클래스를 호출해서, 앱이 죽어있는 동안에도 데이터가 최신 상태로 유지되게 함.
 *
 * (참고: 예전엔 여기서 화면을 렌더링해서 슬립화면 파일로 저장하는 기능도 있었는데,
 * 크레마 기기가 슬립화면을 한 번 지정한 뒤로는 갱신을 반영 안 하는 것으로 확인되어
 * 실효가 없는 부하만 주는 기능이라 제거함)
 */
public class MarketRefreshWorker {

    /** 한국시간 기준 현재 몇 시인지 보고 어떤 시장을 보여줄지 결정 */
    public static MainActivity.Market determineMarketByTime() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Seoul"));
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        boolean isKoreanHours = hour >= MainActivity.KR_MARKET_START_HOUR && hour < MainActivity.KR_MARKET_END_HOUR;
        return isKoreanHours ? MainActivity.Market.KR : MainActivity.Market.US;
    }

    /** 선택된 시장의 44종목 시세를 병렬로 조회해서 SECTORS_US/SECTORS_KR 배열에 채워넣음 */
    public static void refreshStockData(Context context, MainActivity.Market market) {
        if (market == MainActivity.Market.US) {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (final TreemapView.Sector sector : MainActivity.SECTORS_US) {
                for (final TreemapView.Stock stock : sector.stocks) {
                    futures.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            org.json.JSONObject json = MainActivity.fetchQuoteJson(stock.symbol);
                            if (json == null) return;
                            double current = json.optDouble("c", Double.NaN);
                            double prevClose = json.optDouble("pc", Double.NaN);
                            if (!Double.isNaN(current) && !Double.isNaN(prevClose) && prevClose != 0) {
                                stock.percentChange = ((current - prevClose) / prevClose) * 100.0;
                                stock.lastUpdatedMs = System.currentTimeMillis();
                            }
                            if (!Double.isNaN(current)) {
                                PriceHistory.record(context, stock.symbol, current);
                            }
                        }
                    }));
                }
            }
            waitAll(futures);
            pool.shutdown();
        } else {
            List<String> codes = new ArrayList<>();
            for (TreemapView.Sector sector : MainActivity.SECTORS_KR) {
                for (TreemapView.Stock stock : sector.stocks) codes.add(stock.symbol);
            }
            Map<String, double[]> results = MainActivity.fetchKoreanBatch(codes);
            for (TreemapView.Sector sector : MainActivity.SECTORS_KR) {
                for (TreemapView.Stock stock : sector.stocks) {
                    double[] r = results.get(stock.symbol);
                    if (r != null && !Double.isNaN(r[1])) {
                        stock.percentChange = r[1];
                        stock.lastUpdatedMs = System.currentTimeMillis();
                        if (!Double.isNaN(r[0])) {
                            PriceHistory.record(context, stock.symbol, r[0]);
                        }
                    }
                }
            }
        }
    }

    private static void waitAll(List<java.util.concurrent.Future<?>> futures) {
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
    }

    /** 환율/코스피/암호화폐/주요지수 값을 간단히 담는 결과 묶음 */
    public static class MacroSnapshot {
        double usdKrw = Double.NaN;
        double kospi = Double.NaN, kospiPct = Double.NaN;
        double btc = Double.NaN, eth = Double.NaN;
        double dow = Double.NaN, dowPct = Double.NaN;
        double nasdaq = Double.NaN, nasdaqPct = Double.NaN;
        double sp500 = Double.NaN, sp500Pct = Double.NaN;
        double vix = Double.NaN, vixPct = Double.NaN;
    }

    public static MacroSnapshot fetchMacroSnapshot() {
        MacroSnapshot m = new MacroSnapshot();
        m.usdKrw = MainActivity.fetchUsdKrw();
        double[] kospi = MainActivity.fetchKospi();
        m.kospi = kospi[0];
        m.kospiPct = kospi[1];
        m.btc = MainActivity.fetchSimplePrice("BINANCE:BTCUSDT");
        m.eth = MainActivity.fetchSimplePrice("BINANCE:ETHUSDT");
        double[] dow = MainActivity.fetchWorldIndex("^DJI");
        m.dow = dow[0];
        m.dowPct = dow[1];
        double[] nasdaq = MainActivity.fetchWorldIndex("^IXIC");
        m.nasdaq = nasdaq[0];
        m.nasdaqPct = nasdaq[1];
        double[] sp500 = MainActivity.fetchWorldIndex("^GSPC");
        m.sp500 = sp500[0];
        m.sp500Pct = sp500[1];
        double[] vix = MainActivity.fetchWorldIndex("^VIX");
        m.vix = vix[0];
        m.vixPct = vix[1];
        return m;
    }
}
