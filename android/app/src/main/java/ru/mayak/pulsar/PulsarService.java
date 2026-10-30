package ru.mayak.pulsar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * VPN: весь телефон → tun → hev-socks5-tunnel → SOCKS5 ядра «Пульсар» (отдельный процесс
 * libpulsar.so) → облако VK → сервер. Само приложение исключено из VPN, чтобы ядро ходило
 * в облако напрямую.
 */
public class PulsarService extends VpnService {
    static final String ACTION_START = "ru.mayak.pulsar.START";
    static final String ACTION_STOP = "ru.mayak.pulsar.STOP";
    static final String EXTRA_LINK = "link";
    static final int SOCKS_PORT = 10808;
    private static final String CHANNEL = "pulsar";
    private static final int NOTE_ID = 1;

    // ---- состояние для экрана ----
    static final int OFF = 0, CONNECTING = 1, ON = 2;
    static volatile int state = OFF;
    static volatile long rtt, rx, tx, rxRate, txRate;
    static volatile int streams;
    static volatile String error = "";
    static final List<String> log = new ArrayList<>();
    static volatile Runnable listener;

    private static final Handler main = new Handler(Looper.getMainLooper());

    static void log(String s) {
        synchronized (log) {
            log.add(s);
            while (log.size() > 300) log.remove(0);
        }
        notifyUi();
    }

    static void notifyUi() {
        Runnable l = listener;
        if (l != null) main.post(l);
    }

