import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MockMirServer } from '../src/tcp/MockMirServer.js';
import { GateSession, SessionState } from '../src/session/GateSession.js';
import { Direction, BridgeEvent, Job, Gender } from '@mir2/shared';

describe('Multi-Session & Observer Visibility (M4)', () => {
  let server: MockMirServer;
  const loginPort = 27000;
  const selectPort = 27100;
  const gamePort = 27200;

  beforeAll(async () => {
    server = new MockMirServer('127.0.0.1', loginPort, selectPort, gamePort);
    await server.start();
  });

  afterAll(async () => {
    await server.stop();
  });

  it('allows two sessions to see each other, track movement, and observe disconnect', async () => {
    const sessionA = new GateSession({
      targetHost: '127.0.0.1',
      loginPort,
      selectPort,
      gamePort
    });
    const sessionB = new GateSession({
      targetHost: '127.0.0.1',
      loginPort,
      selectPort,
      gamePort
    });

    const eventsA: BridgeEvent[] = [];
    const eventsB: BridgeEvent[] = [];

    sessionA.on('event', e => eventsA.push(e));
    sessionB.on('event', e => eventsB.push(e));

    // Player A logs in and enters
    await sessionA.login('userA', 'passA');
    await sessionA.createCharacter('玩家甲', Job.WARRIOR, Gender.MALE, 1);
    await sessionA.selectCharacter('玩家甲');
    await new Promise(r => setTimeout(r, 100));

    expect(sessionA.state).toBe(SessionState.IN_GAME);
    const playerAId = sessionA.playerId;

    // Player B logs in and enters
    await sessionB.login('userB', 'passB');
    await sessionB.createCharacter('玩家乙', Job.WIZARD, Gender.FEMALE, 2);
    await sessionB.selectCharacter('玩家乙');
    await new Promise(r => setTimeout(r, 100));

    expect(sessionB.state).toBe(SessionState.IN_GAME);
    const playerBId = sessionB.playerId;

    // Session A should see Player B appeared
    const bAppearedOnA = eventsA.find(
      e => e.type === 'objectAppeared' && (e as any).object.id === playerBId
    );
    expect(bAppearedOnA).toBeDefined();

    // Session B should have Player A in visibleObjects
    expect(sessionB.visibleObjects.has(playerAId)).toBe(true);

    // Player B walks
    eventsA.length = 0;
    await sessionB.walk(Direction.RIGHT);
    await new Promise(r => setTimeout(r, 50));

    const bMovedOnA = eventsA.find(
      e => e.type === 'objectMoved' && (e as any).objectId === playerBId
    );
    expect(bMovedOnA).toBeDefined();

    // Player B disconnects
    eventsA.length = 0;
    sessionB.disconnect();
    await new Promise(r => setTimeout(r, 100));

    const bDisappearedOnA = eventsA.find(
      e => e.type === 'objectDisappeared' && (e as any).objectId === playerBId
    );
    expect(bDisappearedOnA).toBeDefined();

    sessionA.disconnect();
  });
});
