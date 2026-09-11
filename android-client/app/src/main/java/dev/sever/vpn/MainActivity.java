package dev.sever.vpn;

import android.app.*;
import android.content.*;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * VISION VPN Android Neon UI v2.0.
 * Visual layer follows the approved neon/glass product concept while preserving
 * the existing tunnel engines, profile store, routing and handover logic.
 */
public final class MainActivity extends Activity {
    private static final int VPN_CONSENT = 10, IMPORT_FILE = 11;

    private static final int BG = Color.rgb(2, 9, 18);
    private static final int SURFACE = Color.rgb(7, 18, 34);
    private static final int SURFACE_2 = Color.rgb(11, 27, 49);
    private static final int SURFACE_3 = Color.rgb(14, 36, 63);
    private static final int BORDER = Color.rgb(27, 55, 88);
    private static final int BLUE = Color.rgb(35, 170, 255);
    private static final int BLUE_SOFT = Color.rgb(26, 112, 170);
    private static final int MINT = Color.rgb(42, 236, 184);
    private static final int WARNING = Color.rgb(255, 190, 92);
    private static final int MUTED = Color.rgb(145, 169, 194);
    private static final int MUTED_2 = Color.rgb(92, 121, 149);

    private LinearLayout content;
    private ScrollView scroll;
    private TextView headerStatus;
    private TextView downloadMetric;
    private TextView uploadMetric;
    private TextView rttMetric;
    private TextView network;
    private TextView endpointText;
    private Button powerButton;
    private boolean fetching;
    private String page = "home";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean on = VisionState.running || VisionState.connecting;

            if (headerStatus != null) {
                headerStatus.setText(fetching ? "ОБНОВЛЕНИЕ ПРОФИЛЯ" : statusLabel());
                headerStatus.setTextColor(fetching ? WARNING : (on ? MINT : MUTED));
            }

            if (downloadMetric != null) {
                downloadMetric.setText(String.format(
                        Locale.ROOT, "%.1f МБ", VisionState.downloaded.get() / 1048576.0));
            }

            if (uploadMetric != null) {
                uploadMetric.setText(String.format(
                        Locale.ROOT, "%.1f МБ", VisionState.uploaded.get() / 1048576.0));
            }

            if (rttMetric != null) {
                rttMetric.setText(VisionState.lastRttMs >= 0
                        ? VisionState.lastRttMs + " мс"
                        : "—");
            }

            if (network != null) {
                network.setText(networkLabel(VisionState.network));
            }

            if (endpointText != null) {
                endpointText.setText(VisionState.endpoint == null || VisionState.endpoint.isEmpty()
                        ? "Endpoint появится после подключения"
                        : VisionState.endpoint);
            }

