import { EventEmitter } from 'events';
import {
  ProtocolConstants,
  Direction,
  DirectionOffsets,
  Job,
  Gender,
  CharacterInfo,
  WorldObjectSnapshot,
  GroundItem,
  BackpackItem,
  BridgeEvent,
  WorldObjectType
} from '@mir2/shared';
import { DefaultMessage } from '../protocol/DefaultMessage.js';
import { WireMessageCodec, WirePacket, ParsedFrame } from '../protocol/WireMessageCodec.js';
import { CharacterDescription } from '../protocol/CharacterDescription.js';
import { ClientItemCodec } from '../protocol/ClientItemCodec.js';
import { MirTcpClient } from '../tcp/MirTcpClient.js';

export enum SessionState {
  IDLE = 'IDLE',
  LOGGING_IN = 'LOGGING_IN',
  LOGGED_IN = 'LOGGED_IN',
  SELECTING_CHARACTER = 'SELECTING_CHARACTER',
  ENTERING_GAME = 'ENTERING_GAME',
  IN_GAME = 'IN_GAME',
  DISCONNECTED = 'DISCONNECTED'
}

export interface GateSessionConfig {
  targetHost: string;
  loginPort: number;
  selectPort: number;
  gamePort: number;
  addressMode: 'simple' | 'strict';
}

export class GateSession extends EventEmitter {
  public state: SessionState = SessionState.IDLE;

  public account = '';
  public characterName = '';
  public certification = 0;
  public serverName = 'MIR2';

  public targetHost = '127.0.0.1';
  public loginPort = 7000;
  public selectPort = 7100;
  public gamePort = 7200;
  public addressMode: 'simple' | 'strict' = 'simple';

  // Game world state
  public playerId = 0;
  public mapId = '0';
  public mapTitle = '';
  public x = 0;
  public y = 0;
  public direction: Direction = Direction.DOWN;
  public hp = 100;
  public maxHp = 100;
  public mp = 50;
  public maxMp = 50;
  public feature = 0;
  public status = 0;
  public level = 1;
  public exp = 0;

  public readonly visibleObjects = new Map<number, WorldObjectSnapshot>();
  public readonly visibleItems = new Map<number, GroundItem>();
  public backpack: BackpackItem[] = [];

  private loginClient: MirTcpClient | null = null;
  private selectClient: MirTcpClient | null = null;
  private gameClient: MirTcpClient | null = null;

  private pendingAction: {
    type: 'walk' | 'run' | 'turn' | 'attack' | 'pickup';
    targetX?: number;
    targetY?: number;
    targetDir?: Direction;
  } | null = null;

  constructor(config?: Partial<GateSessionConfig>) {
    super();
    if (config) {
      if (config.targetHost) this.targetHost = config.targetHost;
      if (config.loginPort) this.loginPort = config.loginPort;
      if (config.selectPort) this.selectPort = config.selectPort;
      if (config.gamePort) this.gamePort = config.gamePort;
      if (config.addressMode) this.addressMode = config.addressMode;
    }
  }

  // --------------------------------------------------------------------------
  // MILESTONE 1: LOGIN & SELECT
  // --------------------------------------------------------------------------

