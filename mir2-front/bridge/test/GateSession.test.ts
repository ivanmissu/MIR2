import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MockMirServer } from '../src/tcp/MockMirServer.js';
import { GateSession, SessionState } from '../src/session/GateSession.js';
import { Direction, BridgeEvent, Job, Gender } from '@mir2/shared';

describe('GateSession E2E & State Machine', () => {
  let server: MockMirServer;
  const loginPort = 17000;
  const selectPort = 17100;
  const gamePort = 17200;

  beforeAll(async () => {
    server = new MockMirServer('127.0.0.1', loginPort, selectPort, gamePort);
    await server.start();
  });

  afterAll(async () => {
    await server.stop();
  });

  it('runs full M1..M3 lifecycle: Login -> Select -> Enter Map -> Move -> Attack -> Loot -> Bag Sync', async () => {
    const session = new GateSession({
      targetHost: '127.0.0.1',
      loginPort,
      selectPort,
      gamePort,
      addressMode: 'simple'
    });

    const events: BridgeEvent[] = [];
    session.on('event', (evt: BridgeEvent) => {
      events.push(evt);
    });

    // M1: Login
    await session.login('hero', '123456');
    expect(session.state).toBe(SessionState.LOGGED_IN);
    expect(session.account).toBe('hero');
    expect(session.certification).toBeGreaterThan(0);

    const loginEvent = events.find(e => e.type === 'loginResult');
    expect(loginEvent).toBeDefined();
    expect((loginEvent as any).ok).toBe(true);

    // M1: Character list
    const charListEvent = events.find(e => e.type === 'characterList');
    expect(charListEvent).toBeDefined();
    expect((charListEvent as any).characters.length).toBeGreaterThanOrEqual(1);

    // M1: Create character
    await session.createCharacter('法师小强', Job.WIZARD, Gender.MALE, 2);
    const charCreatedEvent = events.find(e => e.type === 'characterCreated' && (e as any).name === '法师小强');
    expect(charCreatedEvent).toBeDefined();

    // M1: Delete character
    await session.deleteCharacter('法师小强');
    const charDeletedEvent = events.find(e => e.type === 'characterDeleted' && (e as any).name === '法师小强');
    expect(charDeletedEvent).toBeDefined();

    // M1 -> M2: Select character & Enter Game
    await session.selectCharacter('传奇战士');

    // Wait briefly for RunLogin and initial packets
    await new Promise(r => setTimeout(r, 100));

    expect(session.state).toBe(SessionState.IN_GAME);
    expect(session.characterName).toBe('传奇战士');
    expect(session.playerId).toBeGreaterThan(0);
    expect(session.mapId).toBe('0');
    expect(session.mapTitle).toBe('比奇省');

    const mapEnteredEvent = events.find(e => e.type === 'mapEntered');
    expect(mapEnteredEvent).toBeDefined();

    // M2: Movement (Turn, Walk, Run)
    const initialX = session.x;
    const initialY = session.y;

    // Turn Right
    await session.turn(Direction.RIGHT);
    await new Promise(r => setTimeout(r, 50));
    expect(session.direction).toBe(Direction.RIGHT);

    // Walk Right (1 cell)
    await session.walk(Direction.RIGHT);
    await new Promise(r => setTimeout(r, 50));
    expect(session.x).toBe(initialX + 1);
    expect(session.y).toBe(initialY);

    // Run Down (2 cells)
    const afterWalkX = session.x;
    const afterWalkY = session.y;
    await session.run(Direction.DOWN);
    await new Promise(r => setTimeout(r, 50));
    expect(session.x).toBe(afterWalkX);
    expect(session.y).toBe(afterWalkY + 2);

    // M3: Combat (Attack Chicken at 12, 10)
    // Player is currently at (11, 12). Run UP to (11, 10)
    await session.run(Direction.UP);
    await new Promise(r => setTimeout(r, 50));
    expect(session.x).toBe(11);
    expect(session.y).toBe(10);

    // Now face RIGHT and attack Chicken at (12, 10)
    await session.attack('HIT', Direction.RIGHT);
    await new Promise(r => setTimeout(r, 50));

    const struckEvent = events.find(e => e.type === 'struck');
    expect(struckEvent).toBeDefined();

    const deathEvent = events.find(e => e.type === 'death');
    expect(deathEvent).toBeDefined();

    const expEvent = events.find(e => e.type === 'experienceGained');
    expect(expEvent).toBeDefined();

    const itemShowEvent = events.find(e => e.type === 'itemShow');
    expect(itemShowEvent).toBeDefined();
    expect((itemShowEvent as any).item.name).toBe('鸡肉');

    // M3: Pickup item at (12, 10)
    // Walk to (12, 10) where the item dropped
    await session.walk(Direction.RIGHT);
    await new Promise(r => setTimeout(r, 50));
    expect(session.x).toBe(12);
    expect(session.y).toBe(10);

    await session.pickup();
    await new Promise(r => setTimeout(r, 50));

    const itemHideEvent = events.find(e => e.type === 'itemHide');
    expect(itemHideEvent).toBeDefined();

    const itemAddedEvent = events.find(e => e.type === 'itemAdded');
    expect(itemAddedEvent).toBeDefined();
    expect((itemAddedEvent as any).item.item.name).toBe('鸡肉');

    // M3: Bag query
    await session.queryBagItems();
    await new Promise(r => setTimeout(r, 50));
    const bagUpdatedEvent = events.find(e => e.type === 'bagUpdated');
    expect(bagUpdatedEvent).toBeDefined();
    expect((bagUpdatedEvent as any).items.some((i: any) => i.item.name === '鸡肉')).toBe(true);

    // Disconnect
    session.disconnect();
    expect(session.state).toBe(SessionState.DISCONNECTED);
  });
});
