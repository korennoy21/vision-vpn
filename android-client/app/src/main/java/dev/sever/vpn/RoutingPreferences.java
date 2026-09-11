package dev.sever.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.VpnService;
import java.util.LinkedHashSet;
import java.util.Set;

/** Device-local split-routing policy shared by VISION/WG/AWG engines. */
public final class RoutingPreferences {
    public static final String MODE_ALL = "all";
    public static final String MODE_ONLY = "only";
    public static final String MODE_BYPASS = "bypass";
    public static final String ROUTE_ALL = "all";
    public static final String ROUTE_ONLY = "only_rules";
    private static final String PREFS = "vision_routing";

    private RoutingPreferences() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public static String mode(Context context) { return prefs(context).getString("mode", MODE_ALL); }
    public static void setMode(Context context, String mode) {
        if (!MODE_ALL.equals(mode) && !MODE_ONLY.equals(mode) && !MODE_BYPASS.equals(mode)) mode = MODE_ALL;
        prefs(context).edit().putString("mode", mode).apply();
    }

    public static String routeMode(Context context) { return prefs(context).getString("route_mode", ROUTE_ALL); }
    public static void setRouteMode(Context context, String mode) {
        prefs(context).edit().putString("route_mode", ROUTE_ONLY.equals(mode) ? ROUTE_ONLY : ROUTE_ALL).apply();
    }

    public static Set<String> packages(Context context) { return set(context, "packages"); }
    public static void setPackages(Context context, Set<String> values) { set(context, "packages", values); }
    public static Set<String> vpnCidrs(Context context) { return set(context, "vpn_cidrs"); }
    public static void setVpnCidrs(Context context, Set<String> values) { set(context, "vpn_cidrs", values); }
    public static Set<String> bypassCidrs(Context context) { return set(context, "bypass_cidrs"); }
    public static void setBypassCidrs(Context context, Set<String> values) { set(context, "bypass_cidrs", values); }
    public static Set<String> vpnDomains(Context context) { return set(context, "vpn_domains"); }
    public static void setVpnDomains(Context context, Set<String> values) { set(context, "vpn_domains", values); }
    public static Set<String> bypassDomains(Context context) { return set(context, "bypass_domains"); }
    public static void setBypassDomains(Context context, Set<String> values) { set(context, "bypass_domains", values); }
    public static boolean allowLocalNetwork(Context context) { return prefs(context).getBoolean("allow_local", true); }
    public static void setAllowLocalNetwork(Context context, boolean value) { prefs(context).edit().putBoolean("allow_local", value).apply(); }

    private static Set<String> set(Context c, String key) { return new LinkedHashSet<>(prefs(c).getStringSet(key, new LinkedHashSet<>())); }
    private static void set(Context c, String key, Set<String> values) { prefs(c).edit().putStringSet(key, new LinkedHashSet<>(values)).apply(); }

    public static void applyApplications(VpnService.Builder builder, Context context) throws Exception {
        String mode = mode(context); Set<String> packages = packages(context);
        if (MODE_ONLY.equals(mode)) {
            for (String packageName : packages) if (!packageName.equals(context.getPackageName())) builder.addAllowedApplication(packageName);
        } else if (MODE_BYPASS.equals(mode)) {
            for (String packageName : packages) if (!packageName.equals(context.getPackageName())) builder.addDisallowedApplication(packageName);
        }
    }

    /** Legacy name retained for existing VISION service call sites. */
    public static void apply(VpnService.Builder builder, Context context) throws Exception { applyApplications(builder, context); }
}
