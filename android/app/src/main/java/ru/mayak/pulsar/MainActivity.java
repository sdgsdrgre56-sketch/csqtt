package ru.mayak.pulsar;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_VPN = 1;

    private static final int BG = 0xFF0D1120, CARD = 0xFF161C30, LINE = 0xFF252D48;
    private static final int TEXT = 0xFFEFF2FA, MUTED = 0xFF8C94B0;
    private static final int ACCENT = 0xFF6C8CFF, GREEN = 0xFF2ED47A, AMBER = 0xFFF5A623, RED = 0xFFFF5C6C;

    private SharedPreferences prefs;
    private Orb orb;
    private TextView status, sub, speedDown, speedUp, totals, linkTitle, linkHint, logView, testResult;
    private LinearLayout linkCard, statsCard, logBox;
    private Button pasteBtn, shareBtn, changeBtn, testBtn, logToggle;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean testing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("pulsar", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(build());
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        String text = null;
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) text = i.getData().toString();
        if (Intent.ACTION_SEND.equals(i.getAction())) text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && text.contains("pulsar://")) importLink(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        PulsarService.listener = this::refresh;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        PulsarService.listener = null;
    }

    // ---------------- экран ----------------

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(24));
        scroll.addView(root);

        TextView title = text("Пульсар", 30, TEXT, true);
        root.addView(title);
        TextView tag = text("Свой зашифрованный туннель через облако VK", 14, MUTED, false);
        root.addView(tag, margins(0, dp(2), 0, dp(8)));

        orb = new Orb(this);
        orb.setOnClickListener(v -> toggle());
        FrameLayout orbBox = new FrameLayout(this);
        orbBox.addView(orb, new FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER));
        root.addView(orbBox, new LinearLayout.LayoutParams(-1, dp(270)));

        status = text("Выключено", 22, TEXT, true);
        status.setGravity(Gravity.CENTER);
        root.addView(status);
        sub = text("Нажмите на круг, чтобы включить", 14, MUTED, false);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, margins(0, dp(4), 0, dp(18)));

        // скорость
        statsCard = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        speedDown = text("↓ 0", 20, GREEN, true);
        speedUp = text("↑ 0", 20, ACCENT, true);
        row.addView(speedDown, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(speedUp, new LinearLayout.LayoutParams(0, -2, 1));
        statsCard.addView(row);
        totals = text("", 13, MUTED, false);
        statsCard.addView(totals, margins(0, dp(6), 0, 0));
        testBtn = button("Проверить скорость", false);
        testBtn.setOnClickListener(v -> speedTest());
        statsCard.addView(testBtn, margins(0, dp(12), 0, 0));
        testResult = text("", 14, TEXT, false);
        statsCard.addView(testResult, margins(0, dp(6), 0, 0));
        root.addView(statsCard, margins(0, 0, 0, dp(14)));

        // ссылка
        linkCard = card();
        linkTitle = text("", 17, TEXT, true);
        linkCard.addView(linkTitle);
        linkHint = text("", 14, MUTED, false);
        linkCard.addView(linkHint, margins(0, dp(4), 0, dp(12)));
        pasteBtn = button("Вставить ссылку из буфера", true);
        pasteBtn.setOnClickListener(v -> pasteLink());
        linkCard.addView(pasteBtn);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        shareBtn = button("Поделиться", false);
        shareBtn.setOnClickListener(v -> share());
        changeBtn = button("Сменить", false);
        changeBtn.setOnClickListener(v -> askLink());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(48), 1);
        half.rightMargin = dp(8);
        btns.addView(shareBtn, half);
        btns.addView(changeBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        linkCard.addView(btns, margins(0, dp(8), 0, 0));
        root.addView(linkCard, margins(0, 0, 0, dp(14)));

        // журнал
        logToggle = button("Журнал ▾", false);
        logToggle.setOnClickListener(v -> {
            boolean show = logBox.getVisibility() != View.VISIBLE;
            logBox.setVisibility(show ? View.VISIBLE : View.GONE);
            logToggle.setText(show ? "Журнал ▴" : "Журнал ▾");
            refresh();
        });
        root.addView(logToggle);
        logBox = card();
        logBox.setVisibility(View.GONE);
        logView = text("", 12, MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logBox.addView(logView);
        Button copyLog = button("Скопировать журнал", false);
        copyLog.setOnClickListener(v -> {
            copy(logView.getText().toString());
            toast("Журнал скопирован");
        });
        logBox.addView(copyLog, margins(0, dp(10), 0, 0));
        root.addView(logBox, margins(0, dp(8), 0, 0));

        TextView ver = text("Пульсар " + version(), 12, MUTED, false);
        ver.setGravity(Gravity.CENTER);
        root.addView(ver, margins(0, dp(18), 0, 0));
        return scroll;
    }

    private void refresh() {
        String link = prefs.getString("link", null);
        int st = PulsarService.state;
        orb.setState(st);
        if (link == null) {
            status.setText("Нет подключения");
            sub.setText("Вставьте ссылку от сервера — и можно включать");
            linkTitle.setText("Ссылка подключения");
            linkHint.setText("Её выдаёт сервер при установке (начинается с pulsar://). Скопируйте ссылку и нажмите кнопку ниже.");
            pasteBtn.setVisibility(View.VISIBLE);
            shareBtn.setVisibility(View.GONE);
            changeBtn.setVisibility(View.GONE);
            statsCard.setVisibility(View.GONE);
        } else {
            String name = PulsarService.linkName(link);
            linkTitle.setText(name == null ? "Мой сервер" : name);
            linkHint.setText("Поделитесь ссылкой — у каждого будет своё отдельное подключение.");
            pasteBtn.setVisibility(View.GONE);
            shareBtn.setVisibility(View.VISIBLE);
            changeBtn.setVisibility(View.VISIBLE);
            statsCard.setVisibility(st == PulsarService.OFF ? View.GONE : View.VISIBLE);
            String err = PulsarService.error;
            switch (st) {
                case PulsarService.ON:
                    status.setText("Подключено");
                    sub.setText("Отклик " + String.format(Locale.US, "%.2f", PulsarService.rtt / 1000.0) + " с · соединений " + PulsarService.streams);
                    break;
                case PulsarService.CONNECTING:
                    status.setText("Подключаюсь…");
                    sub.setText(err != null && !err.isEmpty() ? "Облако: " + err : "Обычно 2–5 секунд");
                    break;
                default:
                    status.setText("Выключено");
                    sub.setText(err != null && !err.isEmpty() ? err : "Нажмите на круг, чтобы включить");
            }
        }
        speedDown.setText("↓ " + rate(PulsarService.rxRate));
        speedUp.setText("↑ " + rate(PulsarService.txRate));
        totals.setText("Через облако: получено " + size(PulsarService.rx) + ", отправлено " + size(PulsarService.tx));
        if (logBox.getVisibility() == View.VISIBLE) {
            StringBuilder s = new StringBuilder();
            synchronized (PulsarService.log) {
                int from = Math.max(0, PulsarService.log.size() - 80);
                for (int i = from; i < PulsarService.log.size(); i++) s.append(PulsarService.log.get(i)).append('\n');
            }
            logView.setText(s.length() == 0 ? "Пока пусто" : s);
        }
    }

    // ---------------- действия ----------------

    private void toggle() {
        if (PulsarService.state != PulsarService.OFF) {
            PulsarService.stopVpn(this);
            return;
        }
        String link = prefs.getString("link", null);
        if (link == null) {
            askLink();
            return;
        }
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            startActivityForResult(prep, REQ_VPN);
        } else {
            PulsarService.startVpn(this, link);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VPN) {
            if (res == RESULT_OK) {
                String link = prefs.getString("link", null);
                if (link != null) PulsarService.startVpn(this, link);
            } else {
                toast("Без разрешения на VPN Пульсар не сможет работать");
            }
        }
    }

    private void pasteLink() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        CharSequence t = null;
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        }
        if (t == null || !t.toString().contains("pulsar://")) {
            askLink();
            return;
        }
        importLink(t.toString());
    }

    private void askLink() {
        EditText in = new EditText(this);
        in.setHint("pulsar://…");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        in.setMinLines(3);
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(20), dp(8), dp(20), 0);
        f.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("Ссылка подключения")
                .setMessage("Вставьте ссылку, которую выдал сервер (начинается с pulsar://)")
                .setView(f)
                .setPositiveButton("Сохранить", (d, w) -> importLink(in.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void importLink(String text) {
        String t = text.trim();
        int i = t.indexOf("pulsar://");
        if (i < 0) {
            toast("Это не ссылка Пульсара");
            return;
        }
        String link = t.substring(i).split("\\s")[0];
        try {
            PulsarService.cloudHost(link);
        } catch (Exception e) {
            toast("Ссылка повреждена — скопируйте её целиком");
            return;
        }
        boolean wasOn = PulsarService.state != PulsarService.OFF;
        prefs.edit().putString("link", link).apply();
        toast("Ссылка сохранена");
        if (wasOn) {
            PulsarService.stopVpn(this);
            ui.postDelayed(this::toggle, 800);
        }
        refresh();
    }

    private void share() {
        String link = prefs.getString("link", null);
        if (link == null) return;
        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qr(link, dp(260)));
        qr.setPadding(dp(16), dp(16), dp(16), dp(16));
        new AlertDialog.Builder(this)
                .setTitle("Подключить другой телефон")
                .setMessage("Отсканируйте QR камерой или отправьте ссылку. В ссылке — ключ доступа: давайте только своим.")
                .setView(qr)
                .setPositiveButton("Отправить ссылку", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link);
                    startActivity(Intent.createChooser(s, "Отправить ссылку Пульсара"));
                })
                .setNeutralButton("Копировать", (d, w) -> {
                    copy(link);
                    toast("Ссылка скопирована");
                })
                .show();
    }

    private Bitmap qr(String s, int size) {
        try {
            Map<EncodeHintType, Object> h = new HashMap<>();
            h.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, size, size, h);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < size; x++)
                for (int y = 0; y < size; y++)
                    bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
            return bmp;
        } catch (Exception e) {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }
    }

    /** Замер: скачиваем файл с сервера Cloudflare через туннель (прямо через SOCKS ядра). */
    private void speedTest() {
        if (testing) return;
        if (PulsarService.state != PulsarService.ON) {
            toast("Сначала дождитесь «Подключено»");
            return;
        }
        testing = true;
        testBtn.setEnabled(false);
        testResult.setText("Замеряю… (до 15 секунд)");
        new Thread(() -> {
            String res;
            try {
                Proxy p = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", PulsarService.SOCKS_PORT));
                long t0 = System.nanoTime();
                HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=100000000").openConnection(p);
                c.setConnectTimeout(20000);
                c.setReadTimeout(20000);
                long first = 0, n = 0;
                byte[] buf = new byte[256 << 10];
                try (InputStream in = c.getInputStream()) {
                    int r;
                    while ((r = in.read(buf)) > 0) {
                        if (first == 0) first = System.nanoTime();
                        n += r;
                        if (System.nanoTime() - t0 > 15_000_000_000L) break;
                    }
                }
                c.disconnect();
                double secs = (System.nanoTime() - first) / 1e9;
                double ttfb = (first - t0) / 1e9;
                res = String.format(Locale.US, "Скачано %s за %.1f с — %.1f Мбит/с (ответ сайта через %.1f с)",
                        size(n), secs, n * 8 / secs / 1e6, ttfb);
            } catch (Exception e) {
                res = "Замер не удался: " + e.getMessage();
            }
            String r = res;
            ui.post(() -> {
                testing = false;
                testBtn.setEnabled(true);
                testResult.setText(r);
            });
        }).start();
    }

    // ---------------- мелочи ----------------

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void copy(String s) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Пульсар", s));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private static String rate(long bytesPerSec) {
        double mbit = bytesPerSec * 8 / 1e6;
        if (mbit >= 1) return String.format(Locale.US, "%.1f Мбит/с", mbit);
        return String.format(Locale.US, "%d Кбит/с", (long) (bytesPerSec * 8 / 1e3));
    }

    private static String size(long b) {
        if (b >= 1L << 30) return String.format(Locale.US, "%.2f ГБ", b / (double) (1L << 30));
        if (b >= 1L << 20) return String.format(Locale.US, "%.1f МБ", b / (double) (1L << 20));
        return (b >> 10) + " КБ";
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), LINE);
        c.setBackground(g);
        return c;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.WHITE : TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : 0xFF20283F);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int bt) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(l, t, r, bt);
        return p;
    }

    /** Большой круг-кнопка: серый — выключено, пульсирует жёлтым — подключение, зелёный — работает. */
    static final class Orb extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int state = -1;
        private float phase;
        private final ValueAnimator anim;

        Orb(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
            setClickable(true);
            setContentDescription("Включить или выключить");
        }

        void setState(int s) {
            if (s == state) return;
            state = s;
            if (s == PulsarService.OFF) anim.cancel();
            else if (!anim.isStarted()) anim.start();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            anim.cancel();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            float r = Math.min(w, h) / 2 * 0.78f;
            int col = state == PulsarService.ON ? GREEN : state == PulsarService.CONNECTING ? AMBER : 0xFF3A4366;
            // волны
            if (state != PulsarService.OFF) {
                for (int i = 0; i < 2; i++) {
                    float p = (phase + i * 0.5f) % 1f;
                    ring.setStrokeWidth(r * 0.05f);
                    ring.setColor(col);
                    ring.setAlpha((int) (140 * (1 - p)));
                    c.drawCircle(cx, cy, r * (1 + 0.25f * p), ring);
                }
            }
            fill.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.3f,
                    lighten(col), darken(col), Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, fill);
            label.setTextSize(r * 0.24f);
            String t = state == PulsarService.ON ? "ВКЛ" : state == PulsarService.CONNECTING ? "…" : "ВЫКЛ";
            c.drawText(t, cx, cy + r * 0.09f, label);
        }

        private static int lighten(int c) {
            return Color.rgb(Math.min(255, Color.red(c) + 50), Math.min(255, Color.green(c) + 50), Math.min(255, Color.blue(c) + 50));
        }

        private static int darken(int c) {
            return Color.rgb(Color.red(c) * 6 / 10, Color.green(c) * 6 / 10, Color.blue(c) * 6 / 10);
        }
    }
}
