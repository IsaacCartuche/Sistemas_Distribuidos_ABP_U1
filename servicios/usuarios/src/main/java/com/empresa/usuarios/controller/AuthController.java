package com.empresa.usuarios.controller;

import com.empresa.usuarios.dto.*;
import com.empresa.usuarios.model.Role;
import com.empresa.usuarios.model.Usuario;
import com.empresa.usuarios.publisher.UsuarioProducer;
import com.empresa.usuarios.repository.UsuarioRepository;
import com.empresa.usuarios.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/auth", "/api/usuarios"}) // Soporta /api/auth y mantiene compatibilidad con /api/usuarios
@RequiredArgsConstructor
public class AuthController {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final UsuarioProducer usuarioProducer;

    @PostMapping({"/registro", "/register"}) // Soporta ambos nombres de endpoint
    public ResponseEntity<?> registrar(@RequestBody RegistroRequest request) {
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            return ResponseEntity.badRequest().body("El email ya se encuentra registrado.");
        }

        Role rolAsignado = request.getRol() != null ? request.getRol() : Role.ROLE_PASAJERO;

        Usuario usuario = Usuario.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .nombre(request.getNombre())
                .apellido(request.getApellido())
                .rol(rolAsignado)
                .build();

        // 1. Guardar en base de datos
        Usuario usuarioGuardado = usuarioRepository.save(usuario);

        // 2. Publicar evento en RabbitMQ pasando la entidad completa
        usuarioProducer.publicarUsuarioRegistrado(usuarioGuardado);

        return ResponseEntity.ok("Usuario registrado exitosamente");
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // Credenciales invalidas -> 401 (no 500): un RuntimeException sin
        // handler acaba en /error y el cliente no distingue el motivo real.
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail()).orElse(null);

        if (usuario == null || !passwordEncoder.matches(request.getPassword(), usuario.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Credenciales inválidas");
        }

        String token = jwtUtils.generateToken(usuario.getId(), usuario.getEmail(), usuario.getRol());

        return ResponseEntity.ok(new AuthResponse(token, usuario.getId(), usuario.getEmail(), usuario.getRol().name()));
    }
}