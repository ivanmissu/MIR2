import React, { useState } from 'react';
import { GameState } from '../store/gameStore.js';
import { Server, User, Lock, Play, Sparkles, Network } from 'lucide-react';

interface LoginPanelProps {
  state: GameState;
  onLogin: (account: string, pass: string, host?: string, port?: number) => void;
  onConnectWs?: (url: string) => void;
}

export const LoginPanel: React.FC<LoginPanelProps> = ({ state, onLogin, onConnectWs }) => {
  const [account, setAccount] = useState('hero');
  const [password, setPassword] = useState('123456');
  const [targetHost, setTargetHost] = useState(state.targetHost || '127.0.0.1');
  const [loginPort, setLoginPort] = useState(String(state.loginPort || 7000));
  const [wsUrl, setWsUrl] = useState(state.wsUrl);
  const [showAdvanced, setShowAdvanced] = useState(false);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!account.trim() || !password.trim()) return;
    if (onConnectWs && wsUrl !== state.wsUrl) {
      onConnectWs(wsUrl);
    }
    onLogin(account.trim(), password.trim(), targetHost.trim(), parseInt(loginPort, 10) || 7000);
  };

  const setPreset = (acc: string, pass: string) => {
    setAccount(acc);
    setPassword(pass);
  };

  const isLoading = state.phase === 'LOGGING_IN';

  return (
    <div className="max-w-md w-full mx-auto bg-slate-900 border border-slate-800 rounded-2xl p-8 shadow-2xl relative overflow-hidden">
      {/* Decorative background glow */}
      <div className="absolute -top-24 -right-24 w-48 h-48 bg-amber-500/10 rounded-full blur-3xl pointer-events-none" />
      <div className="absolute -bottom-24 -left-24 w-48 h-48 bg-blue-500/10 rounded-full blur-3xl pointer-events-none" />

      <div className="text-center mb-6">
        <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-amber-500/10 border border-amber-500/30 text-amber-400 mb-3 shadow-inner">
          <Server className="w-7 h-7" />
        </div>
        <h2 className="text-xl font-bold text-slate-100">登录传奇 2 服务器</h2>
        <p className="text-xs text-slate-400 mt-1">
          走通「7000 登录网关 → 7100 选人网关 → 7200 游戏网关」三段式验证
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        {/* Account Field */}
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
            <User className="w-3.5 h-3.5 text-slate-400" />
            游戏账号 (Account)
          </label>
          <input
            type="text"
            value={account}
            onChange={(e) => setAccount(e.target.value)}
            disabled={isLoading}
            placeholder="请输入账号"
            className="w-full px-3.5 py-2.5 bg-slate-950 border border-slate-800 rounded-xl text-slate-100 text-sm focus:outline-none focus:border-amber-500/60 focus:ring-1 focus:ring-amber-500/60 transition-all font-mono"
            required
          />
        </div>

        {/* Password Field */}
        <div>
          <label className="block text-xs font-medium text-slate-300 mb-1.5 flex items-center gap-1.5">
            <Lock className="w-3.5 h-3.5 text-slate-400" />
            登录密码 (Password)
          </label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={isLoading}
            placeholder="请输入密码"
            className="w-full px-3.5 py-2.5 bg-slate-950 border border-slate-800 rounded-xl text-slate-100 text-sm focus:outline-none focus:border-amber-500/60 focus:ring-1 focus:ring-amber-500/60 transition-all font-mono"
            required
          />
        </div>

        {/* Quick Presets */}
        <div className="pt-1">
          <div className="text-[11px] text-slate-400 mb-1.5 flex items-center gap-1">
            <Sparkles className="w-3 h-3 text-amber-400" />
            快捷预设账号（点击快速填入）:
          </div>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setPreset('hero', '123456')}
              className="text-xs px-2.5 py-1 rounded-lg bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition-colors border border-slate-700/60 font-mono"
            >
              hero / 123456
            </button>
            <button
              type="button"
              onClick={() => setPreset('player2', '123456')}
              className="text-xs px-2.5 py-1 rounded-lg bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition-colors border border-slate-700/60 font-mono"
            >
              player2 / 123456
            </button>
            <button
              type="button"
              onClick={() => setPreset('warrior1', '123456')}
              className="text-xs px-2.5 py-1 rounded-lg bg-slate-800/80 hover:bg-slate-700 text-slate-300 transition-colors border border-slate-700/60 font-mono"
            >
              warrior1
            </button>
          </div>
        </div>

        {/* Advanced Network Settings Toggle */}
        <div className="pt-2">
          <button
            type="button"
            onClick={() => setShowAdvanced(!showAdvanced)}
            className="text-xs text-slate-400 hover:text-slate-200 flex items-center gap-1 transition-colors"
          >
            <Network className="w-3.5 h-3.5 text-slate-500" />
            {showAdvanced ? '收起高级网络配置' : '展开高级网络配置 (目标主机/网关端口)'}
          </button>
        </div>

        {showAdvanced && (
          <div className="p-3.5 bg-slate-950/80 rounded-xl border border-slate-800 space-y-3 animate-fadeIn">
            <div>
              <label className="block text-[11px] text-slate-400 mb-1">Bridge WebSocket 地址</label>
              <input
                type="text"
                value={wsUrl}
                onChange={(e) => setWsUrl(e.target.value)}
                className="w-full px-2.5 py-1.5 bg-slate-900 border border-slate-700/60 rounded-lg text-xs font-mono text-slate-200"
              />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <label className="block text-[11px] text-slate-400 mb-1">目标主机</label>
                <input
                  type="text"
                  value={targetHost}
                  onChange={(e) => setTargetHost(e.target.value)}
                  className="w-full px-2.5 py-1.5 bg-slate-900 border border-slate-700/60 rounded-lg text-xs font-mono text-slate-200"
                />
              </div>
              <div>
                <label className="block text-[11px] text-slate-400 mb-1">登录网关端口</label>
                <input
                  type="number"
                  value={loginPort}
                  onChange={(e) => setLoginPort(e.target.value)}
                  className="w-full px-2.5 py-1.5 bg-slate-900 border border-slate-700/60 rounded-lg text-xs font-mono text-slate-200"
                />
              </div>
            </div>
          </div>
        )}

        {/* Submit Button */}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full py-3 px-4 bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-400 hover:to-amber-500 text-slate-950 font-bold rounded-xl shadow-lg shadow-amber-500/20 transition-all flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
        >
          {isLoading ? (
            <>
              <div className="w-4 h-4 border-2 border-slate-950 border-t-transparent rounded-full animate-spin" />
              <span>正在握手与验证 (7000/7100)...</span>
            </>
          ) : (
            <>
              <Play className="w-4 h-4 fill-current" />
              <span>连接并登录</span>
            </>
          )}
        </button>
      </form>
    </div>
  );
};
