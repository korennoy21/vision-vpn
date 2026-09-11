package dev.sever.vpn;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Applies Android per-app split tunnelling to WG/AWG configs without exposing secrets. */
final class RoutingConfigInjector {
    private RoutingConfigInjector() {}

    static String apply(String raw, Context context) {
        String mode = RoutingPreferences.mode(context);
        Set<String> packages = RoutingPreferences.packages(context);
        if (RoutingPreferences.MODE_ALL.equals(mode) || packages.isEmpty()) return stripRoutingKeys(raw);
        String cleaned = stripRoutingKeys(raw);
        String key = RoutingPreferences.MODE_ONLY.equals(mode) ? "IncludedApplications" : "ExcludedApplications";
        StringBuilder apps = new StringBuilder();
        for (String p : packages) {
            if (p.equals(context.getPackageName())) continue;
            if (apps.length() > 0) apps.append(", ");
            apps.append(p);
        }
        if (apps.length() == 0) return cleaned;
        String line = key + " = " + apps;
        int section = indexOfIgnoreCase(cleaned, "[Interface]");
        if (section < 0) return cleaned;
        int endLine = cleaned.indexOf('\n', section);
        if (endLine < 0) return cleaned + "\n" + line + "\n";
        return cleaned.substring(0, endLine + 1) + line + "\n" + cleaned.substring(endLine + 1);
    }

    private static String stripRoutingKeys(String raw) {
        String[] lines = raw.replace("\r\n", "\n").replace('\r','\n').split("\n", -1);
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim().toLowerCase();
            if (t.startsWith("includedapplications") || t.startsWith("excludedapplications")) continue;
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    private static int indexOfIgnoreCase(String value, String needle) {
        return value.toLowerCase().indexOf(needle.toLowerCase());
    }
}
