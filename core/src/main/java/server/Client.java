package server;

import java.net.InetAddress;

public class Client {

    private final int num;
    private final InetAddress ip;
    private final int port;
    private final String id;

    // Apariencia
    private String genero = "MASCULINO";
    private String estilo = "CLASICO";

    public Client(int num, InetAddress ip, int port) {
        this.num = num;
        this.ip = ip;
        this.port = port;
        this.id = buildId(ip, port);
    }

    // ✅ helper centralizado
    public static String buildId(InetAddress ip, int port) {
        return ip.toString() + ":" + port;
    }

    public int getNum() { return num; }
    public InetAddress getIp() { return ip; }
    public int getPort() { return port; }
    public String getId() { return id; }

    public String getGenero() { return genero; }
    public String getEstilo() { return estilo; }
    public void setGenero(String genero) { if (genero != null && !genero.isBlank()) this.genero = genero; }
    public void setEstilo(String estilo) { if (estilo != null && !estilo.isBlank()) this.estilo = estilo; }

    @Override
    public String toString() {
        return "Client{num=" + num + ", id=" + id + "}";
    }
}
