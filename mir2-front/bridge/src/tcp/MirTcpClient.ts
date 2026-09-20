import net from 'net';
import { EventEmitter } from 'events';
import { DefaultMessage } from '../protocol/DefaultMessage.js';
import { WireMessageCodec, WirePacket, RunLogin, ParsedFrame } from '../protocol/WireMessageCodec.js';

export interface MirTcpClientEvents {
  frame: (frame: ParsedFrame, raw: Buffer) => void;
  error: (err: Error) => void;
  close: (hadError: boolean) => void;
}

export class MirTcpClient extends EventEmitter {
  private socket: net.Socket | null = null;
  private buffer: Buffer = Buffer.alloc(0);
  private sequence = 0;
  private pendingReads: {
    resolve: (frame: ParsedFrame) => void;
    reject: (err: Error) => void;
    timer?: NodeJS.Timeout;
  }[] = [];

  constructor() {
    super();
  }

  public connect(host: string, port: number, timeoutMs = 5000): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new net.Socket();
      this.socket = socket;

      let timer: NodeJS.Timeout | null = setTimeout(() => {
        timer = null;
        socket.destroy(new Error(`TCP connect timeout to ${host}:${port}`));
      }, timeoutMs);

      socket.connect(port, host, () => {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
        resolve();
      });

      socket.on('data', (data: Buffer) => {
        this.onData(data);
      });

      socket.on('error', (err: Error) => {
        if (timer) {
          clearTimeout(timer);
          timer = null;
          reject(err);
        }
        this.emit('error', err);
        this.rejectAllPending(err);
      });

      socket.on('close', (hadError: boolean) => {
        this.emit('close', hadError);
        this.rejectAllPending(new Error('Socket closed'));
      });
    });
  }

  public get isOpen(): boolean {
    return this.socket !== null && !this.socket.destroyed && this.socket.writable;
  }

  public close(): void {
    if (this.socket) {
      this.socket.destroy();
      this.socket = null;
    }
    this.buffer = Buffer.alloc(0);
    this.rejectAllPending(new Error('Client closed manually'));
  }

  public sendPacket(message: DefaultMessage, plainBody = ''): Promise<void> {
    if (!this.isOpen || !this.socket) {
      return Promise.reject(new Error('Socket is not connected'));
    }
    this.sequence = (this.sequence % 9) + 1;
    const packet: WirePacket = {
      message,
      encodedBody: plainBody ? WireMessageCodec.encodeBody(plainBody) : ''
    };
    const bytes = WireMessageCodec.formatPacket(packet, this.sequence);

    return new Promise((resolve, reject) => {
      this.socket!.write(bytes, err => {
        if (err) reject(err);
        else resolve();
      });
    });
  }

  public sendRunLogin(login: RunLogin): Promise<void> {
    if (!this.isOpen || !this.socket) {
      return Promise.reject(new Error('Socket is not connected'));
    }
    this.sequence = (this.sequence % 9) + 1;
    const bytes = WireMessageCodec.formatRunLogin(login, this.sequence);

    return new Promise((resolve, reject) => {
      this.socket!.write(bytes, err => {
        if (err) reject(err);
        else resolve();
      });
    });
  }

  public readFrame(timeoutMs = 5000): Promise<ParsedFrame> {
    return new Promise((resolve, reject) => {
      let timer: NodeJS.Timeout | undefined;
      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          const idx = this.pendingReads.findIndex(r => r.resolve === resolve);
          if (idx >= 0) {
            this.pendingReads.splice(idx, 1);
            reject(new Error(`Read frame timed out after ${timeoutMs}ms`));
          }
        }, timeoutMs);
      }

      this.pendingReads.push({ resolve, reject, timer });
    });
  }

  private onData(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    this.extractFrames();
  }

  private extractFrames(): void {
    while (this.buffer.length > 0) {
      const startIdx = this.buffer.indexOf(WireMessageCodec.START);
      if (startIdx === -1) {
        // No start delimiter, discard noise buffer if too large
        if (this.buffer.length > WireMessageCodec.MAX_PACKET_BYTES) {
          this.buffer = Buffer.alloc(0);
        }
        break;
      }

      if (startIdx > 0) {
        // Discard leading noise before '#'
        this.buffer = this.buffer.subarray(startIdx);
      }

      const endIdx = this.buffer.indexOf(WireMessageCodec.END);
      if (endIdx === -1) {
        // Incomplete frame, wait for more data
        if (this.buffer.length > WireMessageCodec.MAX_PACKET_BYTES) {
          this.buffer = Buffer.alloc(0);
          throw new Error('Frame exceeds maximum allowed size');
        }
        break;
      }

      // Frame payload is between index 1 and endIdx
      const payload = this.buffer.subarray(1, endIdx);
      const fullFrameRaw = this.buffer.subarray(0, endIdx + 1);
      this.buffer = this.buffer.subarray(endIdx + 1);

      try {
        const parsed = WireMessageCodec.parseFrame(payload);
        if (this.pendingReads.length > 0) {
          const waiter = this.pendingReads.shift()!;
          if (waiter.timer) clearTimeout(waiter.timer);
          waiter.resolve(parsed);
        } else {
          this.emit('frame', parsed, fullFrameRaw);
        }
      } catch (err) {
        this.emit('error', err as Error);
      }
    }
  }

  private rejectAllPending(err: Error): void {
    while (this.pendingReads.length > 0) {
      const waiter = this.pendingReads.shift()!;
      if (waiter.timer) clearTimeout(waiter.timer);
      waiter.reject(err);
    }
  }
}
