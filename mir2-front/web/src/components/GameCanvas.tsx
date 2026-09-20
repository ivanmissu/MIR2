import React, { useRef, useEffect } from 'react';
import { GameState } from '../store/gameStore.js';
import { Direction, DirectionOffsets, WorldObjectType } from '@mir2/shared';

interface GameCanvasProps {
  state: GameState;
  onCanvasClick?: (cellX: number, cellY: number) => void;
}

const CELL_SIZE = 40; // 40 pixels per grid cell
const VIEW_RADIUS = 9; // 19x19 grid viewport around player

export const GameCanvas: React.FC<GameCanvasProps> = ({ state, onCanvasClick }) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animationFrameId: number;

    const render = () => {
      const width = canvas.width;
      const height = canvas.height;

      // Clear canvas with dark slate background
      ctx.fillStyle = '#0b1120';
      ctx.fillRect(0, 0, width, height);

      // Center on player's position
      const centerX = width / 2;
      const centerY = height / 2;
      const playerX = state.x;
      const playerY = state.y;

      // Draw grid lines & coordinate labels
      const minCellX = playerX - VIEW_RADIUS;
      const maxCellX = playerX + VIEW_RADIUS;
      const minCellY = playerY - VIEW_RADIUS;
      const maxCellY = playerY + VIEW_RADIUS;

      // 1. Grid Background
      for (let cx = minCellX; cx <= maxCellX; cx++) {
        for (let cy = minCellY; cy <= maxCellY; cy++) {
          const screenX = centerX + (cx - playerX) * CELL_SIZE;
          const screenY = centerY + (cy - playerY) * CELL_SIZE;

          // Checkered subtle pattern
          const isEven = (cx + cy) % 2 === 0;
          ctx.fillStyle = isEven ? '#0f172a' : '#0d1527';
          ctx.fillRect(screenX - CELL_SIZE / 2, screenY - CELL_SIZE / 2, CELL_SIZE, CELL_SIZE);

          // Grid border
          ctx.strokeStyle = '#1e293b';
          ctx.lineWidth = 1;
          ctx.strokeRect(screenX - CELL_SIZE / 2, screenY - CELL_SIZE / 2, CELL_SIZE, CELL_SIZE);

          // Subtle coordinate text
          ctx.fillStyle = '#334155';
          ctx.font = '9px monospace';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(`${cx},${cy}`, screenX, screenY + CELL_SIZE / 3);
        }
      }

      // 2. Render Ground Items
      for (const item of state.visibleItems.values()) {
        const screenX = centerX + (item.x - playerX) * CELL_SIZE;
        const screenY = centerY + (item.y - playerY) * CELL_SIZE;

        // Ground item glow circle
        ctx.fillStyle = 'rgba(234, 179, 8, 0.2)';
        ctx.beginPath();
        ctx.arc(screenX, screenY, 14, 0, Math.PI * 2);
        ctx.fill();

        // Item icon (Diamond/Package)
        ctx.fillStyle = '#fbbf24';
        ctx.beginPath();
        ctx.moveTo(screenX, screenY - 7);
        ctx.lineTo(screenX + 7, screenY);
        ctx.lineTo(screenX, screenY + 7);
        ctx.lineTo(screenX - 7, screenY);
        ctx.closePath();
        ctx.fill();

        // Item name tag
        ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
        ctx.fillRect(screenX - 26, screenY - 24, 52, 14);
        ctx.strokeStyle = '#eab308';
        ctx.lineWidth = 1;
        ctx.strokeRect(screenX - 26, screenY - 24, 52, 14);

        ctx.fillStyle = '#fef08a';
        ctx.font = 'bold 9px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(item.name, screenX, screenY - 17);
      }

      // 3. Render Other Visible Objects (Monsters & Other Players)
      for (const obj of state.visibleObjects.values()) {
        if (obj.id === state.playerId) continue;

        const screenX = centerX + (obj.x - playerX) * CELL_SIZE;
        const screenY = centerY + (obj.y - playerY) * CELL_SIZE;
        const isMonster = obj.type === WorldObjectType.MONSTER;

        // Direction indicator cone/arrow
        drawDirectionIndicator(ctx, screenX, screenY, obj.direction, isMonster ? '#f87171' : '#38bdf8');

        // Body Avatar circle
        ctx.fillStyle = isMonster ? '#dc2626' : '#0284c7';
        ctx.beginPath();
        ctx.arc(screenX, screenY, 12, 0, Math.PI * 2);
        ctx.fill();

        ctx.strokeStyle = isMonster ? '#fca5a5' : '#7dd3fc';
        ctx.lineWidth = 2;
        ctx.stroke();

        // Monster / Player Label
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 10px sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(isMonster ? '怪' : '人', screenX, screenY);

        // Name tag
        ctx.fillStyle = isMonster ? '#fca5a5' : '#bae6fd';
        ctx.font = '10px sans-serif';
        ctx.fillText(obj.name, screenX, screenY - 20);

        // HP Bar
        const maxHp = obj.maxHp || 100;
        const currentHp = Math.max(0, Math.min(obj.hp, maxHp));
        const hpPercent = currentHp / maxHp;
        drawHpBar(ctx, screenX - 16, screenY - 13, 32, 4, hpPercent);
      }

      // 4. Render Local Player (Self)
      // Direction cone/pointer
      drawDirectionIndicator(ctx, centerX, centerY, state.direction, '#fbbf24');

      // Player Body
      ctx.fillStyle = '#eab308';
      ctx.beginPath();
      ctx.arc(centerX, centerY, 14, 0, Math.PI * 2);
      ctx.fill();

      ctx.strokeStyle = '#fef08a';
      ctx.lineWidth = 2.5;
      ctx.stroke();

      // Inner icon
      ctx.fillStyle = '#0f172a';
      ctx.font = 'bold 11px sans-serif';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText('我', centerX, centerY);

      // Name & Level tag
      ctx.fillStyle = '#fef08a';
      ctx.font = 'bold 11px sans-serif';
      ctx.fillText(`${state.playerName || '我'} (Lv.${state.level})`, centerX, centerY - 24);

      // HP overhead bar
      const selfMaxHp = state.maxHp || 100;
      const selfHp = Math.max(0, Math.min(state.hp, selfMaxHp));
      drawHpBar(ctx, centerX - 18, centerY - 15, 36, 5, selfHp / selfMaxHp);

      // 5. Render Attack Slash Visuals
      for (const atk of state.attackEffects) {
        const screenX = centerX + (atk.x - playerX) * CELL_SIZE;
        const screenY = centerY + (atk.y - playerY) * CELL_SIZE;
        const offset = DirectionOffsets[atk.direction] || { dx: 0, dy: 0 };
        const targetX = screenX + offset.dx * CELL_SIZE * 0.8;
        const targetY = screenY + offset.dy * CELL_SIZE * 0.8;

        ctx.strokeStyle = '#f59e0b';
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(targetX, targetY, 16, 0, Math.PI * 1.5);
        ctx.stroke();

        ctx.fillStyle = '#fff';
        ctx.beginPath();
        ctx.arc(targetX, targetY, 4, 0, Math.PI * 2);
        ctx.fill();
      }

      // 6. Render Floating Damage Numbers
      for (const dmg of state.damageNumbers) {
        const screenX = centerX + (dmg.x - playerX) * CELL_SIZE;
        const screenY = centerY + (dmg.y - playerY) * CELL_SIZE;
        const age = Date.now() - dmg.timestamp;
        const floatProgress = Math.min(1, age / 800);
        const offsetY = -floatProgress * 30;
        const alpha = Math.max(0, 1 - floatProgress);

        ctx.save();
        ctx.globalAlpha = alpha;
        ctx.fillStyle = dmg.color;
        ctx.font = 'bold 16px monospace';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.shadowColor = 'black';
        ctx.shadowBlur = 4;
        ctx.fillText(dmg.text, screenX, screenY + offsetY);
        ctx.restore();
      }

      // 7. HUD Coordinates Overlay (Top-Left)
      ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';
      ctx.fillRect(10, 10, 150, 48);
      ctx.strokeStyle = '#334155';
      ctx.lineWidth = 1;
      ctx.strokeRect(10, 10, 150, 48);

      ctx.fillStyle = '#94a3b8';
      ctx.font = '10px sans-serif';
      ctx.textAlign = 'left';
      ctx.fillText(`地图: ${state.mapTitle || '比奇省'} [${state.mapId}]`, 18, 26);
      ctx.fillText(`坐标: (${state.x}, ${state.y})  朝向: ${getDirectionName(state.direction)}`, 18, 44);

      animationFrameId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animationFrameId);
    };
  }, [state]);

  const handleCanvasClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!onCanvasClick || !canvasRef.current) return;
    const rect = canvasRef.current.getBoundingClientRect();
    const clickX = e.clientX - rect.left;
    const clickY = e.clientY - rect.top;

    const centerX = canvasRef.current.width / 2;
    const centerY = canvasRef.current.height / 2;

    const cellOffsetX = Math.round((clickX - centerX) / CELL_SIZE);
    const cellOffsetY = Math.round((clickY - centerY) / CELL_SIZE);

    const targetCellX = state.x + cellOffsetX;
    const targetCellY = state.y + cellOffsetY;

    onCanvasClick(targetCellX, targetCellY);
  };

  return (
    <div className="relative w-full h-full flex items-center justify-center bg-slate-950 rounded-2xl overflow-hidden border border-slate-800 game-canvas-container">
      <canvas
        ref={canvasRef}
        width={680}
        height={480}
        onClick={handleCanvasClick}
        className="cursor-crosshair max-w-full max-h-full aspect-[17/12]"
      />
    </div>
  );
};

