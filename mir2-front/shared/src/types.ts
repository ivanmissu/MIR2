import { Direction, Job, Gender, WorldObjectType } from './constants.js';

export interface CharacterInfo {
  name: string;
  job: Job;
  hair: number;
  level: number;
  gender: Gender;
}

export interface Position {
  x: number;
  y: number;
}

export interface Ability {
  level: number;
  hp: number;
  maxHp: number;
  mp: number;
  maxMp: number;
  ac: number;
  mac: number;
  dc: number;
  mc: number;
  sc: number;
  exp: number;
  maxExp: number;
  weight: number;
  maxWeight: number;
}

export interface WorldObjectSnapshot {
  id: number;
  name: string;
  type: WorldObjectType;
  mapId: string;
  x: number;
  y: number;
  direction: Direction;
  feature: number;
  status: number;
  hp: number;
  maxHp: number;
  saying?: string;
  sayingUntil?: number;
}

export interface StdItemData {
  name: string;
  stdMode: number;
  shape: number;
  weight: number;
  aniCount: number;
  source: number;
  needIdentify: number;
  looks: number;
  duraMax: number;
  ac: number;
  mac: number;
  dc: number;
  mc: number;
  sc: number;
  need: number;
  needLevel: number;
  price: number;
}

export interface BackpackItem {
  item: StdItemData;
  makeIndex: number;
  dura: number;
  duraMax: number;
}

export interface GroundItem {
  id: number;
  name: string;
  looks: number;
  mapId: string;
  x: number;
  y: number;
}

/** An item currently occupying one of the 13 Delphi equipment slots. */
export interface EquipmentItem {
  slot: number;
  item: BackpackItem;
}

export interface ChatMessage {
  speakerId: number;
  speakerName: string;
  message: string;
  scope: 'normal' | 'whisper' | 'shout' | 'system';
  timestamp: number;
}

// ----------------------------------------------------
// WebSocket Client -> Bridge Commands
// ----------------------------------------------------
export type ClientCommand =
  | { type: 'login'; account: string; password: string; serverName?: string; targetHost?: string; targetPort?: number }
  | { type: 'queryCharacters' }
  | { type: 'createCharacter'; name: string; job: number; gender: number; hair: number }
  | { type: 'deleteCharacter'; name: string }
  | { type: 'selectCharacter'; name: string }
  | { type: 'walk'; direction: Direction }
  | { type: 'run'; direction: Direction }
  | { type: 'turn'; direction: Direction }
  | { type: 'attack'; kind?: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT'; direction?: Direction }
  | { type: 'pickup' }
  | { type: 'openDoor'; x: number; y: number }
  | { type: 'say'; message: string }
  | { type: 'queryBagItems' }
  | { type: 'equip'; slot: number; makeIndex: number; itemName: string }
  | { type: 'unequip'; slot: number; makeIndex: number; itemName: string }
  | { type: 'eat'; makeIndex: number; itemName: string }
  | { type: 'drop'; makeIndex: number; itemName: string }
  | { type: 'merchantLabel'; merchantId?: number; label: '@repair' | '@s_repair' }
  | { type: 'queryRepairCost'; makeIndex: number; itemName: string }
  | { type: 'repairItem'; makeIndex: number; itemName: string }
  | { type: 'disconnect' };

// ----------------------------------------------------
// WebSocket Bridge -> Client Events
// ----------------------------------------------------
export type BridgeEvent =
  | { type: 'loginResult'; ok: boolean; reason?: string; serverName?: string }
  | { type: 'characterList'; characters: CharacterInfo[] }
  | { type: 'characterCreated'; ok: boolean; name?: string; level?: number; reason?: string }
  | { type: 'characterDeleted'; ok: boolean; name?: string; reason?: string }
  | {
      type: 'mapEntered';
      playerId: number;
      name: string;
      mapId: string;
      mapTitle: string;
      x: number;
      y: number;
      direction: Direction;
      hp: number;
      maxHp: number;
      mp: number;
      maxMp: number;
      feature: number;
      dayBright?: number;
      visibleObjects: WorldObjectSnapshot[];
      visibleItems: GroundItem[];
    }
  | { type: 'mapChanged'; mapId: string; mapTitle: string; x: number; y: number; dayBright?: number }
  | { type: 'dayChanging'; gameTime: number; dayBright: number }
  | { type: 'chat'; speakerId: number; speakerName: string; message: string; scope: 'normal' | 'whisper' | 'shout' | 'system'; timestamp: number }
  | { type: 'doorOpened'; mapId?: string; x: number; y: number }
  | { type: 'doorClosed'; mapId?: string; x: number; y: number }
  | { type: 'objectAppeared'; object: WorldObjectSnapshot }
  | { type: 'objectMoved'; objectId: number; x: number; y: number; direction: Direction; movement: 'walk' | 'run' }
  | { type: 'objectTurned'; objectId: number; x: number; y: number; direction: Direction }
  | { type: 'objectDisappeared'; objectId: number }
  | { type: 'actionResult'; action: 'walk' | 'run' | 'turn' | 'attack' | 'pickup'; ok: boolean; tick?: number }
  | { type: 'struck'; victimId: number; attackerId: number; damage: number; hp: number; maxHp: number }
  | { type: 'death'; victimId: number; x: number; y: number; direction: Direction }
  | { type: 'healthChanged'; objectId: number; hp: number; mp: number; maxHp: number }
  | { type: 'experienceGained'; gained: number; total: number }
  | { type: 'itemShow'; item: GroundItem }
  | { type: 'itemHide'; itemId: number }
  | { type: 'bagUpdated'; items: BackpackItem[] }
  | { type: 'itemAdded'; item: BackpackItem }
  | { type: 'itemRemoved'; makeIndex: number; name: string }
  | { type: 'equipmentUpdated'; items: EquipmentItem[] }
  | { type: 'abilityUpdated'; ability: Ability; gold: number; job: Job }
  | { type: 'goldChanged'; gold: number }
  | { type: 'weightChanged'; weight: number; wearWeight: number; handWeight: number }
  | { type: 'durabilityChanged'; slot: number; makeIndex: number; dura: number; duraMax: number; broken: boolean }
  | { type: 'levelUp'; level: number; exp: number }
  | { type: 'repairDialog'; merchantId: number }
  | { type: 'repairCost'; cost: number }
  | { type: 'repairResult'; ok: boolean; gold?: number; dura?: number; duraMax?: number }
  | { type: 'actionError'; action: string; message: string }
  | { type: 'log'; level: 'info' | 'warn' | 'error' | 'debug'; message: string; timestamp?: number; gate?: string }
  | { type: 'disconnected'; reason: string };
