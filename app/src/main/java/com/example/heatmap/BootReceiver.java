package com.example.heatmap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 기기가 재부팅되면 AlarmManager 예약이 사라지므로, 부팅 완료 시 다시 예약함 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            HourlyUpdateReceiver.scheduleNextHourlyAlarm(context.getApplicationContext());
        }
    }
}
