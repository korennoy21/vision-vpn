import dev.sever.vpn.Wire;
import javax.net.ssl.*;
import java.net.Socket;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class JavaInterop {
    public static void main(String[] args) throws Exception {
        byte[] vector=java.util.HexFormat.of().parseHex("01040008000000000001020304050607");
        Wire.Frame parsed=Wire.read(new ByteArrayInputStream(vector));
        if(parsed.kind!=Wire.PING || !Arrays.equals(parsed.data,new byte[]{0,1,2,3,4,5,6,7}))throw new Exception("Vector mismatch");
        KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType()); trust.load(null,null);
        try(InputStream cert=new FileInputStream(args[0])) { trust.setCertificateEntry("test",CertificateFactory.getInstance("X.509").generateCertificate(cert)); }
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);
        SSLContext context=SSLContext.getInstance("TLSv1.3");context.init(null,tm.getTrustManagers(),null);
        try(Socket raw=new Socket("127.0.0.1",Integer.parseInt(args[1]));
            SSLSocket socket=(SSLSocket)context.getSocketFactory().createSocket(raw,"localhost",Integer.parseInt(args[1]),true)) {
            socket.setEnabledProtocols(new String[]{"TLSv1.3"});socket.setSoTimeout(5000);
            SSLParameters params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(params);
            socket.startHandshake();
            if(!socket.getSession().getProtocol().equals("TLSv1.3"))throw new Exception("TLS version");
            byte[] auth=("{\"user\":\"alice\",\"token\":\""+"a".repeat(43)+"\"}").getBytes(StandardCharsets.UTF_8);
            Wire.write(socket.getOutputStream(),Wire.AUTH,auth);
            if(Wire.read(socket.getInputStream()).kind!=Wire.CONFIG)throw new Exception("CONFIG missing");
            byte[] packet=java.util.HexFormat.of().parseHex(args[2]);
            Wire.validatePacket(packet,Wire.address("10.77.0.2"),true);
            Wire.write(socket.getOutputStream(),Wire.PACKET,packet);
            Wire.Frame reply=Wire.read(socket.getInputStream());
            if(reply.kind!=Wire.PACKET)throw new Exception("PACKET missing");
            Wire.validatePacket(reply.data,Wire.address("10.77.0.2"),false);
            Wire.write(socket.getOutputStream(),Wire.PING,new byte[]{0,1,2,3,4,5,6,7});
            Wire.Frame pong=Wire.read(socket.getInputStream());
            if(pong.kind!=Wire.PONG || !Arrays.equals(pong.data,parsed.data))throw new Exception("PONG mismatch");
            System.out.println("JAVA_INTEROP_OK");
        }
    }
}
