import React, { useState } from 'react';
import { GameState } from '../store/gameStore.js';
import { BackpackItem } from '@mir2/shared';
import { Backpack, RefreshCw, Swords, Coins, Sparkles, Wrench, Utensils, Trash2 } from 'lucide-react';

interface InventoryPanelProps {
  state: GameState;
  onRefreshBag: () => void;
  onEquip: (slot: number, item: BackpackItem) => void;
  onUnequip: (slot: number, item: BackpackItem) => void;
  onEat: (item: BackpackItem) => void;
  onDrop: (item: BackpackItem) => void;
  onOpenRepair: (special?: boolean) => void;
  onQueryRepairCost: (item: BackpackItem) => void;
  onRepair: (item: BackpackItem) => void;
}

const TOTAL_SLOTS = 46;

/** Mirrors the live CheckUserItems StdMode-to-slot matrix for the supported slots. */
const slotForItem = (item: BackpackItem): number | null => {
  switch (item.item.stdMode) {
    case 5:
    case 6:
      return 1;
    case 10:
    case 11:
      return 0;
    case 28:
    case 29:
    case 30:
      return 2;
    case 19:
    case 20:
    case 21:
      return 3;
    case 15:
      return 4;
    case 24:
    case 26:
      return 5;
    case 22:
    case 23:
      return 7;
    case 25:
    case 51:
      return 9;
    default:
      return null;
  }
};

