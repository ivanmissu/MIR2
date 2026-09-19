package com.mir2.gate;

import com.mir2.auth.AuthService;
import com.mir2.character.CharacterService;
import com.mir2.protocol.DefaultMessage;
import com.mir2.protocol.ProtocolConstants;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyGateHandlerTest {
  @Test
  void loginCertificationAndCharacterLifecycleFollowClientFields() {
    AuthService auth = new AuthService();
    auth.register("hero", "pw");
    LegacyGateHandler handler = new LegacyGateHandler(new SessionRouter(auth, new CharacterService()),
        new GateSessionRegistry(), new LegacyGateHandler.Config("game.example", 7100, 7200, "MIR2"),
        error -> fail(error));

    LegacyGateHandler.ConnectionState loginState = new LegacyGateHandler.ConnectionState();
    WirePacket login = request(ProtocolConstants.CM_IDPASSWORD, "hero/pw");
    WirePacket serverList = handler.dispatch(GateKind.LOGIN, loginState, login);
    assertEquals(ProtocolConstants.SM_PASSOK_SELECTSERVER, serverList.message().ident());
    assertEquals("MIR2/1/", WireMessageCodec.decodeBody(serverList.encodedBody()));

    WirePacket selectedServer = handler.dispatch(GateKind.LOGIN, loginState,
        request(ProtocolConstants.CM_SELECTSERVER, "MIR2"));
    assertEquals(ProtocolConstants.SM_SELECTSERVER_OK, selectedServer.message().ident());
    String route = WireMessageCodec.decodeBody(selectedServer.encodedBody());
    String certification = route.substring(route.lastIndexOf('/') + 1);
    assertEquals("game.example/7100/" + certification, route);

    LegacyGateHandler.ConnectionState selectState = new LegacyGateHandler.ConnectionState();
    WirePacket empty = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_QUERYCHR, "hero/" + certification));
    assertEquals(ProtocolConstants.SM_QUERYCHR, empty.message().ident());
    assertEquals(0, empty.message().recog());

    WirePacket created = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_NEWCHR, "hero/战士/2/0/0"));
    assertEquals(ProtocolConstants.SM_NEWCHR_SUCCESS, created.message().ident());

    WirePacket characters = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_QUERYCHR, "hero/" + certification));
    assertEquals(1, characters.message().recog());
    assertEquals("战士/0/0/1/0/", WireMessageCodec.decodeBody(characters.encodedBody()));

    WirePacket start = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_SELCHR, "hero/战士"));
    assertEquals(ProtocolConstants.SM_STARTPLAY, start.message().ident());
    assertEquals("game.example/7200", WireMessageCodec.decodeBody(start.encodedBody()));

    int certificationNumber = Integer.parseInt(certification);
    GateSessionRegistry.Session gameSession = handler.authenticateGame(
        new RunLogin("hero", "战士", certificationNumber, 120040918, 9));
    assertEquals("hero", gameSession.account());
    assertEquals("战士", gameSession.selectedCharacter().name());
    assertThrows(SecurityException.class, () -> handler.authenticateGame(
        new RunLogin("hero", "法师", certificationNumber, 120040918, 9)));

    WirePacket deleted = handler.dispatch(GateKind.SELECT, selectState,
        request(ProtocolConstants.CM_DELCHR, "战士"));
    assertEquals(ProtocolConstants.SM_DELCHR_SUCCESS, deleted.message().ident());
  }

  @Test
  void wrongCertificationCannotQueryAnotherAccount() {
    AuthService auth = new AuthService();
    auth.register("hero", "pw");
    auth.register("other", "pw");
    LegacyGateHandler handler = new LegacyGateHandler(new SessionRouter(auth, new CharacterService()));
    LegacyGateHandler.ConnectionState loginState = new LegacyGateHandler.ConnectionState();
    handler.dispatch(GateKind.LOGIN, loginState, request(ProtocolConstants.CM_IDPASSWORD, "hero/pw"));
    WirePacket route = handler.dispatch(GateKind.LOGIN, loginState,
        request(ProtocolConstants.CM_SELECTSERVER, "MIR2"));
    String certification = WireMessageCodec.decodeBody(route.encodedBody()).replaceAll(".*/", "");

    WirePacket response = handler.dispatch(GateKind.SELECT, new LegacyGateHandler.ConnectionState(),
        request(ProtocolConstants.CM_QUERYCHR, "other/" + certification));
    assertEquals(ProtocolConstants.SM_OUTOFCONNECTION, response.message().ident());
  }

  private static WirePacket request(int ident, String body) {
    return new WirePacket(new DefaultMessage(0, ident, 0, 0, 0), WireMessageCodec.encodeBody(body));
  }
}
