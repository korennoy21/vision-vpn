package dev.sever.vpn;

/** One engine owns the OS tunnel at a time. close() must cancel connection work. */
public interface TunnelEngine extends AutoCloseable {
    void start();
    @Override void close();
}
