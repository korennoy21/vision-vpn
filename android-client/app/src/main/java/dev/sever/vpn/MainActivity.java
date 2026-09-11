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

/** VISION VPN: one-button multi-profile client. Visual language intentionally stays on the approved 0.1 design. */
public final class MainActivity extends Activity {
    private static final int VPN_CONSENT=10, IMPORT_FILE=11;
    private static final int BLUE=Color.rgb(28,164,255), MINT=Color.rgb(45,235,183), BG=Color.rgb(4,15,27), CARD=Color.rgb(10,30,47), MUTED=Color.rgb(151,174,199);
    private LinearLayout content; private TextView headerStatus,traffic,network; private Button powerButton; private boolean fetching; private String page="home";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){@Override public void run(){
        if(headerStatus!=null)headerStatus.setText(fetching?"Обновление профиля…":VisionState.status);
        if(traffic!=null)traffic.setText(String.format(Locale.ROOT,"↓ %.1f МБ     ↑ %.1f МБ",VisionState.downloaded.get()/1048576.0,VisionState.uploaded.get()/1048576.0));
        if(network!=null)network.setText("Сеть: "+networkLabel(VisionState.network)+(VisionState.lastRttMs>=0?" • RTT "+VisionState.lastRttMs+" мс":""));
        if(powerButton!=null){boolean on=VisionState.running||VisionState.connecting;powerButton.setText(on?"ОТКЛЮЧИТЬ":"ПОДКЛЮЧИТЬ");powerButton.setTextColor(on?Color.rgb(3,34,27):Color.WHITE);powerButton.setBackground(round(on?MINT:BLUE,999));}
        handler.postDelayed(this,600);
    }};

    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);renderShell();}

    private void renderShell(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.HORIZONTAL);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(22),dp(20),dp(18),dp(12));
        top.addView(text("VISION",27,Color.WHITE,true));top.addView(text(" VPN",27,BLUE,true));Space spacer=new Space(this);top.addView(spacer,new LinearLayout.LayoutParams(0,1,1));top.addView(text("PRIVATE • SMART • FAST",10,MUTED,false));root.addView(top);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),dp(8),dp(18),dp(28));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this);nav.setPadding(dp(8),dp(10),dp(8),dp(14));nav.setBackgroundColor(Color.rgb(5,20,34));addNav(nav,"Главная","home");addNav(nav,"Профили","profiles");addNav(nav,"Маршруты","routes");addNav(nav,"Настройки","settings");root.addView(nav);setContentView(root);renderPage();
    }
    private void addNav(LinearLayout nav,String label,String target){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextColor(target.equals(page)?BLUE:MUTED);b.setTextSize(12);b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->{page=target;renderShell();});nav.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
    private void renderPage(){content.removeAllViews();headerStatus=null;traffic=null;network=null;powerButton=null;switch(page){case"profiles"->renderProfiles();case"routes"->renderRoutes();case"settings"->renderSettings();default->renderHome();}}

    private void renderHome(){
        content.addView(text("Свобода без границ",31,Color.WHITE,true));content.addView(text("Одна кнопка. Умное соединение. Полный контроль.",14,MUTED,false));
        LinearLayout hero=card();hero.setGravity(Gravity.CENTER_HORIZONTAL);hero.setPadding(dp(20),dp(28),dp(20),dp(26));headerStatus=text(VisionState.status,20,MINT,true);hero.addView(headerStatus);
        TextView shield=text("◈",72,BLUE,true);shield.setGravity(Gravity.CENTER);hero.addView(shield);powerButton=new Button(this);powerButton.setAllCaps(false);powerButton.setTextSize(16);powerButton.setTypeface(Typeface.DEFAULT,Typeface.BOLD);powerButton.setPadding(dp(34),dp(16),dp(34),dp(16));powerButton.setOnClickListener(v->toggleVpn());hero.addView(powerButton,new LinearLayout.LayoutParams(-1,dp(64)));
        traffic=text("",15,Color.WHITE,true);traffic.setGravity(Gravity.CENTER);hero.addView(traffic);network=text("",12,MUTED,false);network.setGravity(Gravity.CENTER);hero.addView(network);content.addView(hero,cardMargin());
        try{UnifiedProfile p=ProfileStore.active(this);content.addView(infoCard("Активный профиль",p.name,ProtocolDetector.displayName(p.protocol)),cardMargin());}
        catch(Exception e){content.addView(infoCard("Профиль","Не добавлен","Откройте вкладку «Профили»"),cardMargin());}
        if(!VisionState.endpoint.isEmpty())content.addView(infoCard("Endpoint",VisionState.endpoint,"Автоматическое переподключение Wi‑Fi ↔ LTE"),cardMargin());
        content.addView(infoCard("Маршрутизация",routingTitle(),routingSubtitle()),cardMargin());
    }

    private void renderProfiles(){
        content.addView(text("Профили",30,Color.WHITE,true));content.addView(text("VISION, WireGuard, AmneziaWG и импорт будущих движков",14,MUTED,false));
        try{
            UnifiedProfile active=ProfileStore.active(this);List<UnifiedProfile> all=ProfileStore.all(this);
            for(UnifiedProfile p:all){
                String mark=p.id.equals(active.id)?"АКТИВЕН":"ПРОФИЛЬ";LinearLayout c=infoCard(mark,p.name,ProtocolDetector.displayName(p.protocol));c.setClickable(true);c.setOnClickListener(v->{if(!ensureDisconnected())return;try{ProfileStore.setActive(this,p.id);renderPage();}catch(Exception e){message(e.getMessage());}});
                c.setOnLongClickListener(v->{if(!ensureDisconnected())return true;new AlertDialog.Builder(this).setTitle("Удалить профиль?").setMessage(p.name).setPositiveButton("Удалить",(d,w)->{try{ProfileStore.delete(this,p.id);renderPage();}catch(Exception e){message(e.getMessage());}}).setNegativeButton("Отмена",null).show();return true;});content.addView(c,cardMargin());
            }
        }catch(Exception ignored){}
        action("Добавить по ссылке","Ссылка подписки VISION",this::showUrlImport);action("Сканировать QR-код","VISION / WG / AWG / URI-конфиг",this::scanQr);
        action("Импортировать файл","JSON • .conf • .ovpn • текстовый конфиг",()->{if(ensureDisconnected()){Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(open,IMPORT_FILE);}});
        action("Вставить конфигурацию","Вставка полного конфига или URI",this::showPaste);action("Обновить активный VISION-профиль","Получить свежие endpoint и политики",this::updateSubscription);
        content.addView(infoCard("Движки","VISION Secure • WireGuard • AmneziaWG","VISION Secure, WireGuard и AmneziaWG встроены; OpenVPN/Xray профили сохраняются для Engine Pack"),cardMargin());
    }

    private void renderRoutes(){
        content.addView(text("Маршруты",30,Color.WHITE,true));content.addView(text("Приложения • IP/CIDR • домены • локальные сети",14,MUTED,false));
        RadioGroup apps=new RadioGroup(this);apps.setOrientation(RadioGroup.VERTICAL);apps.setBackground(round(CARD,24));String mode=RoutingPreferences.mode(this);
        RadioButton all=radio("Все приложения по правилам VPN",RoutingPreferences.MODE_ALL.equals(mode));RadioButton only=radio("Только выбранные приложения через VPN",RoutingPreferences.MODE_ONLY.equals(mode));RadioButton bypass=radio("Выбранные приложения без VPN",RoutingPreferences.MODE_BYPASS.equals(mode));apps.addView(all);apps.addView(only);apps.addView(bypass);
        all.setOnClickListener(v->RoutingPreferences.setMode(this,RoutingPreferences.MODE_ALL));only.setOnClickListener(v->RoutingPreferences.setMode(this,RoutingPreferences.MODE_ONLY));bypass.setOnClickListener(v->RoutingPreferences.setMode(this,RoutingPreferences.MODE_BYPASS));content.addView(apps,cardMargin());
        action("Выбрать приложения",RoutingPreferences.packages(this).size()+" выбрано",this::chooseApps);
        RadioGroup routes=new RadioGroup(this);routes.setOrientation(RadioGroup.VERTICAL);routes.setBackground(round(CARD,24));String rm=RoutingPreferences.routeMode(this);
        RadioButton rall=radio("Весь IPv4-трафик через VPN, кроме исключений",RoutingPreferences.ROUTE_ALL.equals(rm));RadioButton ronly=radio("Через VPN только указанные IP/CIDR/домены",RoutingPreferences.ROUTE_ONLY.equals(rm));routes.addView(rall);routes.addView(ronly);rall.setOnClickListener(v->RoutingPreferences.setRouteMode(this,RoutingPreferences.ROUTE_ALL));ronly.setOnClickListener(v->RoutingPreferences.setRouteMode(this,RoutingPreferences.ROUTE_ONLY));content.addView(routes,cardMargin());
        action("IP/CIDR через VPN",summary(RoutingPreferences.vpnCidrs(this)),()->editRules("IP/CIDR через VPN",RoutingPreferences.vpnCidrs(this),true,v->RoutingPreferences.setVpnCidrs(this,v)));
        action("IP/CIDR без VPN",summary(RoutingPreferences.bypassCidrs(this)),()->editRules("IP/CIDR без VPN",RoutingPreferences.bypassCidrs(this),true,v->RoutingPreferences.setBypassCidrs(this,v)));
        action("Домены через VPN",summary(RoutingPreferences.vpnDomains(this)),()->editRules("Домены через VPN",RoutingPreferences.vpnDomains(this),false,v->RoutingPreferences.setVpnDomains(this,v)));
        action("Домены без VPN",summary(RoutingPreferences.bypassDomains(this)),()->editRules("Домены без VPN",RoutingPreferences.bypassDomains(this),false,v->RoutingPreferences.setBypassDomains(this,v)));
        CheckBox local=new CheckBox(this);local.setText("Локальные сети (LAN) пускать напрямую");local.setTextColor(Color.WHITE);local.setChecked(RoutingPreferences.allowLocalNetwork(this));local.setOnCheckedChangeListener((b,v)->RoutingPreferences.setAllowLocalNetwork(this,v));LinearLayout lc=card();lc.setPadding(dp(16),dp(10),dp(16),dp(10));lc.addView(local);content.addView(lc,cardMargin());
        content.addView(infoCard("Важно","Доменные правила резолвятся при подключении","Для динамических CDN адреса обновляются при переподключении/смене сети"),cardMargin());
    }

    private void renderSettings(){
        content.addView(text("Настройки",30,Color.WHITE,true));content.addView(text("Соединение, безопасность и диагностика",14,MUTED,false));
        action("Always-on VPN","Открыть системные параметры Android",()->openVpnSettings());action("Блокировать без VPN","Lockdown настраивается в Android",()->openVpnSettings());
        content.addView(infoCard("Handover","Wi‑Fi ↔ LTE","VISION Secure отслеживает физическую сеть, защищает управляющие сокеты от VPN-петли и переподключается сразу"),cardMargin());
        content.addView(infoCard("Производительность","WSS batching + data no-padding","Пакеты объединяются в WebSocket-сообщения на обновлённом VISION-сервере"),cardMargin());
        content.addView(infoCard("Безопасность","TLS 1.3 • проверка сертификата","TLS-проверка не отключается; профили хранятся AES-GCM в Android Keystore"),cardMargin());
        content.addView(infoCard("Версия","VISION VPN 1.0.0","Unified Profiles • WG/AWG • маршрутизация • handover"),cardMargin());
    }

    private void openVpnSettings(){try{startActivity(new Intent(Settings.ACTION_VPN_SETTINGS));}catch(Exception e){message("Откройте Настройки → VPN вручную");}}
    private void toggleVpn(){
        if(VisionState.running||VisionState.connecting){VisionTunnelController.disconnect(this);return;}
        try{ProfileStore.active(this);Intent consent=VpnService.prepare(this);if(consent!=null)startActivityForResult(consent,VPN_CONSENT);else startVPN();}catch(Exception e){message("Сначала добавьте профиль VPN");}
    }
    private boolean ensureDisconnected(){if(VisionState.running||VisionState.connecting){message("Сначала отключите VPN");return false;}return true;}

    private void showUrlImport(){if(!ensureDisconnected())return;EditText input=darkInput("https://panel.example.com/s/…json");new AlertDialog.Builder(this).setTitle("Добавить профиль по ссылке").setView(input).setPositiveButton("Загрузить",(d,w)->fetchProfile(input.getText().toString().trim(),false)).setNegativeButton("Отмена",null).show();}
    private void showPaste(){if(!ensureDisconnected())return;EditText input=darkInput("Вставьте конфигурацию");input.setMinLines(8);new AlertDialog.Builder(this).setTitle("Импорт конфигурации").setView(input).setPositiveButton("Импортировать",(d,w)->importAny(input.getText().toString())).setNegativeButton("Отмена",null).show();}
    private void scanQr(){if(!ensureDisconnected())return;IntentIntegrator i=new IntentIntegrator(this);i.setPrompt("Наведите камеру на QR-код");i.setBeepEnabled(false);i.setOrientationLocked(false);i.initiateScan();}
    private void updateSubscription(){if(!ensureDisconnected())return;try{UnifiedProfile u=ProfileStore.active(this);if(!ProtocolDetector.VISION.equals(u.protocol)){message("Подписка панели доступна только для VISION-профиля");return;}Profile p=new Profile(u.raw);if(p.subscriptionUrl==null){message("В профиле нет ссылки обновления");return;}fetchProfile(p.subscriptionUrl,true);}catch(Exception e){message(e.getMessage());}}
    private void importAny(String raw){if(!ensureDisconnected())return;String v=raw==null?"":raw.trim();if(v.isEmpty()){message("Пустая конфигурация");return;}if(v.startsWith("https://")||v.startsWith("http://")){fetchProfile(v,false);return;}try{UnifiedProfile p=ProfileStore.add(this,v);renderPage();message("Добавлен профиль: "+p.name+"\n"+ProtocolDetector.displayName(p.protocol));}catch(Exception e){message(e.getMessage());}}
    private void fetchProfile(String url,boolean replace){if(!ensureDisconnected()||fetching)return;fetching=true;new Thread(()->{try{String raw=Subscription.fetch(url,socket->true);runOnUiThread(()->{fetching=false;try{if(replace){UnifiedProfile active=ProfileStore.active(this);ProfileStore.replaceRaw(this,active.id,raw);message("Профиль обновлён");}else{UnifiedProfile p=ProfileStore.add(this,raw);message("Добавлен профиль: "+p.name);}renderPage();}catch(Exception e){message(e.getMessage());}});}catch(Exception e){runOnUiThread(()->{fetching=false;message("Не удалось получить профиль: "+e.getMessage());});}},"vision-profile").start();}

    private void chooseApps(){if(!ensureDisconnected())return;PackageManager pm=getPackageManager();List<ApplicationInfo> apps=new ArrayList<>();for(ApplicationInfo app:pm.getInstalledApplications(0))if(pm.getLaunchIntentForPackage(app.packageName)!=null&&!app.packageName.equals(getPackageName()))apps.add(app);apps.sort(Comparator.comparing(a->pm.getApplicationLabel(a).toString().toLowerCase(Locale.ROOT)));String[] labels=new String[apps.size()];boolean[] checked=new boolean[apps.size()];Set<String> chosen=RoutingPreferences.packages(this);for(int i=0;i<apps.size();i++){labels[i]=pm.getApplicationLabel(apps.get(i)).toString();checked[i]=chosen.contains(apps.get(i).packageName);}new AlertDialog.Builder(this).setTitle("Приложения").setMultiChoiceItems(labels,checked,(d,which,value)->checked[which]=value).setPositiveButton("Сохранить",(d,w)->{Set<String> result=new LinkedHashSet<>();for(int i=0;i<apps.size();i++)if(checked[i])result.add(apps.get(i).packageName);RoutingPreferences.setPackages(this,result);renderPage();}).setNegativeButton("Отмена",null).show();}

    private interface RuleSaver{void save(Set<String> values);}
    private void editRules(String title,Set<String> current,boolean cidr,RuleSaver saver){if(!ensureDisconnected())return;EditText input=darkInput("по одному значению на строку");input.setMinLines(8);input.setText(String.join("\n",current));new AlertDialog.Builder(this).setTitle(title).setView(input).setPositiveButton("Сохранить",(d,w)->{try{Set<String> values=parseLines(input.getText().toString(),cidr);saver.save(values);renderPage();}catch(Exception e){message(e.getMessage());}}).setNegativeButton("Отмена",null).show();}
    private Set<String> parseLines(String raw,boolean cidr)throws Exception{LinkedHashSet<String> result=new LinkedHashSet<>();for(String item:raw.split("[,\\n]")){String v=item.trim();if(v.isEmpty())continue;if(cidr){RoutePlanner.Cidr.parse(v);}else{v=v.toLowerCase(Locale.ROOT);if(!v.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?"))throw new Exception("Некорректный домен: "+v);}result.add(v);if(result.size()>256)throw new Exception("Не более 256 правил в списке");}return result;}
    private String summary(Set<String> values){if(values.isEmpty())return"Правила не заданы";return values.size()+" правил";}
    private String routingTitle(){String mode=RoutingPreferences.mode(this);if(RoutingPreferences.MODE_ONLY.equals(mode))return"Только выбранные приложения";if(RoutingPreferences.MODE_BYPASS.equals(mode))return"Исключения из VPN";return"Все приложения";}
    private String routingSubtitle(){return RoutingPreferences.packages(this).size()+" приложений • "+(RoutingPreferences.ROUTE_ONLY.equals(RoutingPreferences.routeMode(this))?"только заданные маршруты":"full tunnel");}

    private LinearLayout infoCard(String eyebrow,String title,String subtitle){LinearLayout c=card();c.setPadding(dp(18),dp(15),dp(18),dp(16));if(!eyebrow.isEmpty())c.addView(text(eyebrow.toUpperCase(Locale.ROOT),10,BLUE,true));c.addView(text(title,18,Color.WHITE,true));c.addView(text(subtitle,13,MUTED,false));return c;}
    private void action(String title,String subtitle,Runnable click){LinearLayout c=infoCard("",title,subtitle);c.setClickable(true);c.setFocusable(true);c.setOnClickListener(v->click.run());content.addView(c,cardMargin());}
    private LinearLayout card(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setBackground(round(CARD,24));return c;}
    private LinearLayout.LayoutParams cardMargin(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(14),0,0);return lp;}
    private RadioButton radio(String label,boolean checked){RadioButton r=new RadioButton(this);r.setText(label);r.setTextColor(Color.WHITE);r.setTextSize(15);r.setChecked(checked);r.setPadding(dp(12),dp(10),dp(12),dp(10));return r;}
    private TextView text(String value,int sp,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private EditText darkInput(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(MUTED);e.setPadding(dp(12),dp(10),dp(12),dp(10));return e;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void message(String value){if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("VISION VPN").setMessage(value).setPositiveButton("OK",null).show();}
    private void startVPN(){if(fetching)return;VisionTunnelController.connect(this,error->{if(error!=null)runOnUiThread(()->message(error.getMessage()));});}
    private String networkLabel(String v){return switch(v){case"wifi"->"Wi‑Fi";case"cellular"->"LTE/5G";case"ethernet"->"Ethernet";case"offline"->"нет сети";default->v;};}

    @Override protected void onActivityResult(int request,int result,Intent data){IntentResult qr=IntentIntegrator.parseActivityResult(request,result,data);if(qr!=null&&qr.getContents()!=null){importAny(qr.getContents());return;}super.onActivityResult(request,result,data);if(request==VPN_CONSENT&&result==RESULT_OK)startVPN();if(request==IMPORT_FILE&&result==RESULT_OK&&data!=null&&data.getData()!=null){try(InputStream in=getContentResolver().openInputStream(data.getData());ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>1024*1024)throw new IOException("Файл слишком большой");out.write(b,0,n);}importAny(out.toString(StandardCharsets.UTF_8.name()));}catch(Exception e){message("Не удалось прочитать конфигурацию: "+e.getMessage());}}}
    @Override protected void onResume(){super.onResume();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
