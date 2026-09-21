import { WebSocketServer as WSServer, WebSocket } from 'ws';
import { ClientCommand, BridgeEvent } from '@mir2/shared';
import { GateSession } from '../session/GateSession.js';
import { BridgeConfig } from '../config.js';

export class BridgeWebSocketServer {
  private wss: WSServer | null = null;
  private sessions = new Map<WebSocket, GateSession>();

  constructor(private config: BridgeConfig) {}

  public start(): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        this.wss = new WSServer({
          port: this.config.wsPort,
          host: '0.0.0.0'
        });

        this.wss.on('listening', () => {
          console.log(`[Bridge] WebSocket proxy listening on ws://0.0.0.0:${this.config.wsPort}`);
          resolve();
        });

        this.wss.on('error', (err) => {
          console.error('[Bridge] WebSocket server error:', err);
          reject(err);
        });

        this.wss.on('connection', (ws: WebSocket, req) => {
          const clientIp = req.socket.remoteAddress || 'unknown';
          console.log(`[Bridge] New client connected from ${clientIp}`);

          const session = new GateSession({
            targetHost: this.config.targetHost,
            loginPort: this.config.loginPort,
            selectPort: this.config.selectPort,
            gamePort: this.config.gamePort,
            addressMode: this.config.addressMode
          });

          this.sessions.set(ws, session);

          // Forward session events to WebSocket client
          session.on('event', (event: BridgeEvent) => {
            if (ws.readyState === WebSocket.OPEN) {
              ws.send(JSON.stringify(event));
            }
          });

          // Handle client incoming commands
          ws.on('message', async (data) => {
            try {
              const text = data.toString();
              const command = JSON.parse(text) as ClientCommand;
              await this.handleClientCommand(session, command);
            } catch (err) {
              console.error('[Bridge] Error handling client message:', err);
              if (ws.readyState === WebSocket.OPEN) {
                ws.send(
                  JSON.stringify({
                    type: 'log',
                    level: 'error',
                    message: `指令执行异常: ${(err as Error).message}`,
                    timestamp: Date.now()
                  })
                );
              }
            }
          });

          ws.on('close', () => {
            console.log(`[Bridge] Client disconnected: ${clientIp}`);
            session.disconnect();
            this.sessions.delete(ws);
          });

          ws.on('error', (err) => {
            console.error(`[Bridge] Client socket error (${clientIp}):`, err);
            session.disconnect();
            this.sessions.delete(ws);
          });
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  public async stop(): Promise<void> {
    for (const session of this.sessions.values()) {
      session.disconnect();
    }
    this.sessions.clear();

    if (this.wss) {
      await new Promise<void>((resolve) => {
        this.wss!.close(() => resolve());
      });
      this.wss = null;
    }
  }

  private async handleClientCommand(session: GateSession, cmd: ClientCommand): Promise<void> {
    switch (cmd.type) {
      case 'login':
        await session.login(cmd.account, cmd.password, cmd.targetHost, cmd.targetPort);
        break;
      case 'queryCharacters':
        await session.queryCharacters();
        break;
      case 'createCharacter':
        await session.createCharacter(cmd.name, cmd.job, cmd.gender, cmd.hair);
        break;
      case 'deleteCharacter':
        await session.deleteCharacter(cmd.name);
        break;
      case 'selectCharacter':
        await session.selectCharacter(cmd.name);
        break;
      case 'walk':
        await session.walk(cmd.direction);
        break;
      case 'run':
        await session.run(cmd.direction);
        break;
      case 'turn':
        await session.turn(cmd.direction);
        break;
      case 'attack':
        await session.attack(cmd.kind || 'HIT', cmd.direction);
        break;
      case 'pickup':
        await session.pickup();
        break;
      case 'queryBagItems':
        await session.queryBagItems();
        break;
      case 'equip':
        await session.equip(cmd.slot, cmd.makeIndex, cmd.itemName);
        break;
      case 'unequip':
        await session.unequip(cmd.slot, cmd.makeIndex, cmd.itemName);
        break;
      case 'eat':
        await session.eat(cmd.makeIndex, cmd.itemName);
        break;
      case 'drop':
        await session.drop(cmd.makeIndex, cmd.itemName);
        break;
      case 'merchantLabel':
        await session.selectMerchantLabel(cmd.merchantId ?? 1, cmd.label);
        break;
      case 'queryRepairCost':
        await session.queryRepairCost(cmd.makeIndex, cmd.itemName);
        break;
      case 'repairItem':
        await session.repairItem(cmd.makeIndex, cmd.itemName);
        break;
      case 'say':
        await session.say(cmd.message);
        break;
      case 'openDoor':
        await session.openDoor(cmd.x, cmd.y);
        break;
      case 'disconnect':
        session.disconnect();
        break;
      default:
        console.warn('[Bridge] Unknown command received:', (cmd as any).type);
    }
  }
}
