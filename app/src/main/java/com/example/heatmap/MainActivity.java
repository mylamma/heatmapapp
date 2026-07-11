package com.example.heatmap;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 주식시장 전체 화면 트리맵 히트맵 (미국장 / 국내장 전환 가능)
 * - 화면 전체를 스크롤 없이 꽉 채움 (TreemapView 커스텀 뷰 사용)
 * - 섹터/종목 박스 크기는 시가총액 비중에 비례
 * - 1분마다 등락률 + 원/달러 환율 + 코스피 지수 + 주요 지수/암호화폐 실시간 갱신
 * - 좌/우 물리 버튼(또는 화면 키)으로 미국장 <-> 국내장 전환
 * - 종목을 탭하면 상세 화면(StockDetailActivity)으로 이동
 */
public class MainActivity extends Activity {

    private static final long REFRESH_INTERVAL_MS = 60 * 60 * 1000L; // 1시간 (배터리 절약)

    private enum Market { US, KR }

    // ===== 미국장: 섹터 + 종목 + 상대적 시가총액 비중(근사치) =====
    private static final TreemapView.Sector[] SECTORS_US = new TreemapView.Sector[]{
            new TreemapView.Sector("기술", new TreemapView.Stock[]{
                    new TreemapView.Stock("AAPL", 3500),
                    new TreemapView.Stock("MSFT", 3200),
                    new TreemapView.Stock("NVDA", 3300),
                    new TreemapView.Stock("AVGO", 800),
            }),
            new TreemapView.Sector("금융", new TreemapView.Stock[]{
                    new TreemapView.Stock("JPM", 600),
                    new TreemapView.Stock("BAC", 320),
                    new TreemapView.Stock("WFC", 230),
                    new TreemapView.Stock("GS", 150),
            }),
            new TreemapView.Sector("에너지", new TreemapView.Stock[]{
                    new TreemapView.Stock("XOM", 470),
                    new TreemapView.Stock("CVX", 280),
                    new TreemapView.Stock("COP", 130),
                    new TreemapView.Stock("SLB", 60),
            }),
            new TreemapView.Sector("헬스케어", new TreemapView.Stock[]{
                    new TreemapView.Stock("UNH", 460),
                    new TreemapView.Stock("JNJ", 380),
                    new TreemapView.Stock("LLY", 800),
                    new TreemapView.Stock("PFE", 150),
            }),
            new TreemapView.Sector("임의소비재", new TreemapView.Stock[]{
                    new TreemapView.Stock("AMZN", 2000),
                    new TreemapView.Stock("TSLA", 800),
                    new TreemapView.Stock("HD", 400),
                    new TreemapView.Stock("MCD", 210),
            }),
            new TreemapView.Sector("필수소비재", new TreemapView.Stock[]{
                    new TreemapView.Stock("PG", 380),
                    new TreemapView.Stock("KO", 290),
                    new TreemapView.Stock("PEP", 230),
                    new TreemapView.Stock("WMT", 700),
            }),
            new TreemapView.Sector("산업", new TreemapView.Stock[]{
                    new TreemapView.Stock("CAT", 180),
                    new TreemapView.Stock("BA", 130),
                    new TreemapView.Stock("HON", 140),
                    new TreemapView.Stock("UPS", 90),
            }),
            new TreemapView.Sector("소재", new TreemapView.Stock[]{
                    new TreemapView.Stock("LIN", 210),
                    new TreemapView.Stock("SHW", 70),
                    new TreemapView.Stock("ECL", 70),
                    new TreemapView.Stock("FCX", 60),
            }),
            new TreemapView.Sector("부동산", new TreemapView.Stock[]{
                    new TreemapView.Stock("PLD", 100),
                    new TreemapView.Stock("AMT", 90),
                    new TreemapView.Stock("EQIX", 80),
                    new TreemapView.Stock("SPG", 55),
            }),
            new TreemapView.Sector("유틸리티", new TreemapView.Stock[]{
                    new TreemapView.Stock("NEE", 150),
                    new TreemapView.Stock("DUK", 90),
                    new TreemapView.Stock("SO", 95),
                    new TreemapView.Stock("D", 45),
            }),
            new TreemapView.Sector("통신", new TreemapView.Stock[]{
                    new TreemapView.Stock("GOOGL", 2200),
                    new TreemapView.Stock("META", 1500),
                    new TreemapView.Stock("VZ", 180),
                    new TreemapView.Stock("T", 160),
            }),
    };