  public async login(
    account: string,
    pass: string,
    targetHost?: string,
    targetPort?: number
  ): Promise<void> {
    if (targetHost) this.targetHost = targetHost;
    if (targetPort) this.loginPort = targetPort;
    this.account = account;

    this.log('info', `[7000] 正在连接登录网关 ${this.targetHost}:${this.loginPort}...`, 'LOGIN');
    this.state = SessionState.LOGGING_IN;

    try {
      this.loginClient = new MirTcpClient();
      await this.loginClient.connect(this.targetHost, this.loginPort, 5000);
      this.log('info', `[7000] 登录网关 TCP 连接成功，发送 CM_PROTOCOL 握手...`, 'LOGIN');

      // 1. CM_PROTOCOL
      await this.loginClient.sendPacket(new DefaultMessage(0, ProtocolConstants.CM_PROTOCOL, 0, 0, 0));
      const protoReply = await this.loginClient.readFrame(5000);
      if (protoReply.type !== 'packet' || protoReply.packet.message.ident !== ProtocolConstants.SM_CERTIFICATION_SUCCESS) {
        throw new Error('协议握手失败 (CM_PROTOCOL 未返回 SM_CERTIFICATION_SUCCESS)');
      }
      this.log('info', `[7000] 收到 SM_CERTIFICATION_SUCCESS，发送账号密码验证...`, 'LOGIN');

      // 2. CM_IDPASSWORD
      await this.loginClient.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_IDPASSWORD, 0, 0, 0),
        `${account}/${pass}`
      );
      const authReply = await this.loginClient.readFrame(5000);
      if (authReply.type !== 'packet') {
        throw new Error('未收到登录网关认证回复');
      }

      const authIdent = authReply.packet.message.ident;
      if (authIdent === ProtocolConstants.SM_PASSWD_FAIL) {
        throw new Error('账号密码错误');
      }
      if (authIdent === ProtocolConstants.SM_ID_NOTFOUND) {
        throw new Error('账号不存在');
      }
      if (authIdent !== ProtocolConstants.SM_PASSOK_SELECTSERVER) {
        throw new Error(`登录失败，错误码: ${authIdent}`);
      }

      const passOkBody = WireMessageCodec.decodeBody(authReply.packet.encodedBody);
      const serverName = passOkBody.split('/')[0] || 'MIR2';
      this.serverName = serverName;
      this.log('info', `[7000] 登录成功 (服务器: ${serverName})，请求选服路由...`, 'LOGIN');

