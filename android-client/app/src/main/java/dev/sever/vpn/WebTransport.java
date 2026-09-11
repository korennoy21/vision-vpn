package dev.sever.vpn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.net.SocketFactory;
import okhttp3.*;
import okio.ByteString;

/** TLS-verified WSS transport with optional multi-frame batching and physical-network binding. */
public final class WebTransport extends WebSocketListener implements AutoCloseable {
    private static final int MAX_MESSAGE=65536;
    private final OkHttpClient client;
    private final ArrayBlockingQueue<Wire.Frame> incoming=new ArrayBlockingQueue<>(2048);
    private final CountDownLatch opened=new CountDownLatch(1);
    private final ScheduledExecutorService sender=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"vision-wss-send");t.setDaemon(true);return t;});
    private final Object sendLock=new Object();
    private final ByteArrayOutputStream sendBuffer=new ByteArrayOutputStream(MAX_MESSAGE);
    private volatile ScheduledFuture<?> scheduledFlush;
    private volatile IOException failure;
    private volatile boolean closed;
    private volatile boolean batchEnabled;
    private volatile WebSocket ws;
    private static final Wire.Frame STOP=new Wire.Frame(0,new byte[0]);

    public WebTransport(Predicate<Socket> protect, NetworkTracker network) {
        Dns dns=hostname->Arrays.asList(network.resolve(hostname));
        client=new OkHttpClient.Builder()
            .dns(dns).socketFactory(new ProtectedSockets(protect,network))
            .connectionSpecs(Collections.singletonList(new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .protocols(Collections.singletonList(Protocol.HTTP_1_1)).proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).writeTimeout(20,TimeUnit.SECONDS)
            .pingInterval(0,TimeUnit.SECONDS).build();
    }
    public void setBatchEnabled(boolean enabled){batchEnabled=enabled;}
    public void connect(Profile.Endpoint endpoint,Profile profile) throws Exception {
        if(closed)throw new IOException("Stopped");
        Request request=new Request.Builder().url("https://"+endpoint.host+":"+endpoint.port+endpoint.path)
            .header("Authorization",Credentials.basic(profile.user,profile.token,StandardCharsets.UTF_8)).build();
        synchronized(this){if(closed)throw new IOException("Stopped");ws=client.newWebSocket(request,this);}
        if(!opened.await(15,TimeUnit.SECONDS))throw new IOException("WSS handshake timeout");
        if(failure!=null)throw failure;if(closed)throw new IOException("Stopped");
    }
    @Override public void onOpen(WebSocket socket,Response response){opened.countDown();}
    @Override public void onMessage(WebSocket socket,ByteString bytes){
        try{
            if(bytes.size()>MAX_MESSAGE)throw new IOException("Oversized message");
            for(Wire.Frame frame:Wire.decodeMany(bytes.toByteArray()))if(!incoming.offer(frame))throw new IOException("Receive queue overflow");
        }catch(IOException error){fail(error);}
    }
    @Override public void onMessage(WebSocket socket,String text){fail(new IOException("Binary message required"));}
    @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(1000,null);fail(new IOException("Connection closed"));}
    @Override public void onClosed(WebSocket socket,int code,String reason){fail(new IOException("Connection closed"));}
    @Override public void onFailure(WebSocket socket,Throwable error,Response response){fail(new IOException("WSS connection failed: "+error.getClass().getSimpleName(),error));}
    private void fail(IOException error){
        if(failure==null)failure=error;opened.countDown();incoming.clear();incoming.offer(STOP);WebSocket current=ws;if(current!=null)current.cancel();
    }
    public Wire.Frame read() throws Exception {
        if(failure!=null)throw failure;Wire.Frame frame=incoming.poll(90,TimeUnit.SECONDS);
        if(frame==null||frame==STOP)throw new IOException("WSS closed or timed out");return frame;
    }
    public void send(int kind,byte[] data) throws IOException {
        if(failure!=null)throw failure;if(closed||ws==null)throw new IOException("WSS closed");
        byte[] frame=Wire.encode(kind,data);
        if(!batchEnabled||kind!=Wire.PACKET){flushPending();sendImmediate(frame);return;}
        synchronized(sendLock){
            if(sendBuffer.size()+frame.length>MAX_MESSAGE)flushLocked();
            if(sendBuffer.size()+frame.length>MAX_MESSAGE)throw new IOException("Frame too large");
            sendBuffer.write(frame,0,frame.length);
            if(sendBuffer.size()>=48*1024)flushLocked();
            else if(scheduledFlush==null||scheduledFlush.isDone())scheduledFlush=sender.schedule(()->{try{flushPending();}catch(IOException e){fail(e);}},2,TimeUnit.MILLISECONDS);
        }
    }
    private void flushPending() throws IOException { synchronized(sendLock){flushLocked();} }
    private void flushLocked() throws IOException {
        if(sendBuffer.size()==0)return;byte[] data=sendBuffer.toByteArray();sendBuffer.reset();sendImmediate(data);
    }
    private void sendImmediate(byte[] data) throws IOException {
        WebSocket current=ws;if(failure!=null)throw failure;if(closed||current==null||current.queueSize()>4L*1024*1024)throw new IOException("WSS closed or congested");
        if(!current.send(ByteString.of(data)))throw new IOException("WSS send failed");
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;opened.countDown();incoming.clear();incoming.offer(STOP);
        try{flushPending();}catch(Exception ignored){}sender.shutdownNow();if(ws!=null)ws.cancel();client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();
    }
    public boolean isClosed(){return closed||failure!=null;}

    static final class ProtectedSockets extends SocketFactory {
        private final Predicate<Socket> protect; private final NetworkTracker network;
        ProtectedSockets(Predicate<Socket> protect,NetworkTracker network){this.protect=protect;this.network=network;}
        @Override public Socket createSocket() throws IOException { return createPrepared(null, 0); }
        private Socket createPrepared(InetAddress local,int localPort) throws IOException {
            Socket socket=new Socket();
            try{
                // Some Android builds require a real fd before VpnService.protect(). Bind exactly once.
                if(local!=null) socket.bind(new InetSocketAddress(local, Math.max(0,localPort)));
                else socket.bind(new InetSocketAddress(0));
                if(!protect.test(socket))throw new IOException("VPN socket protection failed");
                network.bindPhysical(socket);return socket;
            }catch(IOException error){try{socket.close();}catch(Exception ignored){}throw error;}
        }
        private Socket connect(InetAddress address,int port,InetAddress local,int localPort)throws IOException{
            Socket socket=createPrepared(local,localPort);try{socket.connect(new InetSocketAddress(address,port),10000);return socket;}catch(IOException error){socket.close();throw error;}
        }
        @Override public Socket createSocket(String host,int port)throws IOException{return connect(network.resolve(host)[0],port,null,0);}
        @Override public Socket createSocket(InetAddress host,int port)throws IOException{return connect(host,port,null,0);}
        @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return connect(network.resolve(host)[0],port,local,localPort);}
        @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return connect(host,port,local,localPort);}
    }
}
