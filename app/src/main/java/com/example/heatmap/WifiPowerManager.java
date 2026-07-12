package com.example.heatmap;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;

/**
 * Wi-Fi 전원 관리.
 * - 안드로이드 4.2.2(API 17)는 앱이 setWifiEnabled()로 Wi-Fi를 직접 켜고 끌 수 있어서
 *   가능한 방식 (최신 안드로이드는 이 기능이 앱에서 막혀 있음).
 * - 1분마다 갱신하는 주기라 매번 껐다 켜면 대기시간(약 15초)이 오히려 낭비라서,
 *   최초 1회만 켜두고 그 이후로는 계속 켜진 채로 유지함 (끄기 기능은 비활성화).
 */
public class WifiPowerManager {

    private static final long WIFI_ON_WAIT_MS = 15000; // 처음 켤 때만 연결될 시간을 기다림

    private final Context appContext;
    private final Handler handler = new Handler();

    public WifiPowerManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public interface OnWifiReadyListener {
        void onWifiReady();
    }

    /** Wi-Fi를 켜고(이미 켜져 있으면 바로), 연결 준비될 시간을 잠깐 기다린 뒤 콜백 실행 */
    public void ensureOnThen(final OnWifiReadyListener listener) {
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

    /** Wi-Fi를 계속 켜둔 채로 유지하기로 해서, 이 메서드는 더 이상 아무 것도 하지 않음 */
    public void scheduleOff() {
        // no-op
    }
}