            syncPowerButton();
            handler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        renderShell();
    }

    private void renderShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(renderTopBar());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(6), dp(18), dp(28));
        scroll.addView(content);

        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(renderBottomNav());
        setContentView(root);
        renderPage();
    }

    private View renderTopBar() {
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(18), dp(13), dp(16), dp(10));

        TextView mark = text("V", 18, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable logo = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {
                        Color.rgb(23, 215, 255),
                        Color.rgb(41, 107, 255),
                        Color.rgb(116, 61, 255)
                });
        logo.setCornerRadius(dp(14));
        mark.setBackground(logo);
        mark.setElevation(dp(8));
        top.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(11), 0, 0, 0);
        brand.addView(text("VISION VPN", 19, Color.WHITE, true));
        TextView sub = text("Без границ. Всегда с вами.", 10, MUTED, false);
        brand.addView(sub);
        top.addView(brand);

        Space spacer = new Space(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView secure = text(
                VisionState.running ? "● ЗАЩИЩЕНО" : "● ГОТОВ",
                9,
                VisionState.running ? MINT : BLUE,
                true);
        secure.setGravity(Gravity.CENTER);
        secure.setPadding(dp(10), dp(6), dp(10), dp(6));
        secure.setBackground(roundStroke(
                VisionState.running ? Color.rgb(4, 45, 40) : Color.rgb(8, 31, 55),
                VisionState.running ? Color.rgb(17, 105, 86) : Color.rgb(18, 74, 118),
                999, 1));
        top.addView(secure);

        return top;
    }

    private View renderBottomNav() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(14), dp(7), dp(14), dp(13));
        outer.setBackgroundColor(BG);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(dp(5), dp(5), dp(5), dp(5));
        nav.setBackground(roundStroke(Color.rgb(6, 18, 33), Color.rgb(24, 53, 84), 24, 1));
        nav.setElevation(dp(8));

        addNav(nav, "⌂", "Главная", "home");
        addNav(nav, "◉", "Профили", "profiles");
        addNav(nav, "⇄", "Маршруты", "routes");
        addNav(nav, "⚙", "Настройки", "settings");

        outer.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return outer;
    }

    private void addNav(LinearLayout nav, String icon, String label, String target) {
        boolean active = target.equals(page);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(3), dp(5), dp(3), dp(4));
        item.setClickable(true);
        item.setFocusable(true);

        if (active) {
            GradientDrawable selected = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[] { Color.rgb(10, 58, 91), Color.rgb(13, 40, 75) });
            selected.setCornerRadius(dp(18));
            selected.setStroke(dp(1), Color.rgb(23, 107, 161));
            item.setBackground(selected);
        } else {
            item.setBackground(round(Color.TRANSPARENT, 18));
        }

        TextView iconView = text(icon, 20, active ? BLUE : MUTED_2, true);
        iconView.setGravity(Gravity.CENTER);
        item.addView(iconView);

        TextView labelView = text(label, 9, active ? Color.WHITE : MUTED, active);
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView);

        item.setOnClickListener(v -> {
            if (target.equals(page)) return;
            page = target;
            renderShell();
        });

        nav.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1f));
    }

    private void renderPage() {
        content.removeAllViews();
        headerStatus = null;
        downloadMetric = null;
        uploadMetric = null;
        rttMetric = null;
        network = null;
        endpointText = null;
        powerButton = null;

        switch (page) {
            case "profiles" -> renderProfiles();
            case "routes" -> renderRoutes();
            case "settings" -> renderSettings();
            default -> renderHome();
        }
    }

    private void renderHome() {
        boolean connected = VisionState.running || VisionState.connecting;

        String profileName = "Профиль не выбран";
        String protocol = "Добавьте профиль";
        try {
            UnifiedProfile p = ProfileStore.active(this);
            profileName = p.name;
            protocol = ProtocolDetector.displayName(p.protocol);
        } catch (Exception ignored) {}

        LinearLayout hero = cardGradient();
        hero.setPadding(dp(18), dp(16), dp(18), dp(18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout stateText = new LinearLayout(this);
        stateText.setOrientation(LinearLayout.VERTICAL);
        stateText.addView(text("СОЕДИНЕНИЕ", 9, MUTED_2, true));
        TextView title = text(connected ? "Соединение защищено" : "Готов к подключению",
                22, Color.WHITE, true);
        title.setPadding(0, dp(2), 0, 0);
        stateText.addView(title);
        stateText.addView(text(profileName + " • " + protocol, 12, MUTED, false));
        top.addView(stateText, new LinearLayout.LayoutParams(0, -2, 1f));

        rttMetric = text(VisionState.lastRttMs >= 0 ? VisionState.lastRttMs + " мс" : "—",
                11, BLUE, true);
        rttMetric.setGravity(Gravity.CENTER);
        rttMetric.setPadding(dp(11), dp(6), dp(11), dp(6));
        rttMetric.setBackground(round(Color.rgb(8, 43, 70), 999));
        top.addView(rttMetric);
        hero.addView(top);

        FrameLayout powerArea = new FrameLayout(this);
        powerArea.setPadding(0, dp(12), 0, dp(6));

        View ring3 = new View(this);
        ring3.setBackground(circleStroke(Color.rgb(17, 67, 113), 2));
        FrameLayout.LayoutParams r3 = new FrameLayout.LayoutParams(dp(174), dp(174), Gravity.CENTER);
        powerArea.addView(ring3, r3);

        View ring2 = new View(this);
        ring2.setBackground(circleStroke(Color.rgb(19, 116, 176), 2));
        ring2.setAlpha(.72f);
        FrameLayout.LayoutParams r2 = new FrameLayout.LayoutParams(dp(148), dp(148), Gravity.CENTER);
        powerArea.addView(ring2, r2);

        View glow = new View(this);
        glow.setBackground(circleStroke(
                connected ? Color.rgb(33, 239, 194) : Color.rgb(46, 174, 255), 4));
        glow.setAlpha(.48f);
        glow.setElevation(dp(10));
        FrameLayout.LayoutParams rg = new FrameLayout.LayoutParams(dp(126), dp(126), Gravity.CENTER);
        powerArea.addView(glow, rg);

        powerButton = new Button(this);
        powerButton.setAllCaps(false);
        powerButton.setText("⏻");
        powerButton.setTextSize(40);
        powerButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        powerButton.setGravity(Gravity.CENTER);
        powerButton.setMinWidth(0);
        powerButton.setMinHeight(0);
        powerButton.setPadding(0, 0, 0, dp(3));
        powerButton.setOnClickListener(v -> toggleVpn());
        powerButton.setElevation(dp(14));
        FrameLayout.LayoutParams pb = new FrameLayout.LayoutParams(dp(106), dp(106), Gravity.CENTER);
        powerArea.addView(powerButton, pb);

        hero.addView(powerArea, new LinearLayout.LayoutParams(-1, dp(190)));

        headerStatus = text(
                VisionState.connecting ? "ПОДКЛЮЧЕНИЕ…" : (VisionState.running ? "ПОДКЛЮЧЕНО" : "НЕ ПОДКЛЮЧЕНО"),
                12,
                connected ? MINT : MUTED,
                true);
        headerStatus.setGravity(Gravity.CENTER);
        headerStatus.setLetterSpacing(.08f);
        hero.addView(headerStatus);

        TextView hint = text(
                connected ? "Нажмите на кнопку, чтобы отключиться" : "Одно нажатие — и соединение защищено",
                11, MUTED_2, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(2), 0, 0);
        hero.addView(hint);

        content.addView(hero, cardMargin(6));
        syncPowerButton();

        LinearLayout server = card();
        server.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout serverRow = new LinearLayout(this);
        serverRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView flag = text("◈", 22, BLUE, true);
        flag.setGravity(Gravity.CENTER);
        flag.setBackground(round(Color.rgb(9, 42, 72), 14));
        serverRow.addView(flag, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(profileName, 15, Color.WHITE, true));
        labels.addView(text(protocol + " • " + networkLabel(VisionState.network), 11, MUTED, false));
        serverRow.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView ping = text(VisionState.lastRttMs >= 0 ? "⌁ " + VisionState.lastRttMs + " мс" : "AUTO",
                10, MINT, true);
        serverRow.addView(ping);
        serverRow.addView(text("  ›", 20, MUTED_2, false));

        server.setOnClickListener(v -> {
            page = "profiles";
            renderShell();
        });
        server.setClickable(true);
        server.addView(serverRow);
        content.addView(server, cardMargin(10));

        LinearLayout traffic = new LinearLayout(this);
        traffic.setOrientation(LinearLayout.HORIZONTAL);
        traffic.setWeightSum(2f);

        downloadMetric = text(String.format(Locale.ROOT, "%.1f МБ",
                VisionState.downloaded.get() / 1048576.0), 19, Color.WHITE, true);
        uploadMetric = text(String.format(Locale.ROOT, "%.1f МБ",
                VisionState.uploaded.get() / 1048576.0), 19, Color.WHITE, true);

        traffic.addView(metricCard("↓", "СКАЧАНО", downloadMetric, Color.rgb(35, 213, 255)),
                weightCardParams(1f, 0, 5));
        traffic.addView(metricCard("↑", "ОТПРАВЛЕНО", uploadMetric, Color.rgb(185, 71, 255)),
                weightCardParams(1f, 5, 0));
        content.addView(traffic, cardMargin(10));

        LinearLayout details = card();
        details.setPadding(dp(15), dp(13), dp(15), dp(13));
        details.addView(detailRow("Протокол", protocol, BLUE));

        endpointText = text(
                VisionState.endpoint == null || VisionState.endpoint.isEmpty()
                        ? "После подключения" : VisionState.endpoint,
                12, Color.WHITE, true);
        details.addView(detailRow("Endpoint", endpointText.getText().toString(), MUTED));

        network = text(networkLabel(VisionState.network), 12, Color.WHITE, true);
        details.addView(detailRow("Сеть", network.getText().toString(), MINT));
        details.addView(detailRow("Защита", connected ? "Активна" : "Готова", MINT));

        content.addView(details, cardMargin(10));

        LinearLayout access = featureCard(
                "МАРШРУТИЗАЦИЯ",
                "Доступ ко всем сервисам",
                routingSubtitle(),
                "›",
                MINT);
        access.setOnClickListener(v -> {
            page = "routes";
            renderShell();
        });
        content.addView(access, cardMargin(10));
    }

    private void renderProfiles() {
        screenHeader("Профили", "Быстрое переключение между подключениями");

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);

        EditText search = darkInput("Поиск профилей");
        search.setEnabled(false);
        search.setAlpha(.78f);
        toolbar.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1f));

        TextView plus = text("+", 24, Color.WHITE, true);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(roundStroke(Color.rgb(10, 39, 65), Color.rgb(30, 82, 126), 15, 1));
        plus.setOnClickListener(v -> showUrlImport());
        toolbar.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(46)));
        ((LinearLayout.LayoutParams) plus.getLayoutParams()).setMargins(dp(8), 0, 0, 0);

        content.addView(toolbar, cardMargin(8));

        try {
            UnifiedProfile active = ProfileStore.active(this);
            List<UnifiedProfile> all = ProfileStore.all(this);

            if (all.isEmpty()) {
                content.addView(emptyState(
                        "Профилей пока нет",
                        "Добавьте конфигурацию по QR-коду, ссылке или файлу."),
                        cardMargin(10));
            }

            for (UnifiedProfile p : all) {
                boolean selected = p.id.equals(active.id);
                LinearLayout c = profileCard(p, selected);
                c.setClickable(true);
                c.setOnClickListener(v -> {
                    if (!ensureDisconnected()) return;
                    try {
                        ProfileStore.setActive(this, p.id);
                        renderPage();
                    } catch (Exception e) {
                        message(e.getMessage());
                    }
                });
                c.setOnLongClickListener(v -> {
                    if (!ensureDisconnected()) return true;
                    new AlertDialog.Builder(this)
                            .setTitle("Удалить профиль?")
                            .setMessage(p.name)
                            .setPositiveButton("Удалить", (d, w) -> {
                                try {
                                    ProfileStore.delete(this, p.id);
                                    renderPage();
                                } catch (Exception e) {
                                    message(e.getMessage());
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                    return true;
                });
                content.addView(c, cardMargin(9));
            }
        } catch (Exception ignored) {
            content.addView(emptyState(
                    "Профилей пока нет",
                    "Добавьте конфигурацию по QR-коду, ссылке или файлу."),
                    cardMargin(10));
        }

        sectionLabel("ДОБАВИТЬ");

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setWeightSum(3f);

        quick.addView(quickAction("+", "Ссылка", "URL", this::showUrlImport, BLUE),
                weightCardParams(1f, 0, 4));
        quick.addView(quickAction("▦", "QR-код", "Камера", this::scanQr, MINT),
                weightCardParams(1f, 4, 4));
        quick.addView(quickAction("⇩", "Файл", "Импорт", () -> {
            if (ensureDisconnected()) {
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(open, IMPORT_FILE);
            }
        }, Color.rgb(158, 92, 255)), weightCardParams(1f, 4, 0));

        content.addView(quick, cardMargin(8));

        action("⌘", "Вставить конфигурацию",
                "Полный конфиг, URI или текст профиля", this::showPaste);
        action("↻", "Обновить профиль",
                "Получить свежий endpoint и политики", this::updateSubscription);

        sectionLabel("ДВИЖКИ");
        content.addView(engineCard("VISION Secure", "WSS / TLS", true, BLUE), cardMargin(8));
        content.addView(engineCard("WireGuard", "Нативный userspace backend", true, MINT), cardMargin(8));
        content.addView(engineCard("AmneziaWG", "Встроенный AWG backend", true, Color.rgb(95, 142, 255)), cardMargin(8));
        content.addView(engineCard("OpenVPN / Xray", "Engine Pack", false, MUTED), cardMargin(8));
    }

    private void renderRoutes() {
        screenHeader("Маршруты", "Какие приложения и адреса идут через VPN");

        sectionLabel("ПРИЛОЖЕНИЯ");

        LinearLayout appsCard = card();
        appsCard.setPadding(dp(12), dp(10), dp(12), dp(10));

        RadioGroup apps = optionGroup();
        String mode = RoutingPreferences.mode(this);

        RadioButton all = radio("Все приложения по правилам VPN",
                RoutingPreferences.MODE_ALL.equals(mode));
        RadioButton only = radio("Только выбранные через VPN",
                RoutingPreferences.MODE_ONLY.equals(mode));
        RadioButton bypass = radio("Выбранные приложения без VPN",
                RoutingPreferences.MODE_BYPASS.equals(mode));

        apps.addView(all);
        apps.addView(only);
        apps.addView(bypass);

        all.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_ALL));
        only.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_ONLY));
        bypass.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_BYPASS));

        appsCard.addView(apps);
        content.addView(appsCard, cardMargin(8));

        action("◉", "Приложения",
                RoutingPreferences.packages(this).size() + " выбрано", this::chooseApps);

        sectionLabel("МАРШРУТИЗАЦИЯ");

        LinearLayout routeMode = card();
        routeMode.setPadding(dp(12), dp(10), dp(12), dp(10));
        RadioGroup routes = optionGroup();
        String rm = RoutingPreferences.routeMode(this);

        RadioButton rall = radio("Весь трафик через VPN, кроме исключений",
                RoutingPreferences.ROUTE_ALL.equals(rm));
        RadioButton ronly = radio("Через VPN только указанные правила",
                RoutingPreferences.ROUTE_ONLY.equals(rm));

        routes.addView(rall);
        routes.addView(ronly);

        rall.setOnClickListener(v ->
                RoutingPreferences.setRouteMode(this, RoutingPreferences.ROUTE_ALL));
        ronly.setOnClickListener(v ->
                RoutingPreferences.setRouteMode(this, RoutingPreferences.ROUTE_ONLY));

        routeMode.addView(routes);

        CheckBox local = new CheckBox(this);
        local.setText("Локальные сети идут напрямую");
        local.setTextColor(MUTED);
        local.setTextSize(12);
        local.setChecked(RoutingPreferences.allowLocalNetwork(this));
        local.setOnCheckedChangeListener((b, v) ->
                RoutingPreferences.setAllowLocalNetwork(this, v));
        routeMode.addView(local);

        content.addView(routeMode, cardMargin(8));

        action("↗", "IP/CIDR через VPN", summary(RoutingPreferences.vpnCidrs(this)),
                () -> editRules("IP/CIDR через VPN",
                        RoutingPreferences.vpnCidrs(this), true,
                        v -> RoutingPreferences.setVpnCidrs(this, v)));

        action("↘", "IP/CIDR без VPN", summary(RoutingPreferences.bypassCidrs(this)),
                () -> editRules("IP/CIDR без VPN",
                        RoutingPreferences.bypassCidrs(this), true,
                        v -> RoutingPreferences.setBypassCidrs(this, v)));

        action("◇", "Домены через VPN", summary(RoutingPreferences.vpnDomains(this)),
                () -> editRules("Домены через VPN",
                        RoutingPreferences.vpnDomains(this), false,
                        v -> RoutingPreferences.setVpnDomains(this, v)));

        action("○", "Домены без VPN", summary(RoutingPreferences.bypassDomains(this)),
                () -> editRules("Домены без VPN",
                        RoutingPreferences.bypassDomains(this), false,
                        v -> RoutingPreferences.setBypassDomains(this, v)));

        content.addView(callout(
                "SMART ROUTING",
                "Правила применяются при подключении и пересчитываются после смены сети.",
                MINT), cardMargin(10));
    }

    private void renderSettings() {
        screenHeader("Настройки", "Безопасность, подключение и внешний вид");

        sectionLabel("СОЕДИНЕНИЕ");
        action("∞", "Always-on VPN",
                "Системный режим Android", this::openVpnSettings);
        action("⊘", "Блокировать без VPN",
                "Lockdown mode", this::openVpnSettings);

        content.addView(settingsCard(
                "Автоматическое переподключение",
                "Wi‑Fi ↔ LTE/5G handover активен",
                MINT), cardMargin(8));

        content.addView(settingsCard(
                "Выбор протокола",
                "Активный профиль определяет VPN-движок",
                BLUE), cardMargin(8));

        sectionLabel("БЕЗОПАСНОСТЬ");
        content.addView(settingsCard(
                "TLS 1.3 + Android Keystore",
                "Профили защищены AES-GCM",
                MINT), cardMargin(8));

        content.addView(settingsCard(
                "VISION Control",
                "Статус, RTT и сетевые события",
                Color.rgb(98, 116, 255)), cardMargin(8));

        sectionLabel("ДИАГНОСТИКА");
        content.addView(settingsCard(
                "Состояние соединения",
                VisionState.running ? "VPN активен" : "Ошибок подключения сейчас нет",
                VisionState.running ? MINT : BLUE), cardMargin(8));

        sectionLabel("О ПРИЛОЖЕНИИ");
        LinearLayout version = infoCard(
                "VERSION",
                "VISION VPN — Neon UI",
                "Unified Profiles • WG/AWG • Routes • Handover");
        content.addView(version, cardMargin(8));
    }

    private void screenHeader(String title, String subtitle) {
        TextView h = text(title, 27, Color.WHITE, true);
        h.setPadding(dp(1), dp(4), 0, 0);
        content.addView(h);

        TextView s = text(subtitle, 12, MUTED, false);
        s.setPadding(dp(1), dp(1), 0, dp(2));
        content.addView(s);
    }

    private void sectionLabel(String value) {
        TextView label = text(value, 10, MUTED_2, true);
        label.setLetterSpacing(.13f);
        label.setPadding(dp(2), dp(20), 0, dp(2));
        content.addView(label);
    }

    private LinearLayout profileCard(UnifiedProfile p, boolean selected) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                selected
                        ? new int[] { Color.rgb(10, 45, 75), Color.rgb(16, 34, 65) }
                        : new int[] { Color.rgb(7, 19, 35), Color.rgb(9, 23, 42) });
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1),
                selected ? Color.rgb(38, 195, 242) : Color.rgb(25, 52, 82));
        c.setBackground(bg);
        c.setElevation(selected ? dp(5) : dp(1));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text(selected ? "◆" : "◇", 22,
                selected ? BLUE : MUTED_2, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(
                selected ? Color.rgb(9, 55, 84) : Color.rgb(11, 31, 51), 14));
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(p.name, 15, Color.WHITE, true));
        labels.addView(text(ProtocolDetector.displayName(p.protocol),
                11, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        if (selected) {
            TextView active = text("АКТИВЕН", 9, MINT, true);
            active.setPadding(dp(9), dp(5), dp(9), dp(5));
            active.setBackground(round(Color.rgb(5, 48, 41), 999));
            row.addView(active);
        } else {
            row.addView(text("›", 22, MUTED_2, false));
        }

        c.addView(row);
        return c;
    }

    private LinearLayout quickAction(
            String icon, String title, String subtitle, Runnable click, int accent) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(8), dp(11), dp(8), dp(10));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnClickListener(v -> click.run());

        TextView iconView = text(icon, 20, accent, true);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(round(Color.rgb(10, 34, 57), 13));
        c.addView(iconView, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView t = text(title, 12, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);

        TextView s = text(subtitle, 9, MUTED_2, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        return c;
    }

    private LinearLayout engineCard(
            String name, String detail, boolean ready, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text("●", 12, ready ? accent : MUTED_2, true);
        row.addView(icon);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(name, 14, Color.WHITE, true));
        labels.addView(text(detail, 10, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = text(ready ? "ГОТОВ" : "PACK", 9,
                ready ? MINT : MUTED, true);
        state.setPadding(dp(8), dp(5), dp(8), dp(5));
        state.setBackground(round(
                ready ? Color.rgb(4, 47, 40) : Color.rgb(18, 31, 47), 999));
        row.addView(state);

        c.addView(row);
        return c;
    }

    private LinearLayout settingsCard(String title, String detail, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(13), dp(14), dp(13));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text("◉", 15, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(10, 32, 53), 12));
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11), 0, 0, 0);
        labels.addView(text(title, 14, Color.WHITE, true));
        labels.addView(text(detail, 10, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = text("●", 12, accent, true);
        row.addView(state);

        c.addView(row);
        return c;
    }

    private LinearLayout callout(String title, String detail, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(7, 25, 43), Color.rgb(9, 35, 54) });
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), Color.rgb(21, 69, 92));
        c.setBackground(g);

        c.addView(text(title, 9, accent, true));
        TextView d = text(detail, 11, MUTED, false);
        d.setPadding(0, dp(4), 0, 0);
        c.addView(d);
        return c;
    }

    private LinearLayout emptyState(String title, String detail) {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(20), dp(22), dp(20), dp(22));
        TextView icon = text("＋", 28, BLUE, true);
        c.addView(icon);
        c.addView(text(title, 17, Color.WHITE, true));
        TextView d = text(detail, 12, MUTED, false);
        d.setGravity(Gravity.CENTER);
        d.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        c.addView(d);
        return c;
    }

    private LinearLayout featureCard(
            String eyebrow, String title, String subtitle, String end, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setClickable(true);
        c.setFocusable(true);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = text("◉", 15, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(Color.rgb(10, 34, 56), 13));
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11), 0, 0, 0);
        labels.addView(text(eyebrow.toUpperCase(Locale.ROOT), 8, MUTED_2, true));
        labels.addView(text(title, 14, Color.WHITE, true));
        labels.addView(text(subtitle, 10, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(text(end, 21, MUTED_2, false));
        c.addView(row);
        return c;
    }

    private LinearLayout metricCard(
            String icon, String label, TextView value, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(13), dp(12), dp(13), dp(11));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(icon, 14, accent, true));
        TextView l = text(label, 9, MUTED_2, true);
        l.setPadding(dp(6), 0, 0, 0);
        top.addView(l);
        c.addView(top);

        value.setPadding(0, dp(4), 0, 0);
        c.addView(value);

        TextView wave = text("⌁⌁⌁⌁", 12, accent, false);
        wave.setAlpha(.72f);
        c.addView(wave);
        return c;
    }

    private RadioGroup optionGroup() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(5), dp(4), dp(5), dp(4));
        group.setBackgroundColor(Color.TRANSPARENT);
        return group;
    }

    private void action(
            String icon, String title, String subtitle, Runnable click) {
        LinearLayout c = card();
        c.setPadding(dp(13), dp(11), dp(13), dp(11));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnClickListener(v -> click.run());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconView = text(icon, 18, BLUE, true);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(round(Color.rgb(10, 38, 63), 12));
        row.addView(iconView, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(11), 0, 0, 0);
        labels.addView(text(title, 14, Color.WHITE, true));
        labels.addView(text(subtitle, 10, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(text("›", 21, MUTED_2, false));
        c.addView(row);
        content.addView(c, cardMargin(8));
    }

    private GradientDrawable circleStroke(int stroke, int width) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setShape(GradientDrawable.OVAL);
        d.setStroke(dp(width), stroke);
        return d;
    }

    private LinearLayout detailRow(String label, String value, int accent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView l = text(label, 11, MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView v = text(value, 11, accent == MUTED ? Color.WHITE : accent, true);
        row.addView(v);
        return row;
    }
    private void openVpnSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));
        } catch (Exception e) {
            message("Откройте Настройки → VPN вручную");
        }
    }

    private void toggleVpn() {
        if (VisionState.running || VisionState.connecting) {
            VisionTunnelController.disconnect(this);
            return;
        }

        try {
            ProfileStore.active(this);
            Intent consent = VpnService.prepare(this);
            if (consent != null) startActivityForResult(consent, VPN_CONSENT);
            else startVPN();
        } catch (Exception e) {
            message("Сначала добавьте профиль VPN");
        }
    }

    private boolean ensureDisconnected() {
        if (VisionState.running || VisionState.connecting) {
            message("Сначала отключите VPN");
            return false;
        }
        return true;
    }

    private void showUrlImport() {
        if (!ensureDisconnected()) return;
        EditText input = darkInput("https://panel.example.com/s/…json");
        new AlertDialog.Builder(this)
                .setTitle("Добавить профиль по ссылке")
                .setView(input)
                .setPositiveButton("Загрузить", (d, w) ->
                        fetchProfile(input.getText().toString().trim(), false))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showPaste() {
        if (!ensureDisconnected()) return;
        EditText input = darkInput("Вставьте конфигурацию");
        input.setMinLines(8);
        new AlertDialog.Builder(this)
                .setTitle("Импорт конфигурации")
                .setView(input)
                .setPositiveButton("Импортировать", (d, w) ->
                        importAny(input.getText().toString()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void scanQr() {
        if (!ensureDisconnected()) return;
        IntentIntegrator i = new IntentIntegrator(this);
        i.setPrompt("Наведите камеру на QR-код");
        i.setBeepEnabled(false);
        i.setOrientationLocked(false);
        i.initiateScan();
    }

    private void updateSubscription() {
        if (!ensureDisconnected()) return;
        try {
            UnifiedProfile u = ProfileStore.active(this);
            if (!ProtocolDetector.VISION.equals(u.protocol)) {
                message("Подписка панели доступна только для VISION-профиля");
                return;
            }
            Profile p = new Profile(u.raw);
            if (p.subscriptionUrl == null) {
                message("В профиле нет ссылки обновления");
                return;
            }
            fetchProfile(p.subscriptionUrl, true);
        } catch (Exception e) {
            message(e.getMessage());
        }
    }

    private void importAny(String raw) {
        if (!ensureDisconnected()) return;
        String v = raw == null ? "" : raw.trim();

        if (v.isEmpty()) {
            message("Пустая конфигурация");
            return;
        }

        if (v.startsWith("https://") || v.startsWith("http://")) {
            fetchProfile(v, false);
            return;
        }

        try {
            UnifiedProfile p = ProfileStore.add(this, v);
            renderPage();
            message("Добавлен профиль: " + p.name + "\n" +
                    ProtocolDetector.displayName(p.protocol));
        } catch (Exception e) {
            message(e.getMessage());
        }
    }

    private void fetchProfile(String url, boolean replace) {
        if (!ensureDisconnected() || fetching) return;
        fetching = true;

        new Thread(() -> {
            try {
                String raw = Subscription.fetch(url, socket -> true);
                runOnUiThread(() -> {
                    fetching = false;
                    try {
                        if (replace) {
                            UnifiedProfile active = ProfileStore.active(this);
                            ProfileStore.replaceRaw(this, active.id, raw);
                            message("Профиль обновлён");
                        } else {
                            UnifiedProfile p = ProfileStore.add(this, raw);
                            message("Добавлен профиль: " + p.name);
                        }
                        renderPage();
                    } catch (Exception e) {
                        message(e.getMessage());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fetching = false;
                    message("Не удалось получить профиль: " + e.getMessage());
                });
            }
        }, "vision-profile").start();
    }

    private void chooseApps() {
        if (!ensureDisconnected()) return;

        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<>();

        for (ApplicationInfo app : pm.getInstalledApplications(0)) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null
                    && !app.packageName.equals(getPackageName())) {
                apps.add(app);
            }
        }

        apps.sort(Comparator.comparing(
                a -> pm.getApplicationLabel(a).toString().toLowerCase(Locale.ROOT)));

        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        Set<String> chosen = RoutingPreferences.packages(this);

        for (int i = 0; i < apps.size(); i++) {
            labels[i] = pm.getApplicationLabel(apps.get(i)).toString();
            checked[i] = chosen.contains(apps.get(i).packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Приложения")
                .setMultiChoiceItems(labels, checked,
                        (d, which, value) -> checked[which] = value)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Set<String> result = new LinkedHashSet<>();
                    for (int i = 0; i < apps.size(); i++) {
                        if (checked[i]) result.add(apps.get(i).packageName);
                    }
                    RoutingPreferences.setPackages(this, result);
                    renderPage();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private interface RuleSaver {
        void save(Set<String> values);
    }

    private void editRules(
            String title, Set<String> current, boolean cidr, RuleSaver saver) {
        if (!ensureDisconnected()) return;

        EditText input = darkInput("по одному значению на строку");
        input.setMinLines(8);
        input.setText(String.join("\n", current));

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        Set<String> values =
                                parseLines(input.getText().toString(), cidr);
                        saver.save(values);
                        renderPage();
                    } catch (Exception e) {
                        message(e.getMessage());
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private Set<String> parseLines(String raw, boolean cidr) throws Exception {
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String item : raw.split("[,\\n]")) {
            String v = item.trim();
            if (v.isEmpty()) continue;

            if (cidr) {
                RoutePlanner.Cidr.parse(v);
            } else {
                v = v.toLowerCase(Locale.ROOT);
                if (!v.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) {
                    throw new Exception("Некорректный домен: " + v);
                }
            }

            result.add(v);
            if (result.size() > 256) {
                throw new Exception("Не более 256 правил в списке");
            }
        }

        return result;
    }

    private String summary(Set<String> values) {
        if (values.isEmpty()) return "Правила не заданы";
        return values.size() + " правил";
    }

    private String routingTitle() {
        String mode = RoutingPreferences.mode(this);
        if (RoutingPreferences.MODE_ONLY.equals(mode))
            return "Только выбранные приложения";
        if (RoutingPreferences.MODE_BYPASS.equals(mode))
            return "Исключения из VPN";
        return "Все приложения";
    }

    private String routingSubtitle() {
        return RoutingPreferences.packages(this).size() + " приложений • " +
                (RoutingPreferences.ROUTE_ONLY.equals(
                        RoutingPreferences.routeMode(this))
                        ? "только заданные маршруты"
                        : "full tunnel");
    }

    private LinearLayout infoCard(
            String eyebrow, String title, String subtitle) {
        LinearLayout c = card();
        c.setPadding(dp(17), dp(14), dp(17), dp(15));

        if (!eyebrow.isEmpty()) {
            TextView e = text(eyebrow.toUpperCase(Locale.ROOT),
                    9, BLUE, true);
            e.setLetterSpacing(.09f);
            c.addView(e);
        }

        c.addView(text(title, 17, Color.WHITE, true));
        c.addView(text(subtitle, 12, MUTED, false));
        return c;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(7, 18, 34), Color.rgb(9, 24, 43) });
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), Color.rgb(24, 50, 79));
        c.setBackground(g);
        c.setElevation(dp(2));
        return c;
    }

    private LinearLayout cardGradient() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {
                        Color.rgb(7, 25, 45),
                        Color.rgb(5, 15, 31),
                        Color.rgb(15, 18, 48)
                });
        g.setCornerRadius(dp(26));
        g.setStroke(dp(1), Color.rgb(22, 83, 126));
        c.setBackground(g);
        c.setElevation(dp(6));
        return c;
    }

    private LinearLayout.LayoutParams cardMargin(int topDp) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(topDp), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams weightCardParams(
            float weight, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMargins(dp(leftDp), 0, dp(rightDp), 0);
        return lp;
    }

    private RadioButton radio(String label, boolean checked) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(Color.WHITE);
        r.setTextSize(14);
        r.setChecked(checked);
        r.setPadding(dp(10), dp(11), dp(10), dp(11));
        return r;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private EditText darkInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(MUTED_2);
        e.setBackground(roundStroke(SURFACE_2, BORDER, 16, 1));
        e.setPadding(dp(13), dp(12), dp(13), dp(12));
        return e;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable roundStroke(
            int color, int stroke, int radius, int strokeWidth) {
        GradientDrawable d = round(color, radius);
        d.setStroke(dp(strokeWidth), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String statusLabel() {
        if (fetching) return "ОБНОВЛЕНИЕ ПРОФИЛЯ";
        if (VisionState.connecting) return "ПОДКЛЮЧЕНИЕ";
        if (VisionState.running) return "ЗАЩИЩЕНО";
        return "НЕ ПОДКЛЮЧЕНО";
    }

    private void syncPowerButton() {
        if (powerButton == null) return;

        boolean on = VisionState.running || VisionState.connecting;
        powerButton.setText("⏻");
        powerButton.setTextColor(Color.WHITE);

        GradientDrawable orb = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                on
                        ? new int[] {
                                Color.rgb(29, 221, 213),
                                Color.rgb(35, 157, 255),
                                Color.rgb(136, 62, 255)
                        }
                        : new int[] {
                                Color.rgb(30, 129, 255),
                                Color.rgb(74, 78, 230),
                                Color.rgb(132, 52, 255)
                        });
        orb.setShape(GradientDrawable.OVAL);
        orb.setStroke(dp(2), on ? Color.rgb(89, 255, 213) : Color.rgb(66, 195, 255));
        powerButton.setBackground(orb);
        powerButton.setEnabled(!fetching);
        powerButton.setAlpha(fetching ? .65f : 1f);
    }

    private void message(String value) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("VISION VPN")
                .setMessage(value)
                .setPositiveButton("OK", null)
                .show();
    }

    private void startVPN() {
        if (fetching) return;
        VisionTunnelController.connect(this, error -> {
            if (error != null) runOnUiThread(() ->
                    message(error.getMessage()));
        });
    }

    private String networkLabel(String v) {
        if (v == null) return "Нет данных";
        return switch (v) {
            case "wifi" -> "Wi‑Fi";
            case "cellular" -> "LTE/5G";
            case "ethernet" -> "Ethernet";
            case "offline" -> "Нет сети";
            default -> v;
        };
    }

    @Override protected void onActivityResult(
            int request, int result, Intent data) {
        IntentResult qr =
                IntentIntegrator.parseActivityResult(request, result, data);

        if (qr != null && qr.getContents() != null) {
            importAny(qr.getContents());
            return;
        }

        super.onActivityResult(request, result, data);

        if (request == VPN_CONSENT && result == RESULT_OK) startVPN();

        if (request == IMPORT_FILE
                && result == RESULT_OK
                && data != null
                && data.getData() != null) {
            try (InputStream in =
                         getContentResolver().openInputStream(data.getData());
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {

                if (in == null) throw new IOException();

                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) {
                    if (out.size() + n > 1024 * 1024)
                        throw new IOException("Файл слишком большой");
                    out.write(b, 0, n);
                }

                importAny(out.toString(StandardCharsets.UTF_8.name()));
            } catch (Exception e) {
                message("Не удалось прочитать конфигурацию: " + e.getMessage());
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
