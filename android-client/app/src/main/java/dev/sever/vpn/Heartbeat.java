package dev.sever.vpn;
import java.security.SecureRandom;

public final class Heartbeat {
    private final SecureRandom random=new SecureRandom();
    private long idleInterval,idleDeadline,probeDeadline;
    public Heartbeat(){pingSent();}
    public synchronized void packetSent(){idleDeadline=System.nanoTime()+idleInterval;}
    public synchronized void pingSent(){
        long now=System.nanoTime();
        idleInterval=(20000L+random.nextInt(15001))*1000000L;
        idleDeadline=now+idleInterval;
        probeDeadline=now+(50000L+random.nextInt(15001))*1000000L;
    }
    public synchronized boolean due(){return System.nanoTime()>=Math.min(idleDeadline,probeDeadline);}
}
