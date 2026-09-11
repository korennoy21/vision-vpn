import dev.sever.vpn.Wire;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

/** JDK test client uses the production Android Wire.java, not Android/OkHttp APIs. */
public class JavaWssInterop {
    public static void main(String[] args)throws Exception {
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
        try(InputStream in=new FileInputStream(args[0])){trust.setCertificateEntry("lab",CertificateFactory.getInstance("X.509").generateCertificate(in));}
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext ctx=SSLContext.getInstance("TLSv1.3");ctx.init(null,tm.getTrustManagers(),null);
        SSLParameters params=new SSLParameters();params.setProtocols(new String[]{"TLSv1.3"});params.setEndpointIdentificationAlgorithm("HTTPS");
        BlockingQueue<byte[]> queue=new ArrayBlockingQueue<>(8);
        HttpClient client=HttpClient.newBuilder().sslContext(ctx).sslParameters(params).followRedirects(HttpClient.Redirect.NEVER).build();
        String auth=Base64.getEncoder().encodeToString(("alice:"+"a".repeat(43)).getBytes(StandardCharsets.UTF_8));
        WebSocket ws=client.newWebSocketBuilder().header("Authorization","Basic "+auth).buildAsync(URI.create("wss://localhost:"+args[1]+"/api/session"),new WebSocket.Listener(){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            public void onOpen(WebSocket ws){ws.request(1);}
            public CompletionStage<?> onBinary(WebSocket ws,ByteBuffer bytes,boolean last){
                while(bytes.hasRemaining())buffer.write(bytes.get());
                if(buffer.size()>4615)throw new IllegalStateException("oversized message");
                if(last){queue.add(buffer.toByteArray());buffer.reset();}
                ws.request(1);return CompletableFuture.completedFuture(null);
            }
        }).join();
        try {
            if(Wire.decode(take(queue)).kind!=Wire.CONFIG)throw new Exception("CONFIG missing");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            Wire.write(out,Wire.PACKET,HexFormat.of().parseHex(args[2]));
            ws.sendBinary(ByteBuffer.wrap(out.toByteArray()),true).join();
            Wire.Frame received=Wire.decode(take(queue));
            if(received.kind!=Wire.PACKET)throw new Exception("PACKET missing");
            Wire.validatePacket(received.data,Wire.address("10.77.0.2"),false);
            out.reset();Wire.write(out,Wire.PING,new byte[]{0,1,2,3,4,5,6,7});
            ws.sendBinary(ByteBuffer.wrap(out.toByteArray()),true).join();
            Wire.Frame pong=Wire.decode(take(queue));
            if(pong.kind!=Wire.PONG || !Arrays.equals(pong.data,new byte[]{0,1,2,3,4,5,6,7}))throw new Exception("PONG mismatch");
            System.out.println("JAVA_WSS_OK");
        }finally{ws.abort();}
    }
    static byte[] take(BlockingQueue<byte[]> queue)throws Exception{byte[] data=queue.poll(5,TimeUnit.SECONDS);if(data==null)throw new Exception("timeout");return data;}
}
