package com.belmonte.usuarios_service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.belmonte.usuarios_service.repository.SesionUsuarioRepository;
import com.belmonte.usuarios_service.repository.UsuarioRepository;
import com.belmonte.usuarios_service.service.ControlDecisionSegura;
import com.belmonte.usuarios_service.service.DecisionAutorizacionService;
import com.belmonte.usuarios_service.service.RelojBaseDatos;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class DecisionAutorizacionBloqueoTest {
    @Test
    void sinFuenteIndependienteNoPreparaNiRepiteDecisiones() {
        var usuarios = mock(UsuarioRepository.class);
        var sesiones = mock(SesionUsuarioRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var entityManager = mock(EntityManager.class);
        var transacciones = mock(PlatformTransactionManager.class);
        var reloj = mock(RelojBaseDatos.class);
        var decisiones = new DecisionAutorizacionService(
                usuarios, sesiones, jdbc, entityManager, transacciones, reloj, new ControlDecisionSegura());
        var solicitud = new DecisionAutorizacionService.Solicitud(
                "VEHICULOS_ESTADO", "clave", 1L, "sesion", "a".repeat(64),
                OffsetDateTime.parse("2030-01-02T03:04:05Z"));

        assertEquals(503, assertThrows(ResponseStatusException.class, () -> decisiones.decidir(solicitud))
                .getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> new ControlDecisionSegura().obtenerLimiteSuperior()).getStatusCode().value());
        verifyNoInteractions(usuarios, sesiones, jdbc, entityManager, transacciones, reloj);
    }
}
