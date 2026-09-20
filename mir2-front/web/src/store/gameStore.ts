import {
  CharacterInfo,
  Direction,
  GroundItem,
  BackpackItem,
  WorldObjectSnapshot,
  ChatMessage
} from '@mir2/shared';

export interface LogEntry {
  id: string;
  level: 'info' | 'warn' | 'error' | 'debug';
  message: string;
  timestamp: number;
  gate?: string;
}

export interface DamageNumber {
  id: string;
  text: string;
  x: number;
  y: number;
  color: string;
  timestamp: number;
}

export interface AttackEffect {
  id: string;
  x: number;
  y: number;
  direction: Direction;
  timestamp: number;
}

export type ConnectionPhase = 'DISCONNECTED' | 'LOGGING_IN' | 'LOGGED_IN' | 'ENTERING_GAME' | 'IN_GAME';

export interface GameState {
  phase: ConnectionPhase;
  wsUrl: string;
  targetHost: string;
  loginPort: number;
  account: string;
  serverName: string;

  // Characters
  characters: CharacterInfo[];
  selectedCharacter: string | null;
  isCreateModalOpen: boolean;

  // Player in-game state
  playerId: number;
  playerName: string;
  mapId: string;
  mapTitle: string;
  x: number;
  y: number;
  direction: Direction;
  hp: number;
  maxHp: number;
  mp: number;
  maxMp: number;
  level: number;
  exp: number;
  feature: number;
  dayBright: number;

  // Visible entities
  visibleObjects: Map<number, WorldObjectSnapshot>;
  visibleItems: Map<number, GroundItem>;
  backpack: BackpackItem[];
  chatMessages: ChatMessage[];

  // Visual effects & combat feedback
  damageNumbers: DamageNumber[];
  attackEffects: AttackEffect[];
  lastAck: { action: string; ok: boolean; tick?: number; timestamp: number } | null;

  // UI state
  activeTab: 'game' | 'inventory' | 'help';
  logs: LogEntry[];
  logFilter: 'ALL' | 'LOGIN' | 'SELECT' | 'GAME' | 'WARN_ERROR';
}

export const initialGameState: GameState = {
  phase: 'DISCONNECTED',
  wsUrl: typeof window !== 'undefined'
    ? `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws`
    : 'ws://127.0.0.1:8080/ws',
  targetHost: '127.0.0.1',
  loginPort: 7000,
  account: 'hero',
  serverName: 'MIR2',

  characters: [],
  selectedCharacter: null,
  isCreateModalOpen: false,

  playerId: 0,
  playerName: '',
  mapId: '0',
  mapTitle: '',
  x: 0,
  y: 0,
  direction: Direction.DOWN,
  hp: 100,
  maxHp: 100,
  mp: 50,
  maxMp: 50,
  level: 1,
  exp: 0,
  feature: 0,
  dayBright: 0,

  visibleObjects: new Map(),
  visibleItems: new Map(),
  backpack: [],
  chatMessages: [],

  damageNumbers: [],
  attackEffects: [],
  lastAck: null,

  activeTab: 'game',
  logs: [],
  logFilter: 'ALL'
};
