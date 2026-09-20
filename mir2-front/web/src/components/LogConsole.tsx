import React, { useState, useRef, useEffect } from 'react';
import { GameState, LogEntry } from '../store/gameStore.js';
import { Terminal, Trash2, Search } from 'lucide-react';

interface LogConsoleProps {
  logs: LogEntry[];
  filter: GameState['logFilter'];
  onFilterChange: (filter: GameState['logFilter']) => void;
  onClearLogs: () => void;
}

export const LogConsole: React.FC<LogConsoleProps> = ({
  logs,
  filter,
  onFilterChange,
  onClearLogs
}) => {
  const [searchTerm, setSearchTerm] = useState('');
  const [autoScroll, setAutoScroll] = useState(true);
  const logContainerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (autoScroll && logContainerRef.current) {
      logContainerRef.current.scrollTop = logContainerRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  const filteredLogs = logs.filter((log) => {
    // 1. Level / Gate Filter
    if (filter === 'LOGIN' && log.gate !== 'LOGIN') return false;
    if (filter === 'SELECT' && log.gate !== 'SELECT') return false;
    if (filter === 'GAME' && log.gate !== 'GAME') return false;
    if (filter === 'WARN_ERROR' && log.level !== 'warn' && log.level !== 'error') return false;

    // 2. Search Filter
    if (searchTerm.trim()) {
      return log.message.toLowerCase().includes(searchTerm.toLowerCase());
    }
    return true;
  });

  const getLevelBadge = (level: LogEntry['level']) => {
    switch (level) {
      case 'info':
        return <span className="text-blue-400 font-bold">[INFO]</span>;
      case 'warn':
        return <span className="text-amber-400 font-bold">[WARN]</span>;
      case 'error':
        return <span className="text-rose-400 font-bold">[ERROR]</span>;
      case 'debug':
        return <span className="text-slate-500 font-bold">[DEBUG]</span>;
    }
  };

  const formatTime = (ts: number) => {
    const d = new Date(ts);
    return `${d.getHours().toString().padStart(2, '0')}:${d
      .getMinutes()
      .toString()
      .padStart(2, '0')}:${d.getSeconds().toString().padStart(2, '0')}.${d
      .getMilliseconds()
      .toString()
      .padStart(3, '0')}`;
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-4 shadow-xl flex flex-col h-[280px]">
      {/* Log Header Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2 pb-3 border-b border-slate-800 text-xs">
        <div className="flex items-center space-x-2">
          <Terminal className="w-4 h-4 text-amber-400" />
          <span className="font-bold text-slate-200">协议与事件日志控制台</span>
          <span className="px-1.5 py-0.5 rounded bg-slate-800 text-slate-400 font-mono text-[11px]">
            {filteredLogs.length} 条
          </span>
        </div>

        {/* Filter Buttons */}
        <div className="flex items-center space-x-1 bg-slate-950 p-1 rounded-lg border border-slate-800">
          {(
            [
              { id: 'ALL', label: '全部' },
              { id: 'LOGIN', label: '7000 登录' },
              { id: 'SELECT', label: '7100 选人' },
              { id: 'GAME', label: '7200 游戏' },
              { id: 'WARN_ERROR', label: '告警/错误' }
            ] as const
          ).map((item) => (
            <button
              key={item.id}
              onClick={() => onFilterChange(item.id)}
              className={`px-2 py-0.5 rounded text-[11px] font-medium transition-all cursor-pointer ${
                filter === item.id
                  ? 'bg-amber-500 text-slate-950 font-bold shadow-sm'
                  : 'text-slate-400 hover:text-slate-200'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {/* Search & Action Controls */}
        <div className="flex items-center space-x-2">
          <div className="relative">
            <Search className="w-3.5 h-3.5 text-slate-500 absolute left-2 top-1/2 -translate-y-1/2" />
            <input
              type="text"
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="搜索日志..."
              className="pl-7 pr-2 py-1 bg-slate-950 border border-slate-800 rounded-lg text-[11px] text-slate-200 focus:outline-none focus:border-amber-500 w-28 sm:w-36 font-mono"
            />
          </div>

          <label className="flex items-center space-x-1 text-[11px] text-slate-400 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={autoScroll}
              onChange={(e) => setAutoScroll(e.target.checked)}
              className="rounded bg-slate-950 border-slate-800 text-amber-500 focus:ring-0"
            />
            <span>自动滚动</span>
          </label>

          <button
            onClick={onClearLogs}
            className="p-1 bg-slate-800 hover:bg-slate-700 text-slate-400 hover:text-rose-400 rounded-lg transition-colors border border-slate-700 cursor-pointer"
            title="清空日志"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      {/* Log Output Stream */}
      <div
        ref={logContainerRef}
        className="flex-1 overflow-y-auto mt-2 font-mono text-xs space-y-1 bg-slate-950/80 p-3 rounded-xl border border-slate-800/80"
      >
        {filteredLogs.length === 0 ? (
          <div className="text-center py-10 text-slate-600 text-xs">
            暂无匹配的协议日志...
          </div>
        ) : (
          filteredLogs.map((log) => (
            <div key={log.id} className="leading-relaxed hover:bg-slate-900/60 px-1 py-0.5 rounded">
              <span className="text-slate-500 mr-2 text-[10px]">{formatTime(log.timestamp)}</span>
              <span className="mr-2">{getLevelBadge(log.level)}</span>
              {log.gate && (
                <span className="text-slate-400 font-semibold mr-2 text-[11px]">
                  [{log.gate}]
                </span>
              )}
              <span
                className={
                  log.level === 'error'
                    ? 'text-rose-300'
                    : log.level === 'warn'
                    ? 'text-amber-300'
                    : log.level === 'debug'
                    ? 'text-slate-400'
                    : 'text-slate-200'
                }
              >
                {log.message}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
};