    // ===== 국내장(KOSPI): 섹터 + 종목코드 + 한글 표시명 + 상대적 시가총액 비중(근사치) =====
    private static final TreemapView.Sector[] SECTORS_KR = new TreemapView.Sector[]{
            new TreemapView.Sector("반도체/IT", new TreemapView.Stock[]{
                    new TreemapView.Stock("005930", "삼성전자", 400),
                    new TreemapView.Stock("000660", "SK하이닉스", 140),
                    new TreemapView.Stock("035420", "NAVER", 40),
                    new TreemapView.Stock("035720", "카카오", 20),
            }),
            new TreemapView.Sector("금융", new TreemapView.Stock[]{
                    new TreemapView.Stock("105560", "KB금융", 30),
                    new TreemapView.Stock("055550", "신한지주", 25),
                    new TreemapView.Stock("086790", "하나금융지주", 15),
                    new TreemapView.Stock("032830", "삼성생명", 12),
            }),
            new TreemapView.Sector("자동차", new TreemapView.Stock[]{
                    new TreemapView.Stock("005380", "현대차", 45),
                    new TreemapView.Stock("000270", "기아", 35),
                    new TreemapView.Stock("012330", "현대모비스", 20),
                    new TreemapView.Stock("018880", "한온시스템", 3),
            }),
            new TreemapView.Sector("2차전지/화학", new TreemapView.Stock[]{
                    new TreemapView.Stock("373220", "LG에너지솔루션", 70),
                    new TreemapView.Stock("006400", "삼성SDI", 20),
                    new TreemapView.Stock("051910", "LG화학", 18),
                    new TreemapView.Stock("005490", "POSCO홀딩스", 15),
            }),
            new TreemapView.Sector("바이오", new TreemapView.Stock[]{
                    new TreemapView.Stock("207940", "삼성바이오로직스", 50),
                    new TreemapView.Stock("068270", "셀트리온", 20),
                    new TreemapView.Stock("326030", "SK바이오팜", 5),
                    new TreemapView.Stock("000100", "유한양행", 3),
            }),
            new TreemapView.Sector("유통/소비재", new TreemapView.Stock[]{
                    new TreemapView.Stock("033780", "KT&G", 10),
                    new TreemapView.Stock("090430", "아모레퍼시픽", 4),
                    new TreemapView.Stock("097950", "CJ제일제당", 3),
                    new TreemapView.Stock("023530", "롯데쇼핑", 2),
            }),
            new TreemapView.Sector("조선/중공업", new TreemapView.Stock[]{
                    new TreemapView.Stock("009540", "HD한국조선해양", 15),
                    new TreemapView.Stock("034020", "두산에너빌리티", 6),
                    new TreemapView.Stock("010140", "삼성중공업", 5),
                    new TreemapView.Stock("042660", "한화오션", 4),
            }),
            new TreemapView.Sector("통신", new TreemapView.Stock[]{
                    new TreemapView.Stock("017670", "SK텔레콤", 12),
                    new TreemapView.Stock("030200", "KT", 9),
                    new TreemapView.Stock("402340", "SK스퀘어", 8),
                    new TreemapView.Stock("032640", "LG유플러스", 5),
            }),
            new TreemapView.Sector("지주/그룹사", new TreemapView.Stock[]{
                    new TreemapView.Stock("034730", "SK", 8),
                    new TreemapView.Stock("003550", "LG", 6),
                    new TreemapView.Stock("000880", "한화", 4),
                    new TreemapView.Stock("078930", "GS", 3),
            }),
            new TreemapView.Sector("에너지/유틸리티", new TreemapView.Stock[]{
                    new TreemapView.Stock("015760", "한국전력", 10),
                    new TreemapView.Stock("010950", "S-Oil", 4),
                    new TreemapView.Stock("036460", "한국가스공사", 2),
                    new TreemapView.Stock("051600", "한전KPS", 1),
            }),
            new TreemapView.Sector("게임/엔터", new TreemapView.Stock[]{
                    new TreemapView.Stock("259960", "크래프톤", 15),
                    new TreemapView.Stock("352820", "하이브", 8),
                    new TreemapView.Stock("036570", "엔씨소프트", 6),
                    new TreemapView.Stock("035900", "JYP Ent", 2),
            }),
    };

