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
 * VISION VPN Android UI v1.1.
 * Visual layer follows the approved Figma redesign while preserving
 * the existing tunnel engines, profile store, routing and handover logic.
 */
public final class MainActivity extends Activity {
    private static final int VPN_CONSENT = 10, IMPORT_FILE = 11;

    private static final int BG = Color.rgb(4, 13, 23);
    private static final int SURFACE = Color.rgb(8, 25, 40);
    private static final int SURFACE_2 = Color.rgb(10, 33, 52);
    private static final int SURFACE_3 = Color.rgb(13, 42, 65);
    private static final int BORDER = Color.rgb(25, 63, 91);
    private static final int BLUE = Color.rgb(36, 169, 255);
    private static final int BLUE_SOFT = Color.rgb(26, 112, 170);
    private static final int MINT = Color.rgb(53, 232, 184);
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
        top.setPadding(dp(20), dp(16), dp(18), dp(12));

        TextView mark = text("V", 14, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(roundStroke(Color.rgb(13, 63, 94), BLUE_SOFT, 14, 1));
        top.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(10), 0, 0, 0);
        brand.addView(text("VISION VPN", 18, Color.WHITE, true));
        TextView sub = text("PRIVATE • SMART • FAST", 9, MUTED, true);
        sub.setLetterSpacing(.12f);
        brand.addView(sub);
        top.addView(brand);

        Space spacer = new Space(this);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView badge = text("SECURE", 10, MINT, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(11), dp(5), dp(11), dp(5));
        badge.setBackground(roundStroke(Color.rgb(7, 46, 43), Color.rgb(23, 103, 85), 999, 1));
        top.addView(badge);

