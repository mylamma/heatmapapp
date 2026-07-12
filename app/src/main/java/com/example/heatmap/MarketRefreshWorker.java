package com.example.heatmap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.os.Environment;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 앱(MainActivity)이 화면에 떠 있지 않아도(프로세스가 꺼져 있어도) 실행 가능한
 * "시장 판단 -> 데이터 조회 -> 화면 렌더링 -> 슬립화면 저장" 전체 과정을 담당.
 * AlarmManager로 깨어난 HourlyUpdateReceiver가 이 클래스를 호출함.
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

    /**
     * 화면에 아무것도 안 띄운 채로(Activity 없이) 트리맵+상단바를 그려서 비트맵으로 캡처하고,
     * 180도 회전시켜 슬립 폴더에 저장. MainActivity가 실행 중이 아니어도 동작함.
     */
    public static void renderAndSaveSleepImage(Context context, MainActivity.Market market, MacroSnapshot macro) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            Point size = new Point();
            display.getRealSize(size);
            int width = size.x;
            int height = size.y;
            if (width <= 0 || height <= 0) return;

            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.WHITE);

            LinearLayout macroBar = new LinearLayout(context);
            macroBar.setOrientation(LinearLayout.VERTICAL);
            macroBar.setBackgroundColor(Color.BLACK);

            TextView line1 = new TextView(context);
            String marketLabel = market == MainActivity.Market.US ? "[미국장]" : "[국내장]";
            StringBuilder l1 = new StringBuilder(marketLabel).append(" ");
            l1.append(!Double.isNaN(macro.usdKrw) ? String.format(Locale.KOREA, "원/달러 %,.2f", macro.usdKrw) : "원/달러 --");
            l1.append("   ");
            if (!Double.isNaN(macro.kospi)) {
                String sign = macro.kospiPct >= 0 ? "+" : "";
                l1.append(String.format(Locale.KOREA, "코스피 %,.2f (%s%.2f%%)", macro.kospi, sign, macro.kospiPct));
            } else {
                l1.append("코스피 --");
            }
            l1.append("   ");
            l1.append(!Double.isNaN(macro.btc) ? String.format(Locale.US, "BTC $%,.0f", macro.btc) : "BTC --");
            l1.append("   ");
            l1.append(!Double.isNaN(macro.eth) ? String.format(Locale.US, "ETH $%,.0f", macro.eth) : "ETH --");
            line1.setText(l1.toString());
            line1.setTextColor(Color.WHITE);
            line1.setTextSize(12);
            line1.setPadding(12, 8, 12, 2);
            macroBar.addView(line1);

            TextView line2 = new TextView(context);
            StringBuilder l2 = new StringBuilder();
            l2.append("다우 ").append(formatValuePct(macro.dow, macro.dowPct));
            l2.append("   나스닥 ").append(formatValuePct(macro.nasdaq, macro.nasdaqPct));
            l2.append("   S&P500 ").append(formatValuePct(macro.sp500, macro.sp500Pct));
            l2.append("   VIX ").append(formatValuePct(macro.vix, macro.vixPct));
            line2.setText(l2.toString());
            line2.setTextColor(Color.WHITE);
            line2.setTextSize(12);
            line2.setPadding(12, 2, 12, 8);
            macroBar.addView(line2);

            root.addView(macroBar);

            TreemapView treemapView = new TreemapView(context);
            treemapView.setSectors(market == MainActivity.Market.US ? MainActivity.SECTORS_US : MainActivity.SECTORS_KR);
            LinearLayout.LayoutParams treemapParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(treemapView, treemapParams);

            // Activity 없이 수동으로 측정/배치/그리기 (화면에 실제로 띄우지 않고도 렌더링 가능)
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY);
            root.measure(widthSpec, heightSpec);
            root.layout(0, 0, width, height);

            Bitmap rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(rawBitmap);
            root.draw(canvas);

            Matrix matrix = new Matrix();
            matrix.postRotate(180);
            Bitmap rotatedBitmap = Bitmap.createBitmap(rawBitmap, 0, 0, width, height, matrix, true);
            rawBitmap.recycle();

            File sleepDir = new File(Environment.getExternalStorageDirectory(), "sleep");
            if (!sleepDir.exists()) sleepDir.mkdirs();
            File outFile = new File(sleepDir, "heatmap_sleep_rotated.png");

            FileOutputStream out = new FileOutputStream(outFile);
            rotatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            out.flush();
            out.close();
            rotatedBitmap.recycle();
        } catch (Exception ignored) {
            // 실패해도 다음 시간에 다시 시도되므로 조용히 무시
        }
    }

    private static String formatValuePct(double value, double pct) {
        if (Double.isNaN(value)) return "--";
        String sign = pct >= 0 ? "+" : "";
        String pctText = Double.isNaN(pct) ? "" : String.format(Locale.US, " (%s%.2f%%)", sign, pct);
        return String.format(Locale.US, "%,.2f%s", value, pctText);
    }
}
