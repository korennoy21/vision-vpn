package dev.sever.vpn;

import android.content.Context;
import android.net.*;
import java.io.IOException;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/** Tracks a validated physical (NOT_VPN) network for stable Wi-Fi/LTE handover. */
public final class NetworkTracker implements AutoCloseable {
    public interface Listener { void onPhysicalNetworkChanged(long generation); }
    private final ConnectivityManager cm;
    private final ConnectivityManager.NetworkCallback callback;
    private final AtomicLong generation = new AtomicLong();
    private volatile Network current;
    private volatile Listener listener;

    public NetworkTracker(Context context) {
        cm = context.getSystemService(ConnectivityManager.class);
        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { choose(network); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) choose(network);
            }
            @Override public void onLost(Network network) {
                if (network.equals(current)) { current = bestNetwork(); changed(); }
            }
        };
        cm.registerNetworkCallback(request, callback);
        current = bestNetwork();
        updateNetworkLabel(current);
    }

    public void setListener(Listener value) { listener = value; }
    public long generation() { return generation.get(); }
    public Network current() { Network n = current; return n != null ? n : bestNetwork(); }

    public InetAddress[] resolve(String host) throws UnknownHostException {
        Network n = current(); return n != null ? n.getAllByName(host) : InetAddress.getAllByName(host);
    }

    /** Must be called on an unconnected socket. A local bind is intentional before VpnService.protect(). */
    public void bindPhysical(Socket socket) throws IOException {
        Network n = current(); if (n != null) n.bindSocket(socket);
    }

    private synchronized void choose(Network candidate) {
        NetworkCapabilities c = cm.getNetworkCapabilities(candidate);
        if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return;
        Network old = current;
        if (old == null) {
            current = candidate; updateNetworkLabel(candidate); changed();
            return;
        }
        if (old.equals(candidate)) {
            updateNetworkLabel(candidate);
            return;
        }
        if (better(candidate, old)) {
            current = candidate; updateNetworkLabel(candidate); changed();
        }
    }

    private boolean better(Network a, Network b) {
        NetworkCapabilities ca = cm.getNetworkCapabilities(a), cb = cm.getNetworkCapabilities(b);
        if (ca == null) return false; if (cb == null) return true;
        boolean av = ca.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), bv = cb.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (av != bv) return av;
        boolean aw = ca.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), bw = cb.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        return aw && !bw;
    }

    private Network bestNetwork() {
        Network best = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue;
            if (best == null || better(n, best)) best = n;
        }
        return best;
    }

    private void changed() {
        long g = generation.incrementAndGet(); Listener l = listener; if (l != null) l.onPhysicalNetworkChanged(g);
    }

    private void updateNetworkLabel(Network n) {
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        if (c == null) VisionState.network = "offline";
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) VisionState.network = "wifi";
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) VisionState.network = "cellular";
        else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) VisionState.network = "ethernet";
        else VisionState.network = "other";
    }

    @Override public void close() { try { cm.unregisterNetworkCallback(callback); } catch (Exception ignored) {} }
}
