package com.mir2.auth;
import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*;
class AuthServiceTest {
 @Test void accountLoginCreatesAndInvalidatesSession(){var a=new AuthService();a.register("hero","secret");String s=a.login("hero","secret");assertEquals("hero",a.accountFor(s));a.logout(s);assertThrows(SecurityException.class,()->a.accountFor(s));}
 @Test void wrongPasswordIsRejected(){var a=new AuthService();a.register("hero","secret");assertThrows(SecurityException.class,()->a.login("hero","wrong"));}
}
