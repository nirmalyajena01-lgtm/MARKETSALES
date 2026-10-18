package com.supermarket.repository;

import com.supermarket.domain.Operator;

import java.util.List;
import java.util.Optional;

public interface OperatorRepository {
    Optional<Operator> findByUsername(String username);
    void save(Operator operator);
    void updatePasswordHash(long id, String newHash);
    List<Operator> findAll();
    long count();
}