      // 3. CM_SELECTSERVER
      await this.loginClient.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_SELECTSERVER, 0, 0, 0),
        serverName
      );
      const routeReply = await this.loginClient.readFrame(5000);
      if (routeReply.type !== 'packet' || routeReply.packet.message.ident !== ProtocolConstants.SM_SELECTSERVER_OK) {
        throw new Error('获取选服路由失败 (SM_SELECTSERVER_OK 未返回)');
      }

      const routeBody = WireMessageCodec.decodeBody(routeReply.packet.encodedBody);
      const [advHost, selPortStr, certStr] = routeBody.split('/');
      this.certification = parseInt(certStr, 10);
      const advertisedSelectPort = parseInt(selPortStr, 10);

      if (this.addressMode === 'strict') {
        this.targetHost = advHost;
        this.selectPort = advertisedSelectPort;
      } else {
        if (!isNaN(advertisedSelectPort) && advertisedSelectPort > 0) {
          this.selectPort = advertisedSelectPort;
        }
      }

      this.log(
        'info',
        `[7000] 选服完成，获得认证码: ${this.certification}，下一跳选人网关: ${this.targetHost}:${this.selectPort}`,
        'LOGIN'
      );

      this.loginClient.close();
      this.loginClient = null;

      this.state = SessionState.LOGGED_IN;
      this.emitEvent({ type: 'loginResult', ok: true, serverName: this.serverName });

      // Automatically connect to select gate and query character list
      await this.queryCharacters();
    } catch (err) {
      const errorMsg = (err as Error).message || '登录异常';
      this.log('error', `[7000] 登录失败: ${errorMsg}`, 'LOGIN');
      this.state = SessionState.IDLE;
      if (this.loginClient) {
        this.loginClient.close();
        this.loginClient = null;
      }
      this.emitEvent({ type: 'loginResult', ok: false, reason: errorMsg });
    }
  }

  public async queryCharacters(): Promise<void> {
    try {
      await this.ensureSelectConnection();
      this.log('info', `[7100] 查询角色列表 (CM_QUERYCHR)...`, 'SELECT');

      await this.selectClient!.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_QUERYCHR, 0, 0, 0),
        `${this.account}/${this.certification}`
      );
      const reply = await this.selectClient!.readFrame(5000);
      if (reply.type !== 'packet' || reply.packet.message.ident !== ProtocolConstants.SM_QUERYCHR) {
        throw new Error(`查询角色列表失败 (ident: ${reply.type === 'packet' ? reply.packet.message.ident : 'status'})`);
      }

      const body = WireMessageCodec.decodeBody(reply.packet.encodedBody);
      const characters = this.parseCharacterList(body);
      this.log('info', `[7100] 获取到 ${characters.length} 个角色`, 'SELECT');
      this.emitEvent({ type: 'characterList', characters });
    } catch (err) {
      this.log('error', `[7100] 查询角色列表异常: ${(err as Error).message}`, 'SELECT');
    }
  }

  public async createCharacter(name: string, job: number, gender: number, hair: number): Promise<void> {
    try {
      await this.ensureSelectConnection();
      this.log('info', `[7100] 创建角色 "${name}" (职业: ${job}, 性别: ${gender}, 发型: ${hair})...`, 'SELECT');

      await this.selectClient!.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_NEWCHR, 0, 0, 0),
        `${this.account}/${name}/${job}/${hair}/${gender}`
      );
      const reply = await this.selectClient!.readFrame(5000);
      if (reply.type !== 'packet') throw new Error('创建角色未收到响应');

      if (reply.packet.message.ident === ProtocolConstants.SM_NEWCHR_SUCCESS) {
        const level = reply.packet.message.recog;
        this.log('info', `[7100] 角色 "${name}" 创建成功 (等级: ${level})`, 'SELECT');
        this.emitEvent({ type: 'characterCreated', ok: true, name, level });
        await this.queryCharacters();
      } else {
        this.log('warn', `[7100] 创建角色 "${name}" 失败 (SM_NEWCHR_FAIL)`, 'SELECT');
        this.emitEvent({ type: 'characterCreated', ok: false, reason: '创建角色失败 (重名或达到上限)' });
      }
    } catch (err) {
      this.log('error', `[7100] 创建角色异常: ${(err as Error).message}`, 'SELECT');
      this.emitEvent({ type: 'characterCreated', ok: false, reason: (err as Error).message });
    }
  }

  public async deleteCharacter(name: string): Promise<void> {
    try {
      await this.ensureSelectConnection();
      this.log('info', `[7100] 删除角色 "${name}" (CM_DELCHR)...`, 'SELECT');

      await this.selectClient!.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_DELCHR, 0, 0, 0),
        name
      );
      const reply = await this.selectClient!.readFrame(5000);
      if (reply.type !== 'packet') throw new Error('删除角色未收到响应');

      if (reply.packet.message.ident === ProtocolConstants.SM_DELCHR_SUCCESS) {
        this.log('info', `[7100] 角色 "${name}" 删除成功`, 'SELECT');
        this.emitEvent({ type: 'characterDeleted', ok: true, name });
        await this.queryCharacters();
      } else {
        this.log('warn', `[7100] 删除角色 "${name}" 失败`, 'SELECT');
        this.emitEvent({ type: 'characterDeleted', ok: false, name, reason: '删除角色失败' });
      }
    } catch (err) {
      this.log('error', `[7100] 删除角色异常: ${(err as Error).message}`, 'SELECT');
      this.emitEvent({ type: 'characterDeleted', ok: false, name, reason: (err as Error).message });
    }
  }

  public async selectCharacter(name: string): Promise<void> {
    try {
      await this.ensureSelectConnection();
      this.characterName = name;
      this.log('info', `[7100] 选择角色 "${name}" (CM_SELCHR)...`, 'SELECT');

      await this.selectClient!.sendPacket(
        new DefaultMessage(0, ProtocolConstants.CM_SELCHR, 0, 0, 0),
        `${this.account}/${name}`
      );
      const reply = await this.selectClient!.readFrame(5000);
      if (reply.type !== 'packet' || reply.packet.message.ident !== ProtocolConstants.SM_STARTPLAY) {
        throw new Error(`选择角色失败 (ident: ${reply.type === 'packet' ? reply.packet.message.ident : 'status'})`);
      }

      const body = WireMessageCodec.decodeBody(reply.packet.encodedBody);
      const [advHost, gamePortStr] = body.split('/');
      const advGamePort = parseInt(gamePortStr, 10);

      if (this.addressMode === 'strict') {
        this.targetHost = advHost;
        this.gamePort = advGamePort;
      } else {
        if (!isNaN(advGamePort) && advGamePort > 0) {
          this.gamePort = advGamePort;
        }
      }

      this.log('info', `[7100] 收到 SM_STARTPLAY，准备进入游戏网关 ${this.targetHost}:${this.gamePort}...`, 'SELECT');

      if (this.selectClient) {
        this.selectClient.close();
        this.selectClient = null;
      }

      await this.enterGame();
    } catch (err) {
      this.log('error', `[7100] 选择角色异常: ${(err as Error).message}`, 'SELECT');
    }
  }

  private async ensureSelectConnection(): Promise<void> {
    if (this.selectClient && this.selectClient.isOpen) return;
    this.selectClient = new MirTcpClient();
    await this.selectClient.connect(this.targetHost, this.selectPort, 5000);
  }

  private parseCharacterList(body: string): CharacterInfo[] {
    const fields = body.split('/').filter(f => f.length > 0);
    const result: CharacterInfo[] = [];
    for (let i = 0; i + 4 < fields.length; i += 5) {
      result.push({
        name: fields[i],
        job: parseInt(fields[i + 1], 10) as Job,
        hair: parseInt(fields[i + 2], 10),
        level: parseInt(fields[i + 3], 10),
        gender: parseInt(fields[i + 4], 10) as Gender
      });
    }
    return result;
  }

  // --------------------------------------------------------------------------
  // MILESTONE 2: MAP ENTRY & MOVEMENT (GAME 7200)
  // --------------------------------------------------------------------------

  private async enterGame(): Promise<void> {
    this.state = SessionState.ENTERING_GAME;
    this.log('info', `[7200] 连接游戏网关 ${this.targetHost}:${this.gamePort}...`, 'GAME');

    try {
      this.gameClient = new MirTcpClient();
      await this.gameClient.connect(this.targetHost, this.gamePort, 5000);

      // Listen for continuous game events
      this.gameClient.on('frame', (frame: ParsedFrame) => {
        this.handleGameFrame(frame);
      });

      this.gameClient.on('error', (err: Error) => {
        this.log('error', `[7200] 游戏网关连接错误: ${err.message}`, 'GAME');
      });

      this.gameClient.on('close', () => {
        this.log('warn', `[7200] 与游戏网关的连接已断开`, 'GAME');
        this.state = SessionState.DISCONNECTED;
        this.emitEvent({ type: 'disconnected', reason: '与游戏网关连接断开' });
      });

      // Send RunLogin first packet
      this.log('info', `[7200] 发送 RunLogin 首包 (账号: ${this.account}, 角色: ${this.characterName}, 认证码: ${this.certification})...`, 'GAME');
      await this.gameClient.sendRunLogin({
        account: this.account,
        characterName: this.characterName,
        certification: this.certification,
        clientVersion: ProtocolConstants.CLIENT_VERSION_NUMBER,
        loginCode: 9
      });
    } catch (err) {
      this.log('error', `[7200] 进入游戏失败: ${(err as Error).message}`, 'GAME');
      this.state = SessionState.DISCONNECTED;
      this.emitEvent({ type: 'disconnected', reason: `无法连接游戏网关: ${(err as Error).message}` });
    }
  }

  private handleGameFrame(frame: ParsedFrame): void {
    if (frame.type === 'status') {
      this.handleGameStatus(frame.accepted, frame.serverTick);
      return;
    }

    const packet = frame.packet;
    const ident = packet.message.ident;
    const recog = packet.message.recog;
    const param = packet.message.param;
    const tag = packet.message.tag;
    const series = packet.message.series;

    switch (ident) {
      // Same-server map routes use the legacy clear + change-map sequence rather than the
      // RunLogin-only SM_NEWMAP / SM_LOGON pair. Keep the current identity and appearance, but
      // discard stale entities before SM_MAPDESCRIPTION emits the refreshed mapEntered snapshot.
      case ProtocolConstants.SM_CLEAROBJECTS: {
        this.visibleObjects.clear();
        this.visibleItems.clear();
        this.log('info', '[7200] 服务端清理旧地图对象，准备切换地图', 'GAME');
        break;
      }

      case ProtocolConstants.SM_CHANGEMAP: {
        this.playerId = recog || this.playerId;
        this.x = param;
        this.y = tag;
        this.mapId = WireMessageCodec.decodeBody(packet.encodedBody) || this.mapId;
        this.visibleObjects.clear();
        this.visibleItems.clear();
        this.log('info', `[7200] 切换地图: id=${this.mapId}, 坐标: (${this.x}, ${this.y})`, 'GAME');
        break;
      }

      case ProtocolConstants.SM_NEWMAP: {
        this.playerId = recog;
        this.x = param;
        this.y = tag;
        this.mapId = WireMessageCodec.decodeBody(packet.encodedBody) || '0';
        this.visibleObjects.clear();
        this.visibleItems.clear();
        this.log('info', `[7200] 进入地图: id=${this.mapId}, 初始坐标: (${this.x}, ${this.y}), 玩家ID: ${this.playerId}`, 'GAME');
        break;
      }

      case ProtocolConstants.SM_LOGON: {
        this.x = param;
        this.y = tag;
        this.direction = series as Direction;
        try {
          const desc = CharacterDescription.decode(packet.encodedBody);
          this.feature = desc.feature;
          this.status = desc.status;
        } catch {
          // ignore
        }
        this.log('info', `[7200] 角色登入地图成功，朝向: ${this.direction}`, 'GAME');
        break;
      }

      case ProtocolConstants.SM_MAPDESCRIPTION: {
        this.mapTitle = WireMessageCodec.decodeBody(packet.encodedBody) || '传奇世界';
        this.state = SessionState.IN_GAME;
        this.log('info', `[7200] 地图名称: "${this.mapTitle}"，完成进图流程`, 'GAME');

        // Emit mapEntered event to client
        this.emitEvent({
          type: 'mapEntered',
          playerId: this.playerId,
          name: this.characterName,
          mapId: this.mapId,
          mapTitle: this.mapTitle,
          x: this.x,
          y: this.y,
          direction: this.direction,
          hp: this.hp,
          maxHp: this.maxHp,
          mp: this.mp,
          maxMp: this.maxMp,
          feature: this.feature,
          visibleObjects: Array.from(this.visibleObjects.values()),
          visibleItems: Array.from(this.visibleItems.values())
        });

        // Automatically query backpack items on map enter
        this.queryBagItems();
        break;
      }

      case ProtocolConstants.SM_TURN:
      case ProtocolConstants.SM_WALK:
      case ProtocolConstants.SM_RUN: {
        const objId = recog;
        const objX = param;
        const objY = tag;
        const objDir = series as Direction;

        let objFeature = 0;
        let objStatus = 0;
        try {
          if (packet.encodedBody) {
            const desc = CharacterDescription.decode(packet.encodedBody);
            objFeature = desc.feature;
            objStatus = desc.status;
          }
        } catch {
          // ignore
        }

        if (objId === this.playerId) {
          // Self update
          this.x = objX;
          this.y = objY;
          this.direction = objDir;
        } else {
          // Observer object update
          const existing = this.visibleObjects.get(objId);
          const isMonster = objFeature > 0 && (objFeature >>> 24) === 0;
          const objType = isMonster ? WorldObjectType.MONSTER : WorldObjectType.PLAYER;
          const objName = isMonster ? '怪物' : `玩家#${objId}`;

          const updated: WorldObjectSnapshot = {
            id: objId,
            name: existing?.name || objName,
            type: existing?.type || objType,
            mapId: this.mapId,
            x: objX,
            y: objY,
            direction: objDir,
            feature: objFeature || existing?.feature || 0,
            status: objStatus || existing?.status || 0,
            hp: existing?.hp || 100,
            maxHp: existing?.maxHp || 100
          };

          this.visibleObjects.set(objId, updated);

          if (!existing) {
            this.log('debug', `[7200] 视野内出现对象: #${objId} 坐标: (${objX}, ${objY})`, 'GAME');
            this.emitEvent({ type: 'objectAppeared', object: updated });
          } else if (ident === ProtocolConstants.SM_WALK || ident === ProtocolConstants.SM_RUN) {
            const movement = ident === ProtocolConstants.SM_RUN ? 'run' : 'walk';
            this.emitEvent({
              type: 'objectMoved',
              objectId: objId,
              x: objX,
              y: objY,
              direction: objDir,
              movement
            });
          } else {
            this.emitEvent({
              type: 'objectTurned',
              objectId: objId,
              x: objX,
              y: objY,
              direction: objDir
            });
          }
        }
        break;
      }

      case ProtocolConstants.SM_DISAPPEAR: {
        const objId = recog;
        this.visibleObjects.delete(objId);
        this.log('debug', `[7200] 视野内对象离开: #${objId}`, 'GAME');
        this.emitEvent({ type: 'objectDisappeared', objectId: objId });
        break;
      }

      case ProtocolConstants.SM_HIT:
      case ProtocolConstants.SM_HEAVYHIT:
      case ProtocolConstants.SM_BIGHIT: {
        const attackerId = recog;
        const hitX = param;
        const hitY = tag;
        const hitDir = series as Direction;
        this.log('debug', `[7200] 观察到攻击: #${attackerId} 在 (${hitX}, ${hitY}) 朝向 ${hitDir}`, 'GAME');
        break;
      }

      case ProtocolConstants.SM_STRUCK: {
        const victimId = recog;
        const currentHp = param;
        const maxHpVal = tag;
        const damage = series;

        let attackerId = 0;
        try {
          if (packet.encodedBody) {
            const raw = WireMessageCodec.decodeBody(packet.encodedBody);
            // WL format might encode attackerId
          }
        } catch {
          // ignore
        }

        if (victimId === this.playerId) {
          this.hp = currentHp;
          this.maxHp = maxHpVal;
          this.log('warn', `[7200] 玩家自身受到 ${damage} 点伤害！剩余生命值: ${this.hp}/${this.maxHp}`, 'GAME');
        } else {
          const victim = this.visibleObjects.get(victimId);
          if (victim) {
            victim.hp = currentHp;
            victim.maxHp = maxHpVal;
          }
          this.log('info', `[7200] 对象 #${victimId} 受到 ${damage} 点伤害！生命值: ${currentHp}/${maxHpVal}`, 'GAME');
        }

        this.emitEvent({
          type: 'struck',
          victimId,
          attackerId,
          damage,
          hp: currentHp,
          maxHp: maxHpVal
        });
        break;
      }

      case ProtocolConstants.SM_DEATH: {
        const victimId = recog;
        const deathX = param;
        const deathY = tag;
        const deathDir = series as Direction;

        if (victimId === this.playerId) {
          this.hp = 0;
          this.log('error', `[7200] 玩家已阵亡！`, 'GAME');
        } else {
          this.visibleObjects.delete(victimId);
          this.log('info', `[7200] 目标 #${victimId} 死亡`, 'GAME');
        }

        this.emitEvent({
          type: 'death',
          victimId,
          x: deathX,
          y: deathY,
          direction: deathDir
        });
        break;
      }

      case ProtocolConstants.SM_HEALTHSPELLCHANGED: {
        const objId = recog;
        const newHp = param;
        const newMp = tag;
        const newMaxHp = series;

        if (objId === this.playerId) {
          this.hp = newHp;
          this.mp = newMp;
          this.maxHp = newMaxHp;
        } else {
          const obj = this.visibleObjects.get(objId);
          if (obj) {
            obj.hp = newHp;
            obj.maxHp = newMaxHp;
          }
        }

        this.emitEvent({
          type: 'healthChanged',
          objectId: objId,
          hp: newHp,
          mp: newMp,
          maxHp: newMaxHp
        });
        break;
      }

      case ProtocolConstants.SM_WINEXP: {
        const total = recog;
        const gained = param | (tag << 16);
        this.exp = total;
        this.log('info', `[7200] 获得经验值: +${gained} (累计: ${total})`, 'GAME');
        this.emitEvent({ type: 'experienceGained', gained, total });
        break;
      }

      case ProtocolConstants.SM_ITEMSHOW: {
        const itemId = recog;
        const itemX = param;
        const itemY = tag;
        const looks = series;
        const itemName = WireMessageCodec.decodeBody(packet.encodedBody) || '物品';

        const groundItem: GroundItem = {
          id: itemId,
          name: itemName,
          looks,
          mapId: this.mapId,
          x: itemX,
          y: itemY
        };
        this.visibleItems.set(itemId, groundItem);
        this.log('info', `[7200] 地面出现物品: "${itemName}" 坐标: (${itemX}, ${itemY})`, 'GAME');
        this.emitEvent({ type: 'itemShow', item: groundItem });
        break;
      }

      case ProtocolConstants.SM_ITEMHIDE: {
        const itemId = recog;
        this.visibleItems.delete(itemId);
        this.log('debug', `[7200] 地面物品消失: #${itemId}`, 'GAME');
        this.emitEvent({ type: 'itemHide', itemId });
        break;
      }

      case ProtocolConstants.SM_ADDITEM: {
        try {
          const item = ClientItemCodec.decode(packet.encodedBody);
          this.backpack.push(item);
          this.log('info', `[7200] 获得物品: "${item.item.name}" (持久: ${item.dura}/${item.duraMax})`, 'GAME');
          this.emitEvent({ type: 'itemAdded', item });
          this.emitEvent({ type: 'bagUpdated', items: this.backpack });
        } catch (err) {
          this.log('error', `[7200] 解析添加物品失败: ${(err as Error).message}`, 'GAME');
        }
        break;
      }

      case ProtocolConstants.SM_BAGITEMS: {
        try {
          this.backpack = ClientItemCodec.decodeBag(packet.encodedBody);
          this.log('info', `[7200] 背包已同步，共 ${this.backpack.length} 件物品`, 'GAME');
          this.emitEvent({ type: 'bagUpdated', items: this.backpack });
        } catch (err) {
          this.log('error', `[7200] 解析背包物品失败: ${(err as Error).message}`, 'GAME');
        }
        break;
      }
    }
  }

  private handleGameStatus(accepted: boolean, tick: number): void {
    const actionInfo = this.pendingAction;
    this.pendingAction = null;

    if (actionInfo) {
      if (accepted) {
        if (actionInfo.targetX !== undefined && actionInfo.targetY !== undefined) {
          this.x = actionInfo.targetX;
          this.y = actionInfo.targetY;
        }
        if (actionInfo.targetDir !== undefined) {
          this.direction = actionInfo.targetDir;
        }
      }
      this.emitEvent({
        type: 'actionResult',
        action: actionInfo.type,
        ok: accepted,
        tick
      });
      this.log(
        accepted ? 'debug' : 'warn',
        `[7200] 动作 ${actionInfo.type} 服务端确认: ${accepted ? '+GOOD' : '+FAIL'} (tick: ${tick})`,
        'GAME'
      );
    }
  }

  // --------------------------------------------------------------------------
  // IN-GAME COMMANDS (WALK / RUN / TURN / ATTACK / PICKUP / QUERY BAG)
  // --------------------------------------------------------------------------

  public async walk(direction: Direction): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    const offset = DirectionOffsets[direction] || { dx: 0, dy: 0 };
    const targetX = this.x + offset.dx;
    const targetY = this.y + offset.dy;

    this.pendingAction = {
      type: 'walk',
      targetX,
      targetY,
      targetDir: direction
    };

    const packedPos = (targetY << 16) | (targetX & 0xffff);
    await this.gameClient.sendPacket(
      new DefaultMessage(packedPos, ProtocolConstants.CM_WALK, 0, direction, 0)
    );
  }

  public async run(direction: Direction): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    const offset = DirectionOffsets[direction] || { dx: 0, dy: 0 };
    const targetX = this.x + offset.dx * 2;
    const targetY = this.y + offset.dy * 2;

    this.pendingAction = {
      type: 'run',
      targetX,
      targetY,
      targetDir: direction
    };

    const packedPos = (targetY << 16) | (targetX & 0xffff);
    await this.gameClient.sendPacket(
      new DefaultMessage(packedPos, ProtocolConstants.CM_RUN, 0, direction, 0)
    );
  }

  public async turn(direction: Direction): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    this.pendingAction = {
      type: 'turn',
      targetDir: direction
    };

    const packedPos = (this.y << 16) | (this.x & 0xffff);
    await this.gameClient.sendPacket(
      new DefaultMessage(packedPos, ProtocolConstants.CM_TURN, 0, direction, 0)
    );
  }

  public async attack(kind: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT' = 'HIT', direction?: Direction): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    const dir = direction !== undefined ? direction : this.direction;
    this.pendingAction = { type: 'attack', targetDir: dir };

    const ident =
      kind === 'BIG_HIT'
        ? ProtocolConstants.CM_BIGHIT
        : kind === 'HEAVY_HIT'
        ? ProtocolConstants.CM_HEAVYHIT
        : ProtocolConstants.CM_HIT;

    const packedPos = (this.y << 16) | (this.x & 0xffff);
    await this.gameClient.sendPacket(
      new DefaultMessage(packedPos, ident, 0, dir, 0)
    );
  }

  public async pickup(): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    this.pendingAction = { type: 'pickup' };

    await this.gameClient.sendPacket(
      new DefaultMessage(0, ProtocolConstants.CM_PICKUP, this.x, this.y, 0)
    );
  }

  public async queryBagItems(): Promise<void> {
    if (!this.gameClient || !this.gameClient.isOpen) return;
    await this.gameClient.sendPacket(
      new DefaultMessage(0, ProtocolConstants.CM_QUERYBAGITEMS, 0, 0, 0)
    );
  }

  public disconnect(): void {
    if (this.loginClient) {
      this.loginClient.close();
      this.loginClient = null;
    }
    if (this.selectClient) {
      this.selectClient.close();
      this.selectClient = null;
    }
    if (this.gameClient) {
      this.gameClient.close();
      this.gameClient = null;
    }
    this.state = SessionState.DISCONNECTED;
    this.visibleObjects.clear();
    this.visibleItems.clear();
    this.backpack = [];
    this.emitEvent({ type: 'disconnected', reason: '客户端主动断开连接' });
  }

  private log(level: 'info' | 'warn' | 'error' | 'debug', message: string, gate?: string): void {
    this.emitEvent({
      type: 'log',
      level,
      message,
      timestamp: Date.now(),
      gate
    });
  }

  private emitEvent(event: BridgeEvent): void {
    this.emit('event', event);
  }
}
