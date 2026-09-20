import net from 'net';
import { ProtocolConstants, Direction, DirectionOffsets, Job, Gender } from '@mir2/shared';
import { DefaultMessage } from '../protocol/DefaultMessage.js';
import { WireMessageCodec, WirePacket } from '../protocol/WireMessageCodec.js';
import { CharacterDescription } from '../protocol/CharacterDescription.js';
import { ClientItemCodec } from '../protocol/ClientItemCodec.js';

interface MockAccount {
  account: string;
  password: string;
  characters: MockCharacter[];
}

interface MockCharacter {
  name: string;
  job: Job;
  hair: number;
  level: number;
  gender: Gender;
  hp: number;
  maxHp: number;
  mp: number;
  maxMp: number;
  exp: number;
  backpack: any[];
}

interface MockWorldObject {
  id: number;
  name: string;
  type: 'PLAYER' | 'MONSTER';
  mapId: string;
  x: number;
  y: number;
  direction: Direction;
  feature: number;
  status: number;
  hp: number;
  maxHp: number;
  mp: number;
  maxMp: number;
  connection?: MockGameConnection;
}

interface MockGroundItem {
  id: number;
  name: string;
  looks: number;
  mapId: string;
  x: number;
  y: number;
  backpackItem: any;
}

interface MockGameConnection {
  socket: net.Socket;
  playerId: number;
  account: string;
  characterName: string;
  certification: number;
}

export class MockMirServer {
  private loginServer: net.Server | null = null;
  private selectServer: net.Server | null = null;
  private gameServer: net.Server | null = null;

  private accounts = new Map<string, MockAccount>();
  private activeCertifications = new Map<number, { account: string; selectedCharacter?: string }>();
  private nextCert = 1000;
  private nextObjectId = 1001;
  private nextItemId = 5001;

  private worldObjects = new Map<number, MockWorldObject>();
  private groundItems = new Map<number, MockGroundItem>();
  private gameConnections = new Map<number, MockGameConnection>();

  constructor(
    public readonly host = '127.0.0.1',
    public readonly loginPort = 7000,
    public readonly selectPort = 7100,
    public readonly gamePort = 7200
  ) {
    this.seedDefaultData();
  }

  private seedDefaultData() {
    // Seed default account "hero" / "123456"
    this.accounts.set('hero', {
      account: 'hero',
      password: '123456',
      characters: [
        {
          name: '传奇战士',
          job: Job.WARRIOR,
          hair: 1,
          level: 1,
          gender: Gender.MALE,
          hp: 100,
          maxHp: 100,
          mp: 50,
          maxMp: 50,
          exp: 0,
          backpack: [
            {
              item: {
                name: '木剑',
                stdMode: 5,
                shape: 0,
                weight: 2,
                aniCount: 0,
                source: 0,
                needIdentify: 0,
                looks: 1,
                duraMax: 20,
                ac: 0,
                mac: 0,
                dc: 0x00050002,
                mc: 0,
                sc: 0,
                need: 0,
                needLevel: 0,
                price: 400
              },
              makeIndex: 101,
              dura: 20,
              duraMax: 20
            }
          ]
        }
      ]
    });

    // Seed initial monsters
    this.spawnMonsters();
  }

  private spawnMonsters() {
    this.worldObjects.set(2001, {
      id: 2001,
      name: '鸡',
      type: 'MONSTER',
      mapId: '0',
      x: 12,
      y: 10,
      direction: Direction.LEFT,
      feature: 1, // monster feature
      status: 0,
      hp: 6,
      maxHp: 6,
      mp: 0,
      maxMp: 0
    });

    this.worldObjects.set(2002, {
      id: 2002,
      name: '鸡',
      type: 'MONSTER',
      mapId: '0',
      x: 10,
      y: 12,
      direction: Direction.UP,
      feature: 1,
      status: 0,
      hp: 6,
      maxHp: 6,
      mp: 0,
      maxMp: 0
    });

    this.worldObjects.set(2003, {
      id: 2003,
      name: '鹿',
      type: 'MONSTER',
      mapId: '0',
      x: 14,
      y: 14,
      direction: Direction.DOWN,
      feature: 2,
      status: 0,
      hp: 15,
      maxHp: 15,
      mp: 0,
      maxMp: 0
    });
  }

  public async start(): Promise<void> {
    await this.startLoginServer();
    await this.startSelectServer();
    await this.startGameServer();
  }

