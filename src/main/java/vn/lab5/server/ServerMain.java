package vn.lab5.server;

import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import vn.lab5.common.*;

public final class ServerMain {
  public static void main(String[] args) throws Exception {
    Map<String, String> o = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      if (i + 1 >= args.length || !args[i].startsWith("--"))
        throw new IllegalArgumentException("Use --key value");
      String key = args[i].substring(2);
      if (!Set.of("id", "host", "port", "service-port", "peer-host", "peer-port", "db")
              .contains(key)
          || o.put(key, args[i + 1]) != null)
        throw new IllegalArgumentException("Unknown/duplicate option: " + key);
    }
    for (String k : List.of("id", "host", "port", "service-port", "peer-host", "peer-port", "db"))
      if (!o.containsKey(k) || o.get(k).isBlank())
        throw new IllegalArgumentException("Missing --" + k);
    System.setProperty("java.rmi.server.hostname", o.get("host"));
    System.setProperty("sun.rmi.transport.tcp.responseTimeout", "5000");
    System.setProperty("sun.rmi.transport.connectionTimeout", "5000");
    System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", "5000");
    int port = parsePort(o.get("port")), servicePort = parsePort(o.get("service-port"));
    Store store = new Store(o.get("db"), o.get("id"));
    Service service = new Service(store, o.get("id"));
    Registry registry = LocateRegistry.createRegistry(port);
    registry.rebind(NoteService.BINDING, UnicastRemoteObject.exportObject(service, servicePort));
    SyncWorker worker =
        new SyncWorker(store, o.get("id"), o.get("peer-host"), parsePort(o.get("peer-port")));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  worker.close();
                  try {
                    UnicastRemoteObject.unexportObject(service, true);
                    UnicastRemoteObject.unexportObject(registry, true);
                    store.close();
                  } catch (Exception e) {
                    e.printStackTrace();
                  }
                }));
    worker.start(2000);
    System.out.println(
        "READY "
            + o.get("id")
            + " registry="
            + port
            + " service="
            + servicePort
            + " db="
            + o.get("db"));
    new CountDownLatch(1).await();
  }

  private static int parsePort(String s) {
    int p = Integer.parseInt(s);
    if (p < 1 || p > 65535) throw new IllegalArgumentException("Port must be 1..65535");
    return p;
  }
}
