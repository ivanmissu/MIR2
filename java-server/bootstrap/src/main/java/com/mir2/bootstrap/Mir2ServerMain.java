package com.mir2.bootstrap;

/** Executable entry point used by the shaded JAR and container image. */
public final class Mir2ServerMain {
  private Mir2ServerMain() {}

  public static void main(String[] args) {
    try {
      ServerConfig config = ServerConfig.fromEnvironment();
      Mir2Server server = new Mir2Server(config);
      Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mir2-shutdown"));
      server.start();
      server.awaitShutdown();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception error) {
      System.err.println("MIR2 server failed to start: " + error.getMessage());
      error.printStackTrace(System.err);
      System.exit(1);
    }
  }
}
