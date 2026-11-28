package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.belmonte.usuarios_service.security.TokenUtil;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenUtilTest {
    @Test
    void generaTokensUrlSegurosDe256BitsYHashDeterminista() {
        var tokens = new TokenUtil();
        String primero = tokens.generarTokenSeguro();
        String segundo = tokens.generarTokenSeguro();
        assertTrue(primero.matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(primero).length);
        assertNotEquals(primero, segundo);
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                tokens.hashTokenSHA256("abc"));
        assertEquals("024bb90888ca89a15a19e9bdd8c712bfb070465fce1ef25e43c170ea44fc5e5f",
                tokens.hashTokenSHA256("ñ"));
    }
}
