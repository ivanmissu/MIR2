import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { MockMirServer } from '../src/tcp/MockMirServer.js';
import { GateSession, SessionState } from '../src/session/GateSession.js';
import { Direction, BridgeEvent, Job, Gender } from '@mir2/shared';

describe('Chat, Day/Night Cycle & Door Interaction', () => {
  let server: MockMirServer;
  const loginPort = 27010;
  const selectPort = 27110;
  const gamePort = 27210;

  beforeAll(async () => {
    server = new MockMirServer('127.0.0.1', loginPort, selectPort, gamePort);
    await server.start();
  });

  afterAll(async () => {
    await server.stop();
  });

  it('handles local chat broadcast between nearby players', async () => {
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

    await sessionA.login('userChatA', 'passA');
    await sessionA.createCharacter('战士A', Job.WARRIOR, Gender.MALE, 1);
    await sessionA.selectCharacter('战士A');
    await new Promise(r => setTimeout(r, 100));

    await sessionB.login('userChatB', 'passB');
    await sessionB.createCharacter('法师B', Job.WIZARD, Gender.FEMALE, 2);
    await sessionB.selectCharacter('法师B');
    await new Promise(r => setTimeout(r, 100));

    expect(sessionA.state).toBe(SessionState.IN_GAME);
    expect(sessionB.state).toBe(SessionState.IN_GAME);

    eventsA.length = 0;
    eventsB.length = 0;

    // Session A speaks
    await sessionA.say('大家好，传奇世界！');
    await new Promise(r => setTimeout(r, 50));

    // Session A should receive chat echo
    const chatOnA = eventsA.find(
      e => e.type === 'chat' && (e as any).message === '大家好，传奇世界！'
    );
    expect(chatOnA).toBeDefined();
    if (chatOnA && chatOnA.type === 'chat') {
      expect(chatOnA.speakerName).toBe('战士A');
      expect(chatOnA.scope).toBe('normal');
    }

    // Session B should receive chat
    const chatOnB = eventsB.find(
      e => e.type === 'chat' && (e as any).message === '大家好，传奇世界！'
    );
    expect(chatOnB).toBeDefined();
    if (chatOnB && chatOnB.type === 'chat') {
      expect(chatOnB.speakerName).toBe('战士A');
      expect(chatOnB.scope).toBe('normal');
    }

    // Door interaction test
    eventsA.length = 0;
    await sessionA.openDoor(10, 10);
    await new Promise(r => setTimeout(r, 50));

    const doorOpenedEvent = eventsA.find(e => e.type === 'doorOpened');
    expect(doorOpenedEvent).toBeDefined();
    if (doorOpenedEvent && doorOpenedEvent.type === 'doorOpened') {
      expect(doorOpenedEvent.x).toBe(10);
      expect(doorOpenedEvent.y).toBe(10);
    }

    // Day/Night broadcast test
    eventsA.length = 0;
    server.broadcastDayChange(3, 1); // night time, dark
    await new Promise(r => setTimeout(r, 50));

    expect(sessionA.dayBright).toBe(1);
    const dayEvent = eventsA.find(e => e.type === 'dayChanging');
    expect(dayEvent).toBeDefined();
    if (dayEvent && dayEvent.type === 'dayChanging') {
      expect(dayEvent.gameTime).toBe(3);
      expect(dayEvent.dayBright).toBe(1);
    }

    sessionA.disconnect();
    sessionB.disconnect();
  });
});
