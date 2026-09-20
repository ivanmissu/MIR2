export interface BridgeConfig {
  wsPort: number;
  targetHost: string;
  loginPort: number;
  selectPort: number;
  gamePort: number;
  addressMode: 'simple' | 'strict';
  enableMock: boolean;
}

export function loadConfig(): BridgeConfig {
  return {
    wsPort: parseInt(process.env.BRIDGE_WS_PORT || process.env.PORT || '8080', 10),
    targetHost: process.env.BRIDGE_TARGET_HOST || process.env.MIR2_HOST || '127.0.0.1',
    loginPort: parseInt(process.env.BRIDGE_LOGIN_PORT || '7000', 10),
    selectPort: parseInt(process.env.BRIDGE_SELECT_PORT || '7100', 10),
    gamePort: parseInt(process.env.BRIDGE_GAME_PORT || '7200', 10),
    addressMode: process.env.BRIDGE_ADDRESS_MODE === 'strict' ? 'strict' : 'simple',
    enableMock: process.env.BRIDGE_ENABLE_MOCK === 'true' || process.env.ENABLE_MOCK === 'true' || process.env.MIR2_MOCK === 'true'
  };
}