export const InventoryPanel: React.FC<InventoryPanelProps> = ({
  state,
  onRefreshBag,
  onEquip,
  onUnequip,
  onEat,
  onDrop,
  onOpenRepair,
  onQueryRepairCost,
  onRepair
}) => {
  const [selectedItem, setSelectedItem] = useState<BackpackItem | null>(
    state.backpack.length > 0 ? state.backpack[0] : null
  );

  const getStdModeName = (mode: number) => {
    switch (mode) {
      case 5:
        return '武器 (Weapon)';
      case 10:
      case 11:
        return '衣服 (Armor)';
      case 0:
        return '消耗品/肉类 (Consumable)';
      default:
        return `道具 (Type ${mode})`;
    }
  };

  const getDcText = (dc: number) => {
    const min = dc & 0xffff;
    const max = (dc >>> 16) & 0xffff;
    return `${min}-${max}`;
  };

  return (
    <div className="bg-slate-900 border border-slate-800 rounded-2xl p-6 shadow-xl space-y-6">
      <div className="flex items-center justify-between pb-4 border-b border-slate-800">
        <div>
          <h2 className="text-lg font-bold text-slate-100 flex items-center gap-2">
            <Backpack className="w-5 h-5 text-amber-400" />
            角色背包 (46 格 / CM_QUERYBAGITEMS 同步)
          </h2>
          <p className="text-xs text-slate-400 mt-0.5">
            当前物品: <span className="text-amber-400 font-bold">{state.backpack.length}</span> / {TOTAL_SLOTS}
          </p>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => onOpenRepair(false)}
            className="px-2.5 py-1.5 bg-amber-950/70 hover:bg-amber-900 text-amber-300 text-xs rounded-xl border border-amber-800 flex items-center gap-1"
          >
            <Wrench className="w-3.5 h-3.5" /> 普修
          </button>
          <button
            onClick={() => onOpenRepair(true)}
            className="px-2.5 py-1.5 bg-orange-950/70 hover:bg-orange-900 text-orange-300 text-xs rounded-xl border border-orange-800"
          >
            特修
          </button>
          <button
            onClick={onRefreshBag}
            className="px-3.5 py-1.5 bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-medium rounded-xl border border-slate-700 transition-colors flex items-center gap-1.5 cursor-pointer"
          >
            <RefreshCw className="w-3.5 h-3.5" />
            刷新背包
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        {/* Inventory Slots Grid */}
        <div className="md:col-span-2">
          <div className="grid grid-cols-6 sm:grid-cols-8 gap-2 p-3 bg-slate-950 rounded-2xl border border-slate-800/80">
            {Array.from({ length: TOTAL_SLOTS }).map((_, index) => {
              const item = state.backpack[index];
              const isSelected = selectedItem && item && selectedItem.makeIndex === item.makeIndex;

              return (
                <div
                  key={index}
                  onClick={() => item && setSelectedItem(item)}
                  className={`aspect-square rounded-xl border flex flex-col items-center justify-center p-1 relative transition-all cursor-pointer ${
                    item
                      ? isSelected
                        ? 'bg-amber-500/20 border-amber-500 ring-1 ring-amber-500 shadow-md shadow-amber-500/20'
                        : 'bg-slate-900/90 border-slate-700/80 hover:border-slate-500 hover:bg-slate-800'
                      : 'bg-slate-950/40 border-slate-800/40 cursor-default'
                  }`}
                >
                  {item ? (
                    <>
                      <div className="w-7 h-7 rounded-lg bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-amber-400">
                        {item.item.stdMode === 5 ? (
                          <Swords className="w-4 h-4" />
                        ) : (
                          <Sparkles className="w-4 h-4" />
                        )}
                      </div>
                      <span className="text-[10px] text-slate-200 font-medium truncate w-full text-center mt-1">
                        {item.item.name}
                      </span>
                      <span className="text-[9px] text-slate-400 font-mono scale-90">
                        {item.dura}/{item.duraMax}
                      </span>
                    </>
                  ) : (
                    <span className="text-[10px] text-slate-700 font-mono">{index + 1}</span>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Worn equipment snapshot (SM_SENDUSEITEMS) */}
        <div className="md:col-span-2 p-4 bg-slate-950/70 rounded-2xl border border-slate-800/80">
          <div className="text-xs font-bold text-slate-300 mb-3 flex items-center gap-2">
            <Swords className="w-3.5 h-3.5 text-indigo-400" />
            当前装备 (13 槽)
          </div>
          <div className="grid grid-cols-2 sm:grid-cols-5 gap-2">
            {Array.from({ length: 13 }).map((_, slot) => {
              const item = state.equipment.get(slot);
              return (
                <button
                  key={slot}
                  onClick={() => item && onUnequip(slot, item)}
                  disabled={!item}
                  className="text-left p-2 rounded-lg border border-slate-800 bg-slate-900/60 hover:border-indigo-500 disabled:opacity-60 disabled:cursor-default"
                  title={item ? `点击脱下 ${item.item.name}` : '空槽'}
                >
                  <div className="text-[10px] text-slate-500">槽位 {slot}</div>
                  <div className="text-[11px] text-indigo-300 truncate">{item?.item.name || '空'}</div>
                  {item && <div className="text-[10px] text-slate-500 font-mono">{item.dura}/{item.duraMax}</div>}
                </button>
              );
            })}
          </div>
        </div>

        {/* Selected Item Details Card */}
        <div className="p-5 bg-slate-950/90 rounded-2xl border border-slate-800/80 flex flex-col justify-between">
          {selectedItem ? (
            <div className="space-y-4">
              <div className="flex items-center space-x-3 pb-3 border-b border-slate-800">
                <div className="w-12 h-12 rounded-xl bg-amber-500/10 border border-amber-500/30 flex items-center justify-center text-amber-400">
                  {selectedItem.item.stdMode === 5 ? (
                    <Swords className="w-6 h-6" />
                  ) : (
                    <Sparkles className="w-6 h-6" />
                  )}
                </div>
                <div>
                  <h3 className="text-base font-bold text-amber-400">{selectedItem.item.name}</h3>
                  <span className="text-xs text-slate-400">
                    {getStdModeName(selectedItem.item.stdMode)}
                  </span>
                </div>
              </div>

              <div className="space-y-2 text-xs">
                <div className="flex justify-between text-slate-400">
                  <span>持久度:</span>
                  <span className="font-mono text-slate-200 font-bold">
                    {selectedItem.dura} / {selectedItem.duraMax}
                  </span>
                </div>

                <div className="flex justify-between text-slate-400">
                  <span>重量:</span>
                  <span className="font-mono text-slate-200">{selectedItem.item.weight}</span>
                </div>

                {selectedItem.item.dc > 0 && (
                  <div className="flex justify-between text-slate-400">
                    <span>攻击力 (DC):</span>
                    <span className="font-mono text-amber-300 font-bold">
                      {getDcText(selectedItem.item.dc)}
                    </span>
                  </div>
                )}

                {selectedItem.item.ac > 0 && (
                  <div className="flex justify-between text-slate-400">
                    <span>防御力 (AC):</span>
                    <span className="font-mono text-blue-300">{selectedItem.item.ac}</span>
                  </div>
                )}

                <div className="flex justify-between text-slate-400">
                  <span>售价 (Gold):</span>
                  <span className="font-mono text-amber-400 flex items-center gap-1">
                    <Coins className="w-3 h-3" />
                    {selectedItem.item.price}
                  </span>
                </div>

                <div className="pt-2 border-t border-slate-800/80 text-[11px] text-slate-500 font-mono">
                  MakeIndex: #{selectedItem.makeIndex} | Looks: #{selectedItem.item.looks}
                </div>

                <div className="grid grid-cols-2 gap-2 pt-1">
                  {selectedItem.item.stdMode <= 3 && (
                    <button
                      onClick={() => onEat(selectedItem)}
                      className="px-2 py-1.5 rounded-lg bg-emerald-950/70 border border-emerald-800 text-emerald-300 text-[11px] flex items-center justify-center gap-1"
                    >
                      <Utensils className="w-3 h-3" /> 使用
                    </button>
                  )}
                  {slotForItem(selectedItem) !== null && (
                    <button
                      onClick={() => onEquip(slotForItem(selectedItem)!, selectedItem)}
                      className="px-2 py-1.5 rounded-lg bg-indigo-950/70 border border-indigo-800 text-indigo-300 text-[11px] flex items-center justify-center gap-1"
                    >
                      <Swords className="w-3 h-3" /> 穿戴
                    </button>
                  )}
                  <button
                    onClick={() => onDrop(selectedItem)}
                    className="px-2 py-1.5 rounded-lg bg-rose-950/70 border border-rose-800 text-rose-300 text-[11px] flex items-center justify-center gap-1"
                  >
                    <Trash2 className="w-3 h-3" /> 丢弃
                  </button>
                  <button
                    onClick={() => onQueryRepairCost(selectedItem)}
                    className="px-2 py-1.5 rounded-lg bg-amber-950/70 border border-amber-800 text-amber-300 text-[11px] flex items-center justify-center gap-1"
                  >
                    <Wrench className="w-3 h-3" /> 报价
                  </button>
                  <button
                    onClick={() => onRepair(selectedItem)}
                    className="px-2 py-1.5 rounded-lg bg-amber-950/70 border border-amber-800 text-amber-300 text-[11px] flex items-center justify-center gap-1"
                  >
                    <Wrench className="w-3 h-3" /> 修理
                  </button>
                </div>
                {state.repairCost !== null && (
                  <div className="text-[11px] text-amber-300 pt-1">
                    当前报价: {state.repairCost < 0 ? '不可修理' : `${state.repairCost} 金币`}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="text-center py-16 text-slate-500 text-xs">
              点击背包中的物品查看详细属性
            </div>
          )}

          <div className="p-3 bg-slate-900 rounded-xl border border-slate-800 text-[11px] text-slate-400">
            提示: 地面拾取的物品会自动进包并通过 76 字节 TClientItem 协议同步。
          </div>
        </div>
      </div>
    </div>
  );
};
