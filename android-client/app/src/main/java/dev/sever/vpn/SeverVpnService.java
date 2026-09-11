package dev.sever.vpn;

import android.app.*;
import android.content.*;
import android.net.Network;
import android.net.VpnService;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/** VISION Secure WSS engine. WG/AWG use their embeddable native backends. */
public final class SeverVpnService extends VpnService {
    // Compatibility aliases for older UI/debug code.
    public static volatile boolean running;
    public static volatile String status="Отключено";
    public static final AtomicLong uploaded=VisionState.uploaded, downloaded=VisionState.downloaded;

    private Session session;
    private NetworkTracker networks;

    @Override public void onCreate(){
        super.onCreate();
        networks=new NetworkTracker(this);
        networks.setListener(g->{Session s=session;if(s!=null)s.networkChanged(g);});
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { stopNow(startId); return START_NOT_STICKY; }
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("vision-vpn","VISION VPN",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,"vision-vpn").setContentTitle("VISION VPN")
            .setContentText("Защищённое соединение активно").setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open).setOngoing(true).build();
        startForeground(1,notification);
        if(session==null){
            try{
                UnifiedProfile unified=ProfileStore.active(this);
                if(!ProtocolDetector.VISION.equals(unified.protocol))throw new Exception("Активный профиль не VISION Secure");
                VisionState.starting(unified.protocol);syncState();
                session=new Session(unified.id,new Profile(unified.raw));session.start();
            }catch(Exception e){VisionState.failed(e.getMessage());syncState();stopSelf(startId);}
        }
        return START_NOT_STICKY;
    }

    private void stopNow(int startId){
        boolean ownsState=ProtocolDetector.VISION.equals(VisionState.protocol) || VisionState.protocol.isBlank();
        if(ownsState){VisionState.status="Отключение…";syncState();}
        Session s=session;session=null;if(s!=null)s.close();
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);
        if(ownsState){VisionState.stopped();syncState();}
    }
    private static void syncState(){running=VisionState.running||VisionState.connecting;status=VisionState.status;}
    @Override public void onRevoke(){if(ProtocolDetector.VISION.equals(VisionState.protocol)){VisionState.stopped();syncState();}stopSelf();}
    @Override public void onDestroy(){
        Session s=session;session=null;if(s!=null)s.close();if(networks!=null)networks.close();networks=null;
        if(ProtocolDetector.VISION.equals(VisionState.protocol)){VisionState.stopped();syncState();}
        super.onDestroy();
    }

    private final class Session extends Thread implements TunnelEngine {
        final String profileId;
        Profile profile;
        volatile boolean cancelled, networkChanged;
        volatile long connectGeneration;
        WebTransport transport;
        ParcelFileDescriptor tun;
        Session(String profileId,Profile profile){super("vision-wss-session");this.profileId=profileId;this.profile=profile;}
        synchronized void hold(WebTransport value)throws IOException{if(cancelled){value.close();throw new IOException("Stopped");}transport=value;}
        synchronized void hold(ParcelFileDescriptor fd)throws IOException{if(cancelled){fd.close();throw new IOException("Stopped");}tun=fd;}
        synchronized void closeResources(){if(transport!=null)transport.close();try{if(tun!=null)tun.close();}catch(IOException ignored){}transport=null;tun=null;}
        @Override public synchronized void close(){cancelled=true;closeResources();interrupt();}
        void networkChanged(long generation){
            if(cancelled)return;networkChanged=true;VisionState.status="Сеть изменилась • переподключение";syncState();
            WebTransport t=transport;if(t!=null)t.close();interrupt();
        }
        @Override public void run(){
            int attempts=0;uploaded.set(0);downloaded.set(0);
            while(!cancelled){
                networkChanged=false;
                if(!refreshSubscription()){
                    if(cancelled)break;
                    try{Thread.sleep(15000);}catch(InterruptedException e){if(cancelled)break;}
                    continue;
                }
                if(cancelled)break;
                for(Profile.Endpoint endpoint:profile.endpoints){
                    if(cancelled)break;connectGeneration=networks.generation();
                    VisionState.status="Подключение • "+endpoint.host;VisionState.endpoint=endpoint.host+":"+endpoint.port;syncState();
                    try{connect(endpoint);attempts=0;}
                    catch(Exception e){if(!cancelled){VisionState.reconnects++;VisionState.status="Соединение потеряно • переподключение";syncState();}}
                    finally{closeResources();}
                    if(networkChanged||networks.generation()!=connectGeneration)break;
                }
                if(!cancelled){
                    long delay=(networkChanged||networks.generation()!=connectGeneration)?250:Math.min(15000,1000L<<Math.min(attempts++,4));
                    try{Thread.sleep(delay);}catch(InterruptedException e){if(cancelled)break;}
                }
            }
        }

        private boolean refreshSubscription(){
            if(profile.subscriptionUrl==null)return true;
            VisionState.status="Обновление профиля…";syncState();
            try{
                String raw=Subscription.fetch(profile.subscriptionUrl,socket->protect(socket),networks);
                if(cancelled)return false;Profile next=new Profile(raw);ProfileStore.replaceRaw(SeverVpnService.this,profileId,raw);profile=next;
                return true;
            }catch(Subscription.AccessRevokedException revoked){
                // A deliberate revoke must never fall through to the cached credentials.
                VisionState.status="Доступ отключён администратором";syncState();
                return false;
            }catch(Exception temporary){
                // Preserve last known-good profile during Wi-Fi/LTE transitions or short control-plane outages.
                VisionState.status="Панель временно недоступна • используется сохранённый профиль";syncState();
                return true;
            }
        }

        void connect(Profile.Endpoint endpoint)throws Exception{
            WebTransport tls=new WebTransport(socket->protect(socket),networks);hold(tls);tls.connect(endpoint,profile);
            Heartbeat policy=new Heartbeat();Wire.Frame frame=tls.read();if(frame.kind!=Wire.CONFIG)throw new IOException("CONFIG required");
            JSONObject config=new JSONObject(new String(frame.data,StandardCharsets.UTF_8));
            String ip=config.getString("ip"),dns=config.getString("dns");byte[] address=Wire.address(ip);Wire.address(dns);
            int mtu=config.optInt("mtu",1280),prefix=config.optInt("prefix",24);
            if(mtu!=Wire.MTU||prefix!=24||(address[0]&255)!=10||(address[1]&255)!=77||address[2]!=0||(address[3]&255)<2||(address[3]&255)>254)throw new IOException("Invalid config");
            JSONArray features=config.optJSONArray("features");boolean batch=false;
            if(features!=null)for(int i=0;i<features.length();i++)if("batch-v1".equals(features.optString(i)))batch=true;
            tls.setBatchEnabled(batch);

            Builder builder=new Builder().setSession("VISION • "+profile.name).setMtu(mtu).addAddress(ip,prefix).addDnsServer(dns).setBlocking(true);
            RoutePlanner.apply(builder,SeverVpnService.this,networks);
            // VISION Secure v1 is IPv4-only. Capture IPv6 to avoid a silent full-tunnel leak.
            builder.addAddress("fd77::2",128).addRoute("::",0);
            Network physical=networks.current();if(physical!=null)builder.setUnderlyingNetworks(new Network[]{physical});
            ParcelFileDescriptor device=builder.establish();if(device==null)throw new IOException("VPN permission revoked");hold(device);
            FileInputStream tunIn=new FileInputStream(device.getFileDescriptor());FileOutputStream tunOut=new FileOutputStream(device.getFileDescriptor());
            final long[] pingAt={0};
            Thread uplink=new Thread(()->{
                byte[] buffer=new byte[65535];
                try{while(!cancelled&&!tls.isClosed()){
                    int n=tunIn.read(buffer);if(n<0)break;if(n==0||(buffer[0]&255)>>4!=4)continue;
                    byte[] packet=Arrays.copyOf(buffer,n);try{Wire.validatePacket(packet,address,true);}catch(IOException malformed){continue;}
                    tls.send(Wire.PACKET,packet);policy.packetSent();uploaded.addAndGet(n);
                }}catch(Exception ignored){}finally{try{tls.close();}catch(Exception ignored){}}
            },"vision-uplink");
            Thread heartbeat=new Thread(()->{
                SecureRandom random=new SecureRandom();
                try{while(!cancelled&&!tls.isClosed()){
                    Thread.sleep(1000);if(policy.due()){byte[] nonce=new byte[8];random.nextBytes(nonce);pingAt[0]=System.nanoTime();tls.send(Wire.PING,nonce);policy.pingSent();}
                }}catch(Exception ignored){}finally{try{tls.close();}catch(Exception ignored){}}
            },"vision-heartbeat");
            uplink.start();heartbeat.start();VisionState.connected(ProtocolDetector.VISION,endpoint.host+":"+endpoint.port);syncState();
            Thread telemetry=new Thread(()->{
                try{
                    while(!cancelled&&!tls.isClosed()){
                        TelemetryReporter.send(profile,networks,socket->protect(socket),"connected");
                        Thread.sleep(15000);
                    }
                }catch(InterruptedException ignored){}
            },"vision-telemetry");
            telemetry.setDaemon(true);telemetry.start();
            try{while(!cancelled){
                Wire.Frame next=tls.read();
                if(next.kind==Wire.PACKET){try{Wire.validatePacket(next.data,address,false);}catch(IOException malformed){continue;}tunOut.write(next.data);downloaded.addAndGet(next.data.length);}
                else if(next.kind==Wire.PONG&&next.data.length==8){if(pingAt[0]>0)VisionState.lastRttMs=Math.max(0,(System.nanoTime()-pingAt[0])/1_000_000L);}
                else throw new IOException("Unexpected frame");
            }}finally{
                telemetry.interrupt();tls.close();try{device.close();}catch(Exception ignored){}heartbeat.interrupt();uplink.interrupt();
                telemetry.join(500);heartbeat.join(1000);uplink.join(1000);
            }
        }
    }
}
