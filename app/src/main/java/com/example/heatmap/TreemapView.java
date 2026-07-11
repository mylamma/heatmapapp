package com.example.heatmap;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 화면 전체를 채우는 트리맵(Treemap) 히트맵.
 * - 섹터 영역 크기 = 섹터 내 종목들의 시가총액 합에 비례
 * - 종목 영역 크기 = 각 종목의 시가총액에 비례
 * - squarify 알고리즘으로 사각형을 화면 전체에 빈틈없이 배치 (단순 사칙연산이라 매우 가벼움)
 * - 상승/하락은 색상이 아닌 흰/검 배경의 최대 명암 대비로 표현 (흑백 화면 대응)
 */
public class TreemapView extends View {

    public static class Stock {
        final String symbol;       // API 호출용 심볼/코드 (예: AAPL, 005930)
        final String displayName;  // 화면에 표시할 이름 (지정 안 하면 symbol과 동일)
        final double weight; // 상대적 시가총액 비중(근사치, 정적 값)
        double percentChange = Double.NaN;
        long lastUpdatedMs = 0; // 마지막으로 유효한 값을 받은 시각 (1시간 신선도 판단용)
        final RectF rect = new RectF();

        public Stock(String symbol, double weight) {
            this(symbol, symbol, weight);
        }

        public Stock(String symbol, String displayName, double weight) {
            this.symbol = symbol;
            this.displayName = displayName;
            this.weight = weight;
        }
    }

    public static class Sector {
        final String label;
        final Stock[] stocks;
        final RectF rect = new RectF();

        public Sector(String label, Stock[] stocks) {
            this.label = label;
            this.stocks = stocks;
        }

        double totalWeight() {
            double sum = 0;
            for (Stock s : stocks) sum += s.weight;
            return sum;
        }
    }

    private Sector[] sectors = new Sector[0];

    public interface OnStockTapListener {
        void onStockTap(String symbol, String displayName);
    }

    private OnStockTapListener tapListener;

    public void setOnStockTapListener(OnStockTapListener listener) {
        this.tapListener = listener;
    }

    private final Paint fillPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint headerPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint headerTextPaint = new Paint();

    private static final float HEADER_HEIGHT_DP = 20f;

    public TreemapView(Context context) {
        super(context);
        init();
    }

    public TreemapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setColor(Color.BLACK);
        borderPaint.setStrokeWidth(2f);

        headerPaint.setStyle(Paint.Style.FILL);
        headerPaint.setColor(Color.DKGRAY);

        textPaint.setAntiAlias(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);

        headerTextPaint.setAntiAlias(true);
        headerTextPaint.setTextAlign(Paint.Align.CENTER);
        headerTextPaint.setColor(Color.WHITE);
        headerTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    private boolean flashing = false;
    private int flashColorState = 0; // 0=검정, 1=흰색 번갈아 사용
    private final Handler flashHandler = new Handler();

    public void setSectors(Sector[] sectors) {
        this.sectors = sectors;
        invalidate();
    }

    public void refreshChanges() {
        // e-ink 잔상 완화: 검정->흰색 총 2번만 반전시키되, 간격을 넉넉하게(700ms) 줘서
        // 패널이 완전히 반전될 시간을 확보함. 빠르게 여러 번 반복하는 것보다
        // 느리더라도 확실하게 끝까지 전환되는 편이 잔상 제거에 더 효과적이고
        // 불필요한 반복 횟수를 줄여서 배터리도 덜 씀.
        doFlashSequence(2);
    }

