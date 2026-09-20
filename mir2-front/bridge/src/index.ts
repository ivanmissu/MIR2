import { loadConfig } from './config.js';
import { BridgeWebSocketServer } from './ws/WebSocketServer.js';
import { MockMirServer } from './tcp/MockMirServer.js';
import net from 'net';

async function checkPortOpen(host: string, port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = new net.Socket();
    socket.setTimeout(500);
    socket.on('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.on('timeout', () => {
      socket.destroy();
      resolve(false);
    });
    socket.on('error', () => {
      socket.destroy();
      resolve(false);
    });
    socket.connect(port, host);
  });
}

async function main() {
  const config = loadConfig();
  console.log('[Bridge] Starting mir2-front Bridge Proxy with config:', config);

  // Check if java-server is running on loginPort
  const isServerRunning = await checkPortOpen(config.targetHost, config.loginPort);

  let mockServer: MockMirServer | null = null;
  if (config.enableMock || !isServerRunning) {
    if (!isServerRunning) {
      console.log(`[Bridge] No existing Mir2 TCP server detected at ${config.targetHost}:${config.loginPort}. Starting embedded MockMirServer...`);
    } else {
      console.log(`[Bridge] ENABLE_MOCK is set to true. Starting embedded MockMirServer...`);
    }
    mockServer = new MockMirServer(
      config.targetHost,
      config.loginPort,
      config.selectPort,
      config.gamePort
    );
    await mockServer.start();
    console.log(`[Bridge] MockMirServer active on ports: Login=${config.loginPort}, Select=${config.selectPort}, Game=${config.gamePort}`);
  } else {
    console.log(`[Bridge] Detected external Mir2 server at ${config.targetHost}:${config.loginPort}. Connecting directly.`);
  }

  const wsServer = new BridgeWebSocketServer(config);
  await wsServer.start();

  const shutdown = async () => {
    console.log('\n[Bridge] Shutting down...');
    await wsServer.stop();
    if (mockServer) {
      await mockServer.stop();
    }
    process.exit(0);
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => {
  console.error('[Bridge] Fatal startup error:', err);
  process.exit(1);
});
