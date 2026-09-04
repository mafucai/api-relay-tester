package com.mafucai.relayscope;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import java.util.List;

/** Foreground service keeps the user-selected interval instead of WorkManager's 15-minute floor. */
public final class InspectionService extends Service {
    private static final String CHANNEL = "inspection";
    private static final int NOTIFICATION_ID = 71;
    private Thread worker;
    private volatile boolean running;

    @Override public void onCreate() { super.onCreate(); createChannel(); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        float minutes = intent == null ? 30f : intent.getFloatExtra("interval_minutes", 30f);
        if (!(minutes > 0) || Float.isInfinite(minutes)) minutes = 30f;
        startForeground(NOTIFICATION_ID, notification("巡检已开启 · 每 " + minutes + " 分钟"));
        if (worker != null && running) { running = false; worker.interrupt(); }
        startLoop(minutes);
        return START_STICKY;
    }
    private void startLoop(float minutes) {
        running = true; final long delay = Math.max(1000L, (long)(minutes * 60_000L));
        worker = new Thread(() -> { SiteStore store=new SiteStore(this); RelayTester tester=new RelayTester(); while(running){ try { Thread.sleep(delay); if(!running)break; List<RelaySite> sites=store.load(); for(RelaySite site:sites){ if(!running)break; RelayTester.TestResult result=tester.health(site); update("巡检完成 · "+site.name+"："+result.status); } } catch(InterruptedException e){Thread.currentThread().interrupt();break;} catch(Exception ignored){update("巡检异常 · 将在下个周期重试");} } }, "inspection-loop"); worker.start();
    }
    private void update(String text){NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);manager.notify(NOTIFICATION_ID,notification(text));}
    private Notification notification(String text){PendingIntent tap=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);if(Build.VERSION.SDK_INT>=26)return new Notification.Builder(this,CHANNEL).setContentTitle("RelayScope").setContentText(text).setSmallIcon(android.R.drawable.ic_popup_sync).setContentIntent(tap).setOngoing(true).build();return new Notification.Builder(this).setContentTitle("RelayScope").setContentText(text).setSmallIcon(android.R.drawable.ic_popup_sync).setContentIntent(tap).setOngoing(true).build();}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel channel=new NotificationChannel(CHANNEL,"中转站巡检",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(channel);}}
    @Override public void onDestroy(){running=false;if(worker!=null)worker.interrupt();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
