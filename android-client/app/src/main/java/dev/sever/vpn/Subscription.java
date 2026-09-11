package dev.sever.vpn;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.*;
import org.json.JSONObject;

/** Bounded HTTPS profile download. Credentials are never forwarded to redirects. */
final class Subscription {
    static final class AccessRevokedException extends IOException { AccessRevokedException(String m){super(m);} }

    static String validate(String value) throws Exception {
        if(value==null || value.length()>2048)throw new IOException("Некорректная ссылка профиля");
        URI uri=new URI(value);
        if(!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !uri.getRawPath().matches("/s/[A-Za-z0-9_-]{43}\\.json"))
            throw new IOException("Нужна HTTPS-ссылка профиля из панели VISION");
        return value;
    }

    static String fetch(String url, Predicate<Socket> protect) throws Exception {
        return fetch(url, protect, null);
    }

    static String fetch(String url,Predicate<Socket> protect,NetworkTracker network) throws Exception {
        validate(url);
        OkHttpClient.Builder b=new OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(15,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS);
        if(network!=null){b.dns(host->Arrays.asList(network.resolve(host))).socketFactory(new WebTransport.ProtectedSockets(protect,network));}
        OkHttpClient client=b.build();
        try(Response response=client.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if(response.code()==404 || response.code()==410)throw new AccessRevokedException("Профиль отключён администратором");
            if(response.code()!=200 || response.body()==null)throw new IOException("Панель не выдала профиль");
            try(InputStream input=response.body().byteStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] bytes=new byte[4096];int n;
                while((n=input.read(bytes))!=-1){if(out.size()+n>65536)throw new IOException("Профиль слишком большой");out.write(bytes,0,n);}
                JSONObject raw=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));raw.put("subscription_url",url);
                String value=raw.toString();new Profile(value);return value;
            }
        } finally {client.dispatcher().cancelAll();client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
    }
}
