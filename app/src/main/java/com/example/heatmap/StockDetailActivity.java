package com.example.heatmap;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 종목 상세 화면.
 * - 자체 축적 3일 차트 (PriceHistory, 1분 단위 기록)
 * - 당일 시가/고가/저가 (quote)
 * - 회사 개요 (profile2)
 * - 재무지표: PER, 52주 최고/최저 (metric) - forward PER은 무료 플랜에 없어 trailing PER로 대체
 * - 애널리스트 의견 (recommendation)
 */
public class StockDetailActivity extends Activity {

    private static final long INACTIVITY_TIMEOUT_MS = 3 * 60 * 1000L; // 3분간 조작 없으면 메인 화면으로 복귀
    private final android.os.Handler inactivityHandler = new android.os.Handler();
    private final Runnable inactivityReturnRunnable = new Runnable() {
        @Override
        public void run() {
            finish(); // 메인 히트맵 화면으로 복귀 (거기서 3분 더 방치되면 캡처 후 슬립 전환됨)
        }
    };

    private String symbol;
    private String displayName;
    private boolean isKorean;
    private LinearLayout root;
    private TextView statusView;
    private TextView symbolView;
    private SparklineView sparklineView;

    private TextView priceView;
    private TextView pctView;
    private TextView ohlView;
    private TextView overviewView;
    private TextView metricView;
    private TextView newsView;
    private TextView recommendationLabel;
    private RecommendationBar recommendationBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            // 예전엔 여기 FLAG_KEEP_SCREEN_ON이 있었는데, 이게 슬립화면 전환 자체를
            // 막아버리는 원인이었어서 제거함 (메인화면과 동일하게 절전을 따르도록)
            symbol = getIntent().getStringExtra("symbol");
            if (symbol == null) symbol = "";
            displayName = getIntent().getStringExtra("displayName");
            if (displayName == null || displayName.isEmpty()) displayName = symbol;
            String market = getIntent().getStringExtra("market");
            isKorean = "KR".equals(market);

            setContentView(buildLayout());

