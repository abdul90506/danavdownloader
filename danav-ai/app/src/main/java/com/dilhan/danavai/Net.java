package com.dilhan.danavai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Network helpers for Danav AI.
 *
 * Two jobs:
 *  1. Address order. Many mobile networks (and some ISPs) hand out an IPv6 address that the AI service
 *     geolocates wrongly — the request then comes back "User location is not supported for the API use"
 *     even though the country is supported. So every request tries the IPv4 addresses first and only
 *     falls back to IPv6 when there is no working IPv4 route. Nothing else about the request changes.
 *  2. A plain-language network/DNS summary, used by the Settings connection test so a failure always
 *     says what actually happened instead of a vague message.
 */
final class Net {
    static final String HOST = "generativelanguage.googleapis.com";

    private Net() {}

    /** IPv4 addresses first, then the rest — the order is what keeps a bad IPv6 route from breaking calls. */
    static List<InetAddress> order(String host) throws UnknownHostException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> v4 = new ArrayList<>(), v6 = new ArrayList<>();
        for (InetAddress a : all) {
            if (a instanceof Inet4Address) v4.add(a); else v6.add(a);
        }
        v4.addAll(v6);
        if (v4.isEmpty()) throw new UnknownHostException(host);
        return v4;
    }

    /** DNS for OkHttp (chat + the live voice call). */
    static okhttp3.Dns dns() {
        return host -> {
            List<InetAddress> list = order(host);      // throws UnknownHostException, exactly like the system one
            return list;
        };
    }

    /** "net=wifi dns=2v0" — a short, honest picture of what the phone's network is doing right now. */
    static String summary(Context c) {
        StringBuilder s = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm == null || cm.getActiveNetwork() == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) s.append("net=none");
            else if (!nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) s.append("net=no-internet");
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.append("net=wifi");
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.append("net=mobile");
            else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.append("net=ethernet");
            else s.append("net=other");
            if (nc != null && !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s.append("(unverified)");
        } catch (Throwable t) { s.append("net=?"); }
        try {
            InetAddress[] all = InetAddress.getAllByName(HOST);
            int v4 = 0, v6 = 0;
            for (InetAddress a : all) { if (a instanceof Inet4Address) v4++; else v6++; }
            s.append(" dns=").append(v4).append('v').append(v6);
            if (all.length == 0) s.append("(empty)");
        } catch (Throwable t) { s.append(" dns!").append(describe(t)); }
        return s.toString();
    }

    /** Exception → one readable word, never an empty string (so no failure is ever silent again). */
    static String describe(Throwable t) {
        if (t == null) return "unknown";
        String n = t.getClass().getSimpleName();
        String m = t.getMessage();
        if (m == null || m.isEmpty()) return n;
        if (m.length() > 120) m = m.substring(0, 120);
        return n + ":" + m;
    }
}
