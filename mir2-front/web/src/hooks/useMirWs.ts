import { useState, useEffect, useRef, useCallback } from 'react';
import {
  BridgeEvent,
  ClientCommand,
  Direction,
  Job,
  Gender
} from '@mir2/shared';
import { GameState, initialGameState, LogEntry, DamageNumber } from '../store/gameStore.js';

export function useMirWs() {
  const [state, setState] = useState<GameState>(initialGameState);
  const wsRef = useRef<WebSocket | null>(null);
  // Commands issued before the socket finishes its handshake are queued here and
  // flushed on 'open'. The WS handshake latency is unbounded (remote preview
  // proxies / slow networks), so it must never be guessed with a fixed timeout.
  const pendingCommandsRef = useRef<ClientCommand[]>([]);

  const addLog = useCallback((entry: Omit<LogEntry, 'id'>) => {
    setState(prev => {
      const newEntry: LogEntry = {
        id: Math.random().toString(36).substring(2, 9),
        ...entry
      };
      const logs = [...prev.logs, newEntry];
      // Keep up to 500 logs
      if (logs.length > 500) logs.shift();
      return { ...prev, logs };
    });
  }, []);

  const sendCommand = useCallback((cmd: ClientCommand) => {
    const ws = wsRef.current;
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(cmd));
      return;
    }
    if (ws && ws.readyState === WebSocket.CONNECTING) {
      // Still handshaking: queue and let onopen flush it.
      pendingCommandsRef.current.push(cmd);
      return;
    }
    addLog({
      level: 'warn',
      message: 'WebSocket 尚未连接，无法发送指令',
      timestamp: Date.now()
    });
  }, [addLog]);

  const handleBridgeEvent = useCallback((event: BridgeEvent) => {
    switch (event.type) {
      case 'log': {
        addLog({
          level: event.level,
          message: event.message,
          timestamp: event.timestamp || Date.now(),
          gate: event.gate
        });
        break;
      }

      case 'loginResult': {
        setState(prev => {
          if (event.ok) {
            return {
              ...prev,
              phase: 'LOGGED_IN',
              serverName: event.serverName || prev.serverName
            };
          } else {
            return {
              ...prev,
              phase: 'DISCONNECTED'
            };
          }
        });
        break;
      }

      case 'characterList': {
        setState(prev => ({
          ...prev,
          characters: event.characters,
          selectedCharacter: event.characters.length > 0 ? event.characters[0].name : null
        }));
        break;
      }

      case 'characterCreated': {
        if (event.ok) {
          setState(prev => ({ ...prev, isCreateModalOpen: false }));
        }
        break;
      }

      case 'characterDeleted': {
        break;
      }

      case 'mapEntered': {
        setState(prev => {
          const objMap = new Map();
          for (const obj of event.visibleObjects) {
            objMap.set(obj.id, obj);
          }
          const itemMap = new Map();
          for (const itm of event.visibleItems) {
            itemMap.set(itm.id, itm);
          }

          return {
            ...prev,
            phase: 'IN_GAME',
            playerId: event.playerId,
            playerName: event.name,
            mapId: event.mapId,
            mapTitle: event.mapTitle,
            x: event.x,
            y: event.y,
            direction: event.direction,
            hp: event.hp,
            maxHp: event.maxHp,
            mp: event.mp,
            maxMp: event.maxMp,
            feature: event.feature,
            dayBright: event.dayBright ?? 0,
            visibleObjects: objMap,
            visibleItems: itemMap
          };
        });
        break;
      }

      case 'mapChanged': {
        setState(prev => ({
          ...prev,
          mapId: event.mapId,
          mapTitle: event.mapTitle,
          x: event.x,
          y: event.y,
          dayBright: event.dayBright ?? prev.dayBright,
          visibleObjects: new Map(),
          visibleItems: new Map()
        }));
        addLog({
          level: 'info',
          message: `[地图切换] 进入 "${event.mapTitle}" (${event.mapId}) 坐标: (${event.x}, ${event.y})`,
          timestamp: Date.now(),
          gate: 'GAME'
        });
        break;
      }

      case 'dayChanging': {
        setState(prev => ({
          ...prev,
          dayBright: event.dayBright
        }));
        addLog({
          level: 'info',
          message: `[昼夜] 昼夜变化: gameTime=${event.gameTime}, 亮暗=${event.dayBright === 0 ? '白天' : event.dayBright === 1 ? '黑夜' : '黄昏'}`,
          timestamp: Date.now(),
          gate: 'GAME'
        });
        break;
      }

      case 'doorOpened': {
        addLog({
          level: 'info',
          message: `[门] 门已开启: (${event.x}, ${event.y})`,
          timestamp: Date.now(),
          gate: 'GAME'
        });
        break;
      }

      case 'doorClosed': {
        addLog({
          level: 'info',
          message: `[门] 门已关闭: (${event.x}, ${event.y})`,
          timestamp: Date.now(),
          gate: 'GAME'
        });
        break;
      }

      case 'chat': {
        setState(prev => {
          const nextObjects = new Map(prev.visibleObjects);
          if (event.speakerId !== prev.playerId) {
            const obj = nextObjects.get(event.speakerId);
            if (obj) {
              nextObjects.set(event.speakerId, {
                ...obj,
                saying: event.message,
                sayingUntil: Date.now() + 4000
              });
            }
          }
          return {
            ...prev,
            visibleObjects: nextObjects,
            chatMessages: [...prev.chatMessages, event]
          };
        });
        addLog({
          level: 'info',
          message: `[${event.scope.toUpperCase()}] ${event.speakerName}: ${event.message}`,
          timestamp: event.timestamp || Date.now(),
          gate: 'CHAT'
        });
        break;
      }

      case 'objectAppeared': {
        setState(prev => {
          const nextMap = new Map(prev.visibleObjects);
          nextMap.set(event.object.id, event.object);
          return { ...prev, visibleObjects: nextMap };
        });
        break;
      }

      case 'objectMoved': {
        setState(prev => {
          const nextMap = new Map(prev.visibleObjects);
          const existing = nextMap.get(event.objectId);
          if (existing) {
            nextMap.set(event.objectId, {
              ...existing,
              x: event.x,
              y: event.y,
              direction: event.direction
            });
          }
          return { ...prev, visibleObjects: nextMap };
        });
        break;
      }

      case 'objectTurned': {
        setState(prev => {
          const nextMap = new Map(prev.visibleObjects);
          const existing = nextMap.get(event.objectId);
          if (existing) {
            nextMap.set(event.objectId, {
              ...existing,
              x: event.x,
              y: event.y,
              direction: event.direction
            });
          }
          return { ...prev, visibleObjects: nextMap };
        });
        break;
      }

      case 'objectDisappeared': {
        setState(prev => {
          const nextMap = new Map(prev.visibleObjects);
          nextMap.delete(event.objectId);
          return { ...prev, visibleObjects: nextMap };
        });
        break;
      }

      case 'actionResult': {
        setState(prev => ({
          ...prev,
          lastAck: {
            action: event.action,
            ok: event.ok,
            tick: event.tick,
            timestamp: Date.now()
          }
        }));
        break;
      }

      case 'struck': {
        setState(prev => {
          const damageId = Math.random().toString(36).substring(2, 9);
          const isSelf = event.victimId === prev.playerId;

          let targetX = prev.x;
          let targetY = prev.y;

          if (!isSelf) {
            const victim = prev.visibleObjects.get(event.victimId);
            if (victim) {
              targetX = victim.x;
              targetY = victim.y;
            }
          }

          const newDmg: DamageNumber = {
            id: damageId,
            text: `-${event.damage}`,
            x: targetX,
            y: targetY,
            color: isSelf ? '#ef4444' : '#f59e0b',
            timestamp: Date.now()
          };

          const nextObjects = new Map(prev.visibleObjects);
          if (!isSelf) {
            const victim = nextObjects.get(event.victimId);
            if (victim) {
              nextObjects.set(event.victimId, {
                ...victim,
                hp: event.hp,
                maxHp: event.maxHp
              });
            }
          }

          return {
            ...prev,
            hp: isSelf ? event.hp : prev.hp,
            maxHp: isSelf ? event.maxHp : prev.maxHp,
            visibleObjects: nextObjects,
            damageNumbers: [...prev.damageNumbers, newDmg]
          };
        });
        break;
      }

      case 'death': {
        setState(prev => {
          const nextObjects = new Map(prev.visibleObjects);
          if (event.victimId !== prev.playerId) {
            nextObjects.delete(event.victimId);
          }
          return {
            ...prev,
            hp: event.victimId === prev.playerId ? 0 : prev.hp,
            visibleObjects: nextObjects
          };
        });
        break;
      }

      case 'healthChanged': {
        setState(prev => {
          if (event.objectId === prev.playerId) {
            return {
              ...prev,
              hp: event.hp,
              mp: event.mp,
              maxHp: event.maxHp
            };
          }
          const nextObjects = new Map(prev.visibleObjects);
          const obj = nextObjects.get(event.objectId);
          if (obj) {
            nextObjects.set(event.objectId, {
              ...obj,
              hp: event.hp,
              maxHp: event.maxHp
            });
          }
          return { ...prev, visibleObjects: nextObjects };
        });
        break;
      }

      case 'experienceGained': {
        setState(prev => ({
          ...prev,
          exp: event.total
        }));
        break;
      }

      case 'itemShow': {
        setState(prev => {
          const nextItems = new Map(prev.visibleItems);
          nextItems.set(event.item.id, event.item);
          return { ...prev, visibleItems: nextItems };
        });
        break;
      }

      case 'itemHide': {
        setState(prev => {
          const nextItems = new Map(prev.visibleItems);
          nextItems.delete(event.itemId);
          return { ...prev, visibleItems: nextItems };
        });
        break;
      }

      case 'bagUpdated': {
        setState(prev => ({
          ...prev,
          backpack: event.items
        }));
        break;
      }

      case 'itemAdded': {
        setState(prev => ({
          ...prev,
          backpack: [...prev.backpack, event.item]
        }));
        break;
      }

      case 'disconnected': {
        setState(prev => ({
          ...prev,
          phase: 'DISCONNECTED'
        }));
        break;
      }
    }
  }, [addLog]);

  const connect = useCallback((customUrl?: string) => {
    const url = customUrl || state.wsUrl;
    if (wsRef.current && (wsRef.current.readyState === WebSocket.OPEN || wsRef.current.readyState === WebSocket.CONNECTING)) {
      return;
    }

    try {
      addLog({
        level: 'info',
        message: `正在连接 Bridge 网关代理: ${url}...`,
        timestamp: Date.now()
      });

      const ws = new WebSocket(url);
      wsRef.current = ws;

      ws.onopen = () => {
        addLog({
          level: 'info',
          message: 'Bridge 网关代理连接成功！',
          timestamp: Date.now()
        });
        // Flush any commands queued while the handshake was in flight.
        const queued = pendingCommandsRef.current;
        pendingCommandsRef.current = [];
        for (const cmd of queued) {
          ws.send(JSON.stringify(cmd));
        }
      };

      ws.onmessage = (event) => {
        try {
          const data = JSON.parse(event.data) as BridgeEvent;
          handleBridgeEvent(data);
        } catch (err) {
          console.error('Failed to parse bridge message:', err);
        }
      };

      ws.onclose = () => {
        // Drop queued commands so they can't leak into a future session.
        pendingCommandsRef.current = [];
        addLog({
          level: 'warn',
          message: 'Bridge 网关代理连接断开',
          timestamp: Date.now()
        });
        setState(prev => ({
          ...prev,
          phase: 'DISCONNECTED'
        }));
      };

      ws.onerror = () => {
        addLog({
          level: 'error',
          message: `Bridge 连接发生错误`,
          timestamp: Date.now()
        });
      };
    } catch (err) {
      addLog({
        level: 'error',
        message: `创建 WebSocket 失败: ${(err as Error).message}`,
        timestamp: Date.now()
      });
    }
  }, [state.wsUrl, addLog, handleBridgeEvent]);

  // Clean up floating damage numbers older than 1 second
  useEffect(() => {
    const interval = setInterval(() => {
      const now = Date.now();
      setState(prev => {
        if (prev.damageNumbers.length === 0 && prev.attackEffects.length === 0) return prev;
        return {
          ...prev,
          damageNumbers: prev.damageNumbers.filter(d => now - d.timestamp < 1000),
          attackEffects: prev.attackEffects.filter(a => now - a.timestamp < 400)
        };
      });
    }, 200);
    return () => clearInterval(interval);
  }, []);

  // Action methods
  const login = useCallback((account: string, pass: string, host?: string, port?: number) => {
    const cmd: ClientCommand = { type: 'login', account, password: pass, targetHost: host, targetPort: port };
    if (!wsRef.current || (wsRef.current.readyState !== WebSocket.OPEN && wsRef.current.readyState !== WebSocket.CONNECTING)) {
      // Queue first, then connect: onopen flushes it regardless of handshake duration.
      pendingCommandsRef.current.push(cmd);
      connect();
    } else {
      sendCommand(cmd);
    }
    setState(prev => ({ ...prev, phase: 'LOGGING_IN', account }));
  }, [connect, sendCommand]);

  const selectCharacter = useCallback((name: string) => {
    setState(prev => ({ ...prev, phase: 'ENTERING_GAME', selectedCharacter: name }));
    sendCommand({ type: 'selectCharacter', name });
  }, [sendCommand]);

  const createCharacter = useCallback((name: string, job: Job, gender: Gender, hair: number) => {
    sendCommand({ type: 'createCharacter', name, job, gender, hair });
  }, [sendCommand]);

  const deleteCharacter = useCallback((name: string) => {
    sendCommand({ type: 'deleteCharacter', name });
  }, [sendCommand]);

  const walk = useCallback((direction: Direction) => {
    sendCommand({ type: 'walk', direction });
  }, [sendCommand]);

  const run = useCallback((direction: Direction) => {
    sendCommand({ type: 'run', direction });
  }, [sendCommand]);

  const turn = useCallback((direction: Direction) => {
    sendCommand({ type: 'turn', direction });
  }, [sendCommand]);

  const attack = useCallback((kind: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT' = 'HIT', direction?: Direction) => {
    const dir = direction !== undefined ? direction : state.direction;
    // Add attack animation effect
    setState(prev => ({
      ...prev,
      attackEffects: [
        ...prev.attackEffects,
        {
          id: Math.random().toString(36).substring(2, 9),
          x: prev.x,
          y: prev.y,
          direction: dir,
          timestamp: Date.now()
        }
      ]
    }));
    sendCommand({ type: 'attack', kind, direction: dir });
  }, [state.direction, sendCommand]);

  const pickup = useCallback(() => {
    sendCommand({ type: 'pickup' });
  }, [sendCommand]);

  const say = useCallback((message: string) => {
    if (!message.trim()) return;
    sendCommand({ type: 'say', message });
  }, [sendCommand]);

  const openDoor = useCallback((x: number, y: number) => {
    sendCommand({ type: 'openDoor', x, y });
  }, [sendCommand]);

  const queryBagItems = useCallback(() => {
    sendCommand({ type: 'queryBagItems' });
  }, [sendCommand]);

  const disconnect = useCallback(() => {
    sendCommand({ type: 'disconnect' });
    if (wsRef.current) {
      wsRef.current.close();
      wsRef.current = null;
    }
    setState(prev => ({ ...prev, phase: 'DISCONNECTED' }));
  }, [sendCommand]);

  const setLogFilter = useCallback((filter: GameState['logFilter']) => {
    setState(prev => ({ ...prev, logFilter: filter }));
  }, []);

  const clearLogs = useCallback(() => {
    setState(prev => ({ ...prev, logs: [] }));
  }, []);

  const setActiveTab = useCallback((tab: GameState['activeTab']) => {
    setState(prev => ({ ...prev, activeTab: tab }));
  }, []);

  const setCreateModalOpen = useCallback((open: boolean) => {
    setState(prev => ({ ...prev, isCreateModalOpen: open }));
  }, []);

  return {
    state,
    setState,
    connect,
    login,
    selectCharacter,
    createCharacter,
    deleteCharacter,
    walk,
    run,
    turn,
    attack,
    pickup,
    say,
    openDoor,
    queryBagItems,
    disconnect,
    setLogFilter,
    clearLogs,
    setActiveTab,
    setCreateModalOpen
  };
}