    private void doFlashSequence(final int remainingFlashes) {
        if (remainingFlashes <= 0) {
            flashing = false;
            invalidate();
            return;
        }
        flashing = true;
        flashColorState = remainingFlashes % 2;
        invalidate();
        flashHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                doFlashSequence(remainingFlashes - 1);
            }
        }, 700);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (flashing) {
            canvas.drawColor(flashColorState == 0 ? Color.BLACK : Color.WHITE);
            return;
        }
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || sectors.length == 0) return;

        canvas.drawColor(Color.BLACK); // 셀 사이 여백이 얇은 검정 구분선처럼 보이게 하는 배경

        float headerHeightPx = HEADER_HEIGHT_DP * getResources().getDisplayMetrics().density;

        // 1) 섹터들을 전체 화면에 시가총액 비중으로 배치
        List<Sector> sectorList = new ArrayList<>();
        Collections.addAll(sectorList, sectors);
        Collections.sort(sectorList, new Comparator<Sector>() {
            @Override
            public int compare(Sector a, Sector b) {
                return Double.compare(b.totalWeight(), a.totalWeight());
            }
        });

        List<Double> sectorWeights = new ArrayList<>();
        for (Sector s : sectorList) sectorWeights.add(s.totalWeight());

        List<RectF> sectorRects = squarify(sectorWeights, 0, 0, w, h);
        for (int i = 0; i < sectorList.size(); i++) {
            sectorList.get(i).rect.set(sectorRects.get(i));
        }

        // 2) 섹터 내부에 종목들을 시가총액 비중으로 배치 + 렌더링
        for (Sector sector : sectorList) {
            RectF sr = sector.rect;
            if (sr.width() < 4 || sr.height() < 4) continue;

            float hh = Math.min(headerHeightPx, sr.height() * 0.3f);
            RectF headerRect = new RectF(sr.left, sr.top, sr.right, sr.top + hh);
            RectF bodyRect = new RectF(sr.left, sr.top + hh, sr.right, sr.bottom);

            canvas.drawRect(headerRect, headerPaint);
            headerTextPaint.setTextSize(Math.min(hh * 0.7f, 28f));
            canvas.drawText(sector.label, headerRect.centerX(),
                    headerRect.centerY() + headerTextPaint.getTextSize() / 3f, headerTextPaint);

            List<Stock> stockList = new ArrayList<>();
            Collections.addAll(stockList, sector.stocks);
            Collections.sort(stockList, new Comparator<Stock>() {
                @Override
                public int compare(Stock a, Stock b) {
                    return Double.compare(b.weight, a.weight);
                }
            });

            List<Double> weights = new ArrayList<>();
            for (Stock s : stockList) weights.add(s.weight);

            if (bodyRect.width() < 2 || bodyRect.height() < 2) continue;
            List<RectF> stockRects = squarify(weights, bodyRect.left, bodyRect.top, bodyRect.width(), bodyRect.height());

            for (int i = 0; i < stockList.size(); i++) {
                Stock stock = stockList.get(i);
                stock.rect.set(stockRects.get(i));
                drawStockCell(canvas, stock);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // 화면을 터치할 때도 e-ink 잔상 완화용 플래시 실행
            refreshChanges();
        }
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float x = event.getX(), y = event.getY();
            for (Sector sector : sectors) {
                for (Stock stock : sector.stocks) {
                    if (stock.rect.contains(x, y)) {
                        if (tapListener != null) tapListener.onStockTap(stock.symbol, stock.displayName);
                        return true;
                    }
                }
            }
        }
        return true;
    }

    private static final long FRESHNESS_WINDOW_MS = 60L * 60 * 1000; // 1시간

    private void drawStockCell(Canvas canvas, Stock stock) {
        RectF r = stock.rect;

        boolean isStale = stock.lastUpdatedMs == 0
                || (System.currentTimeMillis() - stock.lastUpdatedMs) > FRESHNESS_WINDOW_MS;
        double effectivePct = isStale ? Double.NaN : stock.percentChange;

        boolean darkBg;
        if (Double.isNaN(effectivePct)) {
            fillPaint.setColor(Color.GRAY);
            darkBg = true;
        } else if (effectivePct >= 0) {
            fillPaint.setColor(Color.WHITE);
            darkBg = false;
        } else {
            fillPaint.setColor(Color.BLACK);
            darkBg = true;
        }
        canvas.drawRect(r, fillPaint);
        canvas.drawRect(r, borderPaint);

        textPaint.setColor(darkBg ? Color.WHITE : Color.BLACK);

        float w = r.width(), h = r.height();
        if (w < 8 || h < 6) return; // 정말 극단적으로 작은 칸만 글자 생략

        String line1 = stock.displayName;
        String line2 = Double.isNaN(effectivePct) ? "--" :
                String.format(Locale.US, "%s%.2f%%", effectivePct >= 0 ? "+" : "", effectivePct);

        float cx = r.centerX();
        float cy = r.centerY();

        if (w >= 20 && h >= 16) {
            // 충분히 큰 칸: 종목명 + 등락률 2줄
            float symbolSize = Math.max(6f, Math.min(w / (line1.length() * 0.75f), h * 0.42f));
            symbolSize = Math.min(symbolSize, 40f);
            float pctSize = symbolSize * 0.8f;

            if (h >= symbolSize + pctSize + 4) {
                textPaint.setTextSize(symbolSize);
                canvas.drawText(line1, cx, cy - pctSize * 0.6f, textPaint);
                textPaint.setTextSize(pctSize);
                canvas.drawText(line2, cx, cy + symbolSize * 0.7f, textPaint);
            } else {
                textPaint.setTextSize(symbolSize);
                canvas.drawText(line1, cx, cy + symbolSize * 0.3f, textPaint);
            }
        } else {
            // 작은 칸: 종목명만이라도 최대한 작은 글씨로 표시 (생략하지 않음)
            float shortSide = Math.min(w, h);
            float tinySize = Math.max(5f, Math.min(shortSide * 0.6f, 11f));
            textPaint.setTextSize(tinySize);
            String shortSymbol = line1.length() > 4 ? line1.substring(0, 4) : line1;
            canvas.drawText(shortSymbol, cx, cy + tinySize * 0.3f, textPaint);
        }
    }

    // ===== squarify 트리맵 알고리즘 (Bruls/Huizing/van Wijk 방식) =====

    private List<RectF> squarify(List<Double> rawWeights, float x, float y, float w, float h) {
        List<RectF> result = new ArrayList<>();
        if (rawWeights.isEmpty() || w <= 0 || h <= 0) return result;

        double total = 0;
        for (double v : rawWeights) total += Math.max(v, 0.0001);
        double scale = (double) w * (double) h / total;

        List<Double> values = new ArrayList<>();
        for (double v : rawWeights) values.add(Math.max(v, 0.0001) * scale);

        float curX = x, curY = y, curW = w, curH = h;
        int start = 0;
        int n = values.size();

        while (start < n && curW > 0.5f && curH > 0.5f) {
            float side = Math.min(curW, curH);
            int end = start + 1;

            while (end < n) {
                double currentWorst = worstRatio(values, start, end, side);
                double extendedWorst = worstRatio(values, start, end + 1, side);
                if (extendedWorst <= currentWorst) {
                    end++;
                } else {
                    break;
                }
            }

            double placedSum = 0;
            for (int i = start; i < end; i++) placedSum += values.get(i);

            if (curW >= curH) {
                float rowWidth = (float) (placedSum / curH);
                if (rowWidth <= 0 || rowWidth > curW) rowWidth = curW;
                float ry = curY;
                for (int i = start; i < end; i++) {
                    float rh = (float) (values.get(i) / rowWidth);
                    result.add(new RectF(curX, ry, curX + rowWidth, ry + rh));
                    ry += rh;
                }
                curX += rowWidth;
                curW -= rowWidth;
            } else {
                float rowHeight = (float) (placedSum / curW);
                if (rowHeight <= 0 || rowHeight > curH) rowHeight = curH;
                float rx = curX;
                for (int i = start; i < end; i++) {
                    float rw = (float) (values.get(i) / rowHeight);
                    result.add(new RectF(rx, curY, rx + rw, curY + rowHeight));
                    rx += rw;
                }
                curY += rowHeight;
                curH -= rowHeight;
            }

            start = end;
        }

        // 부동소수점 오차 등으로 못 채운 나머지가 있으면 마지막 칸에 몰아서 배치 (누락 방지)
        while (result.size() < n) {
            result.add(new RectF(curX, curY, curX + Math.max(curW, 1), curY + Math.max(curH, 1)));
        }

        return result;
    }

    private double worstRatio(List<Double> values, int start, int end, float side) {
        double sum = 0;
        for (int i = start; i < end && i < values.size(); i++) sum += values.get(i);
        if (sum <= 0) return Double.MAX_VALUE;

        double maxRatio = 0;
        for (int i = start; i < end && i < values.size(); i++) {
            double v = values.get(i);
            double ratio = Math.max(
                    (side * (double) side * v) / (sum * sum),
                    (sum * sum) / (side * (double) side * v)
            );
            if (ratio > maxRatio) maxRatio = ratio;
        }
        return maxRatio;
    }
}
