import React, { useState } from 'react';
import { GameState } from '../store/gameStore.js';
import { Direction } from '@mir2/shared';
import {
  ArrowUp,
  ArrowDown,
  ArrowLeft,
  ArrowRight,
  ArrowUpLeft,
  ArrowUpRight,
  ArrowDownLeft,
  ArrowDownRight,
  Swords,
  Footprints,
  RotateCcw,
  Hand,
  PackageSearch,
  Flame,
  Zap
} from 'lucide-react';

interface ControlsPanelProps {
  state?: GameState;
  onWalk: (dir: Direction) => void;
  onRun: (dir: Direction) => void;
  onTurn: (dir: Direction) => void;
  onAttack: (kind: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT') => void;
  onPickup: () => void;
  onQueryBag: () => void;
}

export const ControlsPanel: React.FC<ControlsPanelProps> = ({
  onWalk,
  onRun,
  onTurn,
  onAttack,
  onPickup,
  onQueryBag
}) => {
  const [moveMode, setMoveMode] = useState<'walk' | 'run' | 'turn'>('walk');

  const handleDirection = (dir: Direction) => {
    if (moveMode === 'run') {
      onRun(dir);
    } else if (moveMode === 'turn') {
      onTurn(dir);
    } else {
      onWalk(dir);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-5">
      {/* Move Mode Toggle */}
      <div>
        <div className="text-xs font-semibold text-slate-300 mb-2 flex items-center justify-between">
          <span>移动控制模式 (D-Pad)</span>
          <span className="text-[11px] text-slate-400">或直接按 WASD / 方向键</span>
        </div>
        <div className="grid grid-cols-3 gap-1.5 p-1 bg-slate-950 rounded-xl border border-slate-800">
          <button
            onClick={() => setMoveMode('walk')}
            className={`py-1.5 px-2 rounded-lg text-xs font-medium transition-all flex items-center justify-center gap-1 ${
              moveMode === 'walk'
                ? 'bg-amber-500 text-slate-950 font-bold shadow'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Footprints className="w-3.5 h-3.5" />
            走 (1格)
          </button>
          <button
            onClick={() => setMoveMode('run')}
            className={`py-1.5 px-2 rounded-lg text-xs font-medium transition-all flex items-center justify-center gap-1 ${
              moveMode === 'run'
                ? 'bg-amber-500 text-slate-950 font-bold shadow'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <Zap className="w-3.5 h-3.5" />
            跑 (2格)
          </button>
          <button
            onClick={() => setMoveMode('turn')}
            className={`py-1.5 px-2 rounded-lg text-xs font-medium transition-all flex items-center justify-center gap-1 ${
              moveMode === 'turn'
                ? 'bg-amber-500 text-slate-950 font-bold shadow'
                : 'text-slate-400 hover:text-slate-200'
            }`}
          >
            <RotateCcw className="w-3.5 h-3.5" />
            转向 (原地)
          </button>
        </div>
      </div>

      {/* 8-Directional D-Pad */}
      <div className="flex justify-center">
        <div className="grid grid-cols-3 gap-1.5 w-44">
          <button
            onClick={() => handleDirection(Direction.UP_LEFT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="左上 (7)"
          >
            <ArrowUpLeft className="w-4 h-4" />
          </button>
          <button
            onClick={() => handleDirection(Direction.UP)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="上 (8 / W)"
          >
            <ArrowUp className="w-4 h-4" />
          </button>
          <button
            onClick={() => handleDirection(Direction.UP_RIGHT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="右上 (9)"
          >
            <ArrowUpRight className="w-4 h-4" />
          </button>

          <button
            onClick={() => handleDirection(Direction.LEFT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="左 (4 / A)"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div className="p-2.5 bg-slate-950/40 border border-slate-900 rounded-xl text-amber-500/60 flex items-center justify-center text-xs font-mono font-bold">
            8向
          </div>
          <button
            onClick={() => handleDirection(Direction.RIGHT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="右 (6 / D)"
          >
            <ArrowRight className="w-4 h-4" />
          </button>

          <button
            onClick={() => handleDirection(Direction.DOWN_LEFT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="左下 (1)"
          >
            <ArrowDownLeft className="w-4 h-4" />
          </button>
          <button
            onClick={() => handleDirection(Direction.DOWN)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="下 (2 / S)"
          >
            <ArrowDown className="w-4 h-4" />
          </button>
          <button
            onClick={() => handleDirection(Direction.DOWN_RIGHT)}
            className="p-2.5 bg-slate-950 hover:bg-slate-800 border border-slate-800 hover:border-amber-500/50 rounded-xl text-slate-300 hover:text-amber-400 flex items-center justify-center transition-all active:scale-95 cursor-pointer"
            title="右下 (3)"
          >
            <ArrowDownRight className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Combat & Interaction Actions */}
      <div className="space-y-2 pt-2 border-t border-slate-800">
        <div className="text-xs font-semibold text-slate-300 mb-1 flex items-center gap-1.5">
          <Swords className="w-3.5 h-3.5 text-amber-400" />
          战斗与交互操作
        </div>

        <div className="grid grid-cols-3 gap-2">
          <button
            onClick={() => onAttack('HIT')}
            className="py-2 px-2.5 bg-red-950/60 hover:bg-red-900/60 border border-red-800/80 text-red-300 hover:text-red-100 rounded-xl text-xs font-bold transition-all flex flex-col items-center gap-1 active:scale-95 cursor-pointer"
            title="普通攻击 (Space / 1)"
          >
            <Swords className="w-4 h-4 text-red-400" />
            <span>普攻 (HIT)</span>
          </button>

          <button
            onClick={() => onAttack('HEAVY_HIT')}
            className="py-2 px-2.5 bg-orange-950/60 hover:bg-orange-900/60 border border-orange-800/80 text-orange-300 hover:text-orange-100 rounded-xl text-xs font-bold transition-all flex flex-col items-center gap-1 active:scale-95 cursor-pointer"
            title="重击攻击 (2)"
          >
            <Flame className="w-4 h-4 text-orange-400" />
            <span>重击 (HEAVY)</span>
          </button>

          <button
            onClick={() => onAttack('BIG_HIT')}
            className="py-2 px-2.5 bg-amber-950/60 hover:bg-amber-900/60 border border-amber-800/80 text-amber-300 hover:text-amber-100 rounded-xl text-xs font-bold transition-all flex flex-col items-center gap-1 active:scale-95 cursor-pointer"
            title="大砍攻击 (3)"
          >
            <Zap className="w-4 h-4 text-amber-400" />
            <span>大砍 (BIG)</span>
          </button>
        </div>

        <div className="grid grid-cols-2 gap-2 pt-1">
          <button
            onClick={onPickup}
            className="py-2.5 px-3 bg-emerald-950/60 hover:bg-emerald-900/60 border border-emerald-800/80 text-emerald-300 hover:text-emerald-100 rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-1.5 active:scale-95 cursor-pointer"
            title="拾取脚下物品 (G / E)"
          >
            <Hand className="w-4 h-4 text-emerald-400" />
            <span>拾取物品 (G)</span>
          </button>

          <button
            onClick={onQueryBag}
            className="py-2.5 px-3 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 hover:text-slate-100 rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-1.5 active:scale-95 cursor-pointer"
            title="刷新背包物品 (B)"
          >
            <PackageSearch className="w-4 h-4 text-slate-400" />
            <span>同步背包 (B)</span>
          </button>
        </div>
      </div>
    </div>
  );
};
