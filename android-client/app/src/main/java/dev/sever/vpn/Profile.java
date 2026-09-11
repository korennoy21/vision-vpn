package dev.sever.vpn;
import org.json.*;
import java.util.*;

public final class Profile {
    public final String name, user, token, protocol, subscriptionUrl;
    public final List<Endpoint> endpoints=new ArrayList<>();
    public static final class Endpoint {
        public final String host,path; public final int port;
        Endpoint(String host,int port,String path){this.host=host;this.port=port;this.path=path;}
    }
    public Profile(String raw) throws Exception {
        if(raw.length()>65536) throw new Exception("Профиль слишком большой");
        JSONObject p=new JSONObject(raw);
        if(p.getInt("version")!=2 || !p.getString("transport").equals("wss")) throw new Exception("Нужен профиль версии 2 с transport=wss");
        protocol=p.getString("protocol");
        EngineRegistry.requireInstalled(protocol);
        subscriptionUrl=p.has("subscription_url")?Subscription.validate(p.getString("subscription_url")):null;
        name=p.getString("name"); user=p.getString("user"); token=p.getString("token");
        if(!user.matches("[A-Za-z0-9_-]{1,64}") || token.length()<32 || token.length()>256) throw new Exception("Некорректный профиль");
        JSONArray values=p.getJSONArray("endpoints");
        if(values.length()<1 || values.length()>16) throw new Exception("Нужно от 1 до 16 серверов");
        for(int i=0;i<values.length();i++) {
            JSONObject e=values.getJSONObject(i);
            String host=e.getString("host"); int port=e.getInt("port");
            if(!host.matches("[A-Za-z0-9.-]{1,253}") || port<1 || port>65535) throw new Exception("Некорректный сервер");
            String path=e.getString("path");
            if(!path.matches("/[A-Za-z0-9][A-Za-z0-9/_-]{0,126}") || path.startsWith("/_"))throw new Exception("Некорректный путь");
            endpoints.add(new Endpoint(host,port,path));
        }
    }
}