        return top;
    }

    private View renderBottomNav() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), dp(12));
        wrap.setBackgroundColor(Color.rgb(5, 17, 29));

        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(14, 38, 57));
        wrap.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(0, dp(7), 0, 0);

        addNav(nav, "⌂", "Главная", "home");
        addNav(nav, "◎", "Профили", "profiles");
        addNav(nav, "⇄", "Маршруты", "routes");
        addNav(nav, "⚙", "Настройки", "settings");
        wrap.addView(nav);

        return wrap;
    }

    private void addNav(LinearLayout nav, String icon, String label, String target) {
        boolean active = target.equals(page);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(4), dp(7), dp(4), dp(5));
        item.setBackground(active
                ? round(Color.rgb(9, 43, 66), 18)
                : round(Color.TRANSPARENT, 18));
        item.setClickable(true);
        item.setFocusable(true);

        TextView iconView = text(icon, 21, active ? BLUE : MUTED_2, true);
        iconView.setGravity(Gravity.CENTER);
        item.addView(iconView);

        TextView labelView = text(label, 10, active ? Color.WHITE : MUTED, active);
        labelView.setGravity(Gravity.CENTER);
        item.addView(labelView);

        item.setOnClickListener(v -> {
            if (target.equals(page)) return;
            page = target;
            renderShell();
        });

        nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1f));
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
        screenHeader(
                "Защита соединения",
                "Умный VPN-клиент с автоматическим handover между Wi‑Fi и LTE/5G");

        LinearLayout hero = cardGradient();
        hero.setPadding(dp(20), dp(18), dp(20), dp(20));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView dot = text("●", 13, VisionState.running ? MINT : MUTED_2, true);
        statusRow.addView(dot);

        headerStatus = text(statusLabel(), 11,
                VisionState.running ? MINT : MUTED, true);
        headerStatus.setPadding(dp(7), 0, 0, 0);
        headerStatus.setLetterSpacing(.08f);
        statusRow.addView(headerStatus);

        Space statusSpace = new Space(this);
        statusRow.addView(statusSpace, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView auto = text("AUTO", 10, BLUE, true);
        auto.setPadding(dp(10), dp(4), dp(10), dp(4));
        auto.setBackground(roundStroke(Color.rgb(11, 48, 73), Color.rgb(22, 80, 113), 999, 1));
        statusRow.addView(auto);

        hero.addView(statusRow);

        TextView title = text(
                VisionState.running ? "Соединение защищено" : "Готов к подключению",
                27, Color.WHITE, true);
        title.setPadding(0, dp(16), 0, dp(2));
        hero.addView(title);

        TextView subtitle = text(
                "VISION Secure сам выберет лучший доступный маршрут и восстановит туннель при смене сети.",
                13, MUTED, false);
        subtitle.setLineSpacing(0, 1.15f);
        hero.addView(subtitle);

        LinearLayout orbRow = new LinearLayout(this);
        orbRow.setGravity(Gravity.CENTER);
        orbRow.setPadding(0, dp(16), 0, dp(14));

        TextView orb = text("V", 35, Color.WHITE, true);
        orb.setGravity(Gravity.CENTER);
        GradientDrawable orbBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(28, 170, 255), Color.rgb(24, 103, 208) });
        orbBg.setShape(GradientDrawable.OVAL);
        orb.setBackground(orbBg);
        orb.setElevation(dp(8));
        orbRow.addView(orb, new LinearLayout.LayoutParams(dp(96), dp(96)));
        hero.addView(orbRow);

        powerButton = new Button(this);
        powerButton.setAllCaps(false);
        powerButton.setTextSize(15);
        powerButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        powerButton.setGravity(Gravity.CENTER);
        powerButton.setMinHeight(0);
        powerButton.setPadding(dp(18), dp(15), dp(18), dp(15));
        powerButton.setOnClickListener(v -> toggleVpn());
        hero.addView(powerButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        syncPowerButton();

        content.addView(hero, cardMargin(12));

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setWeightSum(3f);

        downloadMetric = text("0.0 МБ", 16, Color.WHITE, true);
        uploadMetric = text("0.0 МБ", 16, Color.WHITE, true);
        rttMetric = text("—", 16, Color.WHITE, true);

        stats.addView(metricCard("↓", "Получено", downloadMetric, BLUE), weightCardParams(1f, 0, 6));
        stats.addView(metricCard("↑", "Отправлено", uploadMetric, MINT), weightCardParams(1f, 6, 6));
        stats.addView(metricCard("⌁", "RTT", rttMetric, WARNING), weightCardParams(1f, 6, 0));
        content.addView(stats, cardMargin(12));

        String profileName = "Профиль не выбран";
        String protocol = "Добавьте профиль";
        try {
            UnifiedProfile p = ProfileStore.active(this);
            profileName = p.name;
            protocol = ProtocolDetector.displayName(p.protocol);
        } catch (Exception ignored) {}

        LinearLayout profile = featureCard(
                "Активный профиль",
                profileName,
                protocol,
                "›",
                BLUE);
        profile.setOnClickListener(v -> {
            page = "profiles";
            renderShell();
        });
        content.addView(profile, cardMargin(12));

        LinearLayout networkCard = card();
        networkCard.setPadding(dp(17), dp(15), dp(17), dp(16));

        LinearLayout networkHead = new LinearLayout(this);
        networkHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView networkIcon = text("⌁", 22, MINT, true);
        networkHead.addView(networkIcon);
        LinearLayout networkText = new LinearLayout(this);
        networkText.setOrientation(LinearLayout.VERTICAL);
        networkText.setPadding(dp(12), 0, 0, 0);
        networkText.addView(text("Текущая сеть", 11, MUTED, true));
        network = text(networkLabel(VisionState.network), 18, Color.WHITE, true);
        networkText.addView(network);
        networkHead.addView(networkText, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView handover = text("HANDOVER", 9, MINT, true);
        handover.setPadding(dp(9), dp(4), dp(9), dp(4));
        handover.setBackground(round(Color.rgb(7, 48, 43), 999));
        networkHead.addView(handover);
        networkCard.addView(networkHead);

        endpointText = text(
                VisionState.endpoint == null || VisionState.endpoint.isEmpty()
                        ? "Endpoint появится после подключения"
                        : VisionState.endpoint,
                12, MUTED, false);
        endpointText.setPadding(0, dp(12), 0, 0);
        networkCard.addView(endpointText);
        content.addView(networkCard, cardMargin(12));

        LinearLayout routing = featureCard(
                "Маршрутизация",
                routingTitle(),
                routingSubtitle(),
                "›",
                MINT);
        routing.setOnClickListener(v -> {
            page = "routes";
            renderShell();
        });
        content.addView(routing, cardMargin(12));
    }

    private void renderProfiles() {
        screenHeader(
                "Профили",
                "Единое управление VISION Secure, WireGuard, AmneziaWG и импортируемыми конфигурациями");

        try {
            UnifiedProfile active = ProfileStore.active(this);
            List<UnifiedProfile> all = ProfileStore.all(this);

            if (all.isEmpty()) {
                content.addView(emptyState(
                        "Профилей пока нет",
                        "Добавьте профиль по QR-коду, ссылке, файлу или вставьте конфигурацию."),
                        cardMargin(12));
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
                content.addView(c, cardMargin(10));
            }
        } catch (Exception ignored) {}

        sectionLabel("ДОБАВИТЬ ПРОФИЛЬ");

        LinearLayout quick = new LinearLayout(this);
        quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.setWeightSum(2f);

        quick.addView(quickAction("▦", "QR-код", "Сканировать", this::scanQr, BLUE),
                weightCardParams(1f, 0, 6));
        quick.addView(quickAction("⇧", "Файл", ".conf / .ovpn / JSON", () -> {
            if (ensureDisconnected()) {
                Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(open, IMPORT_FILE);
            }
        }, MINT), weightCardParams(1f, 6, 0));
        content.addView(quick, cardMargin(10));

        action("＋", "Добавить по ссылке", "Подписка VISION или URL конфигурации", this::showUrlImport);
        action("⌘", "Вставить конфигурацию", "Полный конфиг, URI или текст профиля", this::showPaste);
        action("↻", "Обновить активный VISION-профиль", "Получить свежие endpoint и политики", this::updateSubscription);

        sectionLabel("ДВИЖКИ");

        content.addView(engineCard("VISION Secure", "WSS/TLS • авто-handover", true, BLUE), cardMargin(10));
        content.addView(engineCard("WireGuard", "Нативный userspace backend", true, MINT), cardMargin(10));
        content.addView(engineCard("AmneziaWG", "Встроенный AWG backend", true, MINT), cardMargin(10));
        content.addView(engineCard("OpenVPN / Xray", "Профили сохраняются для Engine Pack", false, MUTED), cardMargin(10));
    }

    private void renderRoutes() {
        screenHeader(
                "Маршруты",
                "Split tunneling по приложениям, IP/CIDR и доменам");

        sectionLabel("ПРИЛОЖЕНИЯ");
        RadioGroup apps = optionGroup();

        String mode = RoutingPreferences.mode(this);
        RadioButton all = radio("Все приложения по правилам VPN",
                RoutingPreferences.MODE_ALL.equals(mode));
        RadioButton only = radio("Только выбранные приложения через VPN",
                RoutingPreferences.MODE_ONLY.equals(mode));
        RadioButton bypass = radio("Выбранные приложения без VPN",
                RoutingPreferences.MODE_BYPASS.equals(mode));

        apps.addView(all);
        apps.addView(only);
        apps.addView(bypass);

        all.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_ALL));
        only.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_ONLY));
        bypass.setOnClickListener(v -> RoutingPreferences.setMode(this, RoutingPreferences.MODE_BYPASS));

        content.addView(apps, cardMargin(10));
        action("◉", "Выбрать приложения",
                RoutingPreferences.packages(this).size() + " выбрано", this::chooseApps);

        sectionLabel("ТРАФИК");
        RadioGroup routes = optionGroup();

        String rm = RoutingPreferences.routeMode(this);
        RadioButton rall = radio("Весь IPv4-трафик через VPN, кроме исключений",
                RoutingPreferences.ROUTE_ALL.equals(rm));
        RadioButton ronly = radio("Через VPN только указанные IP/CIDR/домены",
                RoutingPreferences.ROUTE_ONLY.equals(rm));

        routes.addView(rall);
        routes.addView(ronly);

        rall.setOnClickListener(v -> RoutingPreferences.setRouteMode(this, RoutingPreferences.ROUTE_ALL));
        ronly.setOnClickListener(v -> RoutingPreferences.setRouteMode(this, RoutingPreferences.ROUTE_ONLY));

        content.addView(routes, cardMargin(10));

        action("↗", "IP/CIDR через VPN", summary(RoutingPreferences.vpnCidrs(this)),
                () -> editRules("IP/CIDR через VPN", RoutingPreferences.vpnCidrs(this), true,
                        v -> RoutingPreferences.setVpnCidrs(this, v)));

        action("↘", "IP/CIDR без VPN", summary(RoutingPreferences.bypassCidrs(this)),
                () -> editRules("IP/CIDR без VPN", RoutingPreferences.bypassCidrs(this), true,
                        v -> RoutingPreferences.setBypassCidrs(this, v)));

        action("◇", "Домены через VPN", summary(RoutingPreferences.vpnDomains(this)),
                () -> editRules("Домены через VPN", RoutingPreferences.vpnDomains(this), false,
                        v -> RoutingPreferences.setVpnDomains(this, v)));

        action("○", "Домены без VPN", summary(RoutingPreferences.bypassDomains(this)),
                () -> editRules("Домены без VPN", RoutingPreferences.bypassDomains(this), false,
                        v -> RoutingPreferences.setBypassDomains(this, v)));

        LinearLayout lan = card();
        lan.setPadding(dp(16), dp(12), dp(16), dp(12));
        CheckBox local = new CheckBox(this);
        local.setText("Локальные сети (LAN) пускать напрямую");
        local.setTextColor(Color.WHITE);
        local.setTextSize(14);
        local.setChecked(RoutingPreferences.allowLocalNetwork(this));
        local.setOnCheckedChangeListener((b, v) ->
                RoutingPreferences.setAllowLocalNetwork(this, v));
        lan.addView(local);
        content.addView(lan, cardMargin(10));

        content.addView(callout(
                "DNS И ДОМЕНЫ",
                "Доменные правила резолвятся при подключении и обновляются при смене сети.",
                BLUE), cardMargin(12));
    }

    private void renderSettings() {
        screenHeader(
                "Настройки",
                "Соединение, системная защита, диагностика и безопасность");

        sectionLabel("ANDROID VPN");
        action("∞", "Always-on VPN", "Открыть системные параметры Android", this::openVpnSettings);
        action("⊘", "Блокировать без VPN", "Lockdown настраивается в системном меню", this::openVpnSettings);

        sectionLabel("СОЕДИНЕНИЕ");
        content.addView(settingsCard(
                "Wi‑Fi ↔ LTE/5G handover",
                "VISION Secure отслеживает физическую сеть и восстанавливает туннель после переключения.",
                MINT), cardMargin(10));

        content.addView(settingsCard(
                "Производительность",
                "WSS batching, data no-padding и автоматический выбор доступного endpoint.",
                BLUE), cardMargin(10));

        sectionLabel("БЕЗОПАСНОСТЬ");
        content.addView(settingsCard(
                "TLS 1.3 и проверка сертификата",
                "Проверка TLS не отключается. Профили защищены AES-GCM и Android Keystore.",
                MINT), cardMargin(10));

        content.addView(settingsCard(
                "VISION Control",
                "Клиент готов отправлять телеметрию состояния и сети в Control.",
                BLUE), cardMargin(10));

        sectionLabel("О ПРИЛОЖЕНИИ");
        LinearLayout version = infoCard(
                "VERSION",
                "VISION VPN 1.1 UI",
                "Unified Profiles • WG/AWG • Routing • Handover");
        content.addView(version, cardMargin(10));
    }

    private void screenHeader(String title, String subtitle) {
        TextView kicker = text("VISION / " + page.toUpperCase(Locale.ROOT), 10, BLUE, true);
        kicker.setLetterSpacing(.12f);
        kicker.setPadding(0, dp(6), 0, dp(6));
        content.addView(kicker);

        TextView h = text(title, 29, Color.WHITE, true);
        h.setPadding(0, 0, 0, dp(3));
        content.addView(h);

        TextView s = text(subtitle, 13, MUTED, false);
        s.setLineSpacing(0, 1.12f);
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
        c.setPadding(dp(17), dp(15), dp(17), dp(15));
        c.setBackground(roundStroke(
                selected ? Color.rgb(9, 39, 59) : SURFACE,
                selected ? BLUE_SOFT : BORDER,
                22, 1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView proto = text(ProtocolDetector.displayName(p.protocol), 10,
                selected ? BLUE : MUTED, true);
        proto.setPadding(dp(9), dp(4), dp(9), dp(4));
        proto.setBackground(round(
                selected ? Color.rgb(9, 52, 78) : Color.rgb(13, 35, 53), 999));
        top.addView(proto);

        Space space = new Space(this);
        top.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));

        if (selected) {
            TextView active = text("● АКТИВЕН", 10, MINT, true);
            top.addView(active);
        }

        c.addView(top);

        TextView name = text(p.name, 18, Color.WHITE, true);
        name.setPadding(0, dp(10), 0, dp(3));
        c.addView(name);

        c.addView(text(
                selected ? "Используется для следующего подключения" : "Нажмите, чтобы сделать активным",
                12, MUTED, false));

        return c;
    }

    private LinearLayout quickAction(
            String icon, String title, String subtitle, Runnable click, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(15), dp(15), dp(15), dp(15));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnClickListener(v -> click.run());

        TextView iconView = text(icon, 22, accent, true);
        c.addView(iconView);
        c.addView(text(title, 16, Color.WHITE, true));
        c.addView(text(subtitle, 11, MUTED, false));
        return c;
    }

    private LinearLayout engineCard(
            String name, String detail, boolean ready, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(13), dp(16), dp(13));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView marker = text("●", 11, ready ? accent : MUTED_2, true);
        row.addView(marker);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(name, 15, Color.WHITE, true));
        labels.addView(text(detail, 11, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView state = text(ready ? "READY" : "PACK", 9,
                ready ? MINT : MUTED, true);
        state.setPadding(dp(9), dp(4), dp(9), dp(4));
        state.setBackground(round(
                ready ? Color.rgb(7, 47, 42) : Color.rgb(20, 35, 49), 999));
        row.addView(state);

        c.addView(row);
        return c;
    }

    private LinearLayout settingsCard(String title, String detail, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(15), dp(16), dp(15));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView marker = text("●", 10, accent, true);
        row.addView(marker);

        TextView t = text(title, 15, Color.WHITE, true);
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t);
        c.addView(row);

        TextView d = text(detail, 12, MUTED, false);
        d.setPadding(0, dp(8), 0, 0);
        d.setLineSpacing(0, 1.15f);
        c.addView(d);
        return c;
    }

    private LinearLayout callout(String title, String detail, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(roundStroke(
                Color.rgb(7, 27, 43),
                Color.rgb(19, 58, 84),
                20, 1));
        c.addView(text(title, 10, accent, true));
        TextView d = text(detail, 12, MUTED, false);
        d.setPadding(0, dp(5), 0, 0);
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
        c.setPadding(dp(17), dp(14), dp(17), dp(14));
        c.setClickable(true);
        c.setFocusable(true);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView accentView = text("●", 10, accent, true);
        row.addView(accentView);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(10), 0, 0, 0);
        labels.addView(text(eyebrow.toUpperCase(Locale.ROOT), 9, MUTED_2, true));
        labels.addView(text(title, 17, Color.WHITE, true));
        labels.addView(text(subtitle, 12, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = text(end, 24, MUTED, false);
        row.addView(arrow);
        c.addView(row);
        return c;
    }

    private LinearLayout metricCard(
            String icon, String label, TextView value, int accent) {
        LinearLayout c = card();
        c.setPadding(dp(13), dp(13), dp(13), dp(13));
        c.addView(text(icon, 14, accent, true));
        TextView l = text(label, 9, MUTED_2, true);
        l.setLetterSpacing(.06f);
        c.addView(l);
        c.addView(value);
        return c;
    }

    private RadioGroup optionGroup() {
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.setPadding(dp(8), dp(8), dp(8), dp(8));
        group.setBackground(roundStroke(SURFACE, BORDER, 22, 1));
        return group;
    }

    private void action(
            String icon, String title, String subtitle, Runnable click) {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(13), dp(15), dp(13));
        c.setClickable(true);
        c.setFocusable(true);
        c.setOnClickListener(v -> click.run());

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView iconView = text(icon, 20, BLUE, true);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(round(Color.rgb(10, 47, 70), 14));
        row.addView(iconView, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(12), 0, 0, 0);
        labels.addView(text(title, 15, Color.WHITE, true));
        labels.addView(text(subtitle, 11, MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(text("›", 24, MUTED, false));
        c.addView(row);
        content.addView(c, cardMargin(9));
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
        c.setBackground(roundStroke(SURFACE, BORDER, 22, 1));
        c.setElevation(dp(1));
        return c;
    }

    private LinearLayout cardGradient() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[] {
                        Color.rgb(10, 37, 57),
                        Color.rgb(7, 25, 42),
                        Color.rgb(8, 31, 54)
                });
        g.setCornerRadius(dp(28));
        g.setStroke(dp(1), Color.rgb(22, 66, 95));
        c.setBackground(g);
        c.setElevation(dp(3));
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
        powerButton.setText(
                VisionState.connecting
                        ? "ПОДКЛЮЧЕНИЕ…"
                        : (on ? "ОТКЛЮЧИТЬ VPN" : "ПОДКЛЮЧИТЬ VPN"));
        powerButton.setTextColor(on ? Color.rgb(3, 33, 28) : Color.WHITE);
        powerButton.setBackground(round(on ? MINT : BLUE, 18));
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