function drawHpBar(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  width: number,
  height: number,
  percent: number
) {
  // Background
  ctx.fillStyle = '#1e293b';
  ctx.fillRect(x, y, width, height);

  // Fill
  const fillWidth = Math.max(0, width * percent);
  ctx.fillStyle = percent > 0.5 ? '#22c55e' : percent > 0.2 ? '#f59e0b' : '#ef4444';
  ctx.fillRect(x, y, fillWidth, height);

  // Border
  ctx.strokeStyle = '#0f172a';
  ctx.lineWidth = 1;
  ctx.strokeRect(x, y, width, height);
}

function drawDirectionIndicator(
  ctx: CanvasRenderingContext2D,
  centerX: number,
  centerY: number,
  direction: Direction,
  color: string
) {
  const offset = DirectionOffsets[direction] || { dx: 0, dy: 0 };
  const targetX = centerX + offset.dx * 18;
  const targetY = centerY + offset.dy * 18;

  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = 2;

  ctx.beginPath();
  ctx.moveTo(centerX, centerY);
  ctx.lineTo(targetX, targetY);
  ctx.stroke();

  // Arrow tip
  ctx.beginPath();
  ctx.arc(targetX, targetY, 3.5, 0, Math.PI * 2);
  ctx.fill();
}

function getDirectionName(dir: Direction): string {
  switch (dir) {
    case Direction.UP:
      return '上 (0)';
    case Direction.UP_RIGHT:
      return '右上 (1)';
    case Direction.RIGHT:
      return '右 (2)';
    case Direction.DOWN_RIGHT:
      return '右下 (3)';
    case Direction.DOWN:
      return '下 (4)';
    case Direction.DOWN_LEFT:
      return '左下 (5)';
    case Direction.LEFT:
      return '左 (6)';
    case Direction.UP_LEFT:
      return '左上 (7)';
    default:
      return `${dir}`;
  }
}
