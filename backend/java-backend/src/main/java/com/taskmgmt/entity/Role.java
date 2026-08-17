package com.taskmgmt.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "roles")
@Getter
@Setter
public class Role {
    @Id
    private Short id;

    @Column(nullable = false, unique = true)
    private String name; // ADMIN, MANAGER, MEMBER
}
