package com.example.heatmap;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * AlarmManager가 1분마다 깨우는 리시버.
 * MainActivity(앱 화면)가 실행 중이 아니어도(프로세스가 종료돼 있어도) 안드로이드가
 * 이 리시버를 대신 깨워서 실행시켜주기 때문에, 앱이 죽어도 갱신이 안 끊김.
 * (참고: 화면은 이제 항상 켜둔 채로 운영하므로, 여기서는 데이터만 갱신함)
 */
public class HourlyUpdateReceiver extends BroadcastReceiver {

    private static final int REQUEST_CODE = 1001;
    private static final long REFRESH_INTERVAL_MS = 60 * 1000L; // 1분

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
