import React from 'react';
import { GameState } from '../store/gameStore.js';
import { Swords, Wifi, WifiOff, LogOut, Backpack, HelpCircle } from 'lucide-react';

interface HeaderProps {
  state: GameState;
  onDisconnect: () => void;
  onTabChange: (tab: GameState['activeTab']) => void;
}

export const Header: React.FC<HeaderProps> = ({ state, onDisconnect, onTabChange }) => {
  const isConnected = state.phase !== 'DISCONNECTED';
  const isInGame = state.phase === 'IN_GAME';

  return (
    <header className="bg-slate-900/90 border-b border-slate-800 px-6 py-3 flex items-center justify-between sticky top-0 z-50 backdrop-blur-md">
      <div className="flex items-center space-x-3">
        <div className="w-9 h-9 rounded-lg bg-gradient-to-tr from-amber-600 to-amber-400 flex items-center justify-center shadow-lg shadow-amber-500/20 text-black font-black text-xl tracking-tighter">
          Mir2
        </div>
        <div>
          <h1 className="text-base font-bold text-slate-100 flex items-center gap-2">
            传奇 2 网页联调控制台
            <span className="text-xs px-2 py-0.5 rounded-full bg-slate-800 text-slate-400 font-mono font-normal">
              mir2-front v0.1
            </span>
          </h1>
          <p className="text-xs text-slate-400">
            {isInGame
              ? `地图: ${state.mapTitle || '比奇省'} (${state.x}, ${state.y}) | 角色: ${state.playerName}`
              : 'Delphi 字节级兼容 / 服务端全链路验证'}
          </p>
        </div>
      </div>

      <div className="flex items-center space-x-4">
        {/* Navigation Tabs (when in game) */}
        {isInGame && (
          <div className="flex items-center bg-slate-950 p-1 rounded-lg border border-slate-800 text-xs">
            <button
              onClick={() => onTabChange('game')}
              className={`px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 ${
                state.activeTab === 'game'
                  ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Swords className="w-3.5 h-3.5" />
              游戏视图
            </button>
            <button
              onClick={() => onTabChange('inventory')}
              className={`px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 ${
                state.activeTab === 'inventory'
                  ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <Backpack className="w-3.5 h-3.5" />
              背包 ({state.backpack.length})
            </button>
            <button
              onClick={() => onTabChange('help')}
              className={`px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 ${
                state.activeTab === 'help'
                  ? 'bg-amber-500/20 text-amber-400 border border-amber-500/30'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              <HelpCircle className="w-3.5 h-3.5" />
              快捷键说明
            </button>
          </div>
        )}

        {/* Connection Status Badge */}
        <div className="flex items-center space-x-2 text-xs">
          <div
            className={`px-2.5 py-1 rounded-full flex items-center gap-1.5 border ${
              isInGame
                ? 'bg-emerald-950/60 border-emerald-800/80 text-emerald-400'
                : isConnected
                ? 'bg-blue-950/60 border-blue-800/80 text-blue-400'
                : 'bg-rose-950/60 border-rose-800/80 text-rose-400'
            }`}
          >
            {isInGame ? (
              <>
                <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
                <span>游戏进行中</span>
              </>
            ) : isConnected ? (
              <>
                <Wifi className="w-3.5 h-3.5 text-blue-400 animate-pulse" />
                <span>已连接 ({state.phase})</span>
              </>
            ) : (
              <>
                <WifiOff className="w-3.5 h-3.5 text-rose-400" />
                <span>未连接</span>
              </>
            )}
          </div>

          {isConnected && (
            <button
              onClick={onDisconnect}
              className="p-1.5 bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-rose-400 rounded-lg transition-colors border border-slate-700 cursor-pointer"
              title="断开连接 / 重新登录"
            >
              <LogOut className="w-4 h-4" />
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