            final WifiPowerManager wifiPowerManager = new WifiPowerManager(this);
            wifiPowerManager.ensureOnThen(new WifiPowerManager.OnWifiReadyListener() {
                @Override
                public void onWifiReady() {
                    new DetailFetchTask(wifiPowerManager).execute();
                }
            });

        } catch (Throwable t) {
            showErrorScreen(t);
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetInactivityTimer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetInactivityTimer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        inactivityHandler.removeCallbacks(inactivityReturnRunnable);
    }

    private void resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityReturnRunnable);
        inactivityHandler.postDelayed(inactivityReturnRunnable, INACTIVITY_TIMEOUT_MS);
    }

    private void showErrorScreen(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        TextView tv = new TextView(this);
        tv.setText("화면 표시 중 오류 발생:\n\n" + sw.toString());
        tv.setTextSize(12);
        tv.setPadding(24, 48, 24, 24);
        tv.setTextColor(Color.RED);
        tv.setBackgroundColor(Color.WHITE);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        TextView backBar = new TextView(this);
        backBar.setText("< 목록으로 (탭)");
        backBar.setTextColor(Color.WHITE);
        backBar.setBackgroundColor(Color.BLACK);
        backBar.setTextSize(13);
        backBar.setPadding(20, 20, 20, 20);
        backBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(backBar);

        symbolView = new TextView(this);
        symbolView.setText(displayName);
        symbolView.setTextSize(24);
        symbolView.setTypeface(null, android.graphics.Typeface.BOLD);
        symbolView.setPadding(20, 20, 20, 4);
        root.addView(symbolView);

        statusView = new TextView(this);
        statusView.setText("불러오는 중...");
        statusView.setTextSize(13);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(20, 0, 20, 12);
        root.addView(statusView);

        LinearLayout priceBanner = new LinearLayout(this);
        priceBanner.setOrientation(LinearLayout.HORIZONTAL);
        priceBanner.setPadding(20, 16, 20, 16);
        addDivider();

        priceView = new TextView(this);
        priceView.setText("--");
        priceView.setTextSize(28);
        priceView.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams priceLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        priceBanner.addView(priceView, priceLp);

        pctView = new TextView(this);
        pctView.setText("--");
        pctView.setTextSize(18);
        pctView.setTypeface(null, android.graphics.Typeface.BOLD);
        pctView.setGravity(Gravity.END);
        priceBanner.addView(pctView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(priceBanner);
        addDivider();

        TextView chartLabel = new TextView(this);
        chartLabel.setText("최근 3일 (자체 기록, 1분 단위)");
        chartLabel.setTextSize(12);
        chartLabel.setTextColor(Color.DKGRAY);
        chartLabel.setPadding(20, 14, 20, 4);
        root.addView(chartLabel);

        sparklineView = new SparklineView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 160);
        chartLp.setMargins(20, 0, 20, 4);
        root.addView(sparklineView, chartLp);

        TextView chartLegend = new TextView(this);
        chartLegend.setText("굵은 실선: 정규장 · 얇은 점선: 시간외/휴장 · 세로 점선: 개장·마감");
        chartLegend.setTextSize(10);
        chartLegend.setTextColor(Color.GRAY);
        chartLegend.setPadding(20, 0, 20, 14);
        root.addView(chartLegend);
        addDivider();

        ohlView = new TextView(this);
        ohlView.setText("당일 시가/고가/저가: --");
        ohlView.setTextSize(13);
        ohlView.setPadding(20, 14, 20, 14);
        root.addView(ohlView);
        addDivider();

        overviewView = new TextView(this);
        overviewView.setText("회사 개요: 불러오는 중...");
        overviewView.setTextSize(13);
        overviewView.setPadding(20, 14, 20, 14);
        root.addView(overviewView);
        addDivider();

        metricView = new TextView(this);
        metricView.setText("재무지표: 불러오는 중...");
        metricView.setTextSize(13);
        metricView.setPadding(20, 14, 20, 14);
        root.addView(metricView);
        addDivider();

        newsView = new TextView(this);
        newsView.setText("관련 뉴스: 불러오는 중...");
        newsView.setTextSize(13);
        newsView.setPadding(20, 14, 20, 14);
        root.addView(newsView);
        addDivider();

        recommendationLabel = new TextView(this);
        recommendationLabel.setText("애널리스트 의견: 불러오는 중...");
        recommendationLabel.setTextSize(13);
        recommendationLabel.setPadding(20, 14, 20, 6);
        root.addView(recommendationLabel);

        recommendationBar = new RecommendationBar(this);
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 40);
        recLp.setMargins(20, 0, 20, 24);
        root.addView(recommendationBar, recLp);

        scroll.addView(root);
        return scroll;
    }

    private void addDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(153, 153, 153));
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2));
    }

    // ===== 데이터 모델 =====
    private static class DetailData {
        double current = Double.NaN, prevClose = Double.NaN;
        double open = Double.NaN, high = Double.NaN, low = Double.NaN;
        String companyName = "", industry = "", website = "";
        double pe = Double.NaN, week52High = Double.NaN, week52Low = Double.NaN;
        boolean peIsForward = false; // true면 선행(forward) PER, false면 후행(trailing) PER
        int buy, hold, sell, strongBuy, strongSell;
        boolean hasRecommendation = false;
        List<String> newsHeadlines = new ArrayList<>();

        // ===== 국내 종목 전용 (네이버 integration API 기반) =====
        double eps = Double.NaN, pbr = Double.NaN, bps = Double.NaN, dividendYield = Double.NaN;
        String marketCapText = ""; // "1,666조 1,894억" 같은 단위가 섞인 문자열이라 그대로 표시
        List<String> peerStocks = new ArrayList<>(); // 동종업계 종목 (이름 + 등락률)
        List<String> reports = new ArrayList<>(); // 증권사 리포트 제목
        double analystRecommMean = Double.NaN; // 1(적극매수)~5(매도) 평균 점수
        String analystPriceTarget = ""; // 목표주가 평균 (문자열, 콤마 포함)
    }

    private class DetailFetchTask extends AsyncTask<Void, DetailData, DetailData> {
        private final WifiPowerManager wifiPowerManager;

        DetailFetchTask(WifiPowerManager wifiPowerManager) {
            this.wifiPowerManager = wifiPowerManager;
        }

        @Override
        protected DetailData doInBackground(Void... voids) {
            return isKorean ? fetchKoreanDetail() : fetchUsDetail();
        }

        @Override
        protected void onProgressUpdate(DetailData... values) {
            if (values.length > 0) applyQuickData(values[0]);
        }

        private DetailData fetchUsDetail() {
            DetailData d = new DetailData();

            JSONObject quote = fetchJson("https://finnhub.io/api/v1/quote?symbol=" + symbol
                    + "&token=" + BuildConfig.FINNHUB_API_KEY);
            if (quote != null) {
                d.current = quote.optDouble("c", Double.NaN);
                d.prevClose = quote.optDouble("pc", Double.NaN);
                d.open = quote.optDouble("o", Double.NaN);
                d.high = quote.optDouble("h", Double.NaN);
                d.low = quote.optDouble("l", Double.NaN);
            }
            publishProgress(d); // 가격/등락률/시가·고가·저가부터 먼저 화면에 표시 (나머지는 계속 불러오는 중)

            JSONObject profile = fetchJson("https://finnhub.io/api/v1/stock/profile2?symbol=" + symbol
                    + "&token=" + BuildConfig.FINNHUB_API_KEY);
            if (profile != null) {
                d.companyName = profile.optString("name", "");
                d.industry = profile.optString("finnhubIndustry", "");
                d.website = profile.optString("weburl", "");
            }

            JSONObject metricRoot = fetchJson("https://finnhub.io/api/v1/stock/metric?symbol=" + symbol
                    + "&metric=all&token=" + BuildConfig.FINNHUB_API_KEY);
            if (metricRoot != null) {
                JSONObject metric = metricRoot.optJSONObject("metric");
                if (metric != null) {
                    // forward PER은 무료 플랜에 없는 경우가 많아 trailing PER로 대체
                    d.pe = firstAvailable(metric, "peBasicExclExtraTTM", "peExclExtraTTM", "peNormalizedAnnual");
                    d.peIsForward = false;
                    d.week52High = firstAvailable(metric, "52WeekHigh");
                    d.week52Low = firstAvailable(metric, "52WeekLow");
                }
            }

            JSONArray recArray = fetchJsonArray("https://finnhub.io/api/v1/stock/recommendation?symbol=" + symbol
                    + "&token=" + BuildConfig.FINNHUB_API_KEY);
            if (recArray != null && recArray.length() > 0) {
                JSONObject latest = recArray.optJSONObject(0);
                if (latest != null) {
                    d.buy = latest.optInt("buy", 0);
                    d.hold = latest.optInt("hold", 0);
                    d.sell = latest.optInt("sell", 0);
                    d.strongBuy = latest.optInt("strongBuy", 0);
                    d.strongSell = latest.optInt("strongSell", 0);
                    d.hasRecommendation = true;
                }
            }

            // 최근 7일 관련 뉴스 헤드라인 (최대 3개) - Finnhub는 미국 종목만 지원
            java.text.SimpleDateFormat dateFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
            long now = System.currentTimeMillis();
            String toDate = dateFmt.format(new java.util.Date(now));
            String fromDate = dateFmt.format(new java.util.Date(now - 7L * 24 * 60 * 60 * 1000));
            JSONArray newsArray = fetchJsonArray("https://finnhub.io/api/v1/company-news?symbol=" + symbol
                    + "&from=" + fromDate + "&to=" + toDate + "&token=" + BuildConfig.FINNHUB_API_KEY);
            if (newsArray != null) {
                for (int i = 0; i < newsArray.length() && d.newsHeadlines.size() < 3; i++) {
                    JSONObject article = newsArray.optJSONObject(i);
                    if (article == null) continue;
                    String headline = article.optString("headline", "");
                    if (!headline.isEmpty()) d.newsHeadlines.add(headline);
                }
            }

            return d;
        }

        /**
         * 국내 종목 상세 정보. Finnhub 무료 플랜은 국내 종목을 지원하지 않아
         * 네이버 금융의 비공식 공개 API를 사용함 (회사 개요/뉴스/애널리스트 의견은
         * 이 경로로는 안정적으로 못 가져와서 비워둠 - "정보 없음"으로 표시됨).
         */
        private DetailData fetchKoreanDetail() {
            DetailData d = new DetailData();

            JSONObject realtime = fetchNaverJson("https://polling.finance.naver.com/api/realtime/domestic/stock/" + symbol);
            if (realtime != null) {
                try {
                    JSONObject item = realtime.getJSONArray("datas").getJSONObject(0);
                    d.current = parseNum(item.optString("closePrice", null));
                    d.open = parseNum(item.optString("openPrice", null));
                    d.high = parseNum(item.optString("highPrice", null));
                    d.low = parseNum(item.optString("lowPrice", null));
                    double changeRate = parseNum(item.optString("fluctuationsRatio", null));
                    // prevClose를 역산 (current / (1 + changeRate/100))
                    if (!Double.isNaN(d.current) && !Double.isNaN(changeRate) && (1 + changeRate / 100.0) != 0) {
                        d.prevClose = d.current / (1 + changeRate / 100.0);
                    }
                    d.companyName = item.optString("stockName", "");
                } catch (Exception ignored) {
                }
            }
            publishProgress(d); // 가격/등락률부터 먼저 화면에 표시

            JSONObject integration = fetchNaverJson("https://m.stock.naver.com/api/stock/" + symbol + "/integration");
            if (integration != null) {
                Map<String, String> info = new HashMap<>();
                JSONArray totalInfos = integration.optJSONArray("totalInfos");
                if (totalInfos != null) {
                    for (int i = 0; i < totalInfos.length(); i++) {
                        JSONObject row = totalInfos.optJSONObject(i);
                        if (row == null) continue;
                        String code = row.optString("code", "");
                        String value = row.optString("value", "");
                        if (!code.isEmpty()) info.put(code, value);
                    }
                }
                // 선행(forward) PER인 cnsPer를 우선 사용, 없으면 후행 PER 사용
                if (info.containsKey("cnsPer") && !info.get("cnsPer").isEmpty()) {
                    d.pe = parseNum(info.get("cnsPer"));
                    d.peIsForward = true;
                } else if (info.containsKey("per")) {
                    d.pe = parseNum(info.get("per"));
                    d.peIsForward = false;
                }
                d.week52High = parseNum(info.get("highPriceOf52Weeks"));
                d.week52Low = parseNum(info.get("lowPriceOf52Weeks"));
                d.eps = parseNum(info.get("eps"));
                d.pbr = parseNum(info.get("pbr"));
                d.bps = parseNum(info.get("bps"));
                d.dividendYield = parseNum(info.get("dividendYieldRatio"));
                if (info.containsKey("marketValue")) d.marketCapText = info.get("marketValue");

                // 동종업계 종목 (회사개요 대체 - 산업분류명 자체는 이 API로는 못 가져옴)
                JSONArray industryCompareInfo = integration.optJSONArray("industryCompareInfo");
                if (industryCompareInfo != null) {
                    for (int i = 0; i < Math.min(5, industryCompareInfo.length()); i++) {
                        JSONObject peer = industryCompareInfo.optJSONObject(i);
                        if (peer == null) continue;
                        String name = peer.optString("stockName", "");
                        double pct = parseNum(peer.optString("fluctuationsRatio", null));
                        if (!name.isEmpty()) {
                            String sign = (!Double.isNaN(pct) && pct >= 0) ? "+" : "";
                            String pctText = Double.isNaN(pct) ? "" : String.format(Locale.US, " %s%.2f%%", sign, pct);
                            d.peerStocks.add(name + pctText);
                        }
                    }
                }

                // 증권사 리포트 (관련 뉴스 대체)
                JSONArray researches = integration.optJSONArray("researches");
                if (researches != null) {
                    for (int i = 0; i < Math.min(3, researches.length()); i++) {
                        JSONObject r = researches.optJSONObject(i);
                        if (r == null) continue;
                        String tit = r.optString("tit", "");
                        String bnm = r.optString("bnm", "");
                        String wdt = r.optString("wdt", "");
                        String dateText = wdt.length() == 8
                                ? wdt.substring(0, 4) + "." + wdt.substring(4, 6) + "." + wdt.substring(6, 8)
                                : wdt;
                        if (!tit.isEmpty()) {
                            d.reports.add(tit + " · " + bnm + " · " + dateText);
                        }
                    }
                }

                // 애널리스트 컨센서스 (매수/보유/매도 비율이 아니라 평균 점수+목표주가 형태로 제공됨)
                JSONObject consensus = integration.optJSONObject("consensusInfo");
                if (consensus != null) {
                    d.analystRecommMean = parseNum(consensus.optString("recommMean", null));
                    d.analystPriceTarget = consensus.optString("priceTargetMean", "");
                }
            }

            return d;
        }

        private double parseNum(String raw) {
            if (raw == null || raw.isEmpty()) return Double.NaN;
            try {
                return Double.parseDouble(raw.replace(",", "").replace("배", "").replace("%", "").trim());
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        @Override
        protected void onPostExecute(DetailData d) {
            applyData(d);
            wifiPowerManager.scheduleOff();
        }
    }

    private double firstAvailable(JSONObject obj, String... keys) {
        for (String key : keys) {
            double v = obj.optDouble(key, Double.NaN);
            if (!Double.isNaN(v)) return v;
        }
        return Double.NaN;
    }

    private JSONObject fetchJson(String url) {
        String body = NetUtil.httpGet(url);
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 네이버 금융 API는 Cloudflare Worker 프록시를 거쳐서 요청함 */
    private JSONObject fetchNaverJson(String naverUrl) {
        String body = NetUtil.proxyGet(naverUrl);
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray fetchJsonArray(String url) {
        String body = NetUtil.httpGet(url);
        if (body == null) return null;
        try {
            return new JSONArray(body);
        } catch (Exception e) {
            return null;
        }
    }

    /** 가격/등락률/시가·고가·저가만 우선 반영 (나머지 항목은 계속 "불러오는 중" 상태 유지) */
    private void applyQuickData(DetailData d) {
        if (!Double.isNaN(d.current)) {
            priceView.setText(isKorean
                    ? String.format(Locale.KOREA, "₩%,.0f", d.current)
                    : String.format(Locale.US, "$%,.2f", d.current));
        }
        if (!Double.isNaN(d.current) && !Double.isNaN(d.prevClose) && d.prevClose != 0) {
            double pct = ((d.current - d.prevClose) / d.prevClose) * 100.0;
            String sign = pct >= 0 ? "+" : "";
            pctView.setText(String.format(Locale.US, "%s%.2f%%", sign, pct));
            pctView.setTextColor(pct >= 0 ? Color.rgb(0, 120, 0) : Color.rgb(180, 0, 0));
        }
        if (isKorean) {
            ohlView.setText(String.format(Locale.KOREA, "당일 시가 ₩%,.0f  고가 ₩%,.0f  저가 ₩%,.0f",
                    d.open, d.high, d.low));
        } else {
            ohlView.setText(String.format(Locale.US, "당일 시가 %,.2f  고가 %,.2f  저가 %,.2f",
                    d.open, d.high, d.low));
        }
        if (!d.companyName.isEmpty() && !displayName.equals(d.companyName)) {
            symbolView.setText(displayName);
        }
        List<double[]> history = PriceHistory.getHistory(StockDetailActivity.this, symbol);
        sparklineView.setHistory(history);
    }

    private void applyData(DetailData d) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
        statusView.setText((d.companyName.isEmpty() ? "" : d.companyName + " · ")
                + "마지막 갱신 " + sdf.format(new java.util.Date()));

        if (!Double.isNaN(d.current)) {
            priceView.setText(isKorean
                    ? String.format(Locale.KOREA, "₩%,.0f", d.current)
                    : String.format(Locale.US, "$%,.2f", d.current));
        }
        if (!Double.isNaN(d.current) && !Double.isNaN(d.prevClose) && d.prevClose != 0) {
            double pct = ((d.current - d.prevClose) / d.prevClose) * 100.0;
            String sign = pct >= 0 ? "+" : "";
            pctView.setText(String.format(Locale.US, "%s%.2f%%", sign, pct));
            pctView.setTextColor(pct >= 0 ? Color.rgb(0, 120, 0) : Color.rgb(180, 0, 0));
        }

        if (isKorean) {
            ohlView.setText(String.format(Locale.KOREA, "당일 시가 ₩%,.0f  고가 ₩%,.0f  저가 ₩%,.0f",
                    d.open, d.high, d.low));
        } else {
            ohlView.setText(String.format(Locale.US, "당일 시가 %,.2f  고가 %,.2f  저가 %,.2f",
                    d.open, d.high, d.low));
        }

        if (isKorean) {
            // 국내 종목: "회사 개요" 대신 동종업계 종목 (네이버 API가 산업분류명/웹사이트는 안 주고
            // 같은 업종의 다른 종목 목록만 제공해서, 그걸 활용함)
            StringBuilder overview = new StringBuilder("동종업계 종목\n");
            if (d.peerStocks.isEmpty()) {
                overview.append("정보 없음");
            } else {
                for (String peer : d.peerStocks) overview.append(peer).append("\n");
            }
            overviewView.setText(overview.toString().trim());
        } else {
            StringBuilder overview = new StringBuilder("회사 개요\n");
            overview.append("산업: ").append(d.industry.isEmpty() ? "정보 없음" : d.industry).append("\n");
            overview.append(d.website.isEmpty() ? "웹사이트 정보 없음" : d.website);
            overviewView.setText(overview.toString());
        }

        StringBuilder metricText = new StringBuilder("재무지표\n");
        metricText.append(d.peIsForward ? "PER(선행): " : "PER(trailing): ")
                .append(Double.isNaN(d.pe) ? "--" : String.format(Locale.US, "%,.1f", d.pe)).append("\n");
        if (isKorean) {
            metricText.append("EPS: ").append(Double.isNaN(d.eps) ? "--" : String.format(Locale.US, "%,.0f원", d.eps)).append("\n");
            metricText.append("PBR: ").append(Double.isNaN(d.pbr) ? "--" : String.format(Locale.US, "%,.2f", d.pbr)).append("\n");
            metricText.append("BPS: ").append(Double.isNaN(d.bps) ? "--" : String.format(Locale.US, "%,.0f원", d.bps)).append("\n");
            metricText.append("배당수익률: ").append(Double.isNaN(d.dividendYield) ? "--" : String.format(Locale.US, "%.2f%%", d.dividendYield)).append("\n");
            metricText.append("시가총액: ").append(d.marketCapText.isEmpty() ? "--" : d.marketCapText).append("\n");
            metricText.append("52주 최고/최저: ")
                    .append(Double.isNaN(d.week52High) ? "--" : String.format(Locale.US, "₩%,.0f", d.week52High))
                    .append(" / ")
                    .append(Double.isNaN(d.week52Low) ? "--" : String.format(Locale.US, "₩%,.0f", d.week52Low));
        } else {
            metricText.append("52주 최고/최저: ")
                    .append(Double.isNaN(d.week52High) ? "--" : String.format(Locale.US, "%,.2f", d.week52High))
                    .append(" / ")
                    .append(Double.isNaN(d.week52Low) ? "--" : String.format(Locale.US, "%,.2f", d.week52Low));
        }
        metricView.setText(metricText.toString());

        if (isKorean) {
            // 국내 종목: "관련 뉴스" 대신 증권사 리포트 (네이버 API가 뉴스 헤드라인은 안 주고
            // 증권사 리포트 제목/작성사/날짜를 제공해서, 그걸 활용함)
            if (d.reports.isEmpty()) {
                newsView.setText("증권사 리포트: 정보 없음");
            } else {
                StringBuilder reportText = new StringBuilder("증권사 리포트\n");
                for (String report : d.reports) reportText.append("· ").append(report).append("\n");
                newsView.setText(reportText.toString().trim());
            }
        } else {
            if (d.newsHeadlines.isEmpty()) {
                newsView.setText("관련 뉴스: 최근 7일간 뉴스 없음");
            } else {
                StringBuilder newsText = new StringBuilder("관련 뉴스 (최근 7일)\n");
                for (String headline : d.newsHeadlines) {
                    newsText.append("· ").append(headline).append("\n");
                }
                newsView.setText(newsText.toString().trim());
            }
        }

        if (isKorean) {
            // 국내 종목은 매수/보유/매도 비율이 아니라 "평균 점수 + 목표주가" 형태로 제공되어
            // 막대그래프 대신 텍스트로 표시 (막대는 숨김)
            recommendationBar.setVisibility(View.GONE);
            if (!Double.isNaN(d.analystRecommMean)) {
                String targetText = d.analystPriceTarget.isEmpty() ? "--" : d.analystPriceTarget + "원";
                recommendationLabel.setText(String.format(Locale.KOREA,
                        "애널리스트 평균 의견 %.2f (1=적극매수~5=매도) · 목표주가 평균 %s",
                        d.analystRecommMean, targetText));
            } else {
                recommendationLabel.setText("애널리스트 의견: 데이터 없음");
            }
        } else {
            recommendationBar.setVisibility(View.VISIBLE);
            if (d.hasRecommendation) {
                int totalBuy = d.buy + d.strongBuy;
                int total = totalBuy + d.hold + d.sell + d.strongSell;
                recommendationLabel.setText(String.format(Locale.KOREA,
                        "애널리스트 의견 (표본 %d명)", total));
                recommendationBar.setData(totalBuy, d.hold, d.sell + d.strongSell);
            } else {
                recommendationLabel.setText("애널리스트 의견: 데이터 없음");
            }
        }

        List<double[]> history = PriceHistory.getHistory(StockDetailActivity.this, symbol);
        sparklineView.setHistory(history);
    }

    // ===== 자체 축적 히스토리 선 그래프 =====
    public static class SparklineView extends View {
        private static final String MARKET_TZ = "America/New_York";
        private static final int MARKET_OPEN_MIN = 9 * 60 + 30;  // 09:30
        private static final int MARKET_CLOSE_MIN = 16 * 60;      // 16:00

        private List<double[]> history;
        private final Paint regularPaint = new Paint();   // 정규장 구간: 굵은 실선
        private final Paint afterHoursPaint = new Paint(); // 시간외/휴장 구간: 얇은 점선
        private final Paint boundaryPaint = new Paint();   // 개장/마감 경계: 세로 점선
        private final Paint dotPaint = new Paint();
        private final Paint axisPaint = new Paint();

        public SparklineView(Context context) {
            super(context);
            init();
        }

        public SparklineView(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        private void init() {
            regularPaint.setColor(Color.BLACK);
            regularPaint.setStrokeWidth(3f);
            regularPaint.setStyle(Paint.Style.STROKE);
            regularPaint.setAntiAlias(true);

            afterHoursPaint.setColor(Color.BLACK);
            afterHoursPaint.setStrokeWidth(2f);
            afterHoursPaint.setStyle(Paint.Style.STROKE);
            afterHoursPaint.setAntiAlias(true);
            afterHoursPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10f, 8f}, 0));

            boundaryPaint.setColor(Color.GRAY);
            boundaryPaint.setStrokeWidth(2f);
            boundaryPaint.setStyle(Paint.Style.STROKE);
            boundaryPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{3f, 5f}, 0));

            dotPaint.setColor(Color.BLACK);
            dotPaint.setAntiAlias(true);

            axisPaint.setColor(Color.DKGRAY);
            axisPaint.setTextSize(20f);
        }

        public void setHistory(List<double[]> rawHistory) {
            // 방어적 처리: 순서가 꼬였거나 값이 비정상인 기록이 섞여 있어도
            // 안전하게 정렬/정리해서 저장. 중간에 기록 공백(기기 꺼짐 등)이 있어도
            // 그 공백을 채우려 하지 않고, 남아있는 점들끼리만 시간순으로 이어 그림.
            List<double[]> cleaned = new ArrayList<>();
            if (rawHistory != null) {
                for (double[] p : rawHistory) {
                    if (p == null || p.length != 2) continue;
                    if (Double.isNaN(p[0]) || Double.isNaN(p[1])) continue;
                    if (Double.isInfinite(p[0]) || Double.isInfinite(p[1])) continue;
                    cleaned.add(p);
                }
                Collections.sort(cleaned, new Comparator<double[]>() {
                    @Override
                    public int compare(double[] a, double[] b) {
                        return Double.compare(a[0], b[0]);
                    }
                });
            }
            this.history = cleaned;
            invalidate();
        }

        /** 뉴욕 시간 기준으로 정규장(09:30~16:00, 평일)인지 판별 */
        private boolean isRegularHours(long epochMillis) {
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(MARKET_TZ));
            cal.setTimeInMillis(epochMillis);
            int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
            if (dow == java.util.Calendar.SATURDAY || dow == java.util.Calendar.SUNDAY) return false;
            int minutesOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
            return minutesOfDay >= MARKET_OPEN_MIN && minutesOfDay < MARKET_CLOSE_MIN;
        }

        /** [minT, maxT] 범위 안에 있는 모든 개장(09:30)·마감(16:00) 시각을 epoch ms로 반환 */
        private List<Long> findMarketBoundaries(double minT, double maxT) {
            List<Long> boundaries = new ArrayList<>();
            java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone(MARKET_TZ));
            cal.setTimeInMillis((long) minT);
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
            cal.set(java.util.Calendar.MINUTE, 0);
            cal.set(java.util.Calendar.SECOND, 0);
            cal.set(java.util.Calendar.MILLISECOND, 0);

            // 하루씩 전진하며 개장/마감 시각을 계산 (최대 10일치만 확인, 3일치 데이터라 충분)
            for (int day = 0; day < 10; day++) {
                java.util.Calendar open = (java.util.Calendar) cal.clone();
                open.add(java.util.Calendar.MINUTE, MARKET_OPEN_MIN);
                java.util.Calendar close = (java.util.Calendar) cal.clone();
                close.add(java.util.Calendar.MINUTE, MARKET_CLOSE_MIN);

                long openMs = open.getTimeInMillis();
                long closeMs = close.getTimeInMillis();
                if (openMs >= minT && openMs <= maxT) boundaries.add(openMs);
                if (closeMs >= minT && closeMs <= maxT) boundaries.add(closeMs);

                cal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                if (cal.getTimeInMillis() > maxT) break;
            }
            return boundaries;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (history == null || history.isEmpty()) {
                canvas.drawText("아직 기록된 데이터가 없습니다 (앱을 계속 사용하면 쌓입니다)", 10, getHeight() / 2f, axisPaint);
                return;
            }

            double minP = Double.MAX_VALUE, maxP = -Double.MAX_VALUE;
            double minT = Double.MAX_VALUE, maxT = -Double.MAX_VALUE;
            for (double[] point : history) {
                minT = Math.min(minT, point[0]);
                maxT = Math.max(maxT, point[0]);
                minP = Math.min(minP, point[1]);
                maxP = Math.max(maxP, point[1]);
            }
            if (maxP == minP) {
                maxP += 1;
                minP -= 1;
            }
            if (maxT == minT) maxT += 1;

            int w = getWidth(), h = getHeight();
            int padding = 8;
            float[] xs = new float[history.size()];
            float[] ys = new float[history.size()];
            for (int i = 0; i < history.size(); i++) {
                double[] p = history.get(i);
                xs[i] = (float) (padding + (p[0] - minT) / (maxT - minT) * (w - 2 * padding));
                ys[i] = (float) (h - padding - (p[1] - minP) / (maxP - minP) * (h - 2 * padding));
            }

            // 1) 개장/마감 경계선을 먼저 그려서 데이터 선 아래에 깔리게 함
            for (long boundaryMs : findMarketBoundaries(minT, maxT)) {
                float bx = (float) (padding + (boundaryMs - minT) / (maxT - minT) * (w - 2 * padding));
                canvas.drawLine(bx, padding, bx, h - padding, boundaryPaint);
            }

            // 2) 각 구간을 정규장/시간외 여부에 따라 실선·점선으로 구분해서 그림
            //    (공백 구간도 그냥 앞뒤 점끼리 이어짐 - 특별 처리 없음)
            for (int i = 0; i < xs.length - 1; i++) {
                double t1 = history.get(i)[0];
                double t2 = history.get(i + 1)[0];
                boolean regular = isRegularHours((long) t1) && isRegularHours((long) t2);
                Paint p = regular ? regularPaint : afterHoursPaint;
                canvas.drawLine(xs[i], ys[i], xs[i + 1], ys[i + 1], p);
            }
            if (history.size() <= 20) {
                for (int i = 0; i < xs.length; i++) {
                    canvas.drawCircle(xs[i], ys[i], 5f, dotPaint);
                }
            }
        }
    }

    // ===== 매수/보유/매도 비율 막대 =====
    public static class RecommendationBar extends View {
        private int buy, hold, sell;
        private final Paint buyPaint = new Paint();
        private final Paint holdPaint = new Paint();
        private final Paint sellPaint = new Paint();
        private final Paint borderPaint = new Paint();
        private final Paint labelPaintOnDark = new Paint();
        private final Paint labelPaintOnLight = new Paint();

        public RecommendationBar(Context context) {
            super(context);
            init();
        }

        public RecommendationBar(Context context, AttributeSet attrs) {
            super(context, attrs);
            init();
        }

        private void init() {
            buyPaint.setColor(Color.BLACK);
            holdPaint.setColor(Color.GRAY);
            sellPaint.setColor(Color.WHITE);
            borderPaint.setColor(Color.BLACK);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3f);

            labelPaintOnDark.setColor(Color.WHITE);
            labelPaintOnDark.setTextAlign(Paint.Align.CENTER);
            labelPaintOnDark.setAntiAlias(true);
            labelPaintOnDark.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

            labelPaintOnLight.setColor(Color.BLACK);
            labelPaintOnLight.setTextAlign(Paint.Align.CENTER);
            labelPaintOnLight.setAntiAlias(true);
            labelPaintOnLight.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }

        public void setData(int buy, int hold, int sell) {
            this.buy = buy;
            this.hold = hold;
            this.sell = sell;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int total = buy + hold + sell;
            int w = getWidth(), h = getHeight();
            if (total <= 0) {
                canvas.drawRect(0, 0, w, h, borderPaint);
                return;
            }
            float buyW = w * (buy / (float) total);
            float holdW = w * (hold / (float) total);
            float sellW = w - buyW - holdW;

            canvas.drawRect(0, 0, buyW, h, buyPaint);
            canvas.drawRect(buyW, 0, buyW + holdW, h, holdPaint);
            canvas.drawRect(buyW + holdW, 0, w, h, sellPaint);
            canvas.drawRect(0, 0, w, h, borderPaint);

            float textSize = Math.min(h * 0.4f, 26f);
            labelPaintOnDark.setTextSize(textSize);
            labelPaintOnLight.setTextSize(textSize);
            float textY = h / 2f + textSize * 0.35f;

            drawSegmentLabel(canvas, "매수 " + Math.round(buy * 100f / total) + "%", buyW, 0, buyW, textY, labelPaintOnDark);
            drawSegmentLabel(canvas, "보유 " + Math.round(hold * 100f / total) + "%", holdW, buyW, buyW + holdW, textY, labelPaintOnLight);
            drawSegmentLabel(canvas, "매도 " + Math.round(sell * 100f / total) + "%", sellW, buyW + holdW, w, textY, labelPaintOnLight);
        }

        /** 세그먼트가 텍스트를 담기에 너무 좁으면(대략적인 폭 기준) 생략 */
        private void drawSegmentLabel(Canvas canvas, String text, float segmentWidth, float left, float right, float textY, Paint paint) {
            float estimatedTextWidth = paint.measureText(text);
            if (segmentWidth < estimatedTextWidth + 8f) return;
            float centerX = (left + right) / 2f;
            canvas.drawText(text, centerX, textY, paint);
        }
    }
}
