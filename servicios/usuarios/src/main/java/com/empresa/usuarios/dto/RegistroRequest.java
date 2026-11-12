package com.empresa.usuarios.dto;

import com.empresa.usuarios.model.Role;
import lombok.Data;

@Data
public class RegistroRequest {
    private String email;
    private String password;
    private String nombre;
    private String apellido;
    private Role rol; //ROLE_PASAJERO o ROLE_ADMIN
}