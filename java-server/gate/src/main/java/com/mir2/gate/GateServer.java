package com.mir2.gate;

import java.io.IOException; import java.net.*; import java.util.*; import java.util.concurrent.*; import java.util.function.BiConsumer;
/** Minimal three-listener access layer for PoC; Netty can replace the transport without changing GatePorts. */
public final class GateServer implements AutoCloseable {
 private final GatePorts ports; private final BiConsumer<ClientConnection,IOException> handler; private final ExecutorService acceptors=Executors.newVirtualThreadPerTaskExecutor(); private final List<ServerSocket> listeners=new CopyOnWriteArrayList<>(); private volatile boolean running;
 public GateServer(GatePorts ports,BiConsumer<ClientConnection,IOException> handler){this.ports=Objects.requireNonNull(ports);this.handler=Objects.requireNonNull(handler);}
 public synchronized void start() throws IOException {if(running)throw new IllegalStateException("already started"); try{for(GateKind kind:GateKind.values()){ServerSocket server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(ports.port(kind)));listeners.add(server);acceptors.submit(()->acceptLoop(server,kind));}running=true;}catch(IOException e){close();throw e;}}
 private void acceptLoop(ServerSocket server,GateKind kind){while(!server.isClosed()){try{Socket socket=server.accept(); ClientConnection c=new ClientConnection(socket,kind); acceptors.submit(()->handler.accept(c,null));}catch(IOException e){if(!server.isClosed())handler.accept(null,e);}}}
 public boolean isRunning(){return running;} public synchronized void close(){running=false;for(ServerSocket s:listeners)try{s.close();}catch(IOException ignored){}listeners.clear();acceptors.shutdownNow();}
}
