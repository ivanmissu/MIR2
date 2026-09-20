import React from 'react';
import { useMirWs } from './hooks/useMirWs.js';
import { useKeyboardControls } from './hooks/useKeyboardControls.js';
import { Header } from './components/Header.js';
import { LoginPanel } from './components/LoginPanel.js';
import { CharacterPanel } from './components/CharacterPanel.js';
import { GameCanvas } from './components/GameCanvas.js';
import { ControlsPanel } from './components/ControlsPanel.js';
import { StatusPanel } from './components/StatusPanel.js';
import { InventoryPanel } from './components/InventoryPanel.js';
import { LogConsole } from './components/LogConsole.js';
import { Direction } from '@mir2/shared';
import { HelpCircle, Swords, Footprints, Sparkles } from 'lucide-react';

export const App: React.FC = () => {
  const {
    state,
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
    queryBagItems,
    disconnect,
    setLogFilter,
    clearLogs,
    setActiveTab,
    setCreateModalOpen
  } = useMirWs();

  // Keyboard shortcut listener
  useKeyboardControls({
    enabled: state.phase === 'IN_GAME',
    onWalk: walk,
    onRun: run,
    onTurn: turn,
    onAttack: attack,
    onPickup: pickup,
    onToggleBag: () => {
      setActiveTab(state.activeTab === 'inventory' ? 'game' : 'inventory');
    }
  });

  const handleCanvasClick = (targetCellX: number, targetCellY: number) => {
    // Calculate direction towards clicked cell
    const dx = targetCellX - state.x;
    const dy = targetCellY - state.y;
    const distance = Math.max(Math.abs(dx), Math.abs(dy));

    if (distance === 0) {
      pickup();
      return;
    }

    let dir: Direction = Direction.DOWN;
    if (dx === 0 && dy < 0) dir = Direction.UP;
    else if (dx > 0 && dy < 0) dir = Direction.UP_RIGHT;
    else if (dx > 0 && dy === 0) dir = Direction.RIGHT;
    else if (dx > 0 && dy > 0) dir = Direction.DOWN_RIGHT;
    else if (dx === 0 && dy > 0) dir = Direction.DOWN;
    else if (dx < 0 && dy > 0) dir = Direction.DOWN_LEFT;
    else if (dx < 0 && dy === 0) dir = Direction.LEFT;
    else if (dx < 0 && dy < 0) dir = Direction.UP_LEFT;

    if (distance >= 2) {
      run(dir);
    } else {
      walk(dir);
    }
  };

  return (
    <div className="min-h-screen flex flex-col bg-slate-950 text-slate-100">
      <Header
        state={state}
        onDisconnect={disconnect}
        onTabChange={setActiveTab}
      />

      <main className="flex-1 max-w-7xl w-full mx-auto p-4 sm:p-6 flex flex-col gap-6">
        {/* State: Login Phase */}
        {state.phase === 'DISCONNECTED' || state.phase === 'LOGGING_IN' ? (
          <div className="flex-1 flex items-center justify-center py-10">
            <LoginPanel
              state={state}
              onLogin={login}
              onConnectWs={connect}
            />
          </div>
        ) : state.phase === 'LOGGED_IN' ? (
          /* State: Character Selection Phase */
          <div className="flex-1 flex items-center justify-center py-6">
            <CharacterPanel
              state={state}
              onSelectCharacter={selectCharacter}
              onCreateCharacter={createCharacter}
              onDeleteCharacter={deleteCharacter}
              onDisconnect={disconnect}
              isCreateModalOpen={state.isCreateModalOpen}
              setCreateModalOpen={setCreateModalOpen}
            />
          </div>
        ) : (
          /* State: In-Game Phase */
          <div className="flex-1 flex flex-col gap-6">
            <div className="grid grid-cols-1 lg:grid-cols-12 gap-6">
              {/* Main Game Center Area */}
              <div className="lg:col-span-8 flex flex-col gap-4">
                {state.activeTab === 'game' ? (
                  <div className="h-[500px] w-full">
                    <GameCanvas
                      state={state}
                      onCanvasClick={handleCanvasClick}
                    />
                  </div>
                ) : state.activeTab === 'inventory' ? (
                  <InventoryPanel
                    state={state}
                    onRefreshBag={queryBagItems}
                  />
                ) : (
                  /* Help & Shortcuts Guide */
                  <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-4">
                    <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
                      <HelpCircle className="w-5 h-5 text-amber-400" />
                      键盘快捷键与操作说明
                    </h2>

                    <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 text-xs">
                      <div className="p-4 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
                        <div className="font-bold text-amber-400 flex items-center gap-1.5">
                          <Footprints className="w-4 h-4" />
                          移动与转身控制
                        </div>
                        <ul className="space-y-1 text-slate-300">
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">W / A / S / D</span> 或 <span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">方向键</span>: 走 (1 格)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">Shift + 方向</span>: 奔跑 (2 格)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">Ctrl + 方向</span>: 原地转身 (Turn)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">小键盘 1~9</span>: 支持 8 方向移动</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">鼠标点击画布</span>: 自动朝目标格移动</li>
                        </ul>
                      </div>

                      <div className="p-4 bg-slate-950 rounded-xl border border-slate-800 space-y-2">
                        <div className="font-bold text-red-400 flex items-center gap-1.5">
                          <Swords className="w-4 h-4" />
                          战斗与物品交互
                        </div>
                        <ul className="space-y-1 text-slate-300">
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">空格 Space / F / 1</span>: 普通攻击 (HIT)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">2</span>: 重击攻击 (HEAVY HIT)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">3</span>: 大砍攻击 (BIG HIT)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">G / E / `</span>: 拾取脚下物品 (PICKUP)</li>
                          <li><span className="font-mono bg-slate-800 px-1.5 py-0.5 rounded text-slate-200">B / I</span>: 打开 / 关闭背包面板</li>
                        </ul>
                      </div>
                    </div>

                    <div className="p-4 bg-slate-950 rounded-xl border border-slate-800 text-xs text-slate-400 space-y-1">
                      <div className="font-bold text-blue-400 flex items-center gap-1">
                        <Sparkles className="w-3.5 h-3.5" />
                        多标签页联调说明 (M4 场景)
                      </div>
                      <p>
                        在浏览器中打开多个标签页或无痕窗口，分别使用不同账号（例如 <code className="text-amber-300">hero</code> 与 <code className="text-amber-300">player2</code>）登录进入游戏。
                        当两人角色坐标相距在 12 格以内时，可互相实时观察到彼此的出现、走动、奔跑、挥砍和离开。
                      </p>
                    </div>
                  </div>
                )}
              </div>

              {/* Sidebar Controls & Status Area */}
              <div className="lg:col-span-4 flex flex-col gap-4">
                <StatusPanel state={state} />
                <ControlsPanel
                  state={state}
                  onWalk={walk}
                  onRun={run}
                  onTurn={turn}
                  onAttack={attack}
                  onPickup={pickup}
                  onQueryBag={queryBagItems}
                />
              </div>
            </div>
          </div>
        )}

        {/* Real-time Log Console (Always visible at bottom) */}
        <LogConsole
          logs={state.logs}
          filter={state.logFilter}
          onFilterChange={setLogFilter}
          onClearLogs={clearLogs}
        />
      </main>
    </div>
  );
};
