import { useEffect } from 'react';
import { Direction } from '@mir2/shared';

interface UseKeyboardControlsProps {
  enabled: boolean;
  onWalk: (dir: Direction) => void;
  onRun: (dir: Direction) => void;
  onTurn: (dir: Direction) => void;
  onAttack: (kind: 'HIT' | 'HEAVY_HIT' | 'BIG_HIT') => void;
  onPickup: () => void;
  onToggleBag: () => void;
}

export function useKeyboardControls({
  enabled,
  onWalk,
  onRun,
  onTurn,
  onAttack,
  onPickup,
  onToggleBag
}: UseKeyboardControlsProps) {
  useEffect(() => {
    if (!enabled) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't capture inputs if user is typing in an input field
      const activeTag = (document.activeElement?.tagName || '').toLowerCase();
      if (activeTag === 'input' || activeTag === 'textarea' || activeTag === 'select') {
        return;
      }

      const isShift = e.shiftKey;
      const isCtrl = e.ctrlKey;

      switch (e.code) {
        // Up
        case 'KeyW':
        case 'ArrowUp':
        case 'Numpad8':
          e.preventDefault();
          if (isCtrl) onTurn(Direction.UP);
          else if (isShift) onRun(Direction.UP);
          else onWalk(Direction.UP);
          break;

        // Down
        case 'KeyS':
        case 'ArrowDown':
        case 'Numpad2':
          e.preventDefault();
          if (isCtrl) onTurn(Direction.DOWN);
          else if (isShift) onRun(Direction.DOWN);
          else onWalk(Direction.DOWN);
          break;

        // Left
        case 'KeyA':
        case 'ArrowLeft':
        case 'Numpad4':
          e.preventDefault();
          if (isCtrl) onTurn(Direction.LEFT);
          else if (isShift) onRun(Direction.LEFT);
          else onWalk(Direction.LEFT);
          break;

        // Right
        case 'KeyD':
        case 'ArrowRight':
        case 'Numpad6':
          e.preventDefault();
          if (isCtrl) onTurn(Direction.RIGHT);
          else if (isShift) onRun(Direction.RIGHT);
          else onWalk(Direction.RIGHT);
          break;

        // Diagonals (Numpad)
        case 'Numpad7':
          e.preventDefault();
          if (isShift) onRun(Direction.UP_LEFT);
          else onWalk(Direction.UP_LEFT);
          break;
        case 'Numpad9':
          e.preventDefault();
          if (isShift) onRun(Direction.UP_RIGHT);
          else onWalk(Direction.UP_RIGHT);
          break;
        case 'Numpad1':
          e.preventDefault();
          if (isShift) onRun(Direction.DOWN_LEFT);
          else onWalk(Direction.DOWN_LEFT);
          break;
        case 'Numpad3':
          e.preventDefault();
          if (isShift) onRun(Direction.DOWN_RIGHT);
          else onWalk(Direction.DOWN_RIGHT);
          break;

        // Attack
        case 'Space':
        case 'KeyF':
        case 'KeyJ':
          e.preventDefault();
          onAttack('HIT');
          break;
        case 'Digit1':
          e.preventDefault();
          onAttack('HIT');
          break;
        case 'Digit2':
          e.preventDefault();
          onAttack('HEAVY_HIT');
          break;
        case 'Digit3':
          e.preventDefault();
          onAttack('BIG_HIT');
          break;

        // Pickup
        case 'KeyG':
        case 'KeyE':
        case 'Backquote':
          e.preventDefault();
          onPickup();
          break;

        // Toggle Bag
        case 'KeyB':
        case 'KeyI':
          e.preventDefault();
          onToggleBag();
          break;
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [enabled, onWalk, onRun, onTurn, onAttack, onPickup, onToggleBag]);
}