  public async stop(): Promise<void> {
    const closes: Promise<void>[] = [];
    if (this.loginServer) {
      closes.push(new Promise(res => this.loginServer!.close(() => res())));
    }
    if (this.selectServer) {
      closes.push(new Promise(res => this.selectServer!.close(() => res())));
    }
    if (this.gameServer) {
      closes.push(new Promise(res => this.gameServer!.close(() => res())));
    }
    for (const conn of this.gameConnections.values()) {
      conn.socket.destroy();
    }
    this.gameConnections.clear();
    await Promise.all(closes);
  }

  // --------------------------------------------------------------------------
  // LOGIN SERVER (Port 7000)
  // --------------------------------------------------------------------------
  private startLoginServer(): Promise<void> {
    return new Promise(resolve => {
      this.loginServer = net.createServer(socket => {
        let sessionToken: string | null = null;
        let sessionAccount: string | null = null;
        let cert: number | null = null;

        this.handleFramedSocket(socket, packet => {
          const ident = packet.message.ident;

          if (ident === ProtocolConstants.CM_PROTOCOL) {
            this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_CERTIFICATION_SUCCESS, 0, 0, 0));
            return;
          }

          if (ident === ProtocolConstants.CM_IDPASSWORD) {
            const body = WireMessageCodec.decodeBody(packet.encodedBody);
            const [account, password] = body.split('/');

            // Auto-register account if it does not exist yet (convenient for testing)
            let acc = this.accounts.get(account);
            if (!acc) {
              acc = { account, password, characters: [] };
              this.accounts.set(account, acc);
            }

            if (acc.password !== password) {
              this.sendPacket(socket, new DefaultMessage(-1, ProtocolConstants.SM_PASSWD_FAIL, 0, 0, 0));
              return;
            }

            sessionAccount = account;
            cert = ++this.nextCert;
            this.activeCertifications.set(cert, { account });

            this.sendPacket(
              socket,
              new DefaultMessage(0, ProtocolConstants.SM_PASSOK_SELECTSERVER, 0, 0, 1),
              'MIR2/1/'
            );
            return;
          }

          if (ident === ProtocolConstants.CM_SELECTSERVER) {
            if (!sessionAccount || !cert) {
              this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_STARTFAIL, 0, 0, 0));
              return;
            }
            const body = `${this.host}/${this.selectPort}/${cert}`;
            this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_SELECTSERVER_OK, 0, 0, 0), body);
            return;
          }

          this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_CERTIFICATION_FAIL, 0, 0, 0));
        });
      });

      this.loginServer.listen(this.loginPort, this.host, () => {
        resolve();
      });
    });
  }

  // --------------------------------------------------------------------------
  // SELECT SERVER (Port 7100)
  // --------------------------------------------------------------------------
  private startSelectServer(): Promise<void> {
    return new Promise(resolve => {
      this.selectServer = net.createServer(socket => {
        let currentAccount: string | null = null;
        let currentCert: number | null = null;

        this.handleFramedSocket(socket, packet => {
          const ident = packet.message.ident;
          const body = WireMessageCodec.decodeBody(packet.encodedBody);

          if (ident === ProtocolConstants.CM_QUERYCHR) {
            const [account, certStr] = body.split('/');
            const cert = parseInt(certStr, 10);
            const registered = this.activeCertifications.get(cert);
            if (!registered || registered.account !== account) {
              this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_OUTOFCONNECTION, 0, 0, 0));
              return;
            }
            currentAccount = account;
            currentCert = cert;

            const acc = this.accounts.get(account);
            const chars = acc ? acc.characters : [];
            let listBody = '';
            for (const c of chars) {
              listBody += `${c.name}/${c.job}/${c.hair}/${c.level}/${c.gender}/`;
            }
            this.sendPacket(
              socket,
              new DefaultMessage(chars.length, ProtocolConstants.SM_QUERYCHR, 0, 1, 0),
              listBody
            );
            return;
          }

          if (ident === ProtocolConstants.CM_NEWCHR) {
            const [account, name, jobStr, hairStr, genderStr] = body.split('/');
            if (!currentAccount || currentAccount !== account) {
              this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_NEWCHR_FAIL, 0, 0, 0));
              return;
            }
            const acc = this.accounts.get(account)!;
            const newChar: MockCharacter = {
              name,
              job: parseInt(jobStr, 10) || Job.WARRIOR,
              hair: parseInt(hairStr, 10) || 1,
              gender: parseInt(genderStr, 10) || Gender.MALE,
              level: 1,
              hp: 100,
              maxHp: 100,
              mp: 50,
              maxMp: 50,
              exp: 0,
              backpack: [
                {
                  item: {
                    name: '木剑',
                    stdMode: 5,
                    shape: 0,
                    weight: 2,
                    aniCount: 0,
                    source: 0,
                    needIdentify: 0,
                    looks: 1,
                    duraMax: 20,
                    ac: 0,
                    mac: 0,
                    dc: 0x00050002,
                    mc: 0,
                    sc: 0,
                    need: 0,
                    needLevel: 0,
                    price: 400
                  },
                  makeIndex: ++this.nextItemId,
                  dura: 20,
                  duraMax: 20
                }
              ]
            };
            acc.characters.push(newChar);
            this.sendPacket(socket, new DefaultMessage(newChar.level, ProtocolConstants.SM_NEWCHR_SUCCESS, 0, 0, 0));
            return;
          }

          if (ident === ProtocolConstants.CM_DELCHR) {
            const name = body;
            if (!currentAccount) {
              this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_DELCHR_FAIL, 0, 0, 0));
              return;
            }
            const acc = this.accounts.get(currentAccount)!;
            acc.characters = acc.characters.filter(c => c.name !== name);
            this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_DELCHR_SUCCESS, 0, 0, 0));
            return;
          }

          if (ident === ProtocolConstants.CM_SELCHR) {
            const [account, name] = body.split('/');
            if (!currentAccount || currentAccount !== account || !currentCert) {
              this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_OUTOFCONNECTION, 0, 0, 0));
              return;
            }
            const certInfo = this.activeCertifications.get(currentCert);
            if (certInfo) {
              certInfo.selectedCharacter = name;
            }
            this.sendPacket(
              socket,
              new DefaultMessage(0, ProtocolConstants.SM_STARTPLAY, 0, 0, 0),
              `${this.host}/${this.gamePort}`
            );
            return;
          }
        });
      });

      this.selectServer.listen(this.selectPort, this.host, () => {
        resolve();
      });
    });
  }

  // --------------------------------------------------------------------------
  // GAME SERVER (Port 7200)
  // --------------------------------------------------------------------------
  private startGameServer(): Promise<void> {
    return new Promise(resolve => {
      this.gameServer = net.createServer(socket => {
        let authenticated = false;
        let playerId = 0;

        let buffer = Buffer.alloc(0);

        socket.on('data', chunk => {
          buffer = Buffer.concat([buffer, chunk]);

          while (buffer.length > 0) {
            const startIdx = buffer.indexOf(WireMessageCodec.START);
            if (startIdx === -1) break;
            if (startIdx > 0) buffer = buffer.subarray(startIdx);

            const endIdx = buffer.indexOf(WireMessageCodec.END);
            if (endIdx === -1) break;

            const payload = buffer.subarray(1, endIdx);
            buffer = buffer.subarray(endIdx + 1);

            if (!authenticated) {
              try {
                const runLogin = WireMessageCodec.parseRunLogin(payload);
                const certInfo = this.activeCertifications.get(runLogin.certification);
                if (!certInfo || certInfo.account !== runLogin.account) {
                  this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_STARTFAIL, 0, 0, 0));
                  socket.destroy();
                  return;
                }

                authenticated = true;
                playerId = ++this.nextObjectId;

                const acc = this.accounts.get(runLogin.account);
                const charData = acc?.characters.find(c => c.name === runLogin.characterName) || {
                  name: runLogin.characterName,
                  job: Job.WARRIOR,
                  hair: 1,
                  level: 1,
                  gender: Gender.MALE,
                  hp: 100,
                  maxHp: 100,
                  mp: 50,
                  maxMp: 50,
                  exp: 0,
                  backpack: []
                };

                const playerObj: MockWorldObject = {
                  id: playerId,
                  name: charData.name,
                  type: 'PLAYER',
                  mapId: '0',
                  x: 10,
                  y: 10,
                  direction: Direction.DOWN,
                  feature: 0x01050100, // human feature
                  status: 0,
                  hp: charData.hp,
                  maxHp: charData.maxHp,
                  mp: charData.mp,
                  maxMp: charData.maxMp,
                  connection: {
                    socket,
                    playerId,
                    account: runLogin.account,
                    characterName: runLogin.characterName,
                    certification: runLogin.certification
                  }
                };

                this.worldObjects.set(playerId, playerObj);
                this.gameConnections.set(playerId, playerObj.connection!);

                // 1. SM_NEWMAP
                this.sendPacket(
                  socket,
                  new DefaultMessage(playerId, ProtocolConstants.SM_NEWMAP, playerObj.x, playerObj.y, 0),
                  '0'
                );

                // 2. SM_LOGON
                const logonDesc = new CharacterDescription(playerObj.feature, playerObj.status).encode();
                this.sendPacket(
                  socket,
                  new DefaultMessage(
                    playerId,
                    ProtocolConstants.SM_LOGON,
                    playerObj.x,
                    playerObj.y,
                    playerObj.direction
                  ),
                  logonDesc,
                  false
                );

                // 3. SM_MAPDESCRIPTION
                this.sendPacket(socket, new DefaultMessage(-1, ProtocolConstants.SM_MAPDESCRIPTION, 0, 0, 0), '比奇省');

                // 4. Send existing visible objects to new player
                for (const obj of this.worldObjects.values()) {
                  if (obj.id !== playerId && this.inRange(playerObj, obj)) {
                    const desc = new CharacterDescription(obj.feature, obj.status).encode();
                    this.sendPacket(
                      socket,
                      new DefaultMessage(obj.id, ProtocolConstants.SM_TURN, obj.x, obj.y, obj.direction),
                      desc,
                      false
                    );
                  }
                }

                // 5. Send existing ground items to new player
                for (const item of this.groundItems.values()) {
                  if (this.inItemRange(playerObj, item)) {
                    this.sendPacket(
                      socket,
                      new DefaultMessage(item.id, ProtocolConstants.SM_ITEMSHOW, item.x, item.y, item.looks),
                      item.name
                    );
                  }
                }

                // 6. Broadcast SM_TURN to other nearby players
                this.broadcastToObservers(playerObj, (obsSocket) => {
                  const desc = new CharacterDescription(playerObj.feature, playerObj.status).encode();
                  this.sendPacket(
                    obsSocket,
                    new DefaultMessage(playerId, ProtocolConstants.SM_TURN, playerObj.x, playerObj.y, playerObj.direction),
                    desc,
                    false
                  );
                });
              } catch (err) {
                this.sendPacket(socket, new DefaultMessage(0, ProtocolConstants.SM_STARTFAIL, 0, 0, 0));
                socket.destroy();
                return;
              }
            } else {
              // Authenticated game packets
              try {
                const parsed = WireMessageCodec.parseFrame(payload);
                if (parsed.type === 'packet') {
                  this.handleGamePacket(socket, playerId, parsed.packet);
                }
              } catch (err) {
                // Ignore parse errors in mock
              }
            }
          }
        });

        socket.on('close', () => {
          if (playerId > 0) {
            const playerObj = this.worldObjects.get(playerId);
            if (playerObj) {
              this.broadcastToObservers(playerObj, obsSocket => {
                this.sendPacket(
                  obsSocket,
                  new DefaultMessage(playerId, ProtocolConstants.SM_DISAPPEAR, 0, 0, 0)
                );
              });
            }
            this.worldObjects.delete(playerId);
            this.gameConnections.delete(playerId);
          }
        });
      });

      this.gameServer.listen(this.gamePort, this.host, () => {
        resolve();
      });
    });
  }

  private handleGamePacket(socket: net.Socket, playerId: number, packet: WirePacket) {
    const player = this.worldObjects.get(playerId);
    if (!player) return;

    const ident = packet.message.ident;
    const tick = Date.now() & 0x7fffffff;

    switch (ident) {
      case ProtocolConstants.CM_TURN: {
        const dir = packet.message.tag as Direction;
        player.direction = dir;
        this.sendStatus(socket, true, tick);
        this.broadcastToObservers(player, obsSocket => {
          const desc = new CharacterDescription(player.feature, player.status).encode();
          this.sendPacket(
            obsSocket,
            new DefaultMessage(playerId, ProtocolConstants.SM_TURN, player.x, player.y, player.direction),
            desc,
            false
          );
        });
        break;
      }

      case ProtocolConstants.CM_WALK:
      case ProtocolConstants.CM_RUN: {
        const isRun = ident === ProtocolConstants.CM_RUN;
        const dir = packet.message.tag as Direction;
        const offset = DirectionOffsets[dir] || { dx: 0, dy: 0 };
        const step = isRun ? 2 : 1;
        player.x += offset.dx * step;
        player.y += offset.dy * step;
        player.direction = dir;

        this.sendStatus(socket, true, tick);

        const moveIdent = isRun ? ProtocolConstants.SM_RUN : ProtocolConstants.SM_WALK;
        this.broadcastToObservers(player, obsSocket => {
          const desc = new CharacterDescription(player.feature, player.status).encode();
          this.sendPacket(
            obsSocket,
            new DefaultMessage(playerId, moveIdent, player.x, player.y, player.direction),
            desc,
            false
          );
        });
        break;
      }

      case ProtocolConstants.CM_HIT:
      case ProtocolConstants.CM_HEAVYHIT:
      case ProtocolConstants.CM_BIGHIT: {
        const dir = packet.message.tag as Direction;
        player.direction = dir;
        this.sendStatus(socket, true, tick);

        const hitIdent =
          ident === ProtocolConstants.CM_BIGHIT
            ? ProtocolConstants.SM_BIGHIT
            : ident === ProtocolConstants.CM_HEAVYHIT
            ? ProtocolConstants.SM_HEAVYHIT
            : ProtocolConstants.SM_HIT;

        this.broadcastToObservers(player, obsSocket => {
          this.sendPacket(
            obsSocket,
            new DefaultMessage(playerId, hitIdent, player.x, player.y, player.direction)
          );
        });

        // Melee target check (1 cell in front)
        const offset = DirectionOffsets[dir] || { dx: 0, dy: 0 };
        const targetX = player.x + offset.dx;
        const targetY = player.y + offset.dy;

        for (const target of this.worldObjects.values()) {
          if (target.id !== playerId && target.x === targetX && target.y === targetY) {
            const damage = 7;
            target.hp = Math.max(0, target.hp - damage);

            // Struck packet to self & observers
            const struckPacket = new DefaultMessage(
              target.id,
              ProtocolConstants.SM_STRUCK,
              target.hp,
              target.maxHp,
              damage
            );
            this.sendPacket(socket, struckPacket);
            this.broadcastToObservers(target, obs => this.sendPacket(obs, struckPacket));

            // Health change packet
            this.sendPacket(
              socket,
              new DefaultMessage(target.id, ProtocolConstants.SM_HEALTHSPELLCHANGED, target.hp, target.mp, target.maxHp)
            );

            if (target.hp === 0) {
              // Death
              const deathDesc = new CharacterDescription(target.feature, target.status).encode();
              const deathPacket = new DefaultMessage(
                target.id,
                ProtocolConstants.SM_DEATH,
                target.x,
                target.y,
                target.direction
              );
              this.sendPacket(socket, deathPacket, deathDesc, false);
              this.broadcastToObservers(target, obs => this.sendPacket(obs, deathPacket, deathDesc, false));

              // Exp gain
              const expGained = 15;
              this.sendPacket(
                socket,
                new DefaultMessage(expGained, ProtocolConstants.SM_WINEXP, expGained, 0, 0)
              );

              // Drop chicken meat
              const itemId = ++this.nextItemId;
              const groundItem: MockGroundItem = {
                id: itemId,
                name: '鸡肉',
                looks: 41,
                mapId: target.mapId,
                x: target.x,
                y: target.y,
                backpackItem: {
                  item: {
                    name: '鸡肉',
                    stdMode: 0,
                    shape: 0,
                    weight: 1,
                    aniCount: 0,
                    source: 0,
                    needIdentify: 0,
                    looks: 41,
                    duraMax: 10,
                    ac: 0,
                    mac: 0,
                    dc: 0,
                    mc: 0,
                    sc: 0,
                    need: 0,
                    needLevel: 0,
                    price: 10
                  },
                  makeIndex: itemId,
                  dura: 10,
                  duraMax: 10
                }
              };
              this.groundItems.set(itemId, groundItem);

              // Show item
              const itemShowPacket = new DefaultMessage(
                itemId,
                ProtocolConstants.SM_ITEMSHOW,
                groundItem.x,
                groundItem.y,
                groundItem.looks
              );
              this.sendPacket(socket, itemShowPacket, groundItem.name);
              this.broadcastToObservers(target, obs => this.sendPacket(obs, itemShowPacket, groundItem.name));

              this.worldObjects.delete(target.id);
            }
            break;
          }
        }
        break;
      }

      case ProtocolConstants.CM_PICKUP: {
        const posX = packet.message.param;
        const posY = packet.message.tag;

        let foundItem: MockGroundItem | null = null;
        for (const item of this.groundItems.values()) {
          if (item.x === posX && item.y === posY) {
            foundItem = item;
            break;
          }
        }

        if (foundItem) {
          this.groundItems.delete(foundItem.id);
          const acc = this.accounts.get(player.connection?.account || '');
          const charData = acc?.characters.find(c => c.name === player.name);
          if (charData) {
            charData.backpack.push(foundItem.backpackItem);
          }

          this.sendStatus(socket, true, tick);

          // SM_ADDITEM (series=1)
          const encodedItem = ClientItemCodec.encode(foundItem.backpackItem);
          this.sendPacket(
            socket,
            new DefaultMessage(playerId, ProtocolConstants.SM_ADDITEM, 0, 0, 1),
            encodedItem,
            false
          );

          // SM_ITEMHIDE
          const hidePacket = new DefaultMessage(foundItem.id, ProtocolConstants.SM_ITEMHIDE, foundItem.x, foundItem.y, 0);
          this.sendPacket(socket, hidePacket);
          this.broadcastToObservers(player, obs => this.sendPacket(obs, hidePacket));
        } else {
          this.sendStatus(socket, false, tick);
        }
        break;
      }

      case ProtocolConstants.CM_QUERYBAGITEMS: {
        const acc = this.accounts.get(player.connection?.account || '');
        const charData = acc?.characters.find(c => c.name === player.name);
        const bag = charData?.backpack || [];
        if (bag.length > 0) {
          const encodedBag = ClientItemCodec.encodeBag(bag);
          this.sendPacket(
            socket,
            new DefaultMessage(playerId, ProtocolConstants.SM_BAGITEMS, 0, 0, bag.length),
            encodedBag,
            false
          );
        }
        break;
      }
    }
  }

  private broadcastToObservers(center: { x: number; y: number; id: number }, sender: (socket: net.Socket) => void) {
    for (const [id, conn] of this.gameConnections.entries()) {
      if (id !== center.id && conn.socket.writable) {
        const otherPlayer = this.worldObjects.get(id);
        if (otherPlayer && this.inRange(center, otherPlayer)) {
          sender(conn.socket);
        }
      }
    }
  }

  private inRange(a: { x: number; y: number }, b: { x: number; y: number }): boolean {
    return Math.abs(a.x - b.x) <= 12 && Math.abs(a.y - b.y) <= 12;
  }

  private inItemRange(player: { x: number; y: number }, item: { x: number; y: number }): boolean {
    return Math.abs(player.x - item.x) <= 12 && Math.abs(player.y - item.y) <= 12;
  }

  private sendPacket(socket: net.Socket, message: DefaultMessage, plainOrEncodedBody = '', isPlain = true) {
    if (!socket.writable) return;
    const encodedBody = isPlain && plainOrEncodedBody
      ? WireMessageCodec.encodeBody(plainOrEncodedBody)
      : plainOrEncodedBody;
    const packet: WirePacket = { message, encodedBody };
    const bytes = WireMessageCodec.formatPacket(packet);
    socket.write(bytes);
  }

  private sendStatus(socket: net.Socket, accepted: boolean, tick: number) {
    if (!socket.writable) return;
    socket.write(WireMessageCodec.formatStatus(accepted, tick));
  }

  private handleFramedSocket(socket: net.Socket, onPacket: (packet: WirePacket) => void) {
    let buffer = Buffer.alloc(0);
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length > 0) {
        const startIdx = buffer.indexOf(WireMessageCodec.START);
        if (startIdx === -1) break;
        if (startIdx > 0) buffer = buffer.subarray(startIdx);

        const endIdx = buffer.indexOf(WireMessageCodec.END);
        if (endIdx === -1) break;

        const payload = buffer.subarray(1, endIdx);
        buffer = buffer.subarray(endIdx + 1);

        try {
          const parsed = WireMessageCodec.parseFrame(payload);
          if (parsed.type === 'packet') {
            onPacket(parsed.packet);
          }
        } catch (err) {
          // ignore
        }
      }
    });
  }
}
