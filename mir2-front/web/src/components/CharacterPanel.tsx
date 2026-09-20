import React, { useState } from 'react';
import { GameState } from '../store/gameStore.js';
import { Job, Gender } from '@mir2/shared';
import { User, Shield, Sparkles, Wand2, Plus, Trash2, Play, LogOut } from 'lucide-react';

interface CharacterPanelProps {
  state: GameState;
  onSelectCharacter: (name: string) => void;
  onCreateCharacter: (name: string, job: Job, gender: Gender, hair: number) => void;
  onDeleteCharacter: (name: string) => void;
  onDisconnect: () => void;
  isCreateModalOpen: boolean;
  setCreateModalOpen: (open: boolean) => void;
}

export const CharacterPanel: React.FC<CharacterPanelProps> = ({
  state,
  onSelectCharacter,
  onCreateCharacter,
  onDeleteCharacter,
  onDisconnect,
  isCreateModalOpen,
  setCreateModalOpen
}) => {
  const [selectedName, setSelectedName] = useState<string>(
    state.selectedCharacter || (state.characters.length > 0 ? state.characters[0].name : '')
  );

  // New character form state
  const [newName, setNewName] = useState('');
  const [newJob, setNewJob] = useState<Job>(Job.WARRIOR);
  const [newGender, setNewGender] = useState<Gender>(Gender.MALE);
  const [newHair, setNewHair] = useState(1);

  const handleCreateSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!newName.trim()) return;
    onCreateCharacter(newName.trim(), newJob, newGender, newHair);
    setNewName('');
  };

  const getJobName = (job: Job) => {
    switch (job) {
      case Job.WARRIOR:
        return '战士 (Warrior)';
      case Job.WIZARD:
        return '法师 (Wizard)';
      case Job.TAOIST:
        return '道士 (Taoist)';
      default:
        return '未知职业';
    }
  };

  const getJobIcon = (job: Job) => {
    switch (job) {
      case Job.WARRIOR:
        return <Shield className="w-5 h-5 text-amber-400" />;
      case Job.WIZARD:
        return <Wand2 className="w-5 h-5 text-blue-400" />;
      case Job.TAOIST:
        return <Sparkles className="w-5 h-5 text-emerald-400" />;
    }
  };

  const isEntering = state.phase === 'ENTERING_GAME';

  return (
    <div className="max-w-2xl w-full mx-auto bg-slate-900 border border-slate-800 rounded-2xl p-8 shadow-2xl relative">
      <div className="flex items-center justify-between pb-6 border-b border-slate-800">
        <div>
          <h2 className="text-xl font-bold text-slate-100 flex items-center gap-2">
            <User className="w-5 h-5 text-amber-400" />
            角色选择与管理 (7100 选人网关)
          </h2>
          <p className="text-xs text-slate-400 mt-1">
            当前账号: <span className="font-mono text-amber-400 font-bold">{state.account}</span> | 服务器: <span className="text-slate-200">{state.serverName}</span>
          </p>
        </div>

        <button
          onClick={onDisconnect}
          className="px-3 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-300 hover:text-rose-400 text-xs font-medium rounded-lg border border-slate-700 transition-colors flex items-center gap-1.5"
        >
          <LogOut className="w-3.5 h-3.5" />
          退出登录
        </button>
      </div>

      {/* Character Cards List */}
      <div className="py-6">
        {state.characters.length === 0 ? (
          <div className="text-center py-12 bg-slate-950/60 rounded-xl border border-dashed border-slate-800">
            <User className="w-12 h-12 text-slate-600 mx-auto mb-3" />
            <p className="text-sm text-slate-400">该账号下暂无角色，请先创建角色</p>
            <button
              onClick={() => setCreateModalOpen(true)}
              className="mt-4 px-4 py-2 bg-amber-500/20 hover:bg-amber-500/30 text-amber-400 border border-amber-500/40 rounded-xl text-xs font-bold transition-all inline-flex items-center gap-1.5"
            >
              <Plus className="w-4 h-4" />
              创建新角色
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            {state.characters.map((char) => {
              const isSelected = selectedName === char.name;
              return (
                <div
                  key={char.name}
                  onClick={() => setSelectedName(char.name)}
                  className={`p-5 rounded-xl border transition-all cursor-pointer relative overflow-hidden ${
                    isSelected
                      ? 'bg-amber-500/10 border-amber-500/50 shadow-lg shadow-amber-500/10 ring-1 ring-amber-500/50'
                      : 'bg-slate-950/70 border-slate-800 hover:border-slate-700 hover:bg-slate-950'
                  }`}
                >
                  <div className="flex items-start justify-between">
                    <div className="flex items-center space-x-3">
                      <div className="w-10 h-10 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center">
                        {getJobIcon(char.job)}
                      </div>
                      <div>
                        <h3 className="font-bold text-slate-100 text-base">{char.name}</h3>
                        <p className="text-xs text-slate-400 font-mono">
                          等级: <span className="text-amber-400 font-bold">{char.level}</span> | 性别:{' '}
                          {char.gender === Gender.MALE ? '男' : '女'}
                        </p>
                      </div>
                    </div>

                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        if (confirm(`确定要删除角色 "${char.name}" 吗？此操作不可逆。`)) {
                          onDeleteCharacter(char.name);
                        }
                      }}
                      className="text-slate-600 hover:text-rose-400 p-1.5 rounded-lg hover:bg-slate-900 transition-colors"
                      title="删除角色"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>

                  <div className="mt-4 pt-3 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-400">
                    <span className="px-2 py-0.5 rounded bg-slate-900 text-slate-300 font-medium">
                      {getJobName(char.job)}
                    </span>
                    <span className="font-mono text-[11px]">发型: #{char.hair}</span>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Action Footer */}
      <div className="pt-4 border-t border-slate-800 flex items-center justify-between">
        <button
          onClick={() => setCreateModalOpen(true)}
          className="px-4 py-2.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-bold rounded-xl border border-slate-700 transition-colors flex items-center gap-1.5 cursor-pointer"
        >
          <Plus className="w-4 h-4" />
          创建新角色
        </button>

        <button
          disabled={!selectedName || isEntering}
          onClick={() => {
            if (selectedName) onSelectCharacter(selectedName);
          }}
          className="px-6 py-2.5 bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-slate-950 text-xs font-bold rounded-xl shadow-lg shadow-amber-500/20 transition-all flex items-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {isEntering ? (
            <>
              <div className="w-3.5 h-3.5 border-2 border-slate-950 border-t-transparent rounded-full animate-spin" />
              <span>正在进入游戏 (7200)...</span>
            </>
          ) : (
            <>
              <Play className="w-4 h-4 fill-current" />
              <span>进入游戏世界</span>
            </>
          )}
        </button>
      </div>

      {/* Create Character Modal */}
      {isCreateModalOpen && (
        <div className="fixed inset-0 bg-black/70 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-slate-900 border border-slate-800 rounded-2xl max-w-md w-full p-6 shadow-2xl animate-fadeIn">
            <h3 className="text-lg font-bold text-slate-100 mb-4 flex items-center gap-2">
              <Plus className="w-5 h-5 text-amber-400" />
              新建角色 (CM_NEWCHR)
            </h3>

            <form onSubmit={handleCreateSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1">角色名称</label>
                <input
                  type="text"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="请输入角色名字 (如: 战神无双)"
                  maxLength={14}
                  className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-sm text-slate-100 focus:outline-none focus:border-amber-500"
                  required
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-300 mb-1">职业</label>
                <div className="grid grid-cols-3 gap-2">
                  {[
                    { job: Job.WARRIOR, name: '战士', icon: Shield },
                    { job: Job.WIZARD, name: '法师', icon: Wand2 },
                    { job: Job.TAOIST, name: '道士', icon: Sparkles }
                  ].map((item) => (
                    <button
                      key={item.job}
                      type="button"
                      onClick={() => setNewJob(item.job)}
                      className={`p-2.5 rounded-xl border text-xs font-medium flex flex-col items-center gap-1.5 transition-all ${
                        newJob === item.job
                          ? 'bg-amber-500/10 border-amber-500/60 text-amber-400'
                          : 'bg-slate-950 border-slate-800 text-slate-400 hover:border-slate-700'
                      }`}
                    >
                      <item.icon className="w-4 h-4" />
                      <span>{item.name}</span>
                    </button>
                  ))}
                </div>
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-slate-300 mb-1">性别</label>
                  <select
                    value={newGender}
                    onChange={(e) => setNewGender(parseInt(e.target.value, 10) as Gender)}
                    className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-slate-200"
                  >
                    <option value={Gender.MALE}>男 (Male)</option>
                    <option value={Gender.FEMALE}>女 (Female)</option>
                  </select>
                </div>

                <div>
                  <label className="block text-xs font-medium text-slate-300 mb-1">发型</label>
                  <select
                    value={newHair}
                    onChange={(e) => setNewHair(parseInt(e.target.value, 10))}
                    className="w-full px-3 py-2 bg-slate-950 border border-slate-800 rounded-xl text-xs text-slate-200"
                  >
                    <option value={1}>发型 1</option>
                    <option value={2}>发型 2</option>
                    <option value={3}>发型 3</option>
                  </select>
                </div>
              </div>

              <div className="pt-4 flex items-center justify-end space-x-3">
                <button
                  type="button"
                  onClick={() => setCreateModalOpen(false)}
                  className="px-4 py-2 bg-slate-800 hover:bg-slate-700 text-slate-300 text-xs font-medium rounded-xl transition-colors"
                >
                  取消
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 bg-amber-500 hover:bg-amber-400 text-slate-950 text-xs font-bold rounded-xl shadow-md shadow-amber-500/20 transition-all cursor-pointer"
                >
                  确定创建
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