    private TreemapView treemapView;
    private LinearLayout macroBarContainer;
    private TextView macroBarLine1;
    private TextView macroBarLine2;
    private final Handler handler = new Handler();
    private Runnable refreshTask;
    private Market currentMarket = Market.US;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // 화면 항시 켜기 + 상태바 최소화로 화면 전체 활용
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            // 구형 안드로이드 HTTPS(TLS1.1/1.2) 호환 처리
            try {
                TLSSocketFactory.install();
            } catch (Exception ignored) {
            }

            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);

            macroBarContainer = new LinearLayout(this);
            macroBarContainer.setOrientation(LinearLayout.VERTICAL);
            macroBarContainer.setBackgroundColor(Color.BLACK);

            macroBarLine1 = new TextView(this);
            macroBarLine1.setText("[미국장] 불러오는 중...");
            macroBarLine1.setTextColor(Color.WHITE);
            macroBarLine1.setTextSize(12);
            macroBarLine1.setPadding(12, 8, 12, 2);
            macroBarContainer.addView(macroBarLine1);

            macroBarLine2 = new TextView(this);
            macroBarLine2.setText("주요 지수 불러오는 중... (좌/우 버튼: 국내장 전환)");
            macroBarLine2.setTextColor(Color.WHITE);
            macroBarLine2.setTextSize(12);
            macroBarLine2.setPadding(12, 2, 12, 8);
            macroBarContainer.addView(macroBarLine2);

            root.addView(macroBarContainer);

            treemapView = new TreemapView(this);
            treemapView.setSectors(SECTORS_US);
            treemapView.setOnStockTapListener(new TreemapView.OnStockTapListener() {
                @Override
                public void onStockTap(String symbol, String displayName) {
                    Intent intent = new Intent(MainActivity.this, StockDetailActivity.class);
                    intent.putExtra("symbol", symbol);
                    intent.putExtra("displayName", displayName);
                    intent.putExtra("market", currentMarket.name());
                    startActivity(intent);
                }
            });
            LinearLayout.LayoutParams treemapParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(treemapView, treemapParams);

            setContentView(root);

            refreshTask = new Runnable() {
                @Override
                public void run() {
                    new StockFetchTask().execute();
                    new MacroFetchTask().execute();
                    handler.postDelayed(this, REFRESH_INTERVAL_MS);
                }
            };
            handler.post(refreshTask);

        } catch (Throwable t) {
            showErrorScreen(t);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 이북리더 물리 버튼 역할 분리 (기기마다 실제 키코드가 달라서 후보를 폭넓게 매핑함):
        // - 왼쪽 버튼 계열 -> 한국장 <-> 미국장 전환
        // - 오른쪽 버튼 계열 -> 수동 새로고침 (1시간 기다리지 않고 즉시 최신 정보 조회)
        boolean isLeftKey =
                keyCode == KeyEvent.KEYCODE_PAGE_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                        || keyCode == KeyEvent.KEYCODE_VOLUME_UP;

        boolean isRightKey =
                keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                        || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;

        if (isLeftKey) {
            switchMarket(currentMarket == Market.US ? Market.KR : Market.US);
            return true;
        }
        if (isRightKey) {
            manualRefresh();
            return true;
        }

        // 매핑 안 된 키는 어떤 키코드인지 화면에 잠깐 띄워서, 필요하면 이 코드를 알려주고
        // 정확히 매핑할 수 있게 함 (왼쪽/오른쪽이 반대로 되어 있으면 알려주시면 바로 바꿔드릴 수 있음)
        Toast.makeText(this, "인식된 키코드: " + keyCode, Toast.LENGTH_SHORT).show();
        return super.onKeyDown(keyCode, event);
    }

    private void switchMarket(Market target) {
        if (currentMarket == target) return;
        currentMarket = target;
        treemapView.setSectors(currentMarket == Market.US ? SECTORS_US : SECTORS_KR);
        treemapView.refreshChanges(); // 캐시된 마지막 값으로 즉시 표시 + e-ink 잔상 제거 플래시
        flashMacroBar();
        renderMacroBar(); // 시장 라벨만 갱신, 환율/지수 값은 그대로 유지 (재조회 불필요)
        new StockFetchTask().execute();
    }

    /** 오른쪽 버튼: 1시간 자동 주기와 별개로 즉시 최신 정보를 불러옴 */
    private void manualRefresh() {
        treemapView.refreshChanges(); // e-ink 잔상 제거 플래시
        flashMacroBar();
        new StockFetchTask().execute();
        new MacroFetchTask().execute();
        Toast.makeText(this, "새로고침 중...", Toast.LENGTH_SHORT).show();
    }

    private void showErrorScreen(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));

        TextView tv = new TextView(this);
        tv.setText("앱 실행 중 오류 발생:\n\n" + sw.toString());
        tv.setTextSize(12);
        tv.setPadding(24, 48, 24, 24);
        tv.setTextColor(Color.RED);
        tv.setBackgroundColor(Color.WHITE);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(refreshTask);
        super.onDestroy();
    }

    private class StockFetchTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... voids) {
            if (currentMarket == Market.US) {
                java.util.List<TreemapView.Stock> allStocks = new java.util.ArrayList<>();
                for (TreemapView.Sector sector : SECTORS_US) {
                    for (TreemapView.Stock stock : sector.stocks) allStocks.add(stock);
                }

                java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

                for (final TreemapView.Stock stock : allStocks) {
                    futures.add(pool.submit(new Runnable() {
                        @Override
                        public void run() {
                            // 종목당 quote를 1번만 호출해서 등락률과 현재가(히스토리 기록용)를 같이 얻음
                            // (예전엔 등락률용/기록용으로 2번 호출했는데 합쳐서 API 호출을 절반으로 줄임)
                            JSONObject json = fetchQuoteJson(stock.symbol);
                            if (json == null) return;
                            double current = json.optDouble("c", Double.NaN);
                            double prevClose = json.optDouble("pc", Double.NaN);
                            if (!Double.isNaN(current) && !Double.isNaN(prevClose) && prevClose != 0) {
                                stock.percentChange = ((current - prevClose) / prevClose) * 100.0;
                                stock.lastUpdatedMs = System.currentTimeMillis();
                            }
                            if (!Double.isNaN(current)) {
                                PriceHistory.record(MainActivity.this, stock.symbol, current);
                            }
                        }
                    }));
                }
                for (java.util.concurrent.Future<?> f : futures) {
                    try {
                        f.get();
                    } catch (Exception ignored) {
                    }
                }
                pool.shutdown();
            } else {
                java.util.List<String> codes = new java.util.ArrayList<>();
                for (TreemapView.Sector sector : SECTORS_KR) {
                    for (TreemapView.Stock stock : sector.stocks) {
                        codes.add(stock.symbol);
                    }
                }
                Map<String, double[]> results = fetchKoreanBatch(codes);
                for (TreemapView.Sector sector : SECTORS_KR) {
                    for (TreemapView.Stock stock : sector.stocks) {
                        double[] r = results.get(stock.symbol);
                        if (r != null && !Double.isNaN(r[1])) {
                            stock.percentChange = r[1];
                            stock.lastUpdatedMs = System.currentTimeMillis();
                            if (!Double.isNaN(r[0])) {
                                PriceHistory.record(MainActivity.this, stock.symbol, r[0]);
                            }
                        }
                        // 실패하면 lastUpdatedMs를 갱신하지 않음 -> 1시간 지나면 화면에서 자동으로 "--" 처리됨
                    }
                }
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            treemapView.refreshChanges();
            flashMacroBar();
        }
    }

    // ===== 상단바(환율/코스피/암호화폐/주요지수) - 시장과 무관, 마지막 성공값+시각을 캐싱 =====
    private static final long MACRO_FRESHNESS_WINDOW_MS = 60L * 60 * 1000; // 1시간

    private double cachedUsdKrw = Double.NaN;
    private long cachedUsdKrwTime = 0;
    private double cachedKospi = Double.NaN;
    private double cachedKospiPct = Double.NaN;
    private long cachedKospiTime = 0;
    private double cachedBtc = Double.NaN;
    private long cachedBtcTime = 0;
    private double cachedEth = Double.NaN;
    private long cachedEthTime = 0;
    private double cachedDowValue = Double.NaN;
    private double cachedDowPct = Double.NaN;
    private long cachedDowTime = 0;
    private double cachedNasdaqValue = Double.NaN;
    private double cachedNasdaqPct = Double.NaN;
    private long cachedNasdaqTime = 0;
    private double cachedSp500Value = Double.NaN;
    private double cachedSp500Pct = Double.NaN;
    private long cachedSp500Time = 0;
    private double cachedVixValue = Double.NaN;
    private double cachedVixPct = Double.NaN;
    private long cachedVixTime = 0;

    private boolean isFresh(long timestamp) {
        return timestamp > 0 && (System.currentTimeMillis() - timestamp) <= MACRO_FRESHNESS_WINDOW_MS;
    }

    private static class MacroResult {
        double usdKrw = Double.NaN;
        double kospi = Double.NaN;
        double kospiChangePct = Double.NaN;
        double btcUsd = Double.NaN;
        double ethUsd = Double.NaN;
        double dowValue = Double.NaN;
        double dowChangePct = Double.NaN;
        double nasdaqValue = Double.NaN;
        double nasdaqChangePct = Double.NaN;
        double sp500Value = Double.NaN;
        double sp500ChangePct = Double.NaN;
        double vixValue = Double.NaN;
        double vixChangePct = Double.NaN;
    }

    private class MacroFetchTask extends AsyncTask<Void, Void, MacroResult> {
        @Override
        protected MacroResult doInBackground(Void... voids) {
            MacroResult macro = new MacroResult();
            macro.usdKrw = fetchUsdKrw();
            double[] kospi = fetchKospi();
            macro.kospi = kospi[0];
            macro.kospiChangePct = kospi[1];
            macro.btcUsd = fetchSimplePrice("BINANCE:BTCUSDT");
            macro.ethUsd = fetchSimplePrice("BINANCE:ETHUSDT");

            double[] dow = fetchWorldIndex("^DJI");
            macro.dowValue = dow[0];
            macro.dowChangePct = dow[1];
            double[] nasdaq = fetchWorldIndex("^IXIC");
            macro.nasdaqValue = nasdaq[0];
            macro.nasdaqChangePct = nasdaq[1];
            double[] sp500 = fetchWorldIndex("^GSPC");
            macro.sp500Value = sp500[0];
            macro.sp500ChangePct = sp500[1];
            double[] vix = fetchWorldIndex("^VIX");
            macro.vixValue = vix[0];
            macro.vixChangePct = vix[1];
            return macro;
        }

        @Override
        protected void onPostExecute(MacroResult macro) {
            long now = System.currentTimeMillis();
            // 새로 받아온 값이 유효할 때만 캐시+시각을 갱신 -> 일시적 실패로 "--"로 되돌아가지 않음
            if (!Double.isNaN(macro.usdKrw)) { cachedUsdKrw = macro.usdKrw; cachedUsdKrwTime = now; }
            if (!Double.isNaN(macro.kospi)) { cachedKospi = macro.kospi; cachedKospiPct = macro.kospiChangePct; cachedKospiTime = now; }
            if (!Double.isNaN(macro.btcUsd)) { cachedBtc = macro.btcUsd; cachedBtcTime = now; }
            if (!Double.isNaN(macro.ethUsd)) { cachedEth = macro.ethUsd; cachedEthTime = now; }
            if (!Double.isNaN(macro.dowValue)) { cachedDowValue = macro.dowValue; cachedDowPct = macro.dowChangePct; cachedDowTime = now; }
            if (!Double.isNaN(macro.nasdaqValue)) { cachedNasdaqValue = macro.nasdaqValue; cachedNasdaqPct = macro.nasdaqChangePct; cachedNasdaqTime = now; }
            if (!Double.isNaN(macro.sp500Value)) { cachedSp500Value = macro.sp500Value; cachedSp500Pct = macro.sp500ChangePct; cachedSp500Time = now; }
            if (!Double.isNaN(macro.vixValue)) { cachedVixValue = macro.vixValue; cachedVixPct = macro.vixChangePct; cachedVixTime = now; }

            renderMacroBar();
        }
    }

    /** 상단 환율/지수 배너도 트리맵과 같은 타이밍으로 흑백 반전시켜 잔상 제거 효과를 줌 */
    private void flashMacroBar() {
        doMacroFlash(2);
    }

    private void doMacroFlash(final int remaining) {
        if (remaining <= 0) {
            macroBarContainer.setBackgroundColor(Color.BLACK);
            macroBarLine1.setTextColor(Color.WHITE);
            macroBarLine2.setTextColor(Color.WHITE);
            return;
        }
        boolean showWhite = remaining % 2 == 0;
        macroBarContainer.setBackgroundColor(showWhite ? Color.WHITE : Color.BLACK);
        macroBarLine1.setTextColor(showWhite ? Color.BLACK : Color.WHITE);
        macroBarLine2.setTextColor(showWhite ? Color.BLACK : Color.WHITE);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doMacroFlash(remaining - 1);
            }
        }, 700);
    }

    private void renderMacroBar() {
        String marketLabel = currentMarket == Market.US ? "[미국장]" : "[국내장]";

        boolean usdKrwFresh = isFresh(cachedUsdKrwTime);
        boolean kospiFresh = isFresh(cachedKospiTime);
        boolean btcFresh = isFresh(cachedBtcTime);
        boolean ethFresh = isFresh(cachedEthTime);

        StringBuilder line1 = new StringBuilder(marketLabel).append(" ");
        line1.append(usdKrwFresh
                ? String.format(Locale.KOREA, "원/달러 %,.2f", cachedUsdKrw)
                : "원/달러 --");
        line1.append("   ");
        if (kospiFresh) {
            String sign = cachedKospiPct >= 0 ? "+" : "";
            line1.append(String.format(Locale.KOREA, "코스피 %,.2f (%s%.2f%%)", cachedKospi, sign, cachedKospiPct));
        } else {
            line1.append("코스피 --");
        }
        line1.append("   ");
        line1.append(btcFresh ? String.format(Locale.US, "BTC $%,.0f", cachedBtc) : "BTC --");
        line1.append("   ");
        line1.append(ethFresh ? String.format(Locale.US, "ETH $%,.0f", cachedEth) : "ETH --");
        macroBarLine1.setText(line1.toString());

        StringBuilder line2 = new StringBuilder();
        line2.append("다우 ").append(isFresh(cachedDowTime) ? formatValuePct(cachedDowValue, cachedDowPct) : "--");
        line2.append("   나스닥 ").append(isFresh(cachedNasdaqTime) ? formatValuePct(cachedNasdaqValue, cachedNasdaqPct) : "--");
        line2.append("   S&P500 ").append(isFresh(cachedSp500Time) ? formatValuePct(cachedSp500Value, cachedSp500Pct) : "--");
        line2.append("   VIX ").append(isFresh(cachedVixTime) ? formatValuePct(cachedVixValue, cachedVixPct) : "--");
        macroBarLine2.setText(line2.toString());
    }

    /** "45,123.45 (+0.42%)" 형식으로 수치와 등락률을 함께 표기 */
    private String formatValuePct(double value, double pct) {
        if (Double.isNaN(value)) return "--";
        String sign = pct >= 0 ? "+" : "";
        String pctText = Double.isNaN(pct) ? "" : String.format(Locale.US, " (%s%.2f%%)", sign, pct);
        return String.format(Locale.US, "%,.2f%s", value, pctText);
    }

    // BTC/USDT, ETH/USDT 등 단순 현재가만 필요한 심볼(암호화폐 등) 조회용
    private double fetchSimplePrice(String symbol) {
        JSONObject json = fetchQuoteJson(symbol);
        if (json == null) return Double.NaN;
        return json.optDouble("c", Double.NaN);
    }

    private JSONObject fetchQuoteJson(String symbol) {
        String body = NetUtil.httpGet(
                "https://finnhub.io/api/v1/quote?symbol=" + symbol + "&token=" + BuildConfig.FINNHUB_API_KEY);
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private double fetchUsdKrw() {
        // 무료/무키/표준 JSON 환율 API를 프록시 경유로 사용 (Finnhub forex보다 안정적으로 확인됨)
        String body = NetUtil.proxyGet("https://open.er-api.com/v6/latest/USD");
        if (body == null) return Double.NaN;
        try {
            JSONObject json = new JSONObject(body);
            JSONObject rates = json.optJSONObject("rates");
            if (rates == null) return Double.NaN;
            return rates.optDouble("KRW", Double.NaN);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private double[] fetchKospi() {
        // 참고: 코스피는 Finnhub 무료 플랜에서 지원하지 않아 네이버 금융의 비공식 공개 API를 사용함.
        return fetchNaverIndex("https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI");
    }

    /**
     * 다우(^DJI)/나스닥(^IXIC)/S&P500(^GSPC)/VIX(^VIX) 조회.
     * 네이버의 해외지수 API는 정확한 경로가 공식 확인되지 않아 불안정했어서,
     * 오래전부터 널리 쓰여온 야후 파이낸스의 공개 차트 API(프록시 경유)로 교체함.
     * 반환값: [현재 지수/수치, 등락률(%)]
     */
    private double[] fetchWorldIndex(String yahooSymbol) {
        String body = NetUtil.proxyGet("https://query1.finance.yahoo.com/v8/finance/chart/" + yahooSymbol);
        if (body == null) return new double[]{Double.NaN, Double.NaN};
        try {
            JSONObject json = new JSONObject(body);
            JSONObject result = json.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
            JSONObject meta = result.getJSONObject("meta");
            double current = meta.optDouble("regularMarketPrice", Double.NaN);
            double prevClose = meta.optDouble("previousClose", Double.NaN);
            if (Double.isNaN(prevClose)) {
                prevClose = meta.optDouble("chartPreviousClose", Double.NaN);
            }
            if (Double.isNaN(current)) return new double[]{Double.NaN, Double.NaN};
            double pct = (Double.isNaN(prevClose) || prevClose == 0)
                    ? Double.NaN
                    : ((current - prevClose) / prevClose) * 100.0;
            return new double[]{current, pct};
        } catch (Exception e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    private double[] fetchNaverIndex(String url) {
        String body = NetUtil.proxyGet(url);
        if (body == null) return new double[]{Double.NaN, Double.NaN};
        try {
            JSONObject json = new JSONObject(body);
            JSONObject datas = json.getJSONArray("datas").getJSONObject(0);
            double value = parseNaverNumber(datas.optString("closePrice", null));
            double changeRate = parseNaverNumber(datas.optString("fluctuationsRatio", null));
            return new double[]{value, changeRate};
        } catch (Exception e) {
            return new double[]{Double.NaN, Double.NaN};
        }
    }

    /**
     * 국내 종목 조회. 순서대로 하나씩(순차) 요청하면 44종목 x 네트워크 왕복시간이라
     * 꽤 느려서, 동시에 최대 8개까지 병렬로 요청하도록 개선함 (서버 부담과 속도의 절충점).
     * 반환값: 종목코드 -> [현재가, 등락률(%)]
     */
    private Map<String, double[]> fetchKoreanBatch(java.util.List<String> codes) {
        final Map<String, double[]> result = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (final String code : codes) {
            futures.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    String url = "https://polling.finance.naver.com/api/realtime/domestic/stock/" + code;
                    String body = NetUtil.proxyGet(url);
                    if (body == null) return;
                    try {
                        JSONObject json = new JSONObject(body);
                        JSONObject item = json.getJSONArray("datas").getJSONObject(0);
                        double price = parseNaverNumber(item.optString("closePrice", null));
                        double changeRate = parseNaverNumber(item.optString("fluctuationsRatio", null));
                        result.put(code, new double[]{price, changeRate});
                    } catch (Exception ignored) {
                    }
                }
            }));
        }

        // 전부 끝날 때까지 대기 (StockFetchTask가 이미 백그라운드 스레드에서 실행 중이라 여기서 기다려도 안전함)
        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        pool.shutdown();
        return result;
    }

    private double parseNaverNumber(String raw) {
        if (raw == null || raw.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
