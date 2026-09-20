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
  Zap,
  DoorOpen,
  MessageSquare,
  Send
} from 'lucide-react';

interface ControlsPanelProps {
  state?: GameState;
  onWalk: (dir: Direction) => void;
  onRun: (dir: Direction) => void;
  onTurn: (dir: Direction) => void;
  onAttack: (kind: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT') => void;
  onPickup: () => void;
  onOpenDoor?: (x: number, y: number) => void;
  onSay?: (message: string) => void;
  onQueryBag: () => void;
}

export const ControlsPanel: React.FC<ControlsPanelProps> = ({
  state,
  onWalk,
  onRun,
  onTurn,
  onAttack,
  onPickup,
  onOpenDoor,
  onSay,
  onQueryBag
}) => {
  const [moveMode, setMoveMode] = useState<'walk' | 'run' | 'turn'>('walk');
  const [chatInput, setChatInput] = useState('');

  const handleDirection = (dir: Direction) => {
    if (moveMode === 'run') {
      onRun(dir);
    } else if (moveMode === 'turn') {
      onTurn(dir);
    } else {
      onWalk(dir);
    }
  };

  const handleSendChat = (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!chatInput.trim() || !onSay) return;
    onSay(chatInput.trim());
    setChatInput('');
  };

  const handleDoorClick = () => {
    if (onOpenDoor && state) {
      // open door in front of player
      onOpenDoor(state.x, state.y);
    }
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-5 shadow-xl space-y-5">
      {/* Chat Bar */}
      {onSay && (
        <div className="pb-3 border-b border-slate-800">
          <div className="text-xs font-semibold text-slate-300 mb-2 flex items-center justify-between">
            <span className="flex items-center gap-1.5">
              <MessageSquare className="w-3.5 h-3.5 text-amber-400" />
              发言与指令 (CM_SAY)
            </span>
            <span className="text-[11px] text-slate-400">支持 /私聊 !喊话 @who</span>
          </div>
          <form onSubmit={handleSendChat} className="flex gap-2">
            <input
              type="text"
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              placeholder="输入聊天内容或 /who..."
              className="flex-1 bg-slate-950 border border-slate-800 focus:border-amber-500 rounded-xl px-3 py-1.5 text-xs text-slate-200 focus:outline-none font-mono"
            />
            <button
              type="submit"
              className="px-3 py-1.5 bg-amber-500 hover:bg-amber-400 text-slate-950 font-bold rounded-xl text-xs flex items-center gap-1 transition-all active:scale-95 cursor-pointer"
            >
              <Send className="w-3.5 h-3.5" />
              发送
            </button>
          </form>
        </div>
      )}

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

        <div className="grid grid-cols-3 gap-2 pt-1">
          <button
            onClick={onPickup}
            className="py-2.5 px-2 bg-emerald-950/60 hover:bg-emerald-900/60 border border-emerald-800/80 text-emerald-300 hover:text-emerald-100 rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-1 active:scale-95 cursor-pointer"
            title="拾取脚下物品 (G / E)"
          >
            <Hand className="w-4 h-4 text-emerald-400" />
            <span>拾取 (G)</span>
          </button>

          <button
            onClick={handleDoorClick}
            className="py-2.5 px-2 bg-indigo-950/60 hover:bg-indigo-900/60 border border-indigo-800/80 text-indigo-300 hover:text-indigo-100 rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-1 active:scale-95 cursor-pointer"
            title="打开周围门 (O)"
          >
            <DoorOpen className="w-4 h-4 text-indigo-400" />
            <span>开门 (O)</span>
          </button>

          <button
            onClick={onQueryBag}
            className="py-2.5 px-2 bg-slate-950 hover:bg-slate-800 border border-slate-800 text-slate-300 hover:text-slate-100 rounded-xl text-xs font-bold transition-all flex items-center justify-center gap-1 active:scale-95 cursor-pointer"
            title="刷新背包物品 (B)"
          >
            <PackageSearch className="w-4 h-4 text-slate-400" />
            <span>背包 (B)</span>
          </button>
        </div>
      </div>
    </div>
  );
};
