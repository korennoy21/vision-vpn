package dev.sever.vpn;

import android.content.Context;
import android.net.VpnService;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** IPv4 route compiler used by VISION Secure. Domain rules resolve against the current physical network. */
final class RoutePlanner {
    private RoutePlanner() {}

    static void apply(VpnService.Builder builder, Context context, NetworkTracker network) throws Exception {
        RoutingPreferences.applyApplications(builder, context);
        List<Cidr> includes = parse(RoutingPreferences.vpnCidrs(context));
        List<Cidr> excludes = parse(RoutingPreferences.bypassCidrs(context));
        for (String domain : RoutingPreferences.vpnDomains(context)) resolve(includes, domain, network);
        for (String domain : RoutingPreferences.bypassDomains(context)) resolve(excludes, domain, network);
        if (RoutingPreferences.allowLocalNetwork(context)) {
            excludes.add(Cidr.parse("10.0.0.0/8"));
            excludes.add(Cidr.parse("172.16.0.0/12"));
            excludes.add(Cidr.parse("192.168.0.0/16"));
            excludes.add(Cidr.parse("169.254.0.0/16"));
        }
        if (RoutingPreferences.ROUTE_ONLY.equals(RoutingPreferences.routeMode(context))) {
            if (includes.isEmpty()) throw new Exception("В режиме «только правила» добавьте IP/CIDR или домены через VPN");
            for (Cidr c : normalize(includes)) builder.addRoute(c.address(), c.prefix);
        } else {
            for (Cidr c : complement(normalize(excludes))) builder.addRoute(c.address(), c.prefix);
        }
    }

    private static void resolve(List<Cidr> out, String domain, NetworkTracker network) {
        try {
            String d = domain.trim().toLowerCase(Locale.ROOT);
            if (!d.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) return;
            for (InetAddress ip : network.resolve(d)) if (ip.getAddress().length == 4) out.add(Cidr.parse(ip.getHostAddress() + "/32"));
        } catch (Exception ignored) {}
    }

    private static List<Cidr> parse(Set<String> values) throws Exception {
        List<Cidr> result = new ArrayList<>(); for (String v : values) result.add(Cidr.parse(v)); return result;
    }

    private static List<Cidr> normalize(List<Cidr> values) {
        List<Cidr> sorted = new ArrayList<>(values); sorted.sort(Comparator.comparingLong((Cidr c)->c.start).thenComparingLong(c->c.end));
        List<Cidr> result = new ArrayList<>();
        long start=-1,end=-1;
        for(Cidr c:sorted){ if(start<0){start=c.start;end=c.end;} else if(c.start<=end+1){end=Math.max(end,c.end);} else {result.addAll(rangeToCidrs(start,end));start=c.start;end=c.end;} }
        if(start>=0)result.addAll(rangeToCidrs(start,end)); return result;
    }

    private static List<Cidr> complement(List<Cidr> excluded) {
        List<Cidr> result = new ArrayList<>(); long cursor=0, max=0xffffffffL;
        for(Cidr c:excluded){ if(c.end<cursor)continue; if(c.start>cursor)result.addAll(rangeToCidrs(cursor,Math.min(max,c.start-1))); cursor=Math.max(cursor,c.end+1); if(cursor>max)break; }
        if(cursor<=max)result.addAll(rangeToCidrs(cursor,max)); return result;
    }

    private static List<Cidr> rangeToCidrs(long start,long end){
        ArrayList<Cidr> result=new ArrayList<>(); long cur=start;
        while(cur<=end){
            long block = cur==0 ? (1L<<32) : Long.lowestOneBit(cur);
            long remaining=end-cur+1;
            while(block>remaining)block>>=1;
            int prefix=32-Long.numberOfTrailingZeros(block);
            result.add(new Cidr(cur,cur+block-1,prefix));
            cur+=block;
        }
        return result;
    }

    static final class Cidr {
        final long start,end; final int prefix;
        Cidr(long start,long end,int prefix){this.start=start;this.end=end;this.prefix=prefix;}
        static Cidr parse(String value) throws Exception {
            String[] p=value.trim().split("/",-1); if(p.length<1||p.length>2)throw new Exception("Некорректный CIDR: "+value);
            byte[] b=InetAddress.getByName(p[0]).getAddress(); if(b.length!=4)throw new Exception("Пока поддерживается IPv4 CIDR: "+value);
            int prefix=p.length==2?Integer.parseInt(p[1]):32; if(prefix<0||prefix>32)throw new Exception("Некорректный CIDR: "+value);
            long ip=((b[0]&255L)<<24)|((b[1]&255L)<<16)|((b[2]&255L)<<8)|(b[3]&255L);
            long mask=prefix==0?0:(0xffffffffL << (32-prefix)) & 0xffffffffL; long start=ip&mask; long end=start|(~mask&0xffffffffL);
            return new Cidr(start,end,prefix);
        }
        String address(){return String.format(Locale.ROOT,"%d.%d.%d.%d",(start>>24)&255,(start>>16)&255,(start>>8)&255,start&255);}
    }
}
