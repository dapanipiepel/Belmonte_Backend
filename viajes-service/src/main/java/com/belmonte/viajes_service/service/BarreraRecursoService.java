package com.belmonte.viajes_service.service;

import com.belmonte.viajes_service.model.BarreraRecurso;
import com.belmonte.viajes_service.repository.BarreraRecursoRepository;
import com.belmonte.viajes_service.repository.EstadoTrasladoRepository;
import com.belmonte.viajes_service.repository.TrasladoRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BarreraRecursoService {
    private final BarreraRecursoRepository barreras;
    private final EstadoTrasladoRepository estados;
    private final TrasladoRepository traslados;
    private final TrasladoService trasladoService;

    public BarreraRecursoService(BarreraRecursoRepository barreras, EstadoTrasladoRepository estados,
                                TrasladoRepository traslados, TrasladoService trasladoService) {
        this.barreras = barreras;
        this.estados = estados;
        this.traslados = traslados;
        this.trasladoService = trasladoService;
    }

    @Transactional
    public String prepararBaja(String operacionId, String tipo, Long recursoId) {
        return preparar(operacionId, tipo, recursoId, "BAJA");
    }

    @Transactional
    public String preparar(String operacionId, String tipo, Long recursoId, String proposito) {
        validar(operacionId, tipo, recursoId);
        validarProposito(proposito);
        bloquearOperaciones();
        var anterior = barreras.findById(operacionId);
        if (anterior.isPresent()) {
            var barrera = anterior.get();
            validarRecurso(barrera, tipo, recursoId, proposito);
            return barrera.getEstado();
        }
        if (barreras.existsByTipoRecursoAndRecursoIdAndEstado(tipo, recursoId, "ABIERTA")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurso con operacion pendiente");
        }
        var barrera = new BarreraRecurso(operacionId, tipo, recursoId, proposito);
        if ("BAJA".equals(proposito)) {
            var viajes = tipo.equals("CONDUCTOR")
                    ? traslados.findByConductorId(recursoId) : traslados.findByVehiculoId(recursoId);
            if (viajes.stream().anyMatch(t -> List.of("ASIGNADO", "EN_CURSO")
                    .contains(trasladoService.estadoActual(t)))) {
                barrera.rechazar();
            }
        }
        barreras.saveAndFlush(barrera);
        return barrera.getEstado();
    }

    @Transactional
    public void cerrar(String operacionId, String tipo, Long recursoId) {
        cerrar(operacionId, tipo, recursoId, "BAJA");
    }

    @Transactional
    public void cerrar(String operacionId, String tipo, Long recursoId, String proposito) {
        validar(operacionId, tipo, recursoId);
        validarProposito(proposito);
        bloquearOperaciones();
        var barrera = barreras.findById(operacionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "Barrera inexistente"));
        validarRecurso(barrera, tipo, recursoId, proposito);
        if ("RECHAZADA".equals(barrera.getEstado())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Barrera rechazada");
        }
        barrera.cerrar();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void prepararAsignacion(String conductorOperacion, Long conductorId,
                                   String vehiculoOperacion, Long vehiculoId) {
        validar(conductorOperacion, "CONDUCTOR", conductorId);
        validar(vehiculoOperacion, "VEHICULO", vehiculoId);
        bloquearOperaciones();
        prepararRecursoAsignacion(conductorOperacion, "CONDUCTOR", conductorId);
        prepararRecursoAsignacion(vehiculoOperacion, "VEHICULO", vehiculoId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void verificarAsignacion(String conductorOperacion, Long conductorId,
                                    String vehiculoOperacion, Long vehiculoId) {
        bloquearOperaciones();
        verificarRecursoAsignacion(conductorOperacion, "CONDUCTOR", conductorId);
        verificarRecursoAsignacion(vehiculoOperacion, "VEHICULO", vehiculoId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void cerrarAsignacion(String conductorOperacion, Long conductorId,
                                 String vehiculoOperacion, Long vehiculoId) {
        verificarAsignacion(conductorOperacion, conductorId, vehiculoOperacion, vehiculoId);
        barreras.delete(barreras.findById(conductorOperacion).orElseThrow());
        barreras.delete(barreras.findById(vehiculoOperacion).orElseThrow());
    }

    private void prepararRecursoAsignacion(String id, String tipo, Long recursoId) {
        var existente = barreras.findById(id);
        if (existente.isPresent()) {
            validarRecurso(existente.get(), tipo, recursoId, "ASIGNACION");
            if (!"ABIERTA".equals(existente.get().getEstado())) {
                throw new IllegalStateException("Barrera de asignacion ya cerrada");
            }
        } else {
            if (barreras.existsByTipoRecursoAndRecursoIdAndEstado(tipo, recursoId, "ABIERTA")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recurso con operacion pendiente");
            }
            barreras.saveAndFlush(new BarreraRecurso(id, tipo, recursoId, "ASIGNACION"));
        }
    }

    private void verificarRecursoAsignacion(String id, String tipo, Long recursoId) {
        var barrera = barreras.findById(id).orElseThrow(() ->
                new IllegalStateException("Falta la barrera de asignacion"));
        validarRecurso(barrera, tipo, recursoId, "ASIGNACION");
        if (!"ABIERTA".equals(barrera.getEstado())) {
            throw new IllegalStateException("Barrera de asignacion ya cerrada");
        }
    }

    private void bloquearOperaciones() {
        estados.lockOperaciones().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Estado PENDIENTE no configurado"));
    }

    private void validar(String operacionId, String tipo, Long recursoId) {
        try {
            if (operacionId == null || !UUID.fromString(operacionId).toString().equals(operacionId)) {
                throw new IllegalArgumentException("UUID invalido");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identificador de operacion invalido");
        }
        if ((!"CONDUCTOR".equals(tipo) && !"VEHICULO".equals(tipo)) || recursoId == null || recursoId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recurso invalido");
        }
    }

    private void validarRecurso(BarreraRecurso barrera, String tipo, Long recursoId, String proposito) {
        if (!barrera.getTipoRecurso().equals(tipo) || !barrera.getRecursoId().equals(recursoId)
                || !barrera.getProposito().equals(proposito)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador de operacion reutilizado");
        }
    }

    private void validarProposito(String proposito) {
        if (!"BAJA".equals(proposito) && !"CAMBIO_COMPATIBLE".equals(proposito)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Proposito de barrera invalido");
        }
    }
}
