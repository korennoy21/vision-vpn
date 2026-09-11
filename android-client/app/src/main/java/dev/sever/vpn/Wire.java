package dev.sever.vpn;

import java.io.*;
import java.security.SecureRandom;
import java.util.*;

public final class Wire {
    public static final int AUTH=1, CONFIG=2, PACKET=3, PING=4, PONG=5, CLOSE=6, MTU=1280;
    public static final int MAX_FRAME=4615;
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final class Frame {
        public final int kind; public final byte[] data;
        Frame(int kind, byte[] data) { this.kind=kind; this.data=data; }
    }
    public static byte[] encode(int kind, byte[] data) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); write(out,kind,data); return out.toByteArray();
    }
    public static void write(OutputStream stream, int kind, byte[] data) throws IOException {
        if (kind < 1 || kind > 6 || data.length > 4096) throw new IOException("Invalid frame");
        // Data packets are intentionally unpadded in VISION performance mode. Control frames keep
        // small random padding for wire compatibility without wasting up to 511 bytes per IP packet.
        int padding = kind==PACKET ? 0 : RANDOM.nextInt(96);
        DataOutputStream out = new DataOutputStream(stream);
        out.writeByte(1); out.writeByte(kind); out.writeShort(data.length); out.writeShort(padding); out.writeShort(0);
        out.write(data);
        if(padding>0){byte[] pad=new byte[padding];RANDOM.nextBytes(pad);out.write(pad);}
        out.flush();
    }
    public static Frame read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int version=in.readUnsignedByte(), kind=in.readUnsignedByte();
        int length=in.readUnsignedShort(), padding=in.readUnsignedShort(), reserved=in.readUnsignedShort();
        if(version!=1 || kind<1 || kind>6 || length>4096 || padding>511 || reserved!=0) throw new IOException("Invalid frame header");
        byte[] payload=new byte[length], pad=new byte[padding]; in.readFully(payload); in.readFully(pad); return new Frame(kind,payload);
    }
    public static Frame decode(byte[] bytes) throws IOException {
        List<Frame> frames=decodeMany(bytes); if(frames.size()!=1)throw new IOException("One frame required"); return frames.get(0);
    }
    public static List<Frame> decodeMany(byte[] bytes) throws IOException {
        if(bytes.length<8 || bytes.length>65536)throw new IOException("Invalid message size");
        ByteArrayInputStream input=new ByteArrayInputStream(bytes); ArrayList<Frame> frames=new ArrayList<>();
        while(input.available()>0){ if(input.available()<8)throw new IOException("Truncated frame"); frames.add(read(input)); }
        return frames;
    }
    public static byte[] address(String value) throws IOException {
        String[] parts=value.split("\\.",-1); if(parts.length!=4)throw new IOException("IPv4 required"); byte[] bytes=new byte[4];
        for(int i=0;i<4;i++){if(!parts[i].matches("[0-9]{1,3}"))throw new IOException("Invalid IPv4");int n=Integer.parseInt(parts[i]);if(n>255)throw new IOException("Invalid IPv4");bytes[i]=(byte)n;}return bytes;
    }
    public static void validatePacket(byte[] p, byte[] address, boolean source) throws IOException {
        if(p.length<20 || p.length>MTU || (p[0]&255)>>4!=4)throw new IOException("Invalid IPv4 packet");
        int ihl=(p[0]&15)*4,total=((p[2]&255)<<8)|(p[3]&255);if(ihl<20||ihl>p.length||total!=p.length)throw new IOException("Invalid packet length");
        if(!Arrays.equals(address,Arrays.copyOfRange(p,source?12:16,source?16:20)))throw new IOException("Packet address mismatch");
    }
}