    private ParcelFileDescriptor tun;
    private Process core;
    private volatile boolean running;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = note("Подключаюсь…");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTE_ID, n);
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            stopSelf();
            return START_NOT_STICKY;
        }
        String link = intent == null ? null : intent.getStringExtra(EXTRA_LINK);
        if (link == null) link = getSharedPreferences("pulsar", MODE_PRIVATE).getString("link", null);
        if (link == null || link.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_STICKY;
        final String l = link;
        running = true;
        state = CONNECTING;
        error = "";
        notifyUi();
        worker = new Thread(() -> start(l), "pulsar-start");
        worker.start();
        return START_STICKY;
    }

    private void start(String link) {
        try {
            // 1) адреса облака — заранее (у ядра на Android нет системного DNS)
            String host = cloudHost(link);
            StringBuilder ips = new StringBuilder();
            for (int i = 0; i < 6 && ips.length() == 0 && running; i++) {
                try {
                    for (InetAddress a : InetAddress.getAllByName(host)) {
                        if (a instanceof Inet4Address) {
                            if (ips.length() > 0) ips.append(',');
                            ips.append(a.getHostAddress());
                        }
                    }
                } catch (Exception e) {
                    log("Нет интернета или облако не находится (" + host + "), пробую ещё…");
                    Thread.sleep(2000);
                }
            }
            if (!running) return;
            if (ips.length() == 0) throw new Exception("не удаётся найти облако " + host + " — проверьте интернет");

            // 2) ядро
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libpulsar.so");
            ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(), "client",
                    "--link", link,
                    "--socks", "127.0.0.1:" + SOCKS_PORT,
                    "--resolve", host + "=" + ips,
                    "--json", "--stdin-watch");
            core = pb.start();
            readStdout(core);
            readStderr(core);

            // 3) VPN
            Builder b = new Builder()
                    .setSession("Пульсар")
                    .setMtu(8500)
                    .addAddress("198.18.0.1", 32)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fc00::1", 128)
                    .addRoute("::", 0)
                    .addDnsServer("198.18.0.2");
            try {
                b.addDisallowedApplication(getPackageName());
            } catch (Exception ignored) {
            }
            if (Build.VERSION.SDK_INT >= 29) b.setMetered(false);
            tun = b.establish();
            if (tun == null) throw new Exception("Android не дал включить VPN — разрешите его приложению");

            // 4) tun → SOCKS5
            File conf = new File(getFilesDir(), "tunnel.yml");
            String yml = "tunnel:\n"
                    + "  mtu: 8500\n"
                    + "  ipv4: 198.18.0.1\n"
                    + "  ipv6: 'fc00::1'\n"
                    + "socks5:\n"
                    + "  port: " + SOCKS_PORT + "\n"
                    + "  address: 127.0.0.1\n"
                    + "  udp: 'udp'\n"
                    + "mapdns:\n"
                    + "  address: 198.18.0.2\n"
                    + "  port: 53\n"
                    + "  network: 100.64.0.0\n"
                    + "  netmask: 255.192.0.0\n"
                    + "  cache-size: 10000\n"
                    + "misc:\n"
                    + "  task-stack-size: 81920\n"
                    + "  connect-timeout: 20000\n"
                    + "  tcp-read-write-timeout: 600000\n"
                    + "  log-level: warn\n";
            try (FileOutputStream o = new FileOutputStream(conf)) {
                o.write(yml.getBytes(StandardCharsets.UTF_8));
            }
            if (!TProxyService.TProxyStartService(conf.getAbsolutePath(), tun.getFd())) {
                throw new Exception("не запустился перехват трафика");
            }
            log("VPN включён, жду облако…");
            updateNote("Подключаюсь…");
        } catch (Exception e) {
            error = e.getMessage() == null ? e.toString() : e.getMessage();
            log("Ошибка: " + error);
            stopAll();
            stopSelf();
        }
    }

    static String cloudHost(String link) throws Exception {
        String s = link.trim();
        int i = s.indexOf("pulsar://");
        if (i < 0) throw new Exception("это не ссылка Пульсара");
        s = s.substring(i + 9).split("\\s")[0];
        byte[] j = android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        JSONObject o = new JSONObject(new String(j, StandardCharsets.UTF_8));
        String box = o.getString("b");
        String rest = box.substring(box.lastIndexOf('@') + 1);
        String host = rest.split("/")[0];
        if (host.contains(":")) host = host.substring(0, host.indexOf(':'));
        return host;
    }

    private void readStdout(Process p) {
        new Thread(() -> {
            long lastRx = -1, lastTx = -1, lastT = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject o = new JSONObject(line);
                        long now = System.nanoTime();
                        long nrx = o.optLong("rx"), ntx = o.optLong("tx");
                        if (lastRx >= 0) {
                            double dt = (now - lastT) / 1e9;
                            rxRate = (long) (Math.max(0, nrx - lastRx) / dt);
                            txRate = (long) (Math.max(0, ntx - lastTx) / dt);
                        }
                        lastRx = nrx;
                        lastTx = ntx;
                        lastT = now;
                        rx = nrx;
                        tx = ntx;
                        rtt = o.optLong("rtt");
                        streams = o.optInt("streams");
                        String st = o.optString("state");
                        int ns = "connected".equals(st) ? ON : CONNECTING;
                        if (ns != state) {
                            state = ns;
                            updateNote(ns == ON ? "Подключено" : "Подключаюсь…");
                        }
                        String err = o.optString("err");
                        error = err == null ? "" : err;
                        notifyUi();
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
            if (running) {
                log("Ядро остановилось — перезапускаю");
                main.postDelayed(this::restartCore, 1500);
            }
        }, "pulsar-out").start();
    }

    private void restartCore() {
        if (!running) return;
        String link = getSharedPreferences("pulsar", MODE_PRIVATE).getString("link", null);
        if (link == null) return;
        stopAll();
        Intent i = new Intent(this, PulsarService.class).setAction(ACTION_START).putExtra(EXTRA_LINK, link);
        startService(i);
    }

    private void readStderr(Process p) {
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) log(line);
            } catch (Exception ignored) {
            }
        }, "pulsar-err").start();
    }

    private synchronized void stopAll() {
        running = false;
        try {
            TProxyService.TProxyStopService();
        } catch (Throwable ignored) {
        }
        if (core != null) {
            core.destroy();
            core = null;
        }
        if (tun != null) {
            try {
                tun.close();
            } catch (Exception ignored) {
            }
            tun = null;
        }
        state = OFF;
        rxRate = txRate = 0;
        notifyUi();
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onRevoke() {
        log("VPN выключен системой (включили другой VPN?)");
        stopAll();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    // ---- уведомление ----
    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Пульсар", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
    }

    private Notification note(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, PulsarService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle("Пульсар")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Выключить", stop).build())
                .build();
    }

    private void updateNote(String text) {
        if (!running) return;
        getSystemService(NotificationManager.class).notify(NOTE_ID, note(text));
    }

    static void startVpn(Context c, String link) {
        Intent i = new Intent(c, PulsarService.class).setAction(ACTION_START).putExtra(EXTRA_LINK, link);
        c.startForegroundService(i);
    }

    static void stopVpn(Context c) {
        Intent i = new Intent(c, PulsarService.class).setAction(ACTION_STOP);
        c.startService(i);
    }

    static String linkName(String link) {
        try {
            String s = link.trim();
            s = s.substring(s.indexOf("pulsar://") + 9).split("\\s")[0];
            byte[] j = android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
            JSONObject o = new JSONObject(new String(j, StandardCharsets.UTF_8));
            String n = o.optString("n");
            return n.isEmpty() ? "Мой сервер" : n;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static String uriHost(String u) {
        try {
            return new URI(u).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
