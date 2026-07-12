package com.example.heatmap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * AlarmManager가 10분마다 깨우는 리시버.
 * 화면이 계속 켜져 있는 동안은 MainActivity의 1분 타이머가 갱신을 담당하고,
 * 이 리시버는 "혹시 앱이 죽었을 때"를 대비한 백업용으로 좀 더 뜸하게(10분) 동작함.
 * (예전엔 이것도 1분으로 되어 있어서, 화면 타이머와 매분 겹쳐서 이중으로 조회하며
 * 부하가 2배로 걸리고 있었음 - 발열/다운의 주요 원인 중 하나였음)
 */
public class HourlyUpdateReceiver extends BroadcastReceiver {

    private static final int REQUEST_CODE = 1001;
    private static final long REFRESH_INTERVAL_MS = 10 * 60 * 1000L; // 10분 (백업용, 메인 타이머와 안 겹치게)

    @Override
    public void onReceive(final Context context, Intent intent) {
        final Context appContext = context.getApplicationContext();
        final PendingResult pendingResult = goAsync();

        // 작업이 끝날 때까지 CPU가 중간에 잠들지 않도록 깨워둠
        PowerManager powerManager = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wakeLock = powerManager != null
                ? powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeatmapApp:HourlyUpdate")
                : null;
        if (wakeLock != null) {
            wakeLock.acquire(50 * 1000L);
        }

        final WifiPowerManager wifiPowerManager = new WifiPowerManager(appContext);
        wifiPowerManager.ensureOnThen(new WifiPowerManager.OnWifiReadyListener() {
            @Override
            public void onWifiReady() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            MainActivity.Market market = MarketRefreshWorker.determineMarketByTime();
                            MarketRefreshWorker.refreshStockData(appContext, market);
                            MarketRefreshWorker.MacroSnapshot macro = MarketRefreshWorker.fetchMacroSnapshot();
                            MarketRefreshWorker.renderAndSaveSleepImage(appContext, market, macro);
                        } catch (Exception ignored) {
                        } finally {
                            wifiPowerManager.scheduleOff();
                            // 1분 뒤 다음 갱신도 계속 이어지도록 다시 예약
                            scheduleNextHourlyAlarm(appContext);
                            if (wakeLock != null && wakeLock.isHeld()) {
                                wakeLock.release();
                            }
                            pendingResult.finish();
                        }
                    }
                }).start();
            }
        });
    }

    /** 1분 뒤 이 리시버가 다시 깨어나도록 AlarmManager에 예약 */
    public static void scheduleNextHourlyAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        Intent intent = new Intent(context, HourlyUpdateReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        long triggerAt = System.currentTimeMillis() + REFRESH_INTERVAL_MS;
        // API17은 Doze가 없어서 일반 set()으로도 프로세스가 죽어있어도 정확히 깨어남
        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent);
    }
}
