package com.mir2.gate;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class GateTest { @Test void defaultsMatchDelphiPorts(){var p=GatePorts.defaults();assertEquals(7000,p.port(GateKind.LOGIN));assertEquals(7100,p.port(GateKind.SELECT));assertEquals(7200,p.port(GateKind.GAME));} @Test void invalidPortRejected(){assertThrows(IllegalArgumentException.class,()->new GatePorts(0,7100,7200));} }
