package server;

import java.net.InetAddress;

public class Client {
    private int num;
    private InetAddress ip;
    private int port;
    private String id;

    public Client(int num, InetAddress ip, int port) {
        this.num = num;
        this.ip = ip;
        this.port = port;
        this.id = ip.toString() + ":" + port;
    }

    public int getNum() { return num; }
    public InetAddress getIp() { return ip; }
    public int getPort() { return port; }
    public String getId() { return id; }
}
