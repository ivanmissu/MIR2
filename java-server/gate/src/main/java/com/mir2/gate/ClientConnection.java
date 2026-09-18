package com.mir2.gate;
import java.io.*; import java.net.*; import java.util.concurrent.atomic.AtomicBoolean;
/** Lifecycle wrapper. Protocol parsing stays at the boundary and never exposes raw streams to game code. */
public final class ClientConnection implements AutoCloseable {
 private final Socket socket; private final GateKind kind; private final AtomicBoolean open=new AtomicBoolean(true);
 ClientConnection(Socket s,GateKind k){socket=s;kind=k;}
 public SocketAddress remoteAddress(){return socket.getRemoteSocketAddress();} public GateKind kind(){return kind;} public InputStream input() throws IOException{return socket.getInputStream();} public OutputStream output() throws IOException{return socket.getOutputStream();} public boolean isOpen(){return open.get()&&!socket.isClosed();}
 @Override public void close(){if(open.getAndSet(false))try{socket.close();}catch(IOException ignored){}}
}
