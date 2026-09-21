import React from 'react';
import { GameState } from '../store/gameStore.js';
import { Direction, WorldObjectType } from '@mir2/shared';
import { Heart, Activity, Compass, MapPin, Eye, Award, Coins } from 'lucide-react';

interface StatusPanelProps {
  state: GameState;
}

export const StatusPanel: React.FC<StatusPanelProps> = ({ state }) => {
  const hpPercent = Math.max(0, Math.min(100, (state.hp / (state.maxHp || 100)) * 100));
  const mpPercent = Math.max(0, Math.min(100, (state.mp / (state.maxMp || 50)) * 100));

  let playersCount = 0;
  let monstersCount = 0;
  for (const obj of state.visibleObjects.values()) {
    if (obj.id === state.playerId) continue;
    if (obj.type === WorldObjectType.MONSTER) monstersCount++;
    else playersCount++;
  }
  const groundItemsCount = state.visibleItems.size;

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-4">
      <div className="flex items-center justify-between pb-3 border-b border-slate-800">
        <div className="flex items-center space-x-2.5">
          <div className="w-8 h-8 rounded-lg bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-amber-400 font-bold text-sm">
            Lv.{state.level}
          </div>
          <div>
            <h3 className="font-bold text-slate-100 text-sm">{state.playerName || '玩家'}</h3>
            <span className="text-[11px] text-slate-400 font-mono">ID: #{state.playerId}</span>
          </div>
        </div>

        {/* Action ACK feedback badge */}
        {state.lastAck && (
          <div
            className={`px-2 py-0.5 rounded text-[11px] font-mono border flex items-center gap-1 ${
              state.lastAck.ok
                ? 'bg-emerald-950/80 border-emerald-800 text-emerald-400'
                : 'bg-rose-950/80 border-rose-800 text-rose-400'
            }`}
          >
            <span>{state.lastAck.action}</span>
            <span className="font-bold">{state.lastAck.ok ? '+GOOD' : '+FAIL'}</span>
          </div>
        )}
      </div>

      {/* HP Bar */}
      <div>
        <div className="flex items-center justify-between text-xs font-semibold mb-1">
          <span className="text-red-400 flex items-center gap-1">
            <Heart className="w-3.5 h-3.5 fill-current" />
            生命值 (HP)
          </span>
          <span className="font-mono text-slate-300">
            {state.hp} / {state.maxHp}
          </span>
        </div>
        <div className="w-full h-3 bg-slate-950 rounded-full overflow-hidden border border-slate-800 p-0.5">
          <div
            className="h-full rounded-full transition-all duration-300 bg-gradient-to-r from-red-600 to-red-400 shadow-sm shadow-red-500/50"
            style={{ width: `${hpPercent}%` }}
          />
        </div>
      </div>

      {/* MP Bar */}
      <div>
        <div className="flex items-center justify-between text-xs font-semibold mb-1">
          <span className="text-blue-400 flex items-center gap-1">
            <Activity className="w-3.5 h-3.5" />
            魔法值 (MP)
          </span>
          <span className="font-mono text-slate-300">
            {state.mp} / {state.maxMp}
          </span>
        </div>
        <div className="w-full h-2.5 bg-slate-950 rounded-full overflow-hidden border border-slate-800 p-0.5">
          <div
            className="h-full rounded-full transition-all duration-300 bg-gradient-to-r from-blue-600 to-blue-400 shadow-sm shadow-blue-500/50"
            style={{ width: `${mpPercent}%` }}
          />
        </div>
      </div>

      {/* EXP Bar */}
      <div>
        <div className="flex items-center justify-between text-xs font-semibold mb-1">
          <span className="text-amber-400 flex items-center gap-1">
            <Award className="w-3.5 h-3.5" />
            累计经验 (EXP)
          </span>
          <span className="font-mono text-slate-300">{state.exp}</span>
        </div>
      </div>

      {/* W15 wallet and weight buckets */}
      <div className="grid grid-cols-3 gap-2 text-xs">
        <div className="p-2 bg-slate-950 rounded-xl border border-slate-800/80">
          <div className="text-[10px] text-slate-400 flex items-center gap-1"><Coins className="w-3 h-3 text-amber-400" />金币</div>
          <div className="font-mono font-bold text-amber-300">{state.gold}</div>
        </div>
        <div className="p-2 bg-slate-950 rounded-xl border border-slate-800/80">
          <div className="text-[10px] text-slate-400">负重</div>
          <div className="font-mono font-bold text-slate-200">{state.weight}</div>
        </div>
        <div className="p-2 bg-slate-950 rounded-xl border border-slate-800/80">
          <div className="text-[10px] text-slate-400">装备/手持</div>
          <div className="font-mono font-bold text-slate-200">{state.wearWeight}/{state.handWeight}</div>
        </div>
      </div>

      {/* Coordinates & Location Card */}
      <div className="grid grid-cols-2 gap-2 pt-1 text-xs">
        <div className="p-2.5 bg-slate-950 rounded-xl border border-slate-800/80">
          <div className="text-[11px] text-slate-400 flex items-center gap-1 mb-1">
            <MapPin className="w-3 h-3 text-amber-400" />
            当前坐标
          </div>
          <div className="font-mono font-bold text-slate-200">
            ({state.x}, {state.y})
          </div>
        </div>

        <div className="p-2.5 bg-slate-950 rounded-xl border border-slate-800/80">
          <div className="text-[11px] text-slate-400 flex items-center gap-1 mb-1">
            <Compass className="w-3 h-3 text-blue-400" />
            当前朝向
          </div>
          <div className="font-bold text-slate-200">
            {getDirectionText(state.direction)}
          </div>
        </div>
      </div>

      {/* Surrounding Entities Counts */}
      <div className="p-3 bg-slate-950/60 rounded-xl border border-slate-800/80 text-xs">
        <div className="text-[11px] font-semibold text-slate-400 mb-2 flex items-center gap-1">
          <Eye className="w-3.5 h-3.5 text-slate-400" />
          视野内对象快照 (12 格广播范围)
        </div>
        <div className="grid grid-cols-3 gap-2 text-center">
          <div>
            <div className="text-sm font-mono font-bold text-blue-400">{playersCount}</div>
            <div className="text-[10px] text-slate-400">其他玩家</div>
          </div>
          <div>
            <div className="text-sm font-mono font-bold text-red-400">{monstersCount}</div>
            <div className="text-[10px] text-slate-400">怪物</div>
          </div>
          <div>
            <div className="text-sm font-mono font-bold text-amber-400">{groundItemsCount}</div>
            <div className="text-[10px] text-slate-400">地面物品</div>
          </div>
        </div>
      </div>
    </div>
  );
};

function getDirectionText(dir: Direction): string {
  switch (dir) {
    case Direction.UP:
      return '上 (UP)';
    case Direction.UP_RIGHT:
      return '右上 (UP_RIGHT)';
    case Direction.RIGHT:
      return '右 (RIGHT)';
    case Direction.DOWN_RIGHT:
      return '右下 (DOWN_RIGHT)';
    case Direction.DOWN:
      return '下 (DOWN)';
    case Direction.DOWN_LEFT:
      return '左下 (DOWN_LEFT)';
    case Direction.LEFT:
      return '左 (LEFT)';
    case Direction.UP_LEFT:
      return '左上 (UP_LEFT)';
    default:
      return `${dir}`;
  }
}
