package com.example.heatmap;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;

/**
 * 배터리 절약을 위한 Wi-Fi 전원 관리.
 * - 평소엔 Wi-Fi를 꺼둬서 배터리 소모를 줄이고, 정보를 갱신할 때만 잠깐 켠다.
 * - 안드로이드 4.2.2(API 17)는 앱이 setWifiEnabled()로 Wi-Fi를 직접 켜고 끌 수 있어서
 *   가능한 방식 (최신 안드로이드는 이 기능이 앱에서 막혀 있음).
 * - 화면은 계속 켜둔 채로(기기 자체 슬립화면이 뜨는 걸 막기 위해) Wi-Fi 라디오만 따로 제어함.
 */
public class WifiPowerManager {

    private static final long WIFI_ON_WAIT_MS = 3000; // Wi-Fi가 켜지고 연결될 시간을 잠깐 기다림
    private static final long WIFI_OFF_DELAY_MS = 20000; // 마지막 요청 후 20초 뒤 자동으로 끔 (연속 조작 대비)

    private final Context appContext;
    private final Handler handler = new Handler();
    private Runnable pendingOff;

    public WifiPowerManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface OnWifiReadyListener {
        void onWifiReady();
    }

    /** Wi-Fi를 켜고(이미 켜져 있으면 바로), 연결 준비될 시간을 잠깐 기다린 뒤 콜백 실행 */
    public void ensureOnThen(final OnWifiReadyListener listener) {
        // 예약되어 있던 "끄기"는 취소 (지금 다시 쓰려는 거니까)
        cancelScheduledOff();

        WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
        boolean alreadyOn = false;
        try {
            alreadyOn = wifiManager != null && wifiManager.isWifiEnabled();
            if (wifiManager != null && !alreadyOn) {
                wifiManager.setWifiEnabled(true);
            }
        } catch (Exception ignored) {
            // 일부 기기에서 권한/정책상 실패할 수 있음 - 무시하고 진행 (이미 Wi-Fi가 켜져 있을 수도 있음)
        }

        long waitMs = alreadyOn ? 0 : WIFI_ON_WAIT_MS;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                listener.onWifiReady();
            }
        }, waitMs);
    }

    /** 일정 시간 뒤 Wi-Fi를 자동으로 끄도록 예약 (그 사이 다시 호출되면 예약이 뒤로 미뤄짐) */
    public void scheduleOff() {
        cancelScheduledOff();
        pendingOff = new Runnable() {
            @Override
            public void run() {
                try {
                    WifiManager wifiManager = (WifiManager) appContext.getSystemService(Context.WIFI_SERVICE);
                    if (wifiManager != null && wifiManager.isWifiEnabled()) {
                        wifiManager.setWifiEnabled(false);
                    }
                } catch (Exception ignored) {
                }
            }
        };
        handler.postDelayed(pendingOff, WIFI_OFF_DELAY_MS);
    }

    private void cancelScheduledOff() {
        if (pendingOff != null) {
            handler.removeCallbacks(pendingOff);
            pendingOff = null;
        }
    }
}
